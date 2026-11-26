/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.GestureFrame
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchConstraints
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardEntityUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    private val theme: Theme get() = scope.theme

    val metaTextView =
        textView {
            minLines = 1
            maxLines = 1
            textSize = 11f
            typeface = theme.fonts.key
            setPaddingDp(8, 4, 4, 0)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(scope.colors.keySymbolColor)
            visibility = View.GONE
        }

    val textView =
        textView {
            minLines = 1
            maxLines = 4
            textSize = 14f
            typeface = theme.fonts.key
            setPaddingDp(8, 4, 4, 4)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(scope.colors.keyTextColor)
        }

    private val contentColumn =
        verticalLayout {
            add(metaTextView, lParams(matchParent, wrapContent))
            add(textView, lParams(matchParent, wrapContent))
        }

    val dragHandle =
        textView {
            text = "⋮⋮"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(scope.colors.keySymbolColor)
            alpha = 0.45f
            setPaddingDp(4, 0, 4, 0)
        }

    val pin =
        imageView {
            imageDrawable =
                drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
                    setTint(scope.colors.keySymbolColor)
                    setAlpha(0.3f)
                }
        }

    val layout =
        constraintLayout {
            add(
                dragHandle,
                lParams(dp(26), matchParent) {
                    endOfParent()
                    topOfParent()
                    bottomOfParent()
                },
            )
            add(
                contentColumn,
                lParams(matchConstraints, wrapContent) {
                    startOfParent()
                    before(dragHandle)
                    centerVertically()
                },
            )
            add(
                pin,
                lParams(dp(12), dp(12)) {
                    bottomOfParent(dp(2))
                    before(dragHandle, dp(2))
                },
            )
        }

    override val root =
        GestureFrame(ctx).apply {
            isClickable = true
            minimumHeight = dp(30)
            background =
                scope.decorDrawable(
                    "key_back_color",
                    "key_border_color",
                    dp(theme.style.keyBorder),
                    dp(theme.style.roundCorner),
                )
            add(layout, lParams(matchParent, matchParent))
            layoutParams = lParams(matchParent, wrapContent)
        }

    fun setEntity(
        text: String,
        pinned: Boolean,
        category: String = "",
        note: String = "",
    ) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
        val meta =
            buildString {
                if (category.isNotEmpty()) append("[$category] ")
                if (note.isNotEmpty()) append(note)
            }.trim()
        metaTextView.text = meta
        metaTextView.visibility = if (meta.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Restyles the row after a scheme switch. */
    fun refreshColors() {
        metaTextView.setTextColor(scope.colors.keySymbolColor)
        textView.setTextColor(scope.colors.keyTextColor)
        dragHandle.setTextColor(scope.colors.keySymbolColor)
        pin.drawable?.setTint(scope.colors.keySymbolColor)
        root.background =
            scope.decorDrawable(
                "key_back_color",
                "key_border_color",
                ctx.dp(theme.style.keyBorder),
                ctx.dp(theme.style.roundCorner),
            )
    }
}
