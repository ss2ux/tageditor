package com.ss2ux.tageditor

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * 글자를 길게 눌렀을 때 뜨는 메뉴(잘라내기/복사/붙여넣기/모두 선택)에
 * "DeepL 번역" 항목을 하나 더 붙인 텍스트 메뉴.
 * onTranslate 가 null 이면 번역 항목은 보이지 않는다.
 */
class TranslateTextToolbar(private val view: View) : TextToolbar {

    var onTranslate: (() -> Unit)? = null

    private var actionMode: ActionMode? = null
    private var rect: Rect = Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.rect = rect
        this.onCopy = onCopyRequested
        this.onPaste = onPasteRequested
        this.onCut = onCutRequested
        this.onSelectAll = onSelectAllRequested
        if (actionMode == null) {
            status = TextToolbarStatus.Shown
            actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        } else {
            actionMode?.invalidate()
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    private fun buildMenu(menu: Menu) {
        if (onCopy != null) menu.add(0, ID_COPY, 0, android.R.string.copy)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (onPaste != null) menu.add(0, ID_PASTE, 1, android.R.string.paste)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (onCut != null) menu.add(0, ID_CUT, 2, android.R.string.cut)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        // 순서: 복사 / 붙여넣기 / 잘라내기 / DeepL 번역 / 모두 선택
        if (onTranslate != null) menu.add(0, ID_TRANSLATE, 3, "DeepL 번역")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (onSelectAll != null) menu.add(0, ID_SELECT_ALL, 4, android.R.string.selectAll)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            buildMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            buildMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY -> onCopy?.invoke()
                ID_PASTE -> onPaste?.invoke()
                ID_CUT -> onCut?.invoke()
                ID_SELECT_ALL -> onSelectAll?.invoke()
                ID_TRANSLATE -> onTranslate?.invoke()
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            status = TextToolbarStatus.Hidden
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    private companion object {
        const val ID_COPY = 1
        const val ID_PASTE = 2
        const val ID_CUT = 3
        const val ID_SELECT_ALL = 4
        const val ID_TRANSLATE = 5
    }
}
