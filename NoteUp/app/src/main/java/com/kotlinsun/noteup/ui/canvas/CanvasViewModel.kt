package com.kotlinsun.noteup.ui.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kotlinsun.noteup.data.preferences.DrawingToolSettingsStore
import com.kotlinsun.noteup.domain.model.DrawingSettings
import com.kotlinsun.noteup.domain.model.DrawingTool
import com.kotlinsun.noteup.domain.model.EraserMode
import com.kotlinsun.noteup.domain.model.HighlighterColor
import com.kotlinsun.noteup.domain.model.HighlighterThickness
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.PenThickness
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.repository.NoteRepository
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModel(
    private val noteId: Long,
    private val repository: NoteRepository,
    private val settingsStore: DrawingToolSettingsStore,
) : ViewModel() {

    private val operationQueue = Channel<CanvasOperation>(Channel.UNLIMITED)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenToStroke = mutableMapOf<Long, Stroke>()
    private val tokensScheduledForErase = ConcurrentHashMap.newKeySet<Long>()
    private val undoStack = ArrayDeque<CanvasCommand>()
    private val redoStack = ArrayDeque<CanvasCommand>()
    private val pendingOperations = MutableStateFlow(0)
    private val pageCreationInProgress = MutableStateFlow(false)
    private val suppressedStrokeIds = MutableStateFlow<Set<Long>>(emptySet())
    private val historyState = MutableStateFlow(HistoryState())
    private val selectedPageId = MutableStateFlow<Long?>(null)
    private val viewportState = MutableStateFlow<Map<Long, CanvasViewport>>(emptyMap())
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings = _settings.asStateFlow()
    private val _errors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors: Flow<Unit> = _errors
    private val _events = MutableSharedFlow<CanvasEvent>(extraBufferCapacity = 8)
    val events: Flow<CanvasEvent> = _events

    private val content = combine(
        repository.observeNote(noteId),
        repository.observePages(noteId),
        selectedPageId,
    ) { note, pages, requestedPageId ->
        val page = pages.firstOrNull { it.id == requestedPageId } ?: pages.firstOrNull()
        Triple(note, pages, page)
    }.flatMapLatest { (note, pages, page) ->
            if (note == null || page == null) {
                flowOf<CanvasUiState>(CanvasUiState.NotFound)
            } else {
                combine(repository.observeStrokes(page.id), suppressedStrokeIds) { strokes, hidden ->
                    CanvasUiState.Ready(
                        note = note,
                        pages = pages,
                        page = page,
                        pagePosition = pages.indexOfFirst { it.id == page.id },
                        strokes = strokes.filterNot { it.id in hidden },
                        viewport = CanvasViewport(),
                        isSaving = false,
                        isBusy = false,
                        isPageChanging = false,
                        canUndo = false,
                        canRedo = false,
                    )
                }
            }
        }

    val uiState = combine(
        content, pendingOperations, historyState, viewportState, pageCreationInProgress,
    ) { state, pending, history, viewports, isPageChanging ->
        if (state is CanvasUiState.Ready) {
            state.copy(
                viewport = viewports[state.page.id] ?: CanvasViewport(),
                isSaving = pending > 0,
                isBusy = pending > 0,
                isPageChanging = isPageChanging,
                canUndo = pending == 0 && history.canUndo,
                canRedo = pending == 0 && history.canRedo,
            )
        } else state
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CanvasUiState.Loading,
    )

    init {
        persistenceScope.launch {
            for (operation in operationQueue) {
                processOperation(operation)
                if (operation is CanvasOperation.CreatePage) pageCreationInProgress.value = false
                pendingOperations.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    fun addStroke(stroke: PendingCanvasStroke) {
        val pageId = (uiState.value as? CanvasUiState.Ready)?.page?.id
        if (pageId == null) {
            _events.tryEmit(CanvasEvent.PendingDiscarded(stroke.token))
            _errors.tryEmit(Unit)
            return
        }
        enqueue(CanvasOperation.Add(stroke, pageId))
    }

    fun eraseStrokes(strokes: List<ErasableStroke>) {
        if (strokes.isEmpty()) return
        strokes.forEach { target ->
            when (target) {
                is ErasableStroke.Persisted -> suppress(target.stroke.id)
                is ErasableStroke.Pending -> tokensScheduledForErase += target.stroke.token
            }
        }
        enqueue(CanvasOperation.Erase(strokes))
    }

    fun eraseArea(replacements: List<AreaEraseReplacement>) {
        if (replacements.isEmpty()) return
        replacements.forEach { replacement ->
            when (val target = replacement.target) {
                is ErasableStroke.Persisted -> suppress(target.stroke.id)
                is ErasableStroke.Pending -> tokensScheduledForErase += target.stroke.token
            }
        }
        enqueue(CanvasOperation.AreaErase(replacements))
    }

    fun undo() {
        if (pendingOperations.value == 0 && historyState.value.canUndo) enqueue(CanvasOperation.Undo)
    }

    fun redo() {
        if (pendingOperations.value == 0 && historyState.value.canRedo) enqueue(CanvasOperation.Redo)
    }

    fun selectPreviousPage() {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value > 0 || state.pagePosition <= 0) return
        switchPage(state.pages[state.pagePosition - 1])
    }

    fun selectNextPage() {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value > 0 || state.pagePosition >= state.pages.lastIndex) return
        switchPage(state.pages[state.pagePosition + 1])
    }

    fun createPage(template: PageTemplate) {
        if (pendingOperations.value == 0) {
            pageCreationInProgress.value = true
            if (!enqueue(CanvasOperation.CreatePage(template))) {
                pageCreationInProgress.value = false
            }
        }
    }

    fun updateViewport(viewport: CanvasViewport) {
        val pageId = (uiState.value as? CanvasUiState.Ready)?.page?.id ?: return
        viewportState.update { it + (pageId to viewport) }
    }

    fun selectTool(tool: DrawingTool) = updateSettings(_settings.value.copy(tool = tool))
    fun selectEraserMode(mode: EraserMode) = updateSettings(_settings.value.copy(eraserMode = mode))
    fun selectPenColor(color: PenColor) = updateSettings(
        _settings.value.copy(pen = _settings.value.pen.copy(color = color)),
    )
    fun selectPenThickness(thickness: PenThickness) = updateSettings(
        _settings.value.copy(pen = _settings.value.pen.copy(thickness = thickness)),
    )
    fun selectHighlighterColor(color: HighlighterColor) = updateSettings(
        _settings.value.copy(highlighter = _settings.value.highlighter.copy(color = color)),
    )
    fun selectHighlighterThickness(thickness: HighlighterThickness) = updateSettings(
        _settings.value.copy(highlighter = _settings.value.highlighter.copy(thickness = thickness)),
    )

    private suspend fun processOperation(operation: CanvasOperation) {
        runCatching {
            when (operation) {
                is CanvasOperation.Add -> processAdd(operation.stroke, operation.pageId)
                is CanvasOperation.Erase -> processErase(operation.targets)
                is CanvasOperation.AreaErase -> processAreaErase(operation.replacements)
                is CanvasOperation.CreatePage -> processCreatePage(operation.template)
                CanvasOperation.Undo -> processUndo()
                CanvasOperation.Redo -> processRedo()
            }
        }.onFailure {
            rollbackVisualState(operation)
            _errors.tryEmit(Unit)
            _events.tryEmit(CanvasEvent.RefreshStrokes)
        }
    }

    private suspend fun processAdd(pending: PendingCanvasStroke, pageId: Long) {
        val stroke = repository.saveStroke(noteId, pageId, pending.draft)
        tokenToStroke[pending.token] = stroke
        if (pending.token in tokensScheduledForErase) suppress(stroke.id)
        pushNewCommand(CanvasCommand.AddStroke(stroke))
        _events.tryEmit(CanvasEvent.PendingPersisted(pending.token))
    }

    private suspend fun processErase(targets: List<ErasableStroke>) {
        val strokes = targets.mapNotNull { target ->
            when (target) {
                is ErasableStroke.Persisted -> target.stroke
                is ErasableStroke.Pending -> tokenToStroke[target.stroke.token]
            }
        }.distinctBy(Stroke::id)
        targets.filterIsInstance<ErasableStroke.Pending>().forEach {
            tokensScheduledForErase -= it.stroke.token
        }
        if (strokes.isEmpty()) return
        strokes.forEach { suppress(it.id) }
        repository.deleteStrokes(noteId, strokes)
        pushNewCommand(CanvasCommand.DeleteStrokes(strokes))
    }

    private suspend fun processAreaErase(replacements: List<AreaEraseReplacement>) {
        val resolved = replacements.mapNotNull { replacement ->
            val stroke = when (val target = replacement.target) {
                is ErasableStroke.Persisted -> target.stroke
                is ErasableStroke.Pending -> tokenToStroke[target.stroke.token]
            }
            stroke?.let { it to replacement.fragments }
        }
        replacements.map { it.target }.filterIsInstance<ErasableStroke.Pending>().forEach {
            tokensScheduledForErase -= it.stroke.token
        }
        if (resolved.isEmpty()) return
        val removed = resolved.map { it.first }.distinctBy(Stroke::id)
        removed.forEach { suppress(it.id) }
        val added = repository.replaceStrokes(noteId, removed, resolved.flatMap { it.second })
        pushNewCommand(CanvasCommand.ReplaceStrokes(removed, added))
    }

    private suspend fun processCreatePage(template: PageTemplate) {
        val pageId = repository.createPage(noteId, template)
        clearPageSession()
        selectedPageId.value = pageId
        viewportState.update { it + (pageId to CanvasViewport()) }
    }

    private fun switchPage(page: Page) {
        clearPageSession()
        selectedPageId.value = page.id
    }

    private fun clearPageSession() {
        undoStack.clear()
        redoStack.clear()
        tokenToStroke.clear()
        tokensScheduledForErase.clear()
        suppressedStrokeIds.value = emptySet()
        publishHistoryState()
    }

    private suspend fun processUndo() {
        val command = undoStack.peekLast() ?: return
        applyInverse(command)
        undoStack.removeLast()
        redoStack.addLast(command)
        publishHistoryState()
    }

    private suspend fun processRedo() {
        val command = redoStack.peekLast() ?: return
        apply(command)
        redoStack.removeLast()
        undoStack.addLast(command)
        publishHistoryState()
    }

    private suspend fun apply(command: CanvasCommand) {
        when (command) {
            is CanvasCommand.AddStroke -> {
                unsuppress(command.stroke.id)
                repository.restoreStrokes(noteId, listOf(command.stroke))
            }
            is CanvasCommand.DeleteStrokes -> {
                command.strokes.forEach { suppress(it.id) }
                repository.deleteStrokes(noteId, command.strokes)
            }
            is CanvasCommand.ReplaceStrokes -> {
                command.before.forEach { suppress(it.id) }
                command.after.forEach { unsuppress(it.id) }
                repository.deleteStrokes(noteId, command.before)
                repository.restoreStrokes(noteId, command.after)
            }
        }
    }

    private suspend fun applyInverse(command: CanvasCommand) {
        when (command) {
            is CanvasCommand.AddStroke -> {
                suppress(command.stroke.id)
                repository.deleteStrokes(noteId, listOf(command.stroke))
            }
            is CanvasCommand.DeleteStrokes -> {
                command.strokes.forEach { unsuppress(it.id) }
                repository.restoreStrokes(noteId, command.strokes)
            }
            is CanvasCommand.ReplaceStrokes -> {
                command.after.forEach { suppress(it.id) }
                command.before.forEach { unsuppress(it.id) }
                repository.deleteStrokes(noteId, command.after)
                repository.restoreStrokes(noteId, command.before)
            }
        }
    }

    private fun pushNewCommand(command: CanvasCommand) {
        undoStack.addLast(command)
        redoStack.clear()
        publishHistoryState()
    }

    private fun rollbackVisualState(operation: CanvasOperation) {
        when (operation) {
            is CanvasOperation.Add -> _events.tryEmit(CanvasEvent.PendingDiscarded(operation.stroke.token))
            is CanvasOperation.Erase -> operation.targets.forEach { target ->
                when (target) {
                    is ErasableStroke.Persisted -> unsuppress(target.stroke.id)
                    is ErasableStroke.Pending -> {
                        tokensScheduledForErase -= target.stroke.token
                        tokenToStroke[target.stroke.token]?.let { unsuppress(it.id) }
                    }
                }
            }
            is CanvasOperation.AreaErase -> operation.replacements.forEach { replacement ->
                when (val target = replacement.target) {
                    is ErasableStroke.Persisted -> unsuppress(target.stroke.id)
                    is ErasableStroke.Pending -> {
                        tokensScheduledForErase -= target.stroke.token
                        tokenToStroke[target.stroke.token]?.let { unsuppress(it.id) }
                    }
                }
            }
            is CanvasOperation.CreatePage -> Unit
            CanvasOperation.Undo -> rollbackUndo()
            CanvasOperation.Redo -> rollbackRedo()
        }
    }

    private fun rollbackUndo() {
        when (val command = undoStack.peekLast()) {
            is CanvasCommand.AddStroke -> unsuppress(command.stroke.id)
            is CanvasCommand.DeleteStrokes -> command.strokes.forEach { suppress(it.id) }
            is CanvasCommand.ReplaceStrokes -> {
                command.before.forEach { suppress(it.id) }
                command.after.forEach { unsuppress(it.id) }
            }
            null -> Unit
        }
    }

    private fun rollbackRedo() {
        when (val command = redoStack.peekLast()) {
            is CanvasCommand.AddStroke -> suppress(command.stroke.id)
            is CanvasCommand.DeleteStrokes -> command.strokes.forEach { unsuppress(it.id) }
            is CanvasCommand.ReplaceStrokes -> {
                command.before.forEach { unsuppress(it.id) }
                command.after.forEach { suppress(it.id) }
            }
            null -> Unit
        }
    }

    private fun suppress(strokeId: Long) {
        suppressedStrokeIds.update { it + strokeId }
    }

    private fun unsuppress(strokeId: Long) {
        suppressedStrokeIds.update { it - strokeId }
    }

    private fun enqueue(operation: CanvasOperation): Boolean {
        pendingOperations.update { it + 1 }
        val result = operationQueue.trySend(operation)
        if (result.isFailure) {
            pendingOperations.update { (it - 1).coerceAtLeast(0) }
            _errors.tryEmit(Unit)
        }
        return result.isSuccess
    }

    private fun updateSettings(settings: DrawingSettings) {
        _settings.value = settings
        settingsStore.save(settings)
    }

    private fun publishHistoryState() {
        historyState.value = HistoryState(undoStack.isNotEmpty(), redoStack.isNotEmpty())
    }

    override fun onCleared() {
        operationQueue.close()
        super.onCleared()
    }

    private sealed interface CanvasOperation {
        data class Add(val stroke: PendingCanvasStroke, val pageId: Long) : CanvasOperation
        data class Erase(val targets: List<ErasableStroke>) : CanvasOperation
        data class AreaErase(val replacements: List<AreaEraseReplacement>) : CanvasOperation
        data class CreatePage(val template: PageTemplate) : CanvasOperation
        data object Undo : CanvasOperation
        data object Redo : CanvasOperation
    }

    private sealed interface CanvasCommand {
        data class AddStroke(val stroke: Stroke) : CanvasCommand
        data class DeleteStrokes(val strokes: List<Stroke>) : CanvasCommand
        data class ReplaceStrokes(val before: List<Stroke>, val after: List<Stroke>) : CanvasCommand
    }

    private data class HistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false)

    class Factory(
        private val noteId: Long,
        private val repository: NoteRepository,
        private val settingsStore: DrawingToolSettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CanvasViewModel(noteId, repository, settingsStore) as T
    }
}
