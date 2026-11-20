/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.sms

import android.content.Context
import com.osfans.trime.util.WeakHashSet

/** Finds the verification code in the text of an SMS. */
object SmsCode {
    private val KEYWORDS =
        Regex(
            "验证码|校验码|动态码|动态密码|确认码|安全码|识别码|验证|" +
                "(?<![A-Za-z])(?:verification|verify|code|otp|passcode|pin)(?![A-Za-z])",
            RegexOption.IGNORE_CASE,
        )

    // 4-8 digits standing alone, so phone numbers (11 digits) and long order numbers are skipped.
    private val DIGITS = Regex("(?<![0-9])[0-9]{4,8}(?![0-9]|\\s?[元万%])")

    private const val MAX_DISTANCE = 20

    /** @return the code, or null if [body] does not look like a verification SMS. */
    fun extract(body: String): String? {
        val keywords = KEYWORDS.findAll(body).toList()
        if (keywords.isEmpty()) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (digits in DIGITS.findAll(body)) {
            for (keyword in keywords) {
                val distance =
                    when {
                        digits.range.first > keyword.range.last -> digits.range.first - keyword.range.last - 1
                        digits.range.last < keyword.range.first -> keyword.range.first - digits.range.last - 1
                        else -> 0
                    }
                if (distance <= MAX_DISTANCE && distance < bestDistance) {
                    best = digits.value
                    bestDistance = distance
                }
            }
        }
        return best
    }
}

/** The latest verification code, kept for a few minutes so it can be offered when the keyboard opens. */
object SmsCodeStore {
    const val TTL_MS = 10 * 60_000L

    class Entry(
        val code: String,
        val receivedAt: Long,
    )

    fun interface Listener {
        fun onCode(entry: Entry)
    }

    private const val PREFS = "sms_code"
    private const val KEY_CODE = "code"
    private const val KEY_TIME = "time"

    private val listeners = WeakHashSet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun post(
        context: Context,
        entry: Entry,
    ) {
        prefs(context).edit().putString(KEY_CODE, entry.code).putLong(KEY_TIME, entry.receivedAt).apply()
        listeners.toList().forEach { runCatching { it.onCode(entry) } }
    }

    /** The newest code that has not been used or expired yet. */
    fun fresh(context: Context): Entry? {
        val p = prefs(context)
        val code = p.getString(KEY_CODE, null) ?: return null
        val time = p.getLong(KEY_TIME, 0L)
        if (System.currentTimeMillis() - time > TTL_MS) return null
        return Entry(code, time)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CODE).remove(KEY_TIME).apply()
    }
}
