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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline streaming voice input powered by sherpa-onnx (prebuilt libraries).
 *
 * Tap the voice key to start listening, tap again to stop.
 * Partial results are shown as composing text; finished sentences are committed.
 * Listening also ends automatically after a short natural pause (more human-like).
 */
object SherpaVoice {
    private const val SAMPLE_RATE = 16000
    private const val STATUS_LOADING = "正在准备语音识别…"
    private const val STATUS_LISTENING_HINT = ""
    private const val NO_SPEECH_REMINDER_MS = 6_000L
    private const val AUTO_STOP_AFTER_SPEECH_SILENCE_MS = 2_500L
    private const val AUTO_STOP_NO_SPEECH_MS = 12_000L
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
    private val sessionStarting = AtomicBoolean(false)
    private val stateListeners = CopyOnWriteArraySet<() -> Unit>()

    /** What the voice session is doing, for the on-key indicator. */
    enum class Phase { IDLE, PREPARING, LISTENING, FINISHING }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Smoothed microphone level in 0..1, drives the waveform. */
    @Volatile
    var level: Float = 0f
        private set

    private fun setPhase(value: Phase) {
        phase = value
        if (value != Phase.LISTENING) level = 0f
        notifyStateChanged()
    }

    fun addStateListener(listener: () -> Unit) {
        stateListeners.add(listener)
        listener()
    }

    fun removeStateListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyStateChanged() {
        stateListeners.forEach { listener -> runCatching { listener() } }
    }

    @Volatile
    private var activeToast: Toast? = null

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
            toast(service.applicationContext, "正在结束…")
        } else {
            start(service)
        }
        return true
    }

    fun stop() {
        stopRequested = true
        if (phase == Phase.LISTENING) setPhase(Phase.FINISHING)
    }

    private fun toast(
        context: Context,
        text: String,
        duration: Int = Toast.LENGTH_SHORT,
    ) {
        mainHandler.post {
            activeToast?.cancel()
            activeToast = Toast.makeText(context.applicationContext, text, duration).also { it.show() }
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
            toast(context, "需要麦克风权限。授权后回到键盘，再点一次语音键", Toast.LENGTH_LONG)
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
        // Avoid overlapping sessions when the key is tapped quickly.
        if (!sessionStarting.compareAndSet(false, true)) {
            return
        }
        if (isListening) {
            sessionStarting.set(false)
            return
        }
        mainHandler.removeCallbacks(releaseIdle)
        stopRequested = false
        phase = Phase.PREPARING
        listenJob =
            scope.launch(Dispatchers.IO) {
                try {
                    runCatching {
                        if (!modelReady(dir)) {
                            toast(context, "首次使用，正在解压语音模型…")
                            installBundledModel(context, dir)
                        }
                        runSession(service, dir)
                    }.onFailure {
                        Timber.e(it, "voice session failed")
                        showComposing(service, "")
                        val message =
                            when (it) {
                                is SecurityException -> "无法访问麦克风，请在系统设置中允许同文使用麦克风"
                                is IllegalStateException -> "麦克风被占用，请关闭其他正在录音的应用后重试"
                                else -> "语音识别出了点问题，请再试一次"
                            }
                        toast(context, message, Toast.LENGTH_LONG)
                    }
                } finally {
                    sessionStarting.set(false)
                    setPhase(Phase.IDLE)
                    mainHandler.postDelayed(releaseIdle, IDLE_RELEASE_MS)
                }
            }
        notifyStateChanged()
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
        if (text.isBlank()) return
        mainHandler.post {
            val ic = service.currentInputConnection ?: return@post
            // commitText replaces the composing preview; finishing it first would keep the preview
            // and then insert the text a second time.
            ic.commitText(text, 1)
        }
    }

    private fun showComposing(
        service: TrimeInputMethodService,
        text: String,
    ) {
        mainHandler.post {
            val ic = service.currentInputConnection ?: return@post
            if (text.isEmpty()) {
                // Clear the preview first: finishing it would commit it as real text.
                runCatching {
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                }
            } else {
                ic.setComposingText(text, 1)
            }
        }
    }

    /** Insert a space before [next] when the previous committed text ends with a Latin letter/digit. */
    private fun withOptionalSpace(
        previous: String,
        next: String,
    ): String {
        if (previous.isEmpty() || next.isEmpty()) return next
        val last = previous.last()
        val first = next.first()
        val needSpace =
            last.isLetterOrDigit() &&
                first.isLetterOrDigit() &&
                (last.code < 0x2E80) &&
                (first.code < 0x2E80)
        return if (needSpace) " $next" else next
    }

    // RECORD_AUDIO is checked in start() before the session begins.
    @android.annotation.SuppressLint("MissingPermission")
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
            if (minBuf <= 0) {
                toast(context, "当前设备不支持语音录音")
                return
            }
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
            var lastCommitted = ""
            var committedAny = false
            var heardSpeech = false
            var lastActivityAt = SystemClock.elapsedRealtime()
            var silenceReminderShown = false
            val startMs = SystemClock.elapsedRealtime()

            audioRecord.startRecording()
            recordingStarted = true
            setPhase(Phase.LISTENING)
            // Prefer in-field hint over a second toast so the keyboard feels quieter.
            showComposing(service, STATUS_LISTENING_HINT)

            while (
                currentCoroutineContext()[Job]?.isActive != false &&
                !stopRequested &&
                SystemClock.elapsedRealtime() - startMs < MAX_SECONDS * 1000L
            ) {
                val now = SystemClock.elapsedRealtime()

                // Natural end-of-utterance: stop after a short pause once something was recognized.
                if (heardSpeech && now - lastActivityAt >= AUTO_STOP_AFTER_SPEECH_SILENCE_MS) {
                    break
                }
                // No speech at all for a while, stop instead of hanging forever.
                if (!heardSpeech && now - lastActivityAt >= AUTO_STOP_NO_SPEECH_MS) {
                    break
                }

                val n = audioRecord.read(buffer, 0, buffer.size)
                if (n < 0) error("microphone read failed: $n")
                if (n == 0) continue

                val samples = FloatArray(n) { buffer[it] / 32768f }
                run {
                    var sum = 0f
                    for (x in samples) sum += x * x
                    val rms = kotlin.math.sqrt(sum / n)
                    val target = (rms * 7f).coerceIn(0f, 1f)
                    level = if (target > level) target else level * 0.75f + target * 0.25f
                }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }

                val text = rec.getResult(stream).text.trim()
                if (rec.isEndpoint(stream)) {
                    if (text.isNotBlank()) {
                        val toCommit = withOptionalSpace(lastCommitted, text)
                        commit(service, toCommit)
                        lastCommitted = text
                        committedAny = true
                        heardSpeech = true
                        lastActivityAt = SystemClock.elapsedRealtime()
                        silenceReminderShown = false
                    }
                    lastShown = ""
                    rec.reset(stream)
                    showComposing(service, STATUS_LISTENING_HINT)
                } else if (text != lastShown) {
                    lastShown = text
                    if (text.isNotBlank()) {
                        heardSpeech = true
                        lastActivityAt = SystemClock.elapsedRealtime()
                        silenceReminderShown = false
                        showComposing(service, text)
                    } else if (lastShown.isEmpty()) {
                        showComposing(service, STATUS_LISTENING_HINT)
                    }
                }

                if (
                    !heardSpeech &&
                    !silenceReminderShown &&
                    SystemClock.elapsedRealtime() - lastActivityAt >= NO_SPEECH_REMINDER_MS
                ) {
                    silenceReminderShown = true
                    toast(context, "还没听到内容，请靠近麦克风、说完后再稍停一下")
                }
            }

            if (
                !stopRequested &&
                SystemClock.elapsedRealtime() - startMs >= MAX_SECONDS * 1000L
            ) {
                toast(context, "已达 5 分钟上限，正在结束")
            }

            setPhase(Phase.FINISHING)
            // Flush remaining audio for a clean final result.
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            stream.inputFinished()
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            val finalText = rec.getResult(stream).text.trim().ifBlank { lastShown.trim() }
            if (finalText.isNotBlank()) {
                val toCommit = withOptionalSpace(lastCommitted, finalText)
                // Avoid re-committing the exact same endpoint text when auto-stop races endpoint.
                if (toCommit.trim() != lastCommitted.trim()) {
                    commit(service, toCommit)
                    committedAny = true
                }
            }
            showComposing(service, "")
            if (committedAny) {
                toast(context, "已完成")
            } else {
                toast(context, "没有识别到内容，点语音键再说一次")
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
        }.getOrDefault(false)

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
        toast(
            context,
            "首次使用需下载约 230MB 语音模型（仅一次），完成后可完全离线使用",
            Toast.LENGTH_LONG,
        )
        scope.launch(Dispatchers.IO) {
            try {
                dir.mkdirs()
                for (name in MODEL_FILES) {
                    val target = File(dir, name)
                    if (target.exists() && target.length() > 0) continue
                    downloadFile(context, name, target)
                }
                toast(context, "模型已就绪，回到键盘再点一次语音键即可", Toast.LENGTH_LONG)
            } catch (e: Throwable) {
                Timber.e(e, "model download failed")
                toast(context, "模型下载失败，请检查网络后重试", Toast.LENGTH_LONG)
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
            var connection: HttpURLConnection? = null
            try {
                val conn = URL(base + name).openConnection() as HttpURLConnection
                connection = conn
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
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }
}
