package com.kotlinsun.noteup.ui.dashboard

import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook

data class DashboardUiState(
    val notebooks: List<Notebook> = emptyList(),
    val notes: List<DashboardNoteItem> = emptyList(),
    val filter: DashboardFilter = DashboardFilter.All,
)

data class DashboardNoteItem(
    val note: Note,
    val firstPageId: Long?,
    val thumbnailRevision: Long,
)

sealed interface DashboardFilter {
    data object All : DashboardFilter
    data object Unfiled : DashboardFilter
    data class NotebookFilter(val notebookId: Long) : DashboardFilter
}
