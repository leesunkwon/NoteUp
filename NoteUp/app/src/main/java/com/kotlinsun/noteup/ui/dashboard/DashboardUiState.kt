package com.kotlinsun.noteup.ui.dashboard

import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook

data class DashboardUiState(
    val notebooks: List<Notebook> = emptyList(),
    val notes: List<DashboardNoteItem> = emptyList(),
    val filter: DashboardFilter = DashboardFilter.All,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
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
    data object Trash : DashboardFilter
}

sealed interface DashboardEvent {
    data class NoteMovedToTrash(val noteId: Long, val title: String) : DashboardEvent
}
