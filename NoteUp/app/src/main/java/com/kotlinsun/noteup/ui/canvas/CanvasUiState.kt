package com.kotlinsun.noteup.ui.canvas

import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.Stroke

sealed interface CanvasUiState {
    data object Loading : CanvasUiState
    data object NotFound : CanvasUiState
    data class Ready(
        val note: Note,
        val page: Page,
        val strokes: List<Stroke>,
        val isSaving: Boolean,
    ) : CanvasUiState
}
