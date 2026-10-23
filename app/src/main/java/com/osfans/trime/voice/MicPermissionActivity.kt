/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/** A transparent activity used only to ask for the microphone permission (an IME cannot do it itself). */
class MicPermissionActivity : Activity() {
    private var permissionRequestedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        permissionRequestedBefore =
            getPreferences(MODE_PRIVATE).getBoolean(KEY_PERMISSION_REQUESTED, false)
        getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val message =
                when {
                    granted -> "麦克风权限已开启，请返回键盘再点一次语音键"

                    shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                        "语音输入需要麦克风权限；请再次点击语音键并允许使用麦克风"

                    permissionRequestedBefore ->
                        "麦克风权限未开启。请到系统设置中找到同文输入法，允许麦克风权限后重试"

                    else ->
                        "未获得麦克风权限，语音输入暂不可用；再次点击语音键可重新申请"
                }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 1
        const val KEY_PERMISSION_REQUESTED = "microphone_permission_requested"
    }
}
