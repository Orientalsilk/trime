/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import com.osfans.trime.data.clip.ClipboardHelper
import com.osfans.trime.data.clip.FavoriteHelper
import com.osfans.trime.databinding.ActivityClipEditBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.systemservices.inputMethodManager
import timber.log.Timber

class ClipEditActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var entityId: Int = -1
    private lateinit var editText: EditText
    private lateinit var categoryInput: EditText
    private lateinit var noteInput: EditText
    private var clipType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        val binding =
            ActivityClipEditBinding.inflate(layoutInflater).apply {
                editText = clipEditText
                clipEditCancel.setOnClickListener { finish() }
                clipEditOk.setOnClickListener { finishEditing() }
            }
        val pad = dp(12)
        categoryInput =
            EditText(this).apply {
                hint = "分类（可选）"
                setSingleLine()
            }
        noteInput =
            EditText(this).apply {
                hint = "备注（可选）"
                setSingleLine()
            }
        val rootLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val metaRow =
                    LinearLayout(this@ClipEditActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad / 2, pad, 0)
                        addView(categoryInput)
                        addView(noteInput)
                    }
                addView(metaRow)
                addView(binding.root)
            }
        setContentView(rootLayout)
        editText.requestFocus()
        inputMethodManager.showSoftInput(editText, 0)
        processIntent(intent)
    }

    private fun finishEditing() {
        val str = editText.editableText.toString()
        val category = categoryInput.text.toString()
        val note = noteInput.text.toString()
        scope.launch {
            when (clipType) {
                FROM_CLIPBOARD -> {
                    ClipboardHelper.updateText(entityId, str)
                    ClipboardHelper.updateMeta(entityId, category, note)
                }

                FROM_COLLECTION -> {
                    FavoriteHelper.updateText(entityId, str)
                    FavoriteHelper.updateMeta(entityId, category, note)
                }

                else -> {}
            }
            finish()
        }
    }

    private fun setEntity(
        id: Int,
        text: String,
        category: String,
        note: String,
    ) {
        entityId = id
        editText.setText(text)
        categoryInput.setText(category)
        noteInput.setText(note)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        scope.launch {
            intent.run {
                val clipType = intent.getStringExtra(CLIP_TYPE) ?: return@launch
                val entityId = getIntExtra(ENTITY_ID, -1)
                Timber.d("processIntent: id=$entityId, type=$clipType")
                when (clipType) {
                    FROM_CLIPBOARD ->
                        ClipboardHelper.get(entityId)?.let {
                            this@ClipEditActivity.clipType = clipType
                            setEntity(it.id, it.text, it.category, it.note)
                        }

                    FROM_COLLECTION ->
                        FavoriteHelper.get(entityId)?.let {
                            this@ClipEditActivity.clipType = clipType
                            setEntity(it.id, it.text, it.category, it.note)
                        }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ENTITY_ID = "id"
        const val CLIP_TYPE = "clip_type"
        const val FROM_CLIPBOARD = "from_clipboard"
        const val FROM_COLLECTION = "from_collection"
    }
}
