package com.kotlinsun.noteup.domain.model

import android.net.Uri

data class PdfImportPreview(
    val uri: Uri,
    val displayName: String,
    val noteTitle: String,
    val pages: List<PdfImportPage>,
)

sealed interface PdfImportUiState {
    data object Idle : PdfImportUiState
    data object Inspecting : PdfImportUiState
    data class AwaitingConfirmation(val preview: PdfImportPreview) : PdfImportUiState
    data class Importing(val displayName: String) : PdfImportUiState
    data class Completed(val noteId: Long) : PdfImportUiState
    data class Error(val reason: PdfImportError) : PdfImportUiState
}

enum class PdfImportError {
    UNREADABLE,
    INVALID,
    PROTECTED,
    EMPTY,
    STORAGE,
}
