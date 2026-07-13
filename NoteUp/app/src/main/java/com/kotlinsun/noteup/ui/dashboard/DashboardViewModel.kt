package com.kotlinsun.noteup.ui.dashboard

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.savedstate.SavedStateRegistryOwner
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook
import com.kotlinsun.noteup.domain.repository.NoteRepository
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailService
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
import com.kotlinsun.noteup.data.trash.TrashCleanupService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DashboardViewModel(
    private val repository: NoteRepository,
    private val savedStateHandle: SavedStateHandle,
    private val thumbnailStore: PageThumbnailStore,
    private val thumbnailService: PageThumbnailService,
    private val trashCleanupService: TrashCleanupService,
) : ViewModel() {

    private val filterType = savedStateHandle.getStateFlow(FILTER_TYPE_KEY, FILTER_ALL)
    private val notebookId = savedStateHandle.getStateFlow(NOTEBOOK_ID_KEY, NO_NOTEBOOK_ID)
    val searchQuery = savedStateHandle.getStateFlow(SEARCH_QUERY_KEY, "")
    private val debouncedQuery = searchQuery
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .map { it.trim() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, searchQuery.value.trim())

    private val selectedFilter: StateFlow<DashboardFilter> = combine(
        filterType,
        notebookId,
    ) { type, id ->
        when (type) {
            FILTER_UNFILED -> DashboardFilter.Unfiled
            FILTER_NOTEBOOK -> DashboardFilter.NotebookFilter(id)
            FILTER_TRASH -> DashboardFilter.Trash
            else -> DashboardFilter.All
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardFilter.All,
    )

    private val notes = combine(selectedFilter, debouncedQuery) { filter, query -> filter to query }
        .flatMapLatest { (filter, query) ->
            when (filter) {
                DashboardFilter.All -> repository.observeAllNotes(query)
                DashboardFilter.Unfiled -> repository.observeUnfiledNotes(query)
                is DashboardFilter.NotebookFilter -> repository.observeNotes(filter.notebookId, query)
                DashboardFilter.Trash -> repository.observeTrashedNotes(query)
            }
        }

    private val noteItems = combine(
        notes,
        repository.observeFirstPageIds(),
        thumbnailStore.revisions,
    ) { notes, firstPageIds, revisions ->
        val pageIds = notes.mapNotNull { firstPageIds[it.id] }
        thumbnailService.ensure(pageIds)
        notes.map { note ->
            val pageId = firstPageIds[note.id]
            DashboardNoteItem(note, pageId, pageId?.let { revisions[it] } ?: 0L)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeNotebooks(),
        noteItems,
        selectedFilter,
        searchQuery,
    ) { notebooks, notes, filter, query ->
        DashboardUiState(
            notebooks = notebooks,
            notes = notes,
            filter = filter,
            searchQuery = query,
            isSearching = query.trim() != debouncedQuery.value,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    private val _errors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors: Flow<Unit> = _errors
    private val _events = MutableSharedFlow<DashboardEvent>(extraBufferCapacity = 2)
    val events: Flow<DashboardEvent> = _events

    init { trashCleanupService.request() }

    fun selectAllNotes() {
        updateFilter(FILTER_ALL)
    }

    fun selectUnfiledNotes() {
        updateFilter(FILTER_UNFILED)
    }

    fun selectNotebook(notebookId: Long) {
        savedStateHandle[NOTEBOOK_ID_KEY] = notebookId
        updateFilter(FILTER_NOTEBOOK)
    }

    fun selectTrash() = updateFilter(FILTER_TRASH)

    fun setSearchQuery(query: String) { savedStateHandle[SEARCH_QUERY_KEY] = query }

    fun createNotebook(name: String) = launchDataOperation {
        repository.createNotebook(name.trim())
    }

    fun renameNotebook(notebook: Notebook, name: String) = launchDataOperation {
        repository.renameNotebook(notebook.id, name.trim())
    }

    fun deleteNotebook(notebook: Notebook) = launchDataOperation {
        if (currentFilter() == DashboardFilter.NotebookFilter(notebook.id)) {
            selectUnfiledNotes()
        }
        repository.deleteNotebook(notebook.id)
    }

    fun createNote(defaultTitle: String) = launchDataOperation {
        val parentId = (currentFilter() as? DashboardFilter.NotebookFilter)?.notebookId
        repository.createNote(defaultTitle, parentId)
    }

    fun renameNote(note: Note, title: String) = launchDataOperation {
        repository.renameNote(note.id, title.trim())
    }

    fun moveNote(note: Note, notebookId: Long?) = launchDataOperation {
        repository.moveNote(note.id, notebookId)
    }

    fun deleteNote(note: Note) = launchDataOperation {
        repository.moveNoteToTrash(note.id)
        _events.emit(DashboardEvent.NoteMovedToTrash(note.id, note.title))
    }

    fun restoreNote(noteId: Long) = launchDataOperation { repository.restoreNote(noteId) }

    fun permanentlyDeleteNote(noteId: Long) = launchDataOperation {
        repository.permanentlyDeleteNote(noteId).forEach { pageId ->
            runCatching { thumbnailService.delete(pageId) }
        }
    }

    private fun updateFilter(type: String) {
        savedStateHandle[FILTER_TYPE_KEY] = type
    }

    private fun currentFilter(): DashboardFilter = when (
        savedStateHandle.get<String>(FILTER_TYPE_KEY) ?: FILTER_ALL
    ) {
        FILTER_UNFILED -> DashboardFilter.Unfiled
        FILTER_NOTEBOOK -> DashboardFilter.NotebookFilter(
            savedStateHandle.get<Long>(NOTEBOOK_ID_KEY) ?: NO_NOTEBOOK_ID,
        )
        FILTER_TRASH -> DashboardFilter.Trash
        else -> DashboardFilter.All
    }

    private fun launchDataOperation(operation: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { operation() }
                .onFailure { _errors.tryEmit(Unit) }
        }
    }

    class Factory(
        owner: SavedStateRegistryOwner,
        private val repository: NoteRepository,
        private val thumbnailStore: PageThumbnailStore,
        private val thumbnailService: PageThumbnailService,
        private val trashCleanupService: TrashCleanupService,
    ) : AbstractSavedStateViewModelFactory(owner, null) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            key: String,
            modelClass: Class<T>,
            handle: SavedStateHandle,
        ): T = DashboardViewModel(
            repository, handle, thumbnailStore, thumbnailService, trashCleanupService,
        ) as T
    }

    private companion object {
        const val FILTER_TYPE_KEY = "dashboard_filter_type"
        const val NOTEBOOK_ID_KEY = "dashboard_notebook_id"
        const val FILTER_ALL = "all"
        const val FILTER_UNFILED = "unfiled"
        const val FILTER_NOTEBOOK = "notebook"
        const val NO_NOTEBOOK_ID = -1L
        const val FILTER_TRASH = "trash"
        const val SEARCH_QUERY_KEY = "dashboard_search_query"
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
