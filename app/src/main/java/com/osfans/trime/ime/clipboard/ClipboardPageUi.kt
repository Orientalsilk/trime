/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.ime.symbol.SpacesItemDecoration
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.setPaddingDp

class ClipboardPageUi(override val ctx: Context) : Ui {
    private val categoryRow = horizontalLayout()

    private val categoryBar =
        view(::HorizontalScrollView) {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            add(categoryRow, lParams(wrapContent, wrapContent))
        }

    val recyclerView =
        recyclerView {
            addItemDecoration(SpacesItemDecoration(dp(4)))
        }

    override val root: LinearLayout =
        verticalLayout {
            add(categoryBar, lParams(matchParent, wrapContent))
            add(
                recyclerView,
                lParams(matchParent, 0) {
                    weight = 1f
                },
            )
        }

    fun setCategories(
        categories: List<String>,
        selected: String,
        textColor: Int,
        highlightColor: Int,
        onSelect: (String) -> Unit,
    ) {
        if (categories.isEmpty()) {
            categoryBar.visibility = View.GONE
            return
        }
        categoryBar.visibility = View.VISIBLE
        categoryRow.removeAllViews()
        val items = listOf("" to "全部") + categories.map { it to it }
        items.forEach { (key, label) ->
            val isSelected = key == selected
            val chip =
                ctx.textView {
                    text = label
                    textSize = 12f
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isSelected) highlightColor else textColor)
                    alpha = if (isSelected) 1f else 0.7f
                    setPaddingDp(10, 6, 10, 6)
                    setOnClickListener { onSelect(key) }
                }
            categoryRow.apply { add(chip, lParams(wrapContent, wrapContent)) }
        }
    }

    fun attachDragHelper(adapter: ClipboardAdapter): ItemTouchHelper {
        val callback =
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0,
            ) {
                override fun isLongPressDragEnabled(): Boolean = false

                override fun onSelectedChanged(
                    viewHolder: RecyclerView.ViewHolder?,
                    actionState: Int,
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        adapter.onDragStart()
                        viewHolder?.itemView?.alpha = 0.85f
                    }
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = adapter.onDragMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

                override fun onSwiped(
                    viewHolder: RecyclerView.ViewHolder,
                    direction: Int,
                ) = Unit

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.alpha = 1.0f
                    adapter.onDragEnd()
                }
            }
        return ItemTouchHelper(callback).also { it.attachToRecyclerView(recyclerView) }
    }
}
