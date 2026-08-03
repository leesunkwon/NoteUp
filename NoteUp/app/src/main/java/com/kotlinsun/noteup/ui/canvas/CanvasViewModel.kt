package com.kotlinsun.noteup.ui.canvas

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kotlinsun.noteup.data.export.NoteExportService
import com.kotlinsun.noteup.data.pdf.PdfDocumentStore
import com.kotlinsun.noteup.data.pdf.PdfPageRenderStore
import com.kotlinsun.noteup.data.image.CanvasImageStore
import com.kotlinsun.noteup.data.recovery.RecoveryJournal
import com.kotlinsun.noteup.data.version.PageVersionService
import com.kotlinsun.noteup.data.preferences.CustomColorPaletteStore
import com.kotlinsun.noteup.data.preferences.DrawingToolSettingsStore
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
import com.kotlinsun.noteup.domain.model.CanvasImage
import com.kotlinsun.noteup.domain.model.CanvasImageDraft
import com.kotlinsun.noteup.domain.model.CreatedPageText
import com.kotlinsun.noteup.domain.model.TextSize
import com.kotlinsun.noteup.domain.model.highlighterColor
import com.kotlinsun.noteup.domain.model.opaqueColor
import com.kotlinsun.noteup.domain.model.ExportFormat
import com.kotlinsun.noteup.domain.model.ExportUiState
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.PageVersion
import com.kotlinsun.noteup.domain.model.RecoveryEntry
import com.kotlinsun.noteup.domain.ai.AiEngineState
import com.kotlinsun.noteup.domain.ai.AiModelState
import com.kotlinsun.noteup.domain.ai.OnDeviceAiRepository
import com.kotlinsun.noteup.domain.repository.NoteRepository
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private const val ADD_BATCH_WINDOW_MILLIS = 20L
private const val MAXIMUM_ADD_BATCH_SIZE = 16
private const val MAXIMUM_SAVE_ATTEMPTS = 3
private const val SAVE_QUEUE_DELAY_WARNING_MILLIS = 1_500L
private const val SAVE_QUEUE_DELAY_WARNING_COOLDOWN_MILLIS = 10_000L

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModel(
    private val noteId: Long,
    private val repository: NoteRepository,
    private val settingsStore: DrawingToolSettingsStore,
    private val customColorPaletteStore: CustomColorPaletteStore,
    private val thumbnailStore: PageThumbnailStore,
    private val thumbnailService: PageThumbnailService,
    private val exportService: NoteExportService,
    private val pdfDocumentStore: PdfDocumentStore,
    private val pdfPageRenderStore: PdfPageRenderStore,
    private val canvasImageStore: CanvasImageStore,
    private val recoveryJournal: RecoveryJournal,
    private val pageVersionService: PageVersionService,
    private val onDeviceAiRepository: OnDeviceAiRepository,
) : ViewModel() {

    private val operationQueue = Channel<CanvasOperation>(Channel.UNLIMITED)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenToStroke = mutableMapOf<Long, Stroke>()
    private val tokenToRecoveryId = mutableMapOf<Long, String>()
    private val tokensScheduledForErase = ConcurrentHashMap.newKeySet<Long>()
    private val undoStack = ArrayDeque<CanvasCommand>()
    private val redoStack = ArrayDeque<CanvasCommand>()
    private val pendingOperations = MutableStateFlow(0)
    private var lastQueueDelayWarningAt = 0L
    private val pageCreationInProgress = MutableStateFlow(false)
    private val suppressedStrokeIds = MutableStateFlow<Set<Long>>(emptySet())
    private val historyState = MutableStateFlow(HistoryState())
    private val selectedPageId = MutableStateFlow<Long?>(null)
    private val viewportState = MutableStateFlow<Map<Long, CanvasViewport>>(emptyMap())
    private val selectionState = MutableStateFlow(CanvasSelection())
    private val clipboardState = MutableStateFlow(CanvasSelection())
    private val aiAssistantSession = MutableStateFlow(AiAssistantUiState())
    private var aiGenerationJob: Job? = null
    private var aiRequestId = 0L
    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState = _exportState.asStateFlow()
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings = _settings.asStateFlow()
    val customColors = customColorPaletteStore.colors
    private val _errors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors: Flow<Unit> = _errors
    private val _events = MutableSharedFlow<CanvasEvent>(extraBufferCapacity = 8)
    val events: Flow<CanvasEvent> = _events

    val aiAssistantState = combine(
        aiAssistantSession,
        selectionState,
        onDeviceAiRepository.modelState,
        onDeviceAiRepository.engineState,
    ) { session, selection, modelState, engineState ->
        val selectedContext = selection.texts
            .filter { it.content.isNotBlank() }
            .distinctBy(CanvasText::id)
            .sortedBy(CanvasText::elementIndex)
            .joinToString(AI_CONTEXT_SEPARATOR) { it.content.trim() }
            .take(MAXIMUM_AI_CONTEXT_LENGTH)
        session.copy(
            contextText = if (session.requestPageId == null) selectedContext else session.contextText,
            modelState = modelState,
            engineState = engineState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = aiAssistantSession.value.copy(
            modelState = onDeviceAiRepository.modelState.value,
            engineState = onDeviceAiRepository.engineState.value,
        ),
    )

    private val content = combine(
        repository.observeNote(noteId),
        repository.observePages(noteId),
        selectedPageId,
    ) { note, pages, requestedPageId ->
        val page = if (requestedPageId == null) {
            pages.firstOrNull()
        } else {
            pages.firstOrNull { it.id == requestedPageId }
        }
        Triple(note, pages, page)
    }.flatMapLatest { (note, pages, page) ->
            if (note == null || page == null) {
                flowOf<CanvasUiState>(CanvasUiState.NotFound)
            } else {
                thumbnailService.ensure(pages.map(Page::id))
                combine(
                    repository.observeStrokes(page.id),
                    repository.observeTexts(page.id),
                    repository.observeImages(page.id),
                    suppressedStrokeIds,
                    thumbnailStore.revisions,
                ) { strokes, texts, images, hidden, revisions ->
                    CanvasUiState.Ready(
                        note = note,
                        pages = pages,
                        page = page,
                        pagePosition = pages.indexOfFirst { it.id == page.id },
                        strokes = strokes.filterNot { it.id in hidden },
                        texts = texts,
                        images = images,
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

    val pageVersions = content.flatMapLatest { state ->
        (state as? CanvasUiState.Ready)?.page?.id
            ?.let(repository::observePageVersions)
            ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                val now = System.currentTimeMillis()
                if (now - operation.enqueuedAt > SAVE_QUEUE_DELAY_WARNING_MILLIS &&
                    now - lastQueueDelayWarningAt > SAVE_QUEUE_DELAY_WARNING_COOLDOWN_MILLIS
                ) {
                    lastQueueDelayWarningAt = now
                    _events.tryEmit(CanvasEvent.SaveDelayed)
                }
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
                    completePendingOperations(batch.size)
                } else {
                    processOperation(operation)
                    if (operation is CanvasOperation.PageOperation) pageCreationInProgress.value = false
                    completePendingOperations(1)
                }
            }
        }
    }

    private suspend fun processAddBatch(operations: List<CanvasOperation.Add>) {
        val pageId = operations.first().pageId
        var journaledCount = 0
        runCatching {
            runCatching { pageVersionService.capture(pageId) }
            operations.forEach { operation ->
                recoveryJournal.write(
                    RecoveryEntry(
                        operation.operationId,
                        noteId,
                        pageId,
                        operation.enqueuedAt,
                        operation.stroke.draft,
                    ),
                )
                journaledCount++
            }
            val saved = retrySave {
                repository.saveStrokesWithRecoveryIds(
                    noteId,
                    pageId,
                    operations.map { it.operationId to it.stroke.draft },
                )
            }
            operations.zip(saved).forEach { (operation, stroke) ->
                tokenToStroke[operation.stroke.token] = stroke
                tokenToRecoveryId -= operation.stroke.token
                if (operation.stroke.token in tokensScheduledForErase) suppress(stroke.id)
                pushNewCommand(CanvasCommand.AddStroke(stroke))
                _events.tryEmit(CanvasEvent.PendingPersisted(operation.stroke.token))
            }
            recoveryJournal.delete(operations.map(CanvasOperation.Add::operationId))
            thumbnailService.request(pageId)
        }.onFailure {
            if (journaledCount == operations.size) {
                _events.tryEmit(CanvasEvent.RecoveryJournalPreserved)
            } else {
                recoveryJournal.delete(operations.map(CanvasOperation.Add::operationId))
                operations.forEach { operation ->
                    tokenToRecoveryId -= operation.stroke.token
                    rollbackVisualState(operation)
                }
                _errors.tryEmit(Unit)
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
        val operation = CanvasOperation.Add(stroke, pageId)
        tokenToRecoveryId[stroke.token] = operation.operationId
        if (!enqueue(operation)) tokenToRecoveryId -= stroke.token
    }

    fun restoreVersion(versionId: Long) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value == 0) enqueue(CanvasOperation.RestoreVersion(versionId, state.page.id))
    }

    suspend fun loadVersionPreview(version: PageVersion) = pageVersionService.loadPreview(version)

    fun addText(draft: CanvasTextDraft) {
        val pageId = (uiState.value as? CanvasUiState.Ready)?.page?.id ?: return
        if (draft.content.isNotBlank()) enqueue(CanvasOperation.AddText(draft, pageId))
    }

    fun openAiAssistant() {
        aiAssistantSession.update { it.copy(isOpen = true) }
    }

    fun closeAiAssistant() {
        aiAssistantSession.update { it.copy(isOpen = false) }
    }

    fun updateAiPrompt(prompt: String) {
        aiAssistantSession.update { current ->
            if (
                current.prompt != prompt &&
                current.phase != AiAssistantPhase.GENERATING &&
                !current.isInserting
            ) {
                current.copy(
                    prompt = prompt,
                    contextText = "",
                    response = "",
                    requestPageId = null,
                    requestPageNumber = null,
                    phase = AiAssistantPhase.IDLE,
                )
            } else {
                current.copy(prompt = prompt)
            }
        }
    }

    fun generateAiResponse(insertionCenterX: Float, insertionCenterY: Float) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        val currentSession = aiAssistantSession.value
        val question = currentSession.prompt.trim()
        if (
            question.isEmpty() ||
            currentSession.phase == AiAssistantPhase.GENERATING ||
            currentSession.isInserting
        ) return
        if (onDeviceAiRepository.modelState.value !is AiModelState.Ready) {
            aiAssistantSession.update {
                it.copy(
                    isOpen = true,
                    phase = AiAssistantPhase.FAILED,
                    response = "",
                )
            }
            return
        }
        val engineBusy = when (onDeviceAiRepository.engineState.value) {
            AiEngineState.Loading, AiEngineState.Generating -> aiGenerationJob?.isActive != true
            else -> false
        }
        if (engineBusy) {
            aiAssistantSession.update { it.copy(phase = AiAssistantPhase.FAILED) }
            return
        }

        cancelAiGeneration(updateState = false)
        val context = selectionState.value.texts
            .filter { it.pageId == state.page.id && it.content.isNotBlank() }
            .distinctBy(CanvasText::id)
            .sortedBy(CanvasText::elementIndex)
            .joinToString(AI_CONTEXT_SEPARATOR) { it.content.trim() }
            .take(MAXIMUM_AI_CONTEXT_LENGTH)
        val requestId = ++aiRequestId
        aiAssistantSession.value = aiAssistantSession.value.copy(
            isOpen = true,
            contextText = context,
            response = "",
            requestPageId = state.page.id,
            requestPageNumber = state.pagePosition + 1,
            insertionCenterX = insertionCenterX.coerceIn(0f, 1f),
            insertionCenterY = insertionCenterY.coerceIn(0f, 1f),
            phase = AiAssistantPhase.GENERATING,
            isInserting = false,
        )
        aiGenerationJob = viewModelScope.launch {
            val response = StringBuilder()
            try {
                onDeviceAiRepository.generate(buildAiPrompt(question, context)).collect { chunk ->
                    response.append(chunk)
                    if (requestId == aiRequestId) {
                        aiAssistantSession.update {
                            it.copy(
                                response = response.toString(),
                                phase = AiAssistantPhase.GENERATING,
                            )
                        }
                    }
                }
                if (requestId == aiRequestId) {
                    aiAssistantSession.update {
                        it.copy(
                            response = response.toString().trim(),
                            phase = if (response.isBlank()) {
                                AiAssistantPhase.FAILED
                            } else {
                                AiAssistantPhase.COMPLETE
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestId == aiRequestId) {
                    aiAssistantSession.update { it.copy(phase = AiAssistantPhase.CANCELLED) }
                }
                throw cancelled
            } catch (_: Throwable) {
                if (requestId == aiRequestId) {
                    aiAssistantSession.update { it.copy(phase = AiAssistantPhase.FAILED) }
                }
            } finally {
                if (requestId == aiRequestId) aiGenerationJob = null
            }
        }
    }

    fun cancelAiGeneration() {
        cancelAiGeneration(updateState = true)
    }

    fun insertAiResponse(createNewPage: Boolean) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        val assistant = aiAssistantSession.value
        val content = assistant.response.trim()
        if (
            assistant.phase != AiAssistantPhase.COMPLETE ||
            assistant.isInserting ||
            content.isEmpty() ||
            pendingOperations.value > 0
        ) return

        val draft = CanvasTextDraft(
            x = (assistant.insertionCenterX - AI_TEXT_WIDTH / 2f)
                .coerceIn(0f, 1f - AI_TEXT_WIDTH),
            y = (assistant.insertionCenterY - AI_TEXT_VERTICAL_OFFSET)
                .coerceIn(0f, 1f),
            boxWidth = AI_TEXT_WIDTH,
            content = content,
            colorArgb = _settings.value.pen.colorArgb,
            textSizeSp = _settings.value.textSize.sizeSp,
        )
        aiAssistantSession.update { it.copy(isInserting = true) }
        val operation = if (createNewPage) {
            pageCreationInProgress.value = true
            CanvasOperation.CreatePageWithAiText(draft, state.page.id)
        } else {
            val targetPageId = assistant.requestPageId
                ?.takeIf { requested -> state.pages.any { it.id == requested } }
                ?: state.page.id
            pageCreationInProgress.value = true
            CanvasOperation.AddAiText(draft, targetPageId)
        }
        if (!enqueue(operation)) {
            aiAssistantSession.update { it.copy(isInserting = false) }
            if (operation is CanvasOperation.PageOperation) {
                pageCreationInProgress.value = false
            }
        }
    }

    private fun cancelAiGeneration(updateState: Boolean) {
        val activeJob = aiGenerationJob?.takeIf { it.isActive }
        if (activeJob != null) {
            aiRequestId++
            activeJob.cancel()
        }
        aiGenerationJob = null
        if (updateState && aiAssistantSession.value.phase == AiAssistantPhase.GENERATING) {
            aiAssistantSession.update { it.copy(phase = AiAssistantPhase.CANCELLED) }
        }
    }

    private fun buildAiPrompt(question: String, context: String): String = buildString {
        append(AI_ASSISTANT_INSTRUCTION)
        if (context.isNotBlank()) {
            append(AI_CONTEXT_HEADER)
            append(context)
            append(AI_CONTEXT_FOOTER)
        }
        append(AI_QUESTION_HEADER)
        append(question)
        append(AI_ANSWER_INSTRUCTION)
    }

    fun importImage(uri: Uri) {
        val state = uiState.value as? CanvasUiState.Ready ?: return
        if (pendingOperations.value > 0) return
        val pageAspectRatio = state.page.pdfBackground?.let {
            it.widthPoints.toFloat() / it.heightPoints.coerceAtLeast(1)
        } ?: state.viewport.let { viewport ->
            if (viewport.referenceWidth > 0 && viewport.referenceHeight > 0) {
                viewport.referenceWidth.toFloat() / viewport.referenceHeight
            } else DEFAULT_PAGE_ASPECT_RATIO
        }
        enqueue(CanvasOperation.ImportImage(uri, state.page.id, pageAspectRatio))
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
        if (pendingOperations.value == 0 && historyState.value.canUndo) {
            pageCreationInProgress.value = true
            if (!enqueue(CanvasOperation.Undo())) pageCreationInProgress.value = false
        }
    }

    fun redo() {
        if (pendingOperations.value == 0 && historyState.value.canRedo) {
            pageCreationInProgress.value = true
            if (!enqueue(CanvasOperation.Redo())) pageCreationInProgress.value = false
        }
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
        _settings.value.copy(
            pen = _settings.value.pen.copy(color = color, customColorArgb = null),
        ),
    )
    fun selectPenThickness(thickness: PenThickness) = updateSettings(
        _settings.value.copy(pen = _settings.value.pen.copy(thickness = thickness)),
    )
    fun selectHighlighterColor(color: HighlighterColor) = updateSettings(
        _settings.value.copy(
            highlighter = _settings.value.highlighter.copy(color = color, customColorArgb = null),
        ),
    )
    fun selectHighlighterThickness(thickness: HighlighterThickness) = updateSettings(
        _settings.value.copy(highlighter = _settings.value.highlighter.copy(thickness = thickness)),
    )
    fun selectTextSize(size: TextSize) = updateSettings(_settings.value.copy(textSize = size))

    fun selectCustomColor(color: Int) {
        val settings = _settings.value
        if (settings.tool == DrawingTool.HIGHLIGHTER) {
            updateSettings(
                settings.copy(
                    highlighter = settings.highlighter.copy(
                        customColorArgb = highlighterColor(color),
                    ),
                ),
            )
        } else {
            updateSettings(
                settings.copy(
                    pen = settings.pen.copy(customColorArgb = opaqueColor(color)),
                ),
            )
        }
    }

    fun addCustomColor(color: Int) = customColorPaletteStore.add(color)

    fun removeCustomColor(color: Int) = customColorPaletteStore.remove(color)

    private suspend fun processOperation(operation: CanvasOperation) {
        runCatching {
            operation.versionPageId()?.let { pageId ->
                runCatching { pageVersionService.capture(pageId) }
            }
            retrySave { when (operation) {
                is CanvasOperation.Add -> processAdd(operation.stroke, operation.pageId)
                is CanvasOperation.Erase -> processErase(operation.targets)
                is CanvasOperation.AreaErase -> processAreaErase(operation.replacements)
                is CanvasOperation.AddText -> processAddText(operation.draft, operation.pageId)
                is CanvasOperation.AddAiText -> processAddAiText(operation)
                is CanvasOperation.ImportImage -> processImportImage(operation)
                is CanvasOperation.TransformSelection -> processTransformSelection(operation.change)
                is CanvasOperation.DeleteSelection -> processDeleteSelection(operation.selection)
                is CanvasOperation.PasteSelection -> processPasteSelection(
                    operation.source, operation.pageId, operation.offsetX, operation.offsetY,
                )
                is CanvasOperation.ExportPage -> processExportPage(operation)
                is CanvasOperation.ExportPdf -> processExportPdf(operation.note)
                is CanvasOperation.CreatePage -> processCreatePage(operation)
                is CanvasOperation.CreatePageWithAiText -> processCreatePageWithAiText(operation)
                is CanvasOperation.DeletePage -> processDeletePage(operation.pageId, operation.nextPageId)
                is CanvasOperation.ReorderPages -> repository.reorderPages(noteId, operation.pageIds)
                is CanvasOperation.RestoreVersion -> {
                    pageVersionService.restore(noteId, operation.versionId)
                    clearPageSession()
                    _events.tryEmit(CanvasEvent.VersionRestored)
                }
                is CanvasOperation.Undo -> processUndo()
                is CanvasOperation.Redo -> processRedo()
            } }
        }.onFailure {
            rollbackVisualState(operation)
            if (operation !is CanvasOperation.ExportPage && operation !is CanvasOperation.ExportPdf) {
                if (
                    operation is CanvasOperation.AddAiText ||
                    operation is CanvasOperation.CreatePageWithAiText
                ) {
                    _events.tryEmit(CanvasEvent.AiResultInsertionFailed)
                } else {
                    _errors.tryEmit(Unit)
                }
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
        pushNewCommand(CanvasCommand.ElementsAdded(emptyList(), listOf(text), emptyList()))
        thumbnailService.request(pageId)
    }

    private suspend fun processAddAiText(operation: CanvasOperation.AddAiText) {
        val currentPageId = (uiState.value as? CanvasUiState.Ready)?.page?.id
        val text = repository.addText(noteId, operation.pageId, operation.draft)
        if (currentPageId != operation.pageId) {
            clearPageSession()
            selectedPageId.value = operation.pageId
        }
        selectionState.value = CanvasSelection(texts = listOf(text))
        pushNewCommand(CanvasCommand.ElementsAdded(emptyList(), listOf(text), emptyList()))
        thumbnailService.request(operation.pageId)
        aiAssistantSession.update { it.copy(isInserting = false) }
        _events.tryEmit(CanvasEvent.AiResultInserted(newPage = false))
    }

    private suspend fun processCreatePageWithAiText(
        operation: CanvasOperation.CreatePageWithAiText,
    ) {
        val created = operation.created ?: repository.createPageWithText(
            noteId,
            PageTemplate.BLANK,
            operation.draft,
        ).also { operation.created = it }
        awaitPage(created.page.id)
        clearPageSession()
        selectedPageId.value = created.page.id
        viewportState.update { it + (created.page.id to CanvasViewport()) }
        selectionState.value = CanvasSelection(texts = listOf(created.text))
        pushNewCommand(
            CanvasCommand.PageWithTextAdded(
                created = created,
                previousPageId = operation.previousPageId,
            ),
        )
        thumbnailService.request(created.page.id)
        aiAssistantSession.update { it.copy(isInserting = false) }
        _events.tryEmit(CanvasEvent.AiResultInserted(newPage = true))
    }

    private suspend fun processImportImage(operation: CanvasOperation.ImportImage) {
        var storageName: String? = null
        try {
            val imported = canvasImageStore.importImage(operation.uri)
            storageName = imported.storageName
            val rotated = imported.orientationDegrees == 90 || imported.orientationDegrees == 270
            val imageWidth = if (rotated) imported.height else imported.width
            val imageHeight = if (rotated) imported.width else imported.height
            val imageAspectRatio = imageWidth.toFloat() / imageHeight.coerceAtLeast(1)
            val boxWidth = DEFAULT_IMAGE_WIDTH
            val boxHeight = (boxWidth * operation.pageAspectRatio / imageAspectRatio)
                .coerceIn(MINIMUM_IMAGE_SIZE, MAXIMUM_IMAGE_HEIGHT)
            val image = repository.addImage(
                noteId,
                operation.pageId,
                CanvasImageDraft(
                    storageName = imported.storageName,
                    originalWidth = imported.width,
                    originalHeight = imported.height,
                    orientationDegrees = imported.orientationDegrees,
                    x = ((1f - boxWidth) / 2f).coerceAtLeast(0f),
                    y = ((1f - boxHeight) / 2f).coerceAtLeast(0f),
                    boxWidth = boxWidth,
                    boxHeight = boxHeight,
                ),
            )
            selectionState.value = CanvasSelection(images = listOf(image))
            pushNewCommand(CanvasCommand.ElementsAdded(emptyList(), emptyList(), listOf(image)))
            thumbnailService.request(operation.pageId)
        } catch (error: Throwable) {
            storageName?.let(canvasImageStore::delete)
            throw error
        }
    }

    private suspend fun processTransformSelection(change: SelectionChange) {
        repository.updateElements(
            noteId, change.after.strokes, change.after.texts, change.after.images,
        )
        pushNewCommand(CanvasCommand.ElementsTransformed(change.before, change.after))
        change.after.pageId()?.let(thumbnailService::request)
    }

    private suspend fun processDeleteSelection(selection: CanvasSelection) {
        repository.deleteElements(noteId, selection.strokes, selection.texts, selection.images)
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
            source.images.maxOfOrNull { it.x + it.boxWidth } ?: 0f,
        )
        val maximumY = maxOf(
            source.strokes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f,
            source.texts.maxOfOrNull { it.y } ?: 0f,
            source.images.maxOfOrNull { it.y + it.boxHeight } ?: 0f,
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
        val imageDrafts = source.images.map { image ->
            CanvasImageDraft(
                image.storageName, image.originalWidth, image.originalHeight,
                image.orientationDegrees, image.x + safeOffsetX, image.y + safeOffsetY,
                image.boxWidth, image.boxHeight,
            )
        }
        val copied = repository.copyElements(
            noteId, pageId, strokeDrafts, textDrafts, imageDrafts,
        )
        val pasted = CanvasSelection(copied.strokes, copied.texts, copied.images)
        selectionState.value = pasted
        pushNewCommand(CanvasCommand.ElementsAdded(copied.strokes, copied.texts, copied.images))
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
            if (tokenToStroke[it.stroke.token] == null) {
                tokenToRecoveryId.remove(it.stroke.token)?.let { operationId ->
                    recoveryJournal.delete(listOf(operationId))
                }
            }
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

    private suspend fun processCreatePage(operation: CanvasOperation.CreatePage) {
        val pageId = operation.createdPageId
            ?: repository.createPage(noteId, operation.template)
                .also { operation.createdPageId = it }
        awaitPage(pageId)
        clearPageSession()
        selectedPageId.value = pageId
        viewportState.update { it + (pageId to CanvasViewport()) }
        thumbnailService.request(pageId)
    }

    private suspend fun processDeletePage(pageId: Long, nextPageId: Long?) {
        val previousPageId = selectedPageId.value
        if (nextPageId != null) selectedPageId.value = nextPageId
        val deleted = try {
            repository.deletePage(noteId, pageId)
        } catch (error: Throwable) {
            selectedPageId.value = previousPageId
            throw error
        }
        discardRecoveryEntriesForPage(pageId)
        runCatching { thumbnailService.delete(pageId) }
        deleted.pdfStorageNames.forEach { storageName ->
            pdfPageRenderStore.evict(storageName)
            pdfDocumentStore.delete(storageName)
        }
        runCatching { deleteUnusedImageAssets(deleted.imageStorageNames) }
        viewportState.update { it - pageId }
        clearPageSession()
    }

    private fun switchPage(page: Page) {
        clearPageSession()
        selectedPageId.value = page.id
    }

    private fun clearPageSession() {
        undoStack.clear()
        redoStack.clear()
        clearPageRuntimeState()
        publishHistoryState()
    }

    private fun clearPageRuntimeState() {
        tokenToStroke.clear()
        tokensScheduledForErase.clear()
        suppressedStrokeIds.value = emptySet()
        selectionState.value = CanvasSelection()
        val protectedImageStorageNames = protectedInMemoryImageStorageNames()
        persistenceScope.launch {
            runCatching {
                canvasImageStore.cleanupOrphans(
                    repository.getReferencedImageStorageNames() + protectedImageStorageNames +
                        pageVersionService.referencedImageStorageNames(),
                )
            }
        }
    }

    private suspend fun awaitPage(pageId: Long) {
        repository.observePages(noteId).first { pages -> pages.any { it.id == pageId } }
    }

    private suspend fun discardRecoveryEntriesForPage(pageId: Long) {
        val operationIds = recoveryJournal.entries()
            .asSequence()
            .filter { it.pageId == pageId }
            .map(RecoveryEntry::operationId)
            .toSet()
        if (operationIds.isEmpty()) return
        recoveryJournal.delete(operationIds)
        tokenToRecoveryId.entries.removeAll { it.value in operationIds }
    }

    private fun protectedInMemoryImageStorageNames(): Set<String> = buildSet {
        addAll(clipboardState.value.images.map(CanvasImage::storageName))
        undoStack.forEach { addAll(it.imageStorageNames()) }
        redoStack.forEach { addAll(it.imageStorageNames()) }
    }

    private suspend fun deleteUnusedImageAssets(storageNames: Collection<String>) {
        if (storageNames.isEmpty()) return
        val protected = repository.getReferencedImageStorageNames() +
            protectedInMemoryImageStorageNames() +
            pageVersionService.referencedImageStorageNames()
        storageNames.filterNot { it in protected }.forEach(canvasImageStore::delete)
    }

    private suspend fun processUndo() {
        val command = undoStack.peekLast() ?: return
        applyInverse(command)
        selectionState.value = when (command) {
            is CanvasCommand.ElementsTransformed -> command.before
            is CanvasCommand.ElementsDeleted -> command.selection
            is CanvasCommand.ElementsAdded -> CanvasSelection()
            is CanvasCommand.PageWithTextAdded -> CanvasSelection()
            else -> selectionState.value
        }
        undoStack.removeLast()
        redoStack.addLast(command)
        publishHistoryState()
        if (command !is CanvasCommand.PageWithTextAdded) {
            command.pageId()?.let(thumbnailService::request)
        }
    }

    private suspend fun processRedo() {
        val command = redoStack.peekLast() ?: return
        apply(command)
        selectionState.value = when (command) {
            is CanvasCommand.ElementsTransformed -> command.after
            is CanvasCommand.ElementsDeleted -> CanvasSelection()
            is CanvasCommand.ElementsAdded -> CanvasSelection(
                command.strokes, command.texts, command.images,
            )
            is CanvasCommand.PageWithTextAdded -> CanvasSelection(
                texts = listOf(command.created.text),
            )
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
            is CanvasCommand.ElementsAdded -> repository.restoreElements(
                noteId, command.strokes, command.texts, command.images,
            )
            is CanvasCommand.ElementsDeleted -> repository.deleteElements(
                noteId, command.selection.strokes, command.selection.texts,
                command.selection.images,
            )
            is CanvasCommand.ElementsTransformed -> repository.updateElements(
                noteId, command.after.strokes, command.after.texts, command.after.images,
            )
            is CanvasCommand.PageWithTextAdded -> {
                val restored = repository.getPage(command.created.page.id)?.let { existingPage ->
                    command.created.copy(page = existingPage)
                } ?: repository.restorePageWithText(command.created)
                command.created = restored
                awaitPage(restored.page.id)
                clearPageRuntimeState()
                selectedPageId.value = restored.page.id
                viewportState.update { it + (restored.page.id to CanvasViewport()) }
                selectionState.value = CanvasSelection(texts = listOf(restored.text))
                thumbnailService.request(restored.page.id)
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
            is CanvasCommand.ElementsAdded -> repository.deleteElements(
                noteId, command.strokes, command.texts, command.images,
            )
            is CanvasCommand.ElementsDeleted -> repository.restoreElements(
                noteId, command.selection.strokes, command.selection.texts,
                command.selection.images,
            )
            is CanvasCommand.ElementsTransformed -> repository.updateElements(
                noteId, command.before.strokes, command.before.texts, command.before.images,
            )
            is CanvasCommand.PageWithTextAdded -> {
                selectedPageId.value = command.previousPageId
                val deleted = try {
                    repository.deletePage(noteId, command.created.page.id)
                } catch (error: Throwable) {
                    selectedPageId.value = command.created.page.id
                    throw error
                }
                discardRecoveryEntriesForPage(command.created.page.id)
                runCatching { thumbnailService.delete(command.created.page.id) }
                deleted.pdfStorageNames.forEach { storageName ->
                    pdfPageRenderStore.evict(storageName)
                    pdfDocumentStore.delete(storageName)
                }
                runCatching { deleteUnusedImageAssets(deleted.imageStorageNames) }
                clearPageRuntimeState()
                viewportState.update { it - command.created.page.id }
                selectionState.value = CanvasSelection()
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
            is CanvasOperation.AddText -> Unit
            is CanvasOperation.AddAiText -> {
                aiAssistantSession.update { it.copy(isInserting = false) }
            }
            is CanvasOperation.ImportImage -> Unit
            is CanvasOperation.TransformSelection -> selectionState.value = operation.change.before
            is CanvasOperation.DeleteSelection -> selectionState.value = operation.selection
            is CanvasOperation.PasteSelection -> selectionState.value = CanvasSelection()
            is CanvasOperation.ExportPage, is CanvasOperation.ExportPdf -> {
                _exportState.value = ExportUiState.Error()
            }
            is CanvasOperation.CreatePage -> Unit
            is CanvasOperation.CreatePageWithAiText -> {
                aiAssistantSession.update { it.copy(isInserting = false) }
            }
            is CanvasOperation.DeletePage -> Unit
            is CanvasOperation.ReorderPages -> Unit
            is CanvasOperation.RestoreVersion -> Unit
            is CanvasOperation.Undo -> rollbackUndo()
            is CanvasOperation.Redo -> rollbackRedo()
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
            is CanvasCommand.PageWithTextAdded -> {
                selectionState.value = CanvasSelection(texts = listOf(command.created.text))
                selectedPageId.value = command.created.page.id
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
            is CanvasCommand.ElementsAdded -> Unit
            is CanvasCommand.ElementsDeleted -> Unit
            is CanvasCommand.ElementsTransformed -> selectionState.value = command.before
            is CanvasCommand.PageWithTextAdded -> {
                selectionState.value = CanvasSelection()
                selectedPageId.value = command.previousPageId
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

    private fun completePendingOperations(count: Int) {
        pendingOperations.update { (it - count).coerceAtLeast(0) }
        if (pendingOperations.value == 0) {
            tokenToStroke.clear()
            tokensScheduledForErase.clear()
        }
    }

    private suspend fun <T> retrySave(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(MAXIMUM_SAVE_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                lastError = error
                if (attempt < MAXIMUM_SAVE_ATTEMPTS - 1) delay(150L shl attempt)
            }
        }
        throw checkNotNull(lastError)
    }

    private fun CanvasOperation.versionPageId(): Long? = when (this) {
        is CanvasOperation.Add -> null
        is CanvasOperation.Erase -> targets.firstOrNull()?.pageId()
        is CanvasOperation.AreaErase -> replacements.firstOrNull()?.target?.pageId()
        is CanvasOperation.AddText -> pageId
        is CanvasOperation.AddAiText -> pageId
        is CanvasOperation.ImportImage -> pageId
        is CanvasOperation.TransformSelection -> change.before.pageId() ?: change.after.pageId()
        is CanvasOperation.DeleteSelection -> selection.pageId()
        is CanvasOperation.PasteSelection -> pageId
        is CanvasOperation.RestoreVersion -> null
        is CanvasOperation.ExportPage, is CanvasOperation.ExportPdf,
        is CanvasOperation.CreatePage, is CanvasOperation.CreatePageWithAiText,
        is CanvasOperation.DeletePage,
        is CanvasOperation.ReorderPages -> null
        is CanvasOperation.Undo -> undoStack.peekLast()
            ?.takeUnless { it is CanvasCommand.PageWithTextAdded }
            ?.pageId()
        is CanvasOperation.Redo -> redoStack.peekLast()
            ?.takeUnless { it is CanvasCommand.PageWithTextAdded }
            ?.pageId()
    }

    private fun ErasableStroke.pageId(): Long = when (this) {
        is ErasableStroke.Persisted -> stroke.pageId
        is ErasableStroke.Pending -> (uiState.value as? CanvasUiState.Ready)?.page?.id ?: 0L
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
        is CanvasCommand.ElementsAdded -> strokes.firstOrNull()?.pageId
            ?: texts.firstOrNull()?.pageId ?: images.firstOrNull()?.pageId
        is CanvasCommand.ElementsDeleted -> selection.pageId()
        is CanvasCommand.ElementsTransformed -> after.pageId()
        is CanvasCommand.PageWithTextAdded -> created.page.id
    }

    private fun CanvasCommand.imageStorageNames(): List<String> = when (this) {
        is CanvasCommand.ElementsAdded -> images.map(CanvasImage::storageName)
        is CanvasCommand.ElementsDeleted -> selection.images.map(CanvasImage::storageName)
        is CanvasCommand.ElementsTransformed ->
            (before.images + after.images).map(CanvasImage::storageName)
        is CanvasCommand.AddStroke,
        is CanvasCommand.DeleteStrokes,
        is CanvasCommand.ReplaceStrokes,
        is CanvasCommand.PageWithTextAdded -> emptyList()
    }

    private fun CanvasSelection.pageId(): Long? = strokes.firstOrNull()?.pageId
        ?: texts.firstOrNull()?.pageId ?: images.firstOrNull()?.pageId

    override fun onCleared() {
        cancelAiGeneration(updateState = false)
        operationQueue.close()
        super.onCleared()
    }

    private sealed interface CanvasOperation {
        val enqueuedAt: Long
        sealed interface PageOperation : CanvasOperation

        data class Add(
            val stroke: PendingCanvasStroke,
            val pageId: Long,
            val operationId: String = UUID.randomUUID().toString(),
            override val enqueuedAt: Long = System.currentTimeMillis(),
        ) : CanvasOperation
        data class Erase(val targets: List<ErasableStroke>, override val enqueuedAt: Long = System.currentTimeMillis()) : CanvasOperation
        data class AreaErase(val replacements: List<AreaEraseReplacement>, override val enqueuedAt: Long = System.currentTimeMillis()) : CanvasOperation
        data class AddText(val draft: CanvasTextDraft, val pageId: Long, override val enqueuedAt: Long = System.currentTimeMillis()) : CanvasOperation
        data class AddAiText(
            val draft: CanvasTextDraft,
            val pageId: Long,
            override val enqueuedAt: Long = System.currentTimeMillis(),
        ) : PageOperation
        data class ImportImage(
            val uri: Uri,
            val pageId: Long,
            val pageAspectRatio: Float,
            override val enqueuedAt: Long = System.currentTimeMillis(),
        ) : CanvasOperation
        data class TransformSelection(val change: SelectionChange, override val enqueuedAt: Long = System.currentTimeMillis()) : CanvasOperation
        data class DeleteSelection(val selection: CanvasSelection, override val enqueuedAt: Long = System.currentTimeMillis()) : CanvasOperation
        data class PasteSelection(
            val source: CanvasSelection,
            val pageId: Long,
            val offsetX: Float,
            val offsetY: Float,
            override val enqueuedAt: Long = System.currentTimeMillis(),
        ) : CanvasOperation
        data class ExportPage(
            val note: Note,
            val pageId: Long,
            val pageNumber: Int,
            val format: ExportFormat,
            override val enqueuedAt: Long = System.currentTimeMillis(),
        ) : CanvasOperation
        data class ExportPdf(val note: Note, override val enqueuedAt: Long = System.currentTimeMillis()) : CanvasOperation
        data class CreatePage(
            val template: PageTemplate,
            var createdPageId: Long? = null,
            override val enqueuedAt: Long = System.currentTimeMillis(),
        ) : PageOperation
        data class CreatePageWithAiText(
            val draft: CanvasTextDraft,
            val previousPageId: Long,
            var created: CreatedPageText? = null,
            override val enqueuedAt: Long = System.currentTimeMillis(),
        ) : PageOperation
        data class DeletePage(val pageId: Long, val nextPageId: Long?, override val enqueuedAt: Long = System.currentTimeMillis()) : PageOperation
        data class ReorderPages(val pageIds: List<Long>, override val enqueuedAt: Long = System.currentTimeMillis()) : PageOperation
        data class RestoreVersion(val versionId: Long, val pageId: Long, override val enqueuedAt: Long = System.currentTimeMillis()) : CanvasOperation
        data class Undo(override val enqueuedAt: Long = System.currentTimeMillis()) : PageOperation
        data class Redo(override val enqueuedAt: Long = System.currentTimeMillis()) : PageOperation
    }

    private sealed interface CanvasCommand {
        data class AddStroke(val stroke: Stroke) : CanvasCommand
        data class DeleteStrokes(val strokes: List<Stroke>) : CanvasCommand
        data class ReplaceStrokes(val before: List<Stroke>, val after: List<Stroke>) : CanvasCommand
        data class ElementsAdded(
            val strokes: List<Stroke>,
            val texts: List<CanvasText>,
            val images: List<CanvasImage>,
        ) : CanvasCommand
        data class ElementsDeleted(val selection: CanvasSelection) : CanvasCommand
        data class ElementsTransformed(
            val before: CanvasSelection,
            val after: CanvasSelection,
        ) : CanvasCommand
        data class PageWithTextAdded(
            var created: CreatedPageText,
            val previousPageId: Long,
        ) : CanvasCommand
    }

    private data class HistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false)

    class Factory(
        private val noteId: Long,
        private val repository: NoteRepository,
        private val settingsStore: DrawingToolSettingsStore,
        private val customColorPaletteStore: CustomColorPaletteStore,
        private val thumbnailStore: PageThumbnailStore,
        private val thumbnailService: PageThumbnailService,
        private val exportService: NoteExportService,
        private val pdfDocumentStore: PdfDocumentStore,
        private val pdfPageRenderStore: PdfPageRenderStore,
        private val canvasImageStore: CanvasImageStore,
        private val recoveryJournal: RecoveryJournal,
        private val pageVersionService: PageVersionService,
        private val onDeviceAiRepository: OnDeviceAiRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CanvasViewModel(
                noteId, repository, settingsStore, customColorPaletteStore,
                thumbnailStore, thumbnailService, exportService,
                pdfDocumentStore, pdfPageRenderStore, canvasImageStore,
                recoveryJournal, pageVersionService, onDeviceAiRepository,
            ) as T
    }

    private companion object {
        const val DEFAULT_PAGE_ASPECT_RATIO = 16f / 9f
        const val DEFAULT_IMAGE_WIDTH = 0.45f
        const val MINIMUM_IMAGE_SIZE = 0.08f
        const val MAXIMUM_IMAGE_HEIGHT = 0.65f
        const val AI_TEXT_WIDTH = 0.7f
        const val AI_TEXT_VERTICAL_OFFSET = 0.08f
        const val MAXIMUM_AI_CONTEXT_LENGTH = 12_000
        const val AI_CONTEXT_SEPARATOR = "\n\n"
        const val AI_ASSISTANT_INSTRUCTION =
            "당신은 NoteUp의 온디바이스 노트 도우미입니다. 간결하고 정확한 한국어로 답하세요."
        const val AI_CONTEXT_HEADER = "\n\n선택한 노트 문맥:\n---\n"
        const val AI_CONTEXT_FOOTER = "\n---"
        const val AI_QUESTION_HEADER = "\n\n사용자 요청:\n"
        const val AI_ANSWER_INSTRUCTION = "\n\n노트에 바로 넣을 수 있는 답변 본문만 작성하세요."
    }
}
