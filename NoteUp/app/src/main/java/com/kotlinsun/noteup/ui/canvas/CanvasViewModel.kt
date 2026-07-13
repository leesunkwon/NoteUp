package com.kotlinsun.noteup.ui.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.kotlinsun.noteup.data.preferences.DrawingToolSettingsStore
import com.kotlinsun.noteup.data.export.NoteExportService
import com.kotlinsun.noteup.data.pdf.PdfDocumentStore
import com.kotlinsun.noteup.data.pdf.PdfPageRenderStore
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailService
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
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
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasTextDraft
import com.kotlinsun.noteup.domain.model.TextSize
import com.kotlinsun.noteup.domain.model.ExportFormat
import com.kotlinsun.noteup.domain.model.ExportUiState
import com.kotlinsun.noteup.domain.model.Note
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
import kotlinx.coroutines.delay

private const val ADD_BATCH_WINDOW_MILLIS = 20L
private const val MAXIMUM_ADD_BATCH_SIZE = 16

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModel(
    private val noteId: Long,
    private val repository: NoteRepository,
    private val settingsStore: DrawingToolSettingsStore,
    private val thumbnailStore: PageThumbnailStore,
    private val thumbnailService: PageThumbnailService,
    private val exportService: NoteExportService,
    private val pdfDocumentStore: PdfDocumentStore,
    private val pdfPageRenderStore: PdfPageRenderStore,
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
    private val selectionState = MutableStateFlow(CanvasSelection())
    private val clipboardState = MutableStateFlow(CanvasSelection())
    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState = _exportState.asStateFlow()
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
                thumbnailService.ensure(pages.map(Page::id))
                combine(
                    repository.observeStrokes(page.id), repository.observeTexts(page.id),
                    suppressedStrokeIds, thumbnailStore.revisions,
                ) { strokes, texts, hidden, revisions ->
                    CanvasUiState.Ready(
                        note = note,
                        pages = pages,
                        page = page,
                        pagePosition = pages.indexOfFirst { it.id == page.id },
                        strokes = strokes.filterNot { it.id in hidden },
                        texts = texts,
                        viewport = CanvasViewport(),
                        thumbnailRevisions = revisions,
                        isSaving = false,
                        isBusy = false,
                        isPageChanging = false,
                        canUndo = false,
                        canRedo = false,
                    )
                }
            }
        }

    private val baseUiState = combine(
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
    }

    val uiState = combine(
        baseUiState, selectionState, clipboardState, exportState,
    ) { state, selection, clipboard, export ->
        val progress = export as? ExportUiState.Rendering
        val exportBusy = progress != null || export is ExportUiState.Saving
        if (state is CanvasUiState.Ready) state.copy(
            hasSelection = !selection.isEmpty,
            canPaste = !clipboard.isEmpty,
            selection = selection,
            isBusy = state.isBusy || exportBusy,
            isExporting = progress != null,
            exportCompletedPages = progress?.completedPages ?: 0,
            exportTotalPages = progress?.totalPages ?: 0,
        ) else state
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CanvasUiState.Loading,
    )

    init {
        persistenceScope.launch {
            var deferredOperation: CanvasOperation? = null
            while (true) {
                val operation = deferredOperation ?: operationQueue.receiveCatching().getOrNull() ?: break
                deferredOperation = null
                if (operation is CanvasOperation.Add) {
                    delay(ADD_BATCH_WINDOW_MILLIS)
                    val batch = mutableListOf(operation)
                    while (batch.size < MAXIMUM_ADD_BATCH_SIZE) {
                        val next = operationQueue.tryReceive().getOrNull() ?: break
                        if (next is CanvasOperation.Add && next.pageId == operation.pageId) {
                            batch += next
                        } else {
                            deferredOperation = next
                            break
                        }
                    }
                    processAddBatch(batch)
                    pendingOperations.update { (it - batch.size).coerceAtLeast(0) }
                } else {
                    processOperation(operation)
                    if (operation is CanvasOperation.PageOperation) pageCreationInProgress.value = false
                    pendingOperations.update { (it - 1).coerceAtLeast(0) }
                }
            }
        }
    }

    private suspend fun processAddBatch(operations: List<CanvasOperation.Add>) {
        runCatching {
            val saved = repository.saveStrokes(
                noteId, operations.first().pageId, operations.map { it.stroke.draft },
            )
            operations.zip(saved).forEach { (operation, stroke) ->
                tokenToStroke[operation.stroke.token] = stroke
                if (operation.stroke.token in tokensScheduledForErase) suppress(stroke.id)
                pushNewCommand(CanvasCommand.AddStroke(stroke))
                _events.tryEmit(CanvasEvent.PendingPersisted(operation.stroke.token))
            }
            thumbnailService.request(operations.first().pageId)
        }.onFailure {
            operations.forEach { rollbackVisualState(it) }
            _errors.tryEmit(Unit)
            _events.tryEmit(CanvasEvent.RefreshStrokes)
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

    fun addText(draft: CanvasTextDraft) {
        val pageId = (uiState.value as? CanvasUiState.Ready)?.page?.id ?: return
        if (draft.content.isNotBlank()) enqueue(CanvasOperation.AddText(draft, pageId))
    }

    fun exportCurrentPage(format: ExportFormat) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value > 0 || exportState.value is ExportUiState.Rendering) return
        _exportState.value = ExportUiState.Rendering(0, 1)
        enqueue(
            CanvasOperation.ExportPage(
                state.note, state.page.id, state.pagePosition + 1, format,
            ),
        )
    }

    fun exportNotePdf() {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value > 0 || exportState.value is ExportUiState.Rendering) return
        _exportState.value = ExportUiState.Rendering(0, state.pages.size)
        enqueue(CanvasOperation.ExportPdf(state.note))
    }

    fun clearExportResult() { _exportState.value = ExportUiState.Idle }

    fun saveExport(uri: Uri) {
        val artifact = (exportState.value as? ExportUiState.Ready)?.artifact ?: return
        _exportState.value = ExportUiState.Saving(artifact)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { exportService.copyTo(artifact, uri) }
                .onSuccess { _exportState.value = ExportUiState.Ready(artifact) }
                .onFailure { _exportState.value = ExportUiState.Error(it.message) }
        }
    }

    fun updateSelection(value: CanvasSelection) { selectionState.value = value }

    fun transformSelection(change: SelectionChange) {
        selectionState.value = change.after
        enqueue(CanvasOperation.TransformSelection(change))
    }

    fun copySelection() { clipboardState.value = selectionState.value }

    fun deleteSelection() {
        val value = selectionState.value
        if (value.isEmpty) return
        selectionState.value = CanvasSelection()
        enqueue(CanvasOperation.DeleteSelection(value))
    }

    fun pasteSelection(offsetX: Float, offsetY: Float) {
        val source = clipboardState.value
        val pageId = (uiState.value as? CanvasUiState.Ready)?.page?.id ?: return
        if (!source.isEmpty) enqueue(CanvasOperation.PasteSelection(source, pageId, offsetX, offsetY))
    }

    fun editText(before: CanvasText, content: String) {
        if (content.isBlank()) {
            val selection = CanvasSelection(texts = listOf(before))
            selectionState.value = CanvasSelection()
            enqueue(CanvasOperation.DeleteSelection(selection))
        } else {
            val after = before.copy(content = content, updatedAt = System.currentTimeMillis())
            transformSelection(
                SelectionChange(CanvasSelection(texts = listOf(before)), CanvasSelection(texts = listOf(after))),
            )
        }
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

    fun selectPage(page: Page) {
        if (pendingOperations.value == 0) switchPage(page)
    }

    fun deletePage(page: Page) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value > 0 || state.pages.size <= 1) return
        val index = state.pages.indexOfFirst { it.id == page.id }
        val nextPageId = if (page.id == state.page.id) {
            state.pages.getOrNull(index + 1)?.id ?: state.pages.getOrNull(index - 1)?.id
        } else state.page.id
        pageCreationInProgress.value = true
        if (!enqueue(CanvasOperation.DeletePage(page.id, nextPageId))) {
            pageCreationInProgress.value = false
        }
    }

    fun reorderPages(pageIds: List<Long>) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value > 0 || pageIds == state.pages.map(Page::id)) return
        pageCreationInProgress.value = true
        if (!enqueue(CanvasOperation.ReorderPages(pageIds))) pageCreationInProgress.value = false
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
    fun selectTextSize(size: TextSize) = updateSettings(_settings.value.copy(textSize = size))

    private suspend fun processOperation(operation: CanvasOperation) {
        runCatching {
            when (operation) {
                is CanvasOperation.Add -> processAdd(operation.stroke, operation.pageId)
                is CanvasOperation.Erase -> processErase(operation.targets)
                is CanvasOperation.AreaErase -> processAreaErase(operation.replacements)
                is CanvasOperation.AddText -> processAddText(operation.draft, operation.pageId)
                is CanvasOperation.TransformSelection -> processTransformSelection(operation.change)
                is CanvasOperation.DeleteSelection -> processDeleteSelection(operation.selection)
                is CanvasOperation.PasteSelection -> processPasteSelection(
                    operation.source, operation.pageId, operation.offsetX, operation.offsetY,
                )
                is CanvasOperation.ExportPage -> processExportPage(operation)
                is CanvasOperation.ExportPdf -> processExportPdf(operation.note)
                is CanvasOperation.CreatePage -> processCreatePage(operation.template)
                is CanvasOperation.DeletePage -> processDeletePage(operation.pageId, operation.nextPageId)
                is CanvasOperation.ReorderPages -> repository.reorderPages(noteId, operation.pageIds)
                CanvasOperation.Undo -> processUndo()
                CanvasOperation.Redo -> processRedo()
            }
        }.onFailure {
            rollbackVisualState(operation)
            if (operation !is CanvasOperation.ExportPage && operation !is CanvasOperation.ExportPdf) {
                _errors.tryEmit(Unit)
                _events.tryEmit(CanvasEvent.RefreshStrokes)
            }
        }
    }

    private suspend fun processExportPage(operation: CanvasOperation.ExportPage) {
        val artifact = exportService.exportPage(
            operation.note, operation.pageId, operation.pageNumber, operation.format,
        ) { completed, total -> _exportState.value = ExportUiState.Rendering(completed, total) }
        _exportState.value = ExportUiState.Ready(artifact)
    }

    private suspend fun processExportPdf(note: Note) {
        val artifact = exportService.exportNotePdf(note) { completed, total ->
            _exportState.value = ExportUiState.Rendering(completed, total)
        }
        _exportState.value = ExportUiState.Ready(artifact)
    }

    private suspend fun processAddText(draft: CanvasTextDraft, pageId: Long) {
        val text = repository.addText(noteId, pageId, draft)
        selectionState.value = CanvasSelection(texts = listOf(text))
        pushNewCommand(CanvasCommand.ElementsAdded(emptyList(), listOf(text)))
        thumbnailService.request(pageId)
    }

    private suspend fun processTransformSelection(change: SelectionChange) {
        repository.updateElements(noteId, change.after.strokes, change.after.texts)
        pushNewCommand(CanvasCommand.ElementsTransformed(change.before, change.after))
        change.after.pageId()?.let(thumbnailService::request)
    }

    private suspend fun processDeleteSelection(selection: CanvasSelection) {
        repository.deleteElements(noteId, selection.strokes, selection.texts)
        pushNewCommand(CanvasCommand.ElementsDeleted(selection))
        selection.pageId()?.let(thumbnailService::request)
    }

    private suspend fun processPasteSelection(
        source: CanvasSelection,
        pageId: Long,
        offsetX: Float,
        offsetY: Float,
    ) {
        val maximumX = maxOf(
            source.strokes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f,
            source.texts.maxOfOrNull { it.x + it.boxWidth } ?: 0f,
        )
        val maximumY = maxOf(
            source.strokes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f,
            source.texts.maxOfOrNull { it.y } ?: 0f,
        )
        val safeOffsetX = offsetX.coerceAtMost((1f - maximumX).coerceAtLeast(0f))
        val safeOffsetY = offsetY.coerceAtMost((1f - maximumY).coerceAtLeast(0f))
        val strokeDrafts = source.strokes.map { stroke ->
            StrokeDraft(
                stroke.tool, stroke.colorArgb, stroke.width,
                stroke.points.map {
                    it.copy(
                        x = it.x + safeOffsetX,
                        y = it.y + safeOffsetY,
                    )
                },
            )
        }
        val textDrafts = source.texts.map { text ->
            CanvasTextDraft(
                    text.x + safeOffsetX,
                    text.y + safeOffsetY,
                    text.boxWidth, text.content, text.colorArgb, text.textSizeSp,
            )
        }
        val (strokes, texts) = repository.copyElements(noteId, pageId, strokeDrafts, textDrafts)
        val pasted = CanvasSelection(strokes, texts)
        selectionState.value = pasted
        pushNewCommand(CanvasCommand.ElementsAdded(strokes, texts))
        thumbnailService.request(pageId)
    }

    private suspend fun processAdd(pending: PendingCanvasStroke, pageId: Long) {
        val stroke = repository.saveStroke(noteId, pageId, pending.draft)
        tokenToStroke[pending.token] = stroke
        if (pending.token in tokensScheduledForErase) suppress(stroke.id)
        pushNewCommand(CanvasCommand.AddStroke(stroke))
        thumbnailService.request(pageId)
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
        thumbnailService.request(strokes.first().pageId)
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
        thumbnailService.request(removed.first().pageId)
        pushNewCommand(CanvasCommand.ReplaceStrokes(removed, added))
    }

    private suspend fun processCreatePage(template: PageTemplate) {
        val pageId = repository.createPage(noteId, template)
        clearPageSession()
        selectedPageId.value = pageId
        viewportState.update { it + (pageId to CanvasViewport()) }
        thumbnailService.request(pageId)
    }

    private suspend fun processDeletePage(pageId: Long, nextPageId: Long?) {
        val deleted = repository.deletePage(noteId, pageId)
        runCatching { thumbnailService.delete(pageId) }
        deleted.pdfStorageNames.forEach { storageName ->
            pdfPageRenderStore.evict(storageName)
            pdfDocumentStore.delete(storageName)
        }
        viewportState.update { it - pageId }
        clearPageSession()
        selectedPageId.value = nextPageId
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
        selectionState.value = CanvasSelection()
        publishHistoryState()
    }

    private suspend fun processUndo() {
        val command = undoStack.peekLast() ?: return
        applyInverse(command)
        selectionState.value = when (command) {
            is CanvasCommand.ElementsTransformed -> command.before
            is CanvasCommand.ElementsDeleted -> command.selection
            is CanvasCommand.ElementsAdded -> CanvasSelection()
            else -> selectionState.value
        }
        undoStack.removeLast()
        redoStack.addLast(command)
        publishHistoryState()
        command.pageId()?.let(thumbnailService::request)
    }

    private suspend fun processRedo() {
        val command = redoStack.peekLast() ?: return
        apply(command)
        selectionState.value = when (command) {
            is CanvasCommand.ElementsTransformed -> command.after
            is CanvasCommand.ElementsDeleted -> CanvasSelection()
            is CanvasCommand.ElementsAdded -> CanvasSelection(command.strokes, command.texts)
            else -> selectionState.value
        }
        redoStack.removeLast()
        undoStack.addLast(command)
        publishHistoryState()
        command.pageId()?.let(thumbnailService::request)
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
            is CanvasCommand.ElementsAdded -> repository.restoreElements(noteId, command.strokes, command.texts)
            is CanvasCommand.ElementsDeleted -> repository.deleteElements(
                noteId, command.selection.strokes, command.selection.texts,
            )
            is CanvasCommand.ElementsTransformed -> repository.updateElements(
                noteId, command.after.strokes, command.after.texts,
            )
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
            is CanvasCommand.ElementsAdded -> repository.deleteElements(noteId, command.strokes, command.texts)
            is CanvasCommand.ElementsDeleted -> repository.restoreElements(
                noteId, command.selection.strokes, command.selection.texts,
            )
            is CanvasCommand.ElementsTransformed -> repository.updateElements(
                noteId, command.before.strokes, command.before.texts,
            )
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
            is CanvasOperation.AddText -> Unit
            is CanvasOperation.TransformSelection -> selectionState.value = operation.change.before
            is CanvasOperation.DeleteSelection -> selectionState.value = operation.selection
            is CanvasOperation.PasteSelection -> selectionState.value = CanvasSelection()
            is CanvasOperation.ExportPage, is CanvasOperation.ExportPdf -> {
                _exportState.value = ExportUiState.Error()
            }
            is CanvasOperation.CreatePage -> Unit
            is CanvasOperation.DeletePage -> Unit
            is CanvasOperation.ReorderPages -> Unit
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
            is CanvasCommand.ElementsAdded -> Unit
            is CanvasCommand.ElementsDeleted -> Unit
            is CanvasCommand.ElementsTransformed -> selectionState.value = command.after
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
            is CanvasCommand.ElementsAdded -> Unit
            is CanvasCommand.ElementsDeleted -> Unit
            is CanvasCommand.ElementsTransformed -> selectionState.value = command.before
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

    private fun CanvasCommand.pageId(): Long? = when (this) {
        is CanvasCommand.AddStroke -> stroke.pageId
        is CanvasCommand.DeleteStrokes -> strokes.firstOrNull()?.pageId
        is CanvasCommand.ReplaceStrokes -> before.firstOrNull()?.pageId ?: after.firstOrNull()?.pageId
        is CanvasCommand.ElementsAdded -> strokes.firstOrNull()?.pageId ?: texts.firstOrNull()?.pageId
        is CanvasCommand.ElementsDeleted -> selection.pageId()
        is CanvasCommand.ElementsTransformed -> after.pageId()
    }

    private fun CanvasSelection.pageId(): Long? = strokes.firstOrNull()?.pageId ?: texts.firstOrNull()?.pageId

    override fun onCleared() {
        operationQueue.close()
        super.onCleared()
    }

    private sealed interface CanvasOperation {
        data class Add(val stroke: PendingCanvasStroke, val pageId: Long) : CanvasOperation
        data class Erase(val targets: List<ErasableStroke>) : CanvasOperation
        data class AreaErase(val replacements: List<AreaEraseReplacement>) : CanvasOperation
        data class AddText(val draft: CanvasTextDraft, val pageId: Long) : CanvasOperation
        data class TransformSelection(val change: SelectionChange) : CanvasOperation
        data class DeleteSelection(val selection: CanvasSelection) : CanvasOperation
        data class PasteSelection(
            val source: CanvasSelection,
            val pageId: Long,
            val offsetX: Float,
            val offsetY: Float,
        ) : CanvasOperation
        data class ExportPage(
            val note: Note,
            val pageId: Long,
            val pageNumber: Int,
            val format: ExportFormat,
        ) : CanvasOperation
        data class ExportPdf(val note: Note) : CanvasOperation
        sealed interface PageOperation : CanvasOperation
        data class CreatePage(val template: PageTemplate) : PageOperation
        data class DeletePage(val pageId: Long, val nextPageId: Long?) : PageOperation
        data class ReorderPages(val pageIds: List<Long>) : PageOperation
        data object Undo : CanvasOperation
        data object Redo : CanvasOperation
    }

    private sealed interface CanvasCommand {
        data class AddStroke(val stroke: Stroke) : CanvasCommand
        data class DeleteStrokes(val strokes: List<Stroke>) : CanvasCommand
        data class ReplaceStrokes(val before: List<Stroke>, val after: List<Stroke>) : CanvasCommand
        data class ElementsAdded(val strokes: List<Stroke>, val texts: List<CanvasText>) : CanvasCommand
        data class ElementsDeleted(val selection: CanvasSelection) : CanvasCommand
        data class ElementsTransformed(
            val before: CanvasSelection,
            val after: CanvasSelection,
        ) : CanvasCommand
    }

    private data class HistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false)

    class Factory(
        private val noteId: Long,
        private val repository: NoteRepository,
        private val settingsStore: DrawingToolSettingsStore,
        private val thumbnailStore: PageThumbnailStore,
        private val thumbnailService: PageThumbnailService,
        private val exportService: NoteExportService,
        private val pdfDocumentStore: PdfDocumentStore,
        private val pdfPageRenderStore: PdfPageRenderStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CanvasViewModel(
                noteId, repository, settingsStore, thumbnailStore, thumbnailService, exportService,
                pdfDocumentStore, pdfPageRenderStore,
            ) as T
    }
}
