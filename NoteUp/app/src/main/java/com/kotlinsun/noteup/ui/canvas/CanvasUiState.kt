package com.kotlinsun.noteup.ui.canvas

import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.CanvasText

data class CanvasViewport(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val referenceWidth: Int = 0,
    val referenceHeight: Int = 0,
)

sealed interface CanvasUiState {
    data object Loading : CanvasUiState
    data object NotFound : CanvasUiState
    data class Ready(
        val note: Note,
        val pages: List<Page>,
        val page: Page,
        val pagePosition: Int,
        val strokes: List<Stroke>,
        val texts: List<CanvasText>,
        val viewport: CanvasViewport,
        val thumbnailRevisions: Map<Long, Long>,
        val isSaving: Boolean,
        val isBusy: Boolean,
        val isPageChanging: Boolean,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val hasSelection: Boolean = false,
        val canPaste: Boolean = false,
        val selection: CanvasSelection = CanvasSelection(),
        val isExporting: Boolean = false,
        val exportCompletedPages: Int = 0,
        val exportTotalPages: Int = 0,
    ) : CanvasUiState
}
