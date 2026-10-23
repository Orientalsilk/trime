/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline streaming voice input powered by sherpa-onnx (prebuilt libraries).
 * Tap the voice key to start listening, tap again to stop.
 * Partial results are shown as composing text, finished sentences are committed.
 */
object SherpaVoice {
    private const val SAMPLE_RATE = 16000
    private const val STATUS_LOADING = "⏳ 正在加载语音模型，请稍候…"
    private const val STATUS_LISTENING = "🎙 正在听，再点一次语音键结束"
    private const val STATUS_RECOGNIZING = "⌛ 正在整理识别结果…"
    private const val NO_RESULT_REMINDER_MS = 8_000L
    private const val MAX_SECONDS = 300
    private const val IDLE_RELEASE_MS = 60_000L
    private const val MODEL_DIR_NAME = "sherpa-paraformer-zh-en"
    private const val ENCODER = "encoder.int8.onnx"
    private const val DECODER = "decoder.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private val MODEL_FILES = listOf(ENCODER, DECODER, TOKENS)
    private val MODEL_BASE_URLS =
        listOf(
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/",
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/",
        )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var listenJob: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var downloading = false

    private val nativeOk: Boolean by lazy {
        runCatching { System.loadLibrary("sherpa-onnx-jni") }
            .onFailure { Timber.w(it, "sherpa-onnx native library not available") }
            .isSuccess
    }

    private val releaseIdle =
        Runnable {
            if (listenJob?.isActive != true) {
                synchronized(this) {
                    recognizer?.release()
                    recognizer = null
                }
            }
        }

    val isListening: Boolean
        get() = listenJob?.isActive == true

    /**
     * @return false if sherpa-onnx is unavailable, so the caller can fall back to another voice input.
     */
    fun toggle(service: TrimeInputMethodService): Boolean {
        if (!nativeOk) return false
        if (isListening) {
            stop()
        } else {
            start(service)
        }
        return true
    }

    fun stop() {
        stopRequested = true
    }

    private fun toast(
        context: Context,
        text: String,
    ) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, MODEL_DIR_NAME)

    private fun modelReady(dir: File): Boolean =
        MODEL_FILES.all { File(dir, it).let { file -> file.exists() && file.length() > 0 } }

    private fun start(service: TrimeInputMethodService) {
        val context = service.applicationContext
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(context, "需要麦克风权限，请在弹出的窗口里允许")
            context.startActivity(
                Intent(context, MicPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val dir = modelDir(context)
        val bundled = hasBundledModel(context)
        if (!modelReady(dir) && !bundled) {
            downloadModel(context, dir)
            return
        }
        mainHandler.removeCallbacks(releaseIdle)
        stopRequested = false
        listenJob =
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (!modelReady(dir)) {
                        toast(context, "首次使用，正在准备语音模型…")
                        installBundledModel(context, dir)
                    }
                    runSession(service, dir)
                }
                    .onFailure {
                        Timber.e(it, "voice session failed")
                        showComposing(service, "")
                        toast(context, "语音识别出错：${it.message}")
                    }
                mainHandler.postDelayed(releaseIdle, IDLE_RELEASE_MS)
            }
    }

    private fun getRecognizer(dir: File): OnlineRecognizer =
        synchronized(this) {
            recognizer ?: run {
                val config =
                    OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig =
                            OnlineModelConfig(
                                paraformer =
                                    OnlineParaformerModelConfig(
                                        encoder = File(dir, ENCODER).absolutePath,
                                        decoder = File(dir, DECODER).absolutePath,
                                    ),
                                tokens = File(dir, TOKENS).absolutePath,
                                numThreads = 2,
                                provider = "cpu",
                                modelType = "paraformer",
                            ),
                        enableEndpoint = true,
                        decodingMethod = "greedy_search",
                    )
                OnlineRecognizer(config = config).also { recognizer = it }
            }
        }

    private fun commit(
        service: TrimeInputMethodService,
        text: String,
    ) {
        mainHandler.post { service.currentInputConnection?.commitText(text, 1) }
    }

    private fun showComposing(
        service: TrimeInputMethodService,
        text: String,
    ) {
        mainHandler.post { service.currentInputConnection?.setComposingText(text, 1) }
    }

    private suspend fun runSession(
        service: TrimeInputMethodService,
        dir: File,
    ) {
        val context = service.applicationContext
        toast(context, STATUS_LOADING)
        val rec = getRecognizer(dir)
        val stream = rec.createStream()
        var record: AudioRecord? = null
        var recordingStarted = false
        try {
            val minBuf =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, SAMPLE_RATE),
                )
            record = audioRecord
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                toast(context, "无法打开麦克风")
                return
            }

            val buffer = ShortArray(SAMPLE_RATE / 10)
            var lastShown = ""
            var committedAny = false
            var lastRecognitionAt = SystemClock.elapsedRealtime()
            var silenceReminderShown = false
            val startMs = SystemClock.elapsedRealtime()

            audioRecord.startRecording()
            recordingStarted = true
            toast(context, STATUS_LISTENING)
            while (
                scope.isActive &&
                !stopRequested &&
                SystemClock.elapsedRealtime() - startMs < MAX_SECONDS * 1000L
            ) {
                val n = audioRecord.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) continue

                val samples = FloatArray(n) { buffer[it] / 32768f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }

                val text = rec.getResult(stream).text
                if (rec.isEndpoint(stream)) {
                    if (text.isNotBlank()) {
                        commit(service, text)
                        committedAny = true
                        lastRecognitionAt = SystemClock.elapsedRealtime()
                        silenceReminderShown = false
                    }
                    lastShown = ""
                    rec.reset(stream)
                    showComposing(service, "")
                } else if (text != lastShown) {
                    lastShown = text
                    if (text.isNotBlank()) {
                        lastRecognitionAt = SystemClock.elapsedRealtime()
                        silenceReminderShown = false
                        showComposing(service, text)
                    } else {
                        showComposing(service, "")
                    }
                }

                if (
                    lastShown.isBlank() &&
                    !silenceReminderShown &&
                    SystemClock.elapsedRealtime() - lastRecognitionAt >= NO_RESULT_REMINDER_MS
                ) {
                    silenceReminderShown = true
                    toast(context, "暂未识别到文字，请靠近麦克风并清晰说话")
                }
            }

            if (
                !stopRequested &&
                SystemClock.elapsedRealtime() - startMs >= MAX_SECONDS * 1000L
            ) {
                toast(context, "语音输入已达到 5 分钟时长上限，正在结束…")
            }

            toast(context, STATUS_RECOGNIZING)
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            stream.inputFinished()
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            val finalText = rec.getResult(stream).text.ifBlank { lastShown }
            if (finalText.isNotBlank()) {
                commit(service, finalText)
                committedAny = true
            }
            showComposing(service, "")
            if (committedAny) {
                toast(context, "✓ 语音输入完成")
            } else {
                toast(context, "未识别到语音内容，请重试")
            }
        } finally {
            record?.let {
                if (recordingStarted) {
                    runCatching { it.stop() }
                }
                runCatching { it.release() }
            }
            runCatching { stream.release() }
        }
    }

    /** The model may be packaged inside the APK (assets/MODEL_DIR_NAME). */
    private fun hasBundledModel(context: Context): Boolean =
        runCatching {
            context.assets.list(MODEL_DIR_NAME)?.toSet()?.containsAll(MODEL_FILES) == true
        }
            .getOrDefault(false)

    private fun installBundledModel(
        context: Context,
        dir: File,
    ) {
        dir.mkdirs()
        for (name in MODEL_FILES) {
            val tmp = File(dir, "$name.part")
            context.assets.open("$MODEL_DIR_NAME/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            val target = File(dir, name)
            target.delete()
            check(tmp.renameTo(target)) { "无法写入 $name" }
        }
    }

    private fun downloadModel(
        context: Context,
        dir: File,
    ) {
        if (downloading) {
            toast(context, "语音模型正在下载，请稍候…")
            return
        }
        downloading = true
        toast(context, "首次使用，正在下载语音模型（约 230MB），请保持网络畅通")
        scope.launch(Dispatchers.IO) {
            try {
                dir.mkdirs()
                for (name in MODEL_FILES) {
                    val target = File(dir, name)
                    if (target.exists() && target.length() > 0) continue
                    downloadFile(context, name, target)
                }
                toast(context, "语音模型下载完成，请再点一次语音键")
            } catch (e: Throwable) {
                Timber.e(e, "model download failed")
                toast(context, "模型下载失败：${e.message}")
            } finally {
                downloading = false
            }
        }
    }

    private fun downloadFile(
        context: Context,
        name: String,
        target: File,
    ) {
        var lastError: Throwable? = null
        for (base in MODEL_BASE_URLS) {
            val part = File(target.parentFile, "$name.part")
            try {
                val conn = URL(base + name).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt() / 25 * 25
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    toast(context, "下载 $name：$percent%")
                                }
                            }
                        }
                    }
                }
                if (part.length() == 0L) error("empty file")
                if (total > 0 && done != total) error("incomplete download: $done/$total bytes")
                if (!part.renameTo(target)) error("cannot save $name")
                return
            } catch (e: Throwable) {
                lastError = e
                part.delete()
                Timber.w(e, "download $name from $base failed")
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }
}
