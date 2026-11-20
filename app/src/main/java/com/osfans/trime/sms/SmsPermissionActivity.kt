/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.sms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** A transparent activity used only to ask for the SMS permission (an IME cannot do it itself). */
class SmsPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Toast.makeText(applicationContext, "短信权限已开启，收到验证码后键盘会自动提示", Toast.LENGTH_LONG).show()
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECEIVE_SMS)) {
                // No dialog can be shown (the system blocks it for apps installed outside a store, or it
                // was refused for good), so take the person to the app's settings page instead.
                Toast.makeText(
                    applicationContext,
                    "请在应用信息里：先点右上角菜单选“允许受限制的设置”（如有），再到“权限”里允许短信",
                    Toast.LENGTH_LONG,
                ).show()
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } else {
                Toast.makeText(applicationContext, "未开启短信权限，验证码提示不可用", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private companion object {
        const val REQUEST_SMS = 1001
    }
}
