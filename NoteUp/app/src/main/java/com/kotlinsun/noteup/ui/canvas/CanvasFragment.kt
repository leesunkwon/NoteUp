package com.kotlinsun.noteup.ui.canvas

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kotlinsun.noteup.NoteUpApplication
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentCanvasBinding
import com.kotlinsun.noteup.domain.model.AppSettings
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasTextDraft
import com.kotlinsun.noteup.domain.model.DrawingSettings
import com.kotlinsun.noteup.domain.model.DrawingTool
import com.kotlinsun.noteup.domain.model.EraserMode
import com.kotlinsun.noteup.domain.model.ExportArtifact
import com.kotlinsun.noteup.domain.model.ExportFormat
import com.kotlinsun.noteup.domain.model.ExportUiState
import com.kotlinsun.noteup.domain.model.HighlighterColor
import com.kotlinsun.noteup.domain.model.HighlighterThickness
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.PenThickness
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.TextSize
import com.kotlinsun.noteup.ui.common.applyCriticalPositiveAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

class CanvasFragment : Fragment() {
    private var _binding: FragmentCanvasBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var renderedStrokes: List<Stroke> = emptyList()
    private var renderedTexts: List<CanvasText> = emptyList()
    private var renderedPageId: Long? = null
    private var currentSettings = DrawingSettings()
    private var currentAppSettings = AppSettings()
    private var currentState: CanvasUiState = CanvasUiState.Loading
    private var presentedArtifactPath: String? = null
    private var savingArtifact = false
    private var pagePanelOpen = false
    private var pdfRenderJob: Job? = null
    private var pdfRenderKey: String? = null
    private var pdfPageLoading = false
    private var pdfDisplayedPageId: Long? = null
    private var pdfRenderGeneration = 0L
    private var currentZoomScale = MIN_ZOOM
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            savingArtifact = true
            viewModel.saveExport(uri)
        } else {
            (viewModel.exportState.value as? ExportUiState.Ready)?.artifact?.let {
                presentedArtifactPath = null
                showExportResultDialog(it)
            }
        }
    }
    private val pageAdapter by lazy {
        val store = (requireActivity().application as NoteUpApplication).container.pageThumbnailStore
        PageThumbnailAdapter(
            store,
            onClick = {
                viewModel.selectPage(it)
                performHapticFeedback()
            },
            onDelete = ::confirmPageDeletion,
            onOrderChanged = viewModel::reorderPages,
        )
    }

    private val noteId: Long by lazy {
        requireArguments().getLong(NOTE_ID_ARGUMENT, INVALID_NOTE_ID)
    }
    private val viewModel: CanvasViewModel by viewModels {
        val container = (requireActivity().application as NoteUpApplication).container
        CanvasViewModel.Factory(
            noteId,
            container.noteRepository,
            container.drawingToolSettingsStore,
            container.pageThumbnailStore,
            container.pageThumbnailService,
            container.noteExportService,
            container.pdfDocumentStore,
            container.pdfPageRenderStore,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCanvasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagePanelOpen = savedInstanceState?.getBoolean(PAGE_PANEL_OPEN_KEY) ?: pagePanelOpen
        binding.backButton.setOnClickListener { findNavController().popBackStack() }
        binding.drawingCanvas.onStrokeCompleted = viewModel::addStroke
        binding.drawingCanvas.onStrokesErased = viewModel::eraseStrokes
        binding.drawingCanvas.onAreaErased = viewModel::eraseArea
        binding.drawingCanvas.onViewportChanged = { viewport ->
            viewModel.updateViewport(viewport)
            renderZoomControls(viewport.scale)
            (currentState as? CanvasUiState.Ready)?.let {
                renderPdfBackground(it.page, viewport.scale, debounce = true)
            }
        }
        binding.drawingCanvas.onCanvasSizeChanged = { _, _ ->
            (currentState as? CanvasUiState.Ready)?.let {
                renderPdfBackground(it.page, it.viewport.scale, force = true)
            }
        }
        binding.drawingCanvas.onTextRequested = ::showNewTextDialog
        binding.drawingCanvas.onTextEditRequested = ::showEditTextDialog
        binding.drawingCanvas.onSelectionChanged = viewModel::updateSelection
        binding.drawingCanvas.onSelectionTransformed = viewModel::transformSelection
        binding.drawingCanvas.onPageSwipe = { direction ->
            when (direction) {
                PageSwipeDirection.PREVIOUS -> viewModel.selectPreviousPage()
                PageSwipeDirection.NEXT -> viewModel.selectNextPage()
            }
            performHapticFeedback()
        }
        setupToolbar()
        setupPagePanel()
        binding.pagePanel.isVisible = pagePanelOpen
        observeState()
    }

    private fun setupToolbar() = with(binding) {
        listOf(
            penToolButton, highlighterToolButton, eraserToolButton, lassoToolButton,
            shapeToolButton, textToolButton,
            thinButton, mediumButton, thickButton,
            strokeEraserModeButton, areaEraserModeButton,
        ).forEach { it.isCheckable = true }
        configureToolbarAccessibility()
        TooltipCompat.setTooltipText(zoomOutButton, getString(R.string.zoom_out))
        TooltipCompat.setTooltipText(zoomInButton, getString(R.string.zoom_in))
        penToolButton.setOnClickListener { selectDrawingTool(DrawingTool.PEN) }
        highlighterToolButton.setOnClickListener { selectDrawingTool(DrawingTool.HIGHLIGHTER) }
        eraserToolButton.setOnClickListener { selectDrawingTool(DrawingTool.ERASER) }
        lassoToolButton.setOnClickListener { selectDrawingTool(DrawingTool.LASSO) }
        shapeToolButton.setOnClickListener { showShapeMenu() }
        textToolButton.setOnClickListener { selectDrawingTool(DrawingTool.TEXT) }
        moreButton.setOnClickListener { showMoreMenu() }
        copySelectionButton.setOnClickListener { viewModel.copySelection() }
        pasteSelectionButton.setOnClickListener {
            val offset = PASTE_OFFSET_DP * resources.displayMetrics.density
            viewModel.pasteSelection(
                offset / drawingCanvas.width.coerceAtLeast(1),
                offset / drawingCanvas.height.coerceAtLeast(1),
            )
        }
        deleteSelectionButton.setOnClickListener {
            deleteCurrentSelection()
        }
        editTextButton.setOnClickListener {
            drawingCanvas.currentSelection().texts.singleOrNull()?.let(::showEditTextDialog)
        }
        strokeEraserModeButton.setOnClickListener {
            viewModel.selectEraserMode(EraserMode.STROKE)
            performHapticFeedback()
        }
        areaEraserModeButton.setOnClickListener {
            viewModel.selectEraserMode(EraserMode.AREA)
            performHapticFeedback()
        }
        blackColorButton.setOnClickListener { selectColorSlot(0) }
        blueColorButton.setOnClickListener { selectColorSlot(1) }
        redColorButton.setOnClickListener { selectColorSlot(2) }
        greenColorButton.setOnClickListener { selectColorSlot(3) }
        thinButton.setOnClickListener { selectThicknessSlot(0) }
        mediumButton.setOnClickListener { selectThicknessSlot(1) }
        thickButton.setOnClickListener { selectThicknessSlot(2) }
        undoButton.setOnClickListener { viewModel.undo(); performHapticFeedback() }
        redoButton.setOnClickListener { viewModel.redo(); performHapticFeedback() }
        previousPageButton.setOnClickListener {
            viewModel.selectPreviousPage()
            performHapticFeedback()
        }
        nextPageButton.setOnClickListener {
            viewModel.selectNextPage()
            performHapticFeedback()
        }
        addPageButton.setOnClickListener { showPageTemplateDialog() }
        zoomOutButton.setOnClickListener { drawingCanvas.adjustZoom(-ZOOM_STEP) }
        zoomResetButton.setOnClickListener { drawingCanvas.resetZoom() }
        zoomInButton.setOnClickListener { drawingCanvas.adjustZoom(ZOOM_STEP) }
        pageListButton.setOnClickListener {
            pagePanelOpen = !pagePanel.isVisible
            pagePanel.isVisible = pagePanelOpen
        }
        closePagePanelButton.setOnClickListener {
            pagePanelOpen = false
            pagePanel.isVisible = false
        }
    }

    private fun setupPagePanel() = with(binding) {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val orientation = if (isPortrait) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
        val dragDirections = if (isPortrait) {
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        } else {
            ItemTouchHelper.UP or ItemTouchHelper.DOWN
        }
        pageList.layoutManager = LinearLayoutManager(requireContext(), orientation, false)
        pageList.adapter = pageAdapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            dragDirections, 0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                pageAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                pageAdapter.commitOrder()
            }
        }).attachToRecyclerView(pageList)
    }

    private fun configureToolbarAccessibility() = with(binding) {
        listOf(
            backButton to R.string.back,
            penToolButton to R.string.pen_tool,
            highlighterToolButton to R.string.highlighter_tool,
            eraserToolButton to R.string.eraser_tool,
            lassoToolButton to R.string.lasso_tool,
            shapeToolButton to R.string.shape_tool,
            textToolButton to R.string.text_tool,
            undoButton to R.string.undo,
            redoButton to R.string.redo,
            moreButton to R.string.more,
            pageListButton to R.string.page_list,
            previousPageButton to R.string.previous_page,
            nextPageButton to R.string.next_page,
            addPageButton to R.string.add_page,
            closePagePanelButton to R.string.close,
            zoomOutButton to R.string.zoom_out,
            zoomResetButton to R.string.zoom_reset,
            zoomInButton to R.string.zoom_in,
        ).forEach { (button, labelRes) -> setToolbarButtonLabel(button, labelRes) }
        ViewCompat.setAccessibilityLiveRegion(
            saveStatus,
            ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE,
        )
        ViewCompat.setAccessibilityLiveRegion(
            pageIndicator,
            ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE,
        )
    }

    private fun setToolbarButtonLabel(button: MaterialButton, labelRes: Int) {
        val label = getString(labelRes)
        button.contentDescription = label
        TooltipCompat.setTooltipText(button, label)
    }

    private fun renderShapeToolButton(tool: DrawingTool) {
        val (iconRes, labelRes) = when (tool) {
            DrawingTool.LINE -> R.drawable.ic_tool_line to R.string.line_tool
            DrawingTool.RECTANGLE -> R.drawable.ic_tool_rectangle to R.string.rectangle_tool
            DrawingTool.CIRCLE -> R.drawable.ic_tool_circle to R.string.circle_tool
            else -> R.drawable.ic_tool_shape to R.string.shape_tool
        }
        binding.shapeToolButton.setIconResource(iconRes)
        setToolbarButtonLabel(binding.shapeToolButton, labelRes)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.settings.collect(::renderSettings) }
                launch {
                    val store = (requireActivity().application as NoteUpApplication)
                        .container.appSettingsStore
                    store.settings.collect(::renderAppSettings)
                }
                launch {
                    viewModel.errors.collect {
                        Snackbar.make(binding.root, R.string.stroke_operation_error, Snackbar.LENGTH_SHORT).show()
                    }
                }
                launch { viewModel.events.collect(::handleEvent) }
                launch { viewModel.exportState.collect(::renderExportState) }
            }
        }
    }

    private fun render(state: CanvasUiState) = with(binding) {
        currentState = state
        loadingIndicator.isVisible = state == CanvasUiState.Loading
        notFoundState.isVisible = state == CanvasUiState.NotFound
        if (state is CanvasUiState.Ready) {
            noteTitle.text = state.note.title
            saveStatus.text = when {
                state.isExporting -> getString(
                    R.string.export_progress,
                    state.exportCompletedPages,
                    state.exportTotalPages,
                )
                state.isSaving -> getString(R.string.saving)
                else -> getString(R.string.saved)
            }
            undoButton.isEnabled = state.canUndo
            redoButton.isEnabled = state.canRedo
            setAvailabilityState(undoButton, state.canUndo)
            setAvailabilityState(redoButton, state.canRedo)
            previousPageButton.isEnabled = !state.isBusy && state.pagePosition > 0
            nextPageButton.isEnabled = !state.isBusy && state.pagePosition < state.pages.lastIndex
            addPageButton.isEnabled = !state.isBusy
            moreButton.isEnabled = !state.isBusy
            pageIndicator.text = getString(
                R.string.page_indicator,
                state.pagePosition + 1,
                state.pages.size,
            )
            renderCanvasAccessibility()
            pageAdapter.submitPages(state.pages, state.page.id, state.thumbnailRevisions)
            renderPdfBackground(state.page, state.viewport.scale)
            if (renderedPageId != state.page.id) {
                renderedPageId = state.page.id
                renderedStrokes = state.strokes
                renderedTexts = state.texts
                drawingCanvas.showPage(
                    state.page.id,
                    state.page.templateType,
                    state.strokes,
                    state.viewport,
                )
                drawingCanvas.setTexts(state.texts)
            } else {
                if (renderedStrokes != state.strokes) {
                    renderedStrokes = state.strokes
                    drawingCanvas.setStrokes(state.strokes)
                }
                if (renderedTexts != state.texts) {
                    renderedTexts = state.texts
                    drawingCanvas.setTexts(state.texts)
                }
                drawingCanvas.setViewport(state.viewport)
            }
            if (drawingCanvas.currentSelection() != state.selection) {
                drawingCanvas.syncSelection(state.selection)
            }
            renderZoomControls(
                state.viewport.scale,
                controlsEnabled = !state.isPageChanging && !state.isExporting,
            )
        } else {
            noteTitle.text = getString(R.string.canvas_title)
            saveStatus.text = null
            undoButton.isEnabled = false
            redoButton.isEnabled = false
            setAvailabilityState(undoButton, false)
            setAvailabilityState(redoButton, false)
            previousPageButton.isEnabled = false
            nextPageButton.isEnabled = false
            addPageButton.isEnabled = false
            moreButton.isEnabled = false
            pageIndicator.text = null
            renderZoomControls(MIN_ZOOM, controlsEnabled = false)
        }
        renderToolbarState()
        updateInputEnabled()
    }

    private fun renderZoomControls(
        scale: Float,
        controlsEnabled: Boolean = (currentState as? CanvasUiState.Ready)?.let {
            !it.isPageChanging && !it.isExporting
        } == true,
    ) = with(binding) {
        val clampedScale = scale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        currentZoomScale = clampedScale
        zoomPercentage.text = getString(
            R.string.zoom_percentage,
            (clampedScale * 100f).roundToInt(),
        )
        zoomOutButton.isEnabled = controlsEnabled && clampedScale > MIN_ZOOM + ZOOM_EPSILON
        zoomResetButton.isEnabled = controlsEnabled && clampedScale > MIN_ZOOM + ZOOM_EPSILON
        zoomInButton.isEnabled = controlsEnabled && clampedScale < MAX_ZOOM - ZOOM_EPSILON
        renderCanvasAccessibility()
    }

    private fun showShapeMenu() {
        PopupMenu(requireContext(), binding.shapeToolButton).apply {
            menu.add(0, SHAPE_LINE_ID, 0, R.string.line_tool)
            menu.add(0, SHAPE_RECTANGLE_ID, 1, R.string.rectangle_tool)
            menu.add(0, SHAPE_CIRCLE_ID, 2, R.string.circle_tool)
            setOnMenuItemClickListener { item ->
                val tool = when (item.itemId) {
                    SHAPE_LINE_ID -> DrawingTool.LINE
                    SHAPE_RECTANGLE_ID -> DrawingTool.RECTANGLE
                    SHAPE_CIRCLE_ID -> DrawingTool.CIRCLE
                    else -> return@setOnMenuItemClickListener false
                }
                selectDrawingTool(tool)
                true
            }
            show()
        }
    }

    private fun showMoreMenu() {
        val state = currentState as? CanvasUiState.Ready
        PopupMenu(requireContext(), binding.moreButton).apply {
            menu.add(0, MORE_EXPORT_ID, 0, R.string.export).isEnabled = state != null && !state.isBusy
            menu.add(0, MORE_SHORTCUTS_ID, 1, R.string.keyboard_shortcuts)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MORE_EXPORT_ID -> {
                        showExportFormatDialog()
                        true
                    }
                    MORE_SHORTCUTS_ID -> {
                        showKeyboardShortcutsDialog()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showKeyboardShortcutsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.keyboard_shortcuts)
            .setMessage(R.string.keyboard_shortcuts_description)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 || _binding == null) {
            return false
        }
        val state = currentState as? CanvasUiState.Ready
        val commandModifier = event.isCtrlPressed || event.isMetaPressed
        if (commandModifier && event.keyCode == KeyEvent.KEYCODE_Z) {
            if (event.isShiftPressed) {
                if (state?.canRedo == true) viewModel.redo()
            } else if (state?.canUndo == true) {
                viewModel.undo()
            }
            return true
        }
        if (commandModifier && event.keyCode == KeyEvent.KEYCODE_Y) {
            if (state?.canRedo == true) viewModel.redo()
            return true
        }
        if (commandModifier || event.isAltPressed) return false

        val canEdit = state != null && !state.isBusy && !state.isExporting && !pdfPageLoading
        return when (event.keyCode) {
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> {
                if (canEdit) selectDrawingTool(DrawingTool.PEN)
                true
            }
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> {
                if (canEdit) selectDrawingTool(DrawingTool.HIGHLIGHTER)
                true
            }
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> {
                if (canEdit) selectDrawingTool(DrawingTool.ERASER)
                true
            }
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> {
                if (canEdit) selectDrawingTool(DrawingTool.LASSO)
                true
            }
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> {
                if (canEdit) selectDrawingTool(DrawingTool.TEXT)
                true
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> {
                if (canEdit && state != null && state.pagePosition > 0) {
                    viewModel.selectPreviousPage()
                }
                true
            }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                if (canEdit && state != null && state.pagePosition < state.pages.lastIndex) {
                    viewModel.selectNextPage()
                }
                true
            }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                if (canEdit) binding.drawingCanvas.adjustZoom(-ZOOM_STEP)
                true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                if (canEdit) binding.drawingCanvas.resetZoom()
                true
            }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                if (canEdit) binding.drawingCanvas.adjustZoom(ZOOM_STEP)
                true
            }
            KeyEvent.KEYCODE_EQUALS -> {
                if (!event.isShiftPressed) return false
                if (canEdit) binding.drawingCanvas.adjustZoom(ZOOM_STEP)
                true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (canEdit && !binding.drawingCanvas.currentSelection().isEmpty) {
                    deleteCurrentSelection()
                }
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                cancelKeyboardInteraction()
                true
            }
            else -> false
        }
    }

    private fun deleteCurrentSelection() {
        viewModel.deleteSelection()
        binding.drawingCanvas.clearSelection()
    }

    private fun cancelKeyboardInteraction() {
        binding.drawingCanvas.cancelActiveStroke()
        binding.drawingCanvas.clearSelection()
        if (binding.pagePanel.isVisible) {
            pagePanelOpen = false
            binding.pagePanel.isVisible = false
        }
    }

    private fun showExportFormatDialog() {
        val labels = arrayOf(
            getString(R.string.export_current_png),
            getString(R.string.export_current_webp),
            getString(R.string.export_note_pdf),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export)
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> viewModel.exportCurrentPage(ExportFormat.PNG)
                    1 -> viewModel.exportCurrentPage(ExportFormat.WEBP)
                    else -> viewModel.exportNotePdf()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderExportState(state: ExportUiState) {
        when (state) {
            ExportUiState.Idle, is ExportUiState.Rendering -> Unit
            is ExportUiState.Saving -> Unit
            is ExportUiState.Ready -> {
                if (savingArtifact) {
                    savingArtifact = false
                    Snackbar.make(binding.root, R.string.export_saved, Snackbar.LENGTH_SHORT).show()
                    viewModel.clearExportResult()
                } else if (presentedArtifactPath != state.artifact.file.absolutePath) {
                    presentedArtifactPath = state.artifact.file.absolutePath
                    showExportResultDialog(state.artifact)
                }
            }
            is ExportUiState.Error -> {
                savingArtifact = false
                presentedArtifactPath = null
                Snackbar.make(binding.root, R.string.export_failed, Snackbar.LENGTH_SHORT).show()
                viewModel.clearExportResult()
            }
        }
    }

    private fun showExportResultDialog(artifact: ExportArtifact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_complete)
            .setMessage(artifact.displayName)
            .setPositiveButton(R.string.share) { _, _ -> shareArtifact(artifact) }
            .setNeutralButton(R.string.save_elsewhere) { _, _ -> launchCreateDocument(artifact) }
            .setNegativeButton(R.string.close) { _, _ -> viewModel.clearExportResult() }
            .setOnCancelListener { viewModel.clearExportResult() }
            .show()
    }

    private fun shareArtifact(artifact: ExportArtifact) {
        val uri = FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", artifact.file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = artifact.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(artifact.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_export)))
        viewModel.clearExportResult()
    }

    private fun launchCreateDocument(artifact: ExportArtifact) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = artifact.mimeType
            putExtra(Intent.EXTRA_TITLE, artifact.displayName)
        }
        createDocumentLauncher.launch(intent)
    }

    private fun renderSettings(settings: DrawingSettings) = with(binding) {
        currentSettings = settings
        drawingCanvas.drawingSettings = settings
        renderToolbarState()
        updateInputEnabled()
    }

    private fun renderAppSettings(settings: AppSettings) = with(binding) {
        currentAppSettings = settings
        root.keepScreenOn = settings.keepScreenOn
        drawingCanvas.isPageSwipeEnabled = settings.pageSwipeEnabled
        drawingCanvas.canvasAppearance = settings.canvasAppearance
    }

    private fun renderToolbarState() = with(binding) {
        val settings = currentSettings
        penToolButton.isChecked = settings.tool == DrawingTool.PEN
        highlighterToolButton.isChecked = settings.tool == DrawingTool.HIGHLIGHTER
        eraserToolButton.isChecked = settings.tool == DrawingTool.ERASER
        lassoToolButton.isChecked = settings.tool == DrawingTool.LASSO
        shapeToolButton.isChecked = settings.tool in SHAPE_TOOLS
        textToolButton.isChecked = settings.tool == DrawingTool.TEXT
        listOf(
            penToolButton,
            highlighterToolButton,
            eraserToolButton,
            lassoToolButton,
            shapeToolButton,
            textToolButton,
        ).forEach { setSelectionState(it, it.isChecked) }
        renderShapeToolButton(settings.tool)
        val showSettings = settings.tool in DRAWING_OPTION_TOOLS
        colorButtons().forEach { it.isVisible = showSettings }
        thicknessButtons().forEach { it.isVisible = showSettings }
        strokeEraserModeButton.isVisible = settings.tool == DrawingTool.ERASER
        areaEraserModeButton.isVisible = settings.tool == DrawingTool.ERASER
        strokeEraserModeButton.isChecked = settings.eraserMode == EraserMode.STROKE
        areaEraserModeButton.isChecked = settings.eraserMode == EraserMode.AREA
        setSelectionState(strokeEraserModeButton, strokeEraserModeButton.isChecked)
        setSelectionState(areaEraserModeButton, areaEraserModeButton.isChecked)
        if (showSettings) renderColorAndThickness(settings)

        val state = currentState as? CanvasUiState.Ready
        val isLasso = settings.tool == DrawingTool.LASSO
        val hasSelection = state?.hasSelection == true
        val canPaste = state?.canPaste == true
        val isBusy = state?.isBusy != false
        val selection = drawingCanvas.currentSelection()
        val hasSingleTextSelection = settings.tool == DrawingTool.TEXT &&
            selection.texts.size == 1 && selection.strokes.isEmpty()
        val showSelectionActions = (isLasso && hasSelection) || hasSingleTextSelection
        lassoHint.isVisible = isLasso && !hasSelection
        copySelectionButton.isVisible = showSelectionActions
        deleteSelectionButton.isVisible = showSelectionActions
        pasteSelectionButton.isVisible = isLasso && canPaste
        editTextButton.isVisible = (isLasso || settings.tool == DrawingTool.TEXT) &&
            selection.texts.size == 1 && selection.strokes.isEmpty()
        listOf(copySelectionButton, pasteSelectionButton, deleteSelectionButton, editTextButton)
            .forEach { it.isEnabled = !isBusy }
    }

    private fun renderColorAndThickness(settings: DrawingSettings) {
        val colors = if (settings.tool == DrawingTool.HIGHLIGHTER) {
            listOf(
                Triple(HighlighterColor.YELLOW.argb, R.color.highlighter_yellow, R.string.highlighter_color_yellow),
                Triple(HighlighterColor.GREEN.argb, R.color.highlighter_green, R.string.highlighter_color_green),
                Triple(HighlighterColor.PINK.argb, R.color.highlighter_pink, R.string.highlighter_color_pink),
                Triple(HighlighterColor.BLUE.argb, R.color.highlighter_blue, R.string.highlighter_color_blue),
            )
        } else {
            listOf(
                Triple(PenColor.BLACK.argb, R.color.pen_black, R.string.pen_color_black),
                Triple(PenColor.BLUE.argb, R.color.pen_blue, R.string.pen_color_blue),
                Triple(PenColor.RED.argb, R.color.pen_red, R.string.pen_color_red),
                Triple(PenColor.GREEN.argb, R.color.pen_green, R.string.pen_color_green),
            )
        }
        val selectedArgb = if (settings.tool == DrawingTool.HIGHLIGHTER) {
            settings.highlighter.color.argb
        } else settings.pen.color.argb
        val strokeWidth = resources.getDimensionPixelSize(R.dimen.pen_selected_stroke_width)
        colorButtons().zip(colors).forEach { (button, color) ->
            val selected = color.first == selectedArgb
            button.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(color.second))
            button.contentDescription = getString(color.third)
            button.strokeWidth = if (selected) strokeWidth else 0
            button.isCheckable = true
            button.isChecked = selected
            button.icon = if (selected) {
                AppCompatResources.getDrawable(requireContext(), R.drawable.ic_check)
            } else null
            val opaqueSwatchColor = ColorUtils.setAlphaComponent(color.first, 255)
            val checkColor = if (
                ColorUtils.calculateContrast(Color.BLACK, opaqueSwatchColor) >=
                ColorUtils.calculateContrast(Color.WHITE, opaqueSwatchColor)
            ) Color.BLACK else Color.WHITE
            button.iconTint = ColorStateList.valueOf(
                checkColor,
            )
            button.iconPadding = 0
            button.iconSize = resources.getDimensionPixelSize(R.dimen.toolbar_icon_size)
            setSelectionState(button, selected)
        }

        val selectedThickness = if (settings.tool == DrawingTool.HIGHLIGHTER) {
            settings.highlighter.thickness.ordinal
        } else if (settings.tool == DrawingTool.TEXT) {
            settings.textSize.ordinal
        } else settings.pen.thickness.ordinal
        if (settings.tool == DrawingTool.TEXT) {
            binding.thinButton.setText(R.string.text_small)
            binding.mediumButton.setText(R.string.text_medium)
            binding.thickButton.setText(R.string.text_large)
        } else {
            binding.thinButton.setText(R.string.pen_thin)
            binding.mediumButton.setText(R.string.pen_medium)
            binding.thickButton.setText(R.string.pen_thick)
        }
        thicknessButtons().forEachIndexed { index, button ->
            button.isChecked = index == selectedThickness
            setSelectionState(button, button.isChecked)
        }
    }

    private fun setSelectionState(view: View, selected: Boolean) {
        ViewCompat.setStateDescription(
            view,
            getString(
                if (selected) R.string.accessibility_selected
                else R.string.accessibility_not_selected,
            ),
        )
    }

    private fun setAvailabilityState(view: View, available: Boolean) {
        ViewCompat.setStateDescription(
            view,
            getString(
                if (available) R.string.accessibility_available
                else R.string.accessibility_unavailable,
            ),
        )
    }

    private fun renderCanvasAccessibility() {
        val state = currentState as? CanvasUiState.Ready ?: return
        binding.drawingCanvas.contentDescription = getString(
            R.string.accessibility_canvas,
            state.pagePosition + 1,
            state.pages.size,
            (currentZoomScale * 100f).roundToInt(),
        )
    }

    private fun selectColorSlot(index: Int) {
        if (currentSettings.tool == DrawingTool.HIGHLIGHTER) {
            viewModel.selectHighlighterColor(HighlighterColor.entries[index])
        } else {
            viewModel.selectPenColor(PenColor.entries[index])
        }
        performHapticFeedback()
    }

    private fun selectThicknessSlot(index: Int) {
        if (currentSettings.tool == DrawingTool.HIGHLIGHTER) {
            viewModel.selectHighlighterThickness(HighlighterThickness.entries[index])
        } else if (currentSettings.tool == DrawingTool.TEXT) {
            viewModel.selectTextSize(TextSize.entries[index])
        } else {
            viewModel.selectPenThickness(PenThickness.entries[index])
        }
        performHapticFeedback()
    }

    private fun selectDrawingTool(tool: DrawingTool) {
        if (currentSettings.tool != tool) binding.drawingCanvas.clearSelection()
        viewModel.selectTool(tool)
        performHapticFeedback()
    }

    private fun showNewTextDialog(x: Float, y: Float) {
        showTextDialog(null) { content ->
            viewModel.addText(
                CanvasTextDraft(
                    x = x.coerceAtMost(1f - DEFAULT_TEXT_WIDTH),
                    y = y,
                    boxWidth = DEFAULT_TEXT_WIDTH,
                    content = content,
                    colorArgb = currentSettings.pen.color.argb,
                    textSizeSp = currentSettings.textSize.sizeSp,
                ),
            )
        }
    }

    private fun showEditTextDialog(text: CanvasText) {
        showTextDialog(text.content) { content ->
            if (content.isBlank()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_text_title)
                    .setMessage(R.string.delete_text_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ -> viewModel.editText(text, content) }
                    .show()
                    .applyCriticalPositiveAction()
            } else viewModel.editText(text, content)
        }
    }

    private fun showTextDialog(initialValue: String?, onConfirm: (String) -> Unit) {
        val input = TextInputEditText(requireContext()).apply {
            minLines = 3
            maxLines = 8
            setText(initialValue.orEmpty())
            setSelection(text?.length ?: 0)
        }
        val inputLayout = TextInputLayout(
            requireContext(),
            null,
            com.google.android.material.R.attr.textInputStyle,
        ).apply {
            hint = getString(R.string.text_hint)
            addView(input)
            val padding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            setPadding(padding, 0, padding, 0)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (initialValue == null) R.string.enter_text else R.string.edit_text)
            .setView(inputLayout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString().orEmpty()
                if (value.isBlank() && initialValue == null) {
                    inputLayout.error = getString(R.string.required_name_error)
                } else {
                    onConfirm(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showPageTemplateDialog() {
        val templates = arrayOf(
            getString(R.string.template_blank),
            getString(R.string.template_lined),
            getString(R.string.template_grid),
        )
        val orderedTemplates = listOf(PageTemplate.BLANK, PageTemplate.LINED, PageTemplate.GRID)
        var selectedIndex = orderedTemplates.indexOf(currentAppSettings.defaultPageTemplate)
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_page_template)
            .setSingleChoiceItems(templates, selectedIndex) { _, which -> selectedIndex = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                viewModel.createPage(orderedTemplates[selectedIndex])
                performHapticFeedback()
            }
            .show()
    }

    private fun confirmPageDeletion(page: Page) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_page_title)
            .setMessage(R.string.delete_page_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deletePage(page) }
            .show()
            .applyCriticalPositiveAction()
    }

    private fun handleEvent(event: CanvasEvent) = when (event) {
        is CanvasEvent.PendingPersisted -> Unit
        is CanvasEvent.PendingDiscarded -> binding.drawingCanvas.discardPendingStroke(event.token)
        CanvasEvent.RefreshStrokes -> {
            val state = currentState as? CanvasUiState.Ready
            val strokes = state?.strokes.orEmpty()
            val texts = state?.texts.orEmpty()
            renderedStrokes = strokes
            renderedTexts = texts
            binding.drawingCanvas.refreshVisibleStrokes(strokes)
            binding.drawingCanvas.setTexts(texts)
        }
    }

    private fun updateInputEnabled() {
        val state = currentState as? CanvasUiState.Ready
        binding.drawingCanvas.isInputEnabled = state != null && !pdfPageLoading && !state.isPageChanging &&
            !state.isExporting &&
            !(state.isBusy && currentSettings.tool in setOf(
                DrawingTool.ERASER, DrawingTool.LASSO, DrawingTool.TEXT,
            ))
    }

    private fun performHapticFeedback() {
        if (!currentAppSettings.hapticFeedbackEnabled || _binding == null) return
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun renderPdfBackground(
        page: Page,
        scale: Float,
        force: Boolean = false,
        debounce: Boolean = false,
    ) {
        val background = page.pdfBackground
        if (background == null) {
            pdfRenderGeneration += 1
            pdfRenderJob?.cancel()
            pdfRenderJob = null
            pdfRenderKey = null
            pdfPageLoading = false
            pdfDisplayedPageId = null
            binding.drawingCanvas.setPdfBackground(null)
            updateInputEnabled()
            return
        }
        val canvasWidth = binding.drawingCanvas.width
        val canvasHeight = binding.drawingCanvas.height
        val isInitialRender = pdfDisplayedPageId != page.id
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            pdfPageLoading = isInitialRender
            updateInputEnabled()
            return
        }
        val baseEdge = maxOf(canvasWidth, canvasHeight)
        val requestedEdge = ceil(
            baseEdge * scale * PDF_RENDER_OVERSAMPLE / PDF_RENDER_BUCKET.toFloat(),
        ).toInt() *
            PDF_RENDER_BUCKET
        val key = "${page.id}:${background.storageName}:${background.sourcePageIndex}:" +
            "${canvasWidth}x$canvasHeight:$requestedEdge"
        if (!force && key == pdfRenderKey) return
        pdfRenderKey = key
        val generation = ++pdfRenderGeneration
        pdfRenderJob?.cancel()
        pdfPageLoading = isInitialRender
        if (isInitialRender) binding.drawingCanvas.setPdfBackground(null)
        updateInputEnabled()
        pdfRenderJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce && !isInitialRender) delay(PDF_RENDER_DEBOUNCE_MILLIS)
            val container = (requireActivity().application as NoteUpApplication).container
            runCatching { container.pdfPageRenderStore.renderDisplay(background, requestedEdge) }
                .onSuccess { result ->
                    val ready = currentState as? CanvasUiState.Ready
                    if (ready?.page?.id == page.id && pdfRenderKey == key &&
                        pdfRenderGeneration == generation
                    ) {
                        binding.drawingCanvas.setPdfBackground(result.bitmap)
                        pdfDisplayedPageId = page.id
                        pdfPageLoading = false
                        updateInputEnabled()
                    }
                }
                .onFailure {
                    if (pdfRenderKey == key && pdfRenderGeneration == generation) {
                        pdfPageLoading = isInitialRender
                        Snackbar.make(binding.root, R.string.pdf_page_load_error, Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.retry) {
                                renderPdfBackground(page, scale, force = true)
                            }
                            .show()
                        updateInputEnabled()
                    }
                }
        }
    }

    private fun colorButtons(): List<MaterialButton> = with(binding) {
        listOf(blackColorButton, blueColorButton, redColorButton, greenColorButton)
    }

    private fun thicknessButtons(): List<MaterialButton> = with(binding) {
        listOf(thinButton, mediumButton, thickButton)
    }

    override fun onStop() {
        binding.drawingCanvas.cancelActiveStroke()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            PAGE_PANEL_OPEN_KEY,
            _binding?.pagePanel?.isVisible ?: pagePanelOpen,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        binding.root.keepScreenOn = false
        pdfRenderGeneration += 1
        pdfRenderKey = null
        pdfDisplayedPageId = null
        pdfRenderJob?.cancel()
        pdfRenderJob = null
        pagePanelOpen = binding.pagePanel.isVisible
        binding.drawingCanvas.onStrokeCompleted = null
        binding.drawingCanvas.onStrokesErased = null
        binding.drawingCanvas.onAreaErased = null
        binding.drawingCanvas.onViewportChanged = null
        binding.drawingCanvas.onCanvasSizeChanged = null
        binding.drawingCanvas.onTextRequested = null
        binding.drawingCanvas.onTextEditRequested = null
        binding.drawingCanvas.onSelectionChanged = null
        binding.drawingCanvas.onSelectionTransformed = null
        binding.drawingCanvas.onPageSwipe = null
        binding.pageList.adapter = null
        renderedStrokes = emptyList()
        renderedTexts = emptyList()
        renderedPageId = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val PDF_RENDER_BUCKET = 512
        const val PDF_RENDER_DEBOUNCE_MILLIS = 120L
        const val PDF_RENDER_OVERSAMPLE = 1.25f
        const val NOTE_ID_ARGUMENT = "noteId"
        const val INVALID_NOTE_ID = -1L
        const val DEFAULT_TEXT_WIDTH = 0.35f
        const val PASTE_OFFSET_DP = 24f
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val ZOOM_STEP = 0.25f
        const val ZOOM_EPSILON = 0.001f
        const val SHAPE_LINE_ID = 1
        const val SHAPE_RECTANGLE_ID = 2
        const val SHAPE_CIRCLE_ID = 3
        const val MORE_EXPORT_ID = 10
        const val MORE_SHORTCUTS_ID = 11
        const val PAGE_PANEL_OPEN_KEY = "canvas_page_panel_open"
        val SHAPE_TOOLS = setOf(DrawingTool.LINE, DrawingTool.RECTANGLE, DrawingTool.CIRCLE)
        val DRAWING_OPTION_TOOLS = setOf(
            DrawingTool.PEN,
            DrawingTool.HIGHLIGHTER,
            DrawingTool.LINE,
            DrawingTool.RECTANGLE,
            DrawingTool.CIRCLE,
            DrawingTool.TEXT,
        )
    }
}
