/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.clip.ClipboardHelper
import com.osfans.trime.data.clip.FavoriteHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.InputTabLayout
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.segments.SegmentsWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.recyclerview.verticalLayoutManager

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardWindow(di: DI, private val initialTab: Int = 0) : BoardWindow.BarBoardWindow(di) {

    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    private lateinit var clipboardLayout: ClipboardLayout
    private lateinit var clipboardPagesAdapter: ClipboardPagesAdapter
    private var wasAttached = false

    private val prefs = AppPrefs.defaultInstance().clipboard
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val selectedClipboardCategory = MutableStateFlow("")
    private val selectedFavoriteCategory = MutableStateFlow("")
    private var currentClipboardCategories: List<String> = emptyList()
    private var currentFavoriteCategories: List<String> = emptyList()

    private var clipboardEntitiesSubmitJob: Job? = null
    private var favoriteEntitiesSubmitJob: Job? = null
    private var clipboardCategoriesJob: Job? = null
    private var favoriteCategoriesJob: Job? = null

    private var clipboardTouchHelper: ItemTouchHelper? = null
    private var favoriteTouchHelper: ItemTouchHelper? = null

    private val clipboardEntitiesAdapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(scope, clipboardMaskSensitive) {
            override fun onPaste(item: ClipboardItem) {
                service.commitText(item.text)
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_CLIPBOARD)
            }

            override fun onEditMeta(item: ClipboardItem) {
                showEditMetaDialog(item, currentClipboardCategories) { category, note ->
                    ClipboardHelper.updateMeta(item.id, category, note)
                }
            }

            override fun onReorder(updates: List<Pair<Int, Long>>) {
                service.lifecycleScope.launch { ClipboardHelper.updateSortOrders(updates) }
            }

            override fun onStartDrag(holder: RecyclerView.ViewHolder) {
                clipboardTouchHelper?.startDrag(holder)
            }

            override fun onShare(item: ClipboardItem) {
                launchTextSharing(item.text)
            }

            override fun onSegment(item: ClipboardItem) {
                windowManager.attachWindow(SegmentsWindow(di, item.text, item.mask))
            }

            override fun onFavorite(item: ClipboardItem) {
                FavoriteHelper.addNewEntity(item.text)
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.delete(id) }
            }

            override val enableClipActions: Boolean = true
        }
    }

    private val favoriteEntitiesAdapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(scope, false) {
            override fun onPaste(item: ClipboardItem) {
                service.commitText(item.text)
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onEdit(id: Int) {
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_COLLECTION)
            }

            override fun onEditMeta(item: ClipboardItem) {
                showEditMetaDialog(item, currentFavoriteCategories) { category, note ->
                    FavoriteHelper.updateMeta(item.id, category, note)
                }
            }

            override fun onReorder(updates: List<Pair<Int, Long>>) {
                service.lifecycleScope.launch { FavoriteHelper.updateSortOrders(updates) }
            }

            override fun onStartDrag(holder: RecyclerView.ViewHolder) {
                favoriteTouchHelper?.startDrag(holder)
            }

            override fun onShare(item: ClipboardItem) {
                launchTextSharing(item.text)
            }

            override fun onSegment(item: ClipboardItem) {
                windowManager.attachWindow(SegmentsWindow(di, item.text, item.mask))
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch { FavoriteHelper.delete(id) }
            }

            override val enableClipActions: Boolean = false
        }
    }

    private val clipboardPage by lazy {
        ClipboardPageUi(context).apply {
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = clipboardEntitiesAdapter
            }
            clipboardTouchHelper = attachDragHelper(clipboardEntitiesAdapter)
        }
    }

    private val favoritePage by lazy {
        ClipboardPageUi(context).apply {
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = favoriteEntitiesAdapter
            }
            favoriteTouchHelper = attachDragHelper(favoriteEntitiesAdapter)
        }
    }

    override fun onCreateView() = ClipboardLayout(context, scope).apply {
        clipboardLayout = this
        clipboardPagesAdapter = object : ClipboardPagesAdapter() {
            override fun getItemCount(): Int = 2
            override fun onCreatePage(position: Int): ClipboardPageUi = when (position) {
                0 -> clipboardPage
                else -> favoritePage
            }
        }
        viewPager.apply {
            adapter = clipboardPagesAdapter
        }
        titleUi.apply {
            tabLayout.onConfigureTab(viewPager) { tabUi, position ->
                configureTab(tabUi, position)
            }
            deleteAllButton.setOnClickListener {
                val currentItem = viewPager.currentItem
                when (currentItem) {
                    0 -> promptDeleteAll {
                        ClipboardHelper.deleteAll(ClipboardHelper.haveUnpinned())
                    }

                    else -> promptDeleteAll {
                        FavoriteHelper.deleteAll()
                    }
                }
            }
        }
    }

    private fun showEditMetaDialog(
        item: ClipboardItem,
        existingCategories: List<String>,
        onSave: suspend (String, String) -> Unit,
    ) {
        val pad = context.dp(16)
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
        val categoryInput =
            EditText(context).apply {
                hint = if (existingCategories.isNotEmpty()) {
                    "分类（已有：${existingCategories.joinToString("、")}）"
                } else {
                    "分类（可选）"
                }
                setText(item.category)
                setSingleLine()
            }
        val noteInput =
            EditText(context).apply {
                hint = "备注（可选）"
                setText(item.note)
                setSingleLine()
            }
        container.addView(categoryInput)
        container.addView(noteInput)

        val dialog =
            AlertDialog.Builder(context)
                .setTitle("备注与分类")
                .setView(container)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val cat = categoryInput.text.toString()
                    val note = noteInput.text.toString()
                    service.lifecycleScope.launch {
                        onSave(cat, note)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        service.showDialog(dialog)
    }

    private fun launchTextSharing(text: String) {
        val target = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(target, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(chooser)
    }

    private fun promptDeleteAll(action: suspend () -> Unit) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.delete_all)
            .setMessage(R.string.ask_to_delete_all)
            .setPositiveButton(R.string.ok) { _, _ ->
                service.lifecycleScope.launch {
                    action()
                }
            }.setNegativeButton(R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private fun configureTab(
        tabUi: InputTabLayout.TabUi,
        position: Int,
    ) {
        val label =
            when (position) {
                0 -> R.string.clipboard
                else -> R.string.collection
            }
        tabUi.label.apply {
            setText(label)
            textSize = theme.style.candidateTextSize
            setTypeface(theme.fonts.candidate, Typeface.BOLD)
            setTextColor(scope.colors.keyTextColor)
        }
    }

    override fun onAttached() {
        wasAttached = true
        clipboardLayout.viewPager.setCurrentItem(initialTab, false)
        clipboardEntitiesSubmitJob = service.lifecycleScope.launch {
            selectedClipboardCategory
                .flatMapLatest { cat ->
                    ClipboardHelper.allEntitiesFlow(cat).map { list -> list.map { it.toClipboardItem() } }
                }
                .collect { clipboardEntitiesAdapter.submitList(it) }
        }
        favoriteEntitiesSubmitJob = service.lifecycleScope.launch {
            selectedFavoriteCategory
                .flatMapLatest { cat ->
                    FavoriteHelper.allEntitiesFlow(cat).map { list -> list.map { it.toClipboardItem() } }
                }
                .collect { favoriteEntitiesAdapter.submitList(it) }
        }
        clipboardCategoriesJob = service.lifecycleScope.launch {
            combine(ClipboardHelper.categoriesFlow(), selectedClipboardCategory) { cats, sel ->
                cats to sel
            }.collect { (cats, sel) ->
                currentClipboardCategories = cats
                if (sel.isNotEmpty() && sel !in cats) {
                    selectedClipboardCategory.value = ""
                }
                clipboardPage.setCategories(
                    categories = cats,
                    selected = selectedClipboardCategory.value,
                    textColor = scope.colors.keyTextColor,
                    highlightColor = scope.colors.hilitedCandidateBackColor,
                ) { selectedClipboardCategory.value = it }
            }
        }
        favoriteCategoriesJob = service.lifecycleScope.launch {
            combine(FavoriteHelper.categoriesFlow(), selectedFavoriteCategory) { cats, sel ->
                cats to sel
            }.collect { (cats, sel) ->
                currentFavoriteCategories = cats
                if (sel.isNotEmpty() && sel !in cats) {
                    selectedFavoriteCategory.value = ""
                }
                favoritePage.setCategories(
                    categories = cats,
                    selected = selectedFavoriteCategory.value,
                    textColor = scope.colors.keyTextColor,
                    highlightColor = scope.colors.hilitedCandidateBackColor,
                ) { selectedFavoriteCategory.value = it }
            }
        }
    }

    override fun onDetached() {
        clipboardEntitiesAdapter.dismissPopupMenu()
        favoriteEntitiesAdapter.dismissPopupMenu()
        clipboardEntitiesSubmitJob?.cancel()
        favoriteEntitiesSubmitJob?.cancel()
        clipboardCategoriesJob?.cancel()
        favoriteCategoriesJob?.cancel()
    }

    override fun refreshColors() {
        if (!wasAttached) return
        clipboardLayout.titleUi.deleteAllButton.refreshColors()
        clipboardLayout.titleUi.tabLayout.reconfigureTabs { tabUi, position ->
            configureTab(tabUi, position)
        }
        clipboardPage.setCategories(
            categories = currentClipboardCategories,
            selected = selectedClipboardCategory.value,
            textColor = scope.colors.keyTextColor,
            highlightColor = scope.colors.hilitedCandidateBackColor,
        ) { selectedClipboardCategory.value = it }
        favoritePage.setCategories(
            categories = currentFavoriteCategories,
            selected = selectedFavoriteCategory.value,
            textColor = scope.colors.keyTextColor,
            highlightColor = scope.colors.hilitedCandidateBackColor,
        ) { selectedFavoriteCategory.value = it }
        clipboardEntitiesAdapter.refreshColors()
        favoriteEntitiesAdapter.refreshColors()
    }

    override fun onCreateBarView(): View = clipboardLayout.titleUi.root
}
