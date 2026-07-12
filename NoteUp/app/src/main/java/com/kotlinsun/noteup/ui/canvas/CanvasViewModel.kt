package com.kotlinsun.noteup.ui.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.repository.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModel(
    private val noteId: Long,
    private val repository: NoteRepository,
) : ViewModel() {

    private val pendingSaves = MutableStateFlow(0)
    private val saveQueue = Channel<PendingStroke>(Channel.UNLIMITED)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _errors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors: Flow<Unit> = _errors

    private val content = combine(
        repository.observeNote(noteId),
        repository.observeFirstPage(noteId),
    ) { note, page -> note to page }
        .flatMapLatest { (note, page) ->
            if (note == null || page == null) {
                flowOf<CanvasUiState>(CanvasUiState.NotFound)
            } else {
                repository.observeStrokes(page.id).map { strokes ->
                    CanvasUiState.Ready(
                        note = note,
                        page = page,
                        strokes = strokes,
                        isSaving = false,
                    )
                }
            }
        }

    val uiState = combine(content, pendingSaves) { state, pendingCount ->
        if (state is CanvasUiState.Ready) {
            state.copy(isSaving = pendingCount > 0)
        } else {
            state
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CanvasUiState.Loading,
    )

    init {
        persistenceScope.launch {
            for (pendingStroke in saveQueue) {
                runCatching {
                    repository.saveStroke(
                        noteId = pendingStroke.noteId,
                        pageId = pendingStroke.pageId,
                        stroke = pendingStroke.stroke,
                    )
                }.onFailure {
                    _errors.tryEmit(Unit)
                }
                pendingSaves.value = (pendingSaves.value - 1).coerceAtLeast(0)
            }
        }
    }

    fun saveStroke(stroke: StrokeDraft) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        pendingSaves.value += 1
        val result = saveQueue.trySend(
            PendingStroke(noteId = noteId, pageId = state.page.id, stroke = stroke),
        )
        if (result.isFailure) {
            pendingSaves.value = (pendingSaves.value - 1).coerceAtLeast(0)
            _errors.tryEmit(Unit)
        }
    }

    override fun onCleared() {
        saveQueue.close()
        super.onCleared()
    }

    private data class PendingStroke(
        val noteId: Long,
        val pageId: Long,
        val stroke: StrokeDraft,
    )

    class Factory(
        private val noteId: Long,
        private val repository: NoteRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CanvasViewModel(noteId, repository) as T
    }
}
