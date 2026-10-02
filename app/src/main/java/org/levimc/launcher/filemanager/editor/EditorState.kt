package org.levimc.launcher.filemanager.editor

/**
 * v664：编辑器状态占位（原 com.movtery.zalithlauncher.ui.code_editor.EditorState
 * 随编辑器 UI 模块未搬迁，此处按 viewmodel 用法补最小实现）。
 */
sealed class EditorState {
    object Loading : EditorState()
    data class Success(val content: String) : EditorState()
}

/**
 * v664：sora-editor 的 Content 桩（编辑器 UI 未搬，viewmodel 最小可用）。
 */
class Content(val text: String) {
    override fun toString(): String = text
}
