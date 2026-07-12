package com.kotlinsun.noteup.domain.model

data class Page(
    val id: Long,
    val noteId: Long,
    val pageIndex: Int,
    val templateType: PageTemplate,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class PageTemplate {
    BLANK,
    GRID,
    LINED,
}
