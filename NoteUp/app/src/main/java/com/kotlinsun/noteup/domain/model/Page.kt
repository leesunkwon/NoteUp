package com.kotlinsun.noteup.domain.model

data class Page(
    val id: Long,
    val noteId: Long,
    val pageIndex: Int,
    val templateType: PageTemplate,
    val createdAt: Long,
    val updatedAt: Long,
    val pdfBackground: PdfPageBackground? = null,
)

data class PdfPageBackground(
    val pdfId: Long,
    val storageName: String,
    val displayName: String,
    val sourcePageIndex: Int,
    val widthPoints: Int,
    val heightPoints: Int,
)

data class PdfImportPage(
    val sourcePageIndex: Int,
    val widthPoints: Int,
    val heightPoints: Int,
)

data class DeletedAssets(
    val pageIds: List<Long> = emptyList(),
    val pdfStorageNames: List<String> = emptyList(),
)

enum class PageTemplate {
    BLANK,
    GRID,
    LINED,
}
