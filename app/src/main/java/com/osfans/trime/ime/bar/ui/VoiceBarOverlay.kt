/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.osfans.trime.util.sp
import com.osfans.trime.voice.SherpaVoice
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Voice status for hold-to-talk. While the space bar is held the finger covers it, so the live
 * waveform is shown over the bar above the keyboard, where it stays visible.
 * It does not take touches: they fall through to whatever is underneath.
 */
class VoiceBarOverlay(
    context: Context,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(43, 112, 238) }
    private val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

    private val stateListener: () -> Unit = { post { sync() } }

    init {
        visibility = GONE
        isClickable = false
    }

    private fun sync() {
        val show = SherpaVoice.phase != SherpaVoice.Phase.IDLE && SherpaVoice.isHoldSession
        val next = if (show) VISIBLE else GONE
        if (visibility != next) visibility = next
        if (show) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SherpaVoice.addStateListener(stateListener)
    }

    override fun onDetachedFromWindow() {
        SherpaVoice.removeStateListener(stateListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = SherpaVoice.phase
        if (state == SherpaVoice.Phase.IDLE) return
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = dp(3f)
        val radius = (h - inset * 2) / 2f
        canvas.drawRoundRect(inset * 2, inset, w - inset * 2, h - inset, radius, radius, fill)

        val t = (SystemClock.uptimeMillis() % 1_600L) / 1_600f
        if (state == SherpaVoice.Phase.LISTENING) {
            val barW = dp(2f)
            val gap = dp(3f)
            val usable = w * 0.5f
            val count = ((usable + gap) / (barW + gap)).toInt().coerceAtLeast(9) or 1
            val total = count * barW + (count - 1) * gap
            val startX = (w - total) / 2f
            val mid = (count - 1) / 2f
            val level = SherpaVoice.level
            for (i in 0 until count) {
                val dist = abs(i - mid) / mid
                val envelope = 1f - 0.65f * dist * dist
                val wobble = 0.55f + 0.45f * abs(sin(t * 2f * Math.PI * 2f + i * 0.7f)).toFloat()
                val amp = (0.1f + 0.9f * level) * envelope * wobble
                val barH = maxOf(dp(3f), amp * (h - inset * 4))
                val left = startX + i * (barW + gap)
                val top = (h - barH) / 2f
                canvas.drawRoundRect(left, top, left + barW, top + barH, barW / 2f, barW / 2f, bars)
            }
            text.textSize = sp(12f)
            val fm = text.fontMetrics
            canvas.drawText("松开结束", w - dp(48f), h / 2f - (fm.ascent + fm.descent) / 2f, text)
        } else {
            text.textSize = sp(14f)
            val fm = text.fontMetrics
            val label = if (state == SherpaVoice.Phase.PREPARING) "准备中…" else "识别中…"
            canvas.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text)
        }
        postInvalidateDelayed(50L)
    }
}
