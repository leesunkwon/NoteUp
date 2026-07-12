package com.kotlinsun.noteup.ui.dashboard

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.savedstate.SavedStateRegistryOwner
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook
import com.kotlinsun.noteup.domain.repository.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val repository: NoteRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val filterType = savedStateHandle.getStateFlow(FILTER_TYPE_KEY, FILTER_ALL)
    private val notebookId = savedStateHandle.getStateFlow(NOTEBOOK_ID_KEY, NO_NOTEBOOK_ID)

    private val selectedFilter: StateFlow<DashboardFilter> = combine(
        filterType,
        notebookId,
    ) { type, id ->
        when (type) {
            FILTER_UNFILED -> DashboardFilter.Unfiled
            FILTER_NOTEBOOK -> DashboardFilter.NotebookFilter(id)
            else -> DashboardFilter.All
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardFilter.All,
    )

    private val notes = selectedFilter.flatMapLatest { filter ->
        when (filter) {
            DashboardFilter.All -> repository.observeAllNotes()
            DashboardFilter.Unfiled -> repository.observeUnfiledNotes()
            is DashboardFilter.NotebookFilter -> repository.observeNotes(filter.notebookId)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeNotebooks(),
        notes,
        selectedFilter,
    ) { notebooks, notes, filter ->
        DashboardUiState(notebooks = notebooks, notes = notes, filter = filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    private val _errors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors: Flow<Unit> = _errors

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
        repository.deleteNote(note.id)
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
    ) : AbstractSavedStateViewModelFactory(owner, null) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            key: String,
            modelClass: Class<T>,
            handle: SavedStateHandle,
        ): T = DashboardViewModel(repository, handle) as T
    }

    private companion object {
        const val FILTER_TYPE_KEY = "dashboard_filter_type"
        const val NOTEBOOK_ID_KEY = "dashboard_notebook_id"
        const val FILTER_ALL = "all"
        const val FILTER_UNFILED = "unfiled"
        const val FILTER_NOTEBOOK = "notebook"
        const val NO_NOTEBOOK_ID = -1L
    }
}
