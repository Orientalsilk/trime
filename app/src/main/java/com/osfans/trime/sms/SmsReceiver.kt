/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.osfans.trime.data.prefs.AppPrefs

/** Picks the verification code out of an incoming SMS so the keyboard can offer it. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppPrefs.defaultInstance().clipboard.smsCodeSuggestion.getValue()) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val code = SmsCode.extract(body) ?: return
        SmsCodeStore.post(context, SmsCodeStore.Entry(code, System.currentTimeMillis()))
    }
}
