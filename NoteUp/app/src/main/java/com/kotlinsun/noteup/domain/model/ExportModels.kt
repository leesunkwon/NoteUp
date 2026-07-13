package com.kotlinsun.noteup.domain.model

import java.io.File

enum class ExportFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    PDF("pdf", "application/pdf"),
}

data class ExportArtifact(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val format: ExportFormat,
)

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Rendering(val completedPages: Int, val totalPages: Int) : ExportUiState
    data class Ready(val artifact: ExportArtifact) : ExportUiState
    data class Saving(val artifact: ExportArtifact) : ExportUiState
    data class Error(val message: String? = null) : ExportUiState
}
