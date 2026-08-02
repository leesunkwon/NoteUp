package com.kotlinsun.noteup.ui.canvas

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
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
import com.kotlinsun.noteup.databinding.DialogColorPickerBinding
import com.kotlinsun.noteup.databinding.DialogCustomColorPaletteBinding
import com.kotlinsun.noteup.databinding.ItemCustomColorBinding
import com.kotlinsun.noteup.databinding.PopupToolSettingsBinding
import com.kotlinsun.noteup.domain.model.AppSettings
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasTextDraft
import com.kotlinsun.noteup.domain.model.CanvasImage
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
import com.kotlinsun.noteup.domain.model.PageVersion
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.PenThickness
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.TextSize
import com.kotlinsun.noteup.domain.model.opaqueColor
import com.kotlinsun.noteup.ui.common.applyCriticalPositiveAction
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class CanvasFragment : Fragment() {
    private var _binding: FragmentCanvasBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var renderedStrokes: List<Stroke> = emptyList()
    private var renderedTexts: List<CanvasText> = emptyList()
    private var renderedImages: List<CanvasImage> = emptyList()
    private var renderedPageId: Long? = null
    private var currentSettings = DrawingSettings()
    private var currentAppSettings = AppSettings()
    private var currentState: CanvasUiState = CanvasUiState.Loading
    private var renderedHistoryControls: HistoryControlsRenderState? = null
    private var renderedPageControls: PageControlsRenderState? = null
    private var renderedSelectionActions: SelectionActionsRenderState? = null
    private var presentedArtifactPath: String? = null
    private var savingArtifact = false
    private var pagePanelOpen = false
    private var pdfRenderJob: Job? = null
    private var pdfRenderKey: String? = null
    private var pdfPageLoading = false
    private var pdfDisplayedPageId: Long? = null
    private var pdfRenderGeneration = 0L
    private var pdfTileJob: Job? = null
    private var pdfTileGeneration = 0L
    private var pdfTileKey: String? = null
    private var currentZoomScale = MIN_ZOOM
    private var toolSettingsPopup: PopupWindow? = null
    private var toolSettingsBinding: PopupToolSettingsBinding? = null
    private var currentCustomColors: List<Int> = emptyList()
    private var imageRenderJob: Job? = null
    private var imageRenderGeneration = 0L
    private var imageRenderKey: String? = null
    private var pagePreloadJob: Job? = null
    private var pagePreloadKey: String? = null
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
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::importImage) }
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
            container.customColorPaletteStore,
            container.pageThumbnailStore,
            container.pageThumbnailService,
            container.noteExportService,
            container.pdfDocumentStore,
            container.pdfPageRenderStore,
            container.canvasImageStore,
            container.recoveryJournal,
            container.pageVersionService,
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
            positionSelectionActions()
            (currentState as? CanvasUiState.Ready)?.let {
                renderPdfBackground(it.page, viewport.scale, debounce = true)
            }
        }
        binding.drawingCanvas.onCanvasSizeChanged = { _, _ ->
            positionSelectionActions()
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
        ).forEach { it.isCheckable = true }
        configureToolbarAccessibility()
        TooltipCompat.setTooltipText(zoomOutButton, getString(R.string.zoom_out))
        TooltipCompat.setTooltipText(zoomInButton, getString(R.string.zoom_in))
        penToolButton.setOnClickListener { handleToolButtonClick(DrawingTool.PEN) }
        highlighterToolButton.setOnClickListener { handleToolButtonClick(DrawingTool.HIGHLIGHTER) }
        eraserToolButton.setOnClickListener { handleToolButtonClick(DrawingTool.ERASER) }
        lassoToolButton.setOnClickListener { selectDrawingTool(DrawingTool.LASSO) }
        shapeToolButton.setOnClickListener { handleShapeToolClick() }
        textToolButton.setOnClickListener { handleToolButtonClick(DrawingTool.TEXT) }
        toolSettingsButton.setOnClickListener { showToolSettingsPopup() }
        TooltipCompat.setTooltipText(toolSettingsButton, getString(R.string.tool_settings))
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
        toolSettingsButton.contentDescription = getString(R.string.tool_settings)
        ViewCompat.setAccessibilityLiveRegion(
            exportStatus,
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
                    viewModel.customColors.collect { colors ->
                        currentCustomColors = colors
                        toolSettingsBinding?.let { renderToolSettingsPanel(it, currentSettings) }
                    }
                }
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
            noteTitle.setTextIfChanged(state.note.title)
            exportStatus.isVisible = state.isExporting
            exportStatus.setTextIfChanged(
                if (state.isExporting) getString(
                    R.string.export_progress,
                    state.exportCompletedPages,
                    state.exportTotalPages,
                ) else null,
            )
            renderHistoryControls(state)
            renderPageControls(state)
            pageIndicator.setTextIfChanged(getString(
                R.string.page_indicator,
                state.pagePosition + 1,
                state.pages.size,
            ))
            renderCanvasAccessibility()
            pageAdapter.submitPages(state.pages, state.page.id, state.thumbnailRevisions)
            preloadAdjacentPages(state)
            renderPdfBackground(state.page, state.viewport.scale)
            renderPdfTiles(state.page, state.viewport)
            if (renderedPageId != state.page.id) {
                dismissToolSettingsPopup()
                renderedPageId = state.page.id
                renderedStrokes = state.strokes
                renderedTexts = state.texts
                renderedImages = state.images
                drawingCanvas.showPage(
                    state.page.id,
                    state.page.templateType,
                    state.strokes,
                    state.viewport,
                )
                drawingCanvas.setTexts(state.texts)
                drawingCanvas.setImages(state.images, emptyMap())
                renderCanvasImages(state.images, state.viewport.scale, force = true)
            } else {
                if (renderedStrokes != state.strokes) {
                    renderedStrokes = state.strokes
                    drawingCanvas.setStrokes(state.strokes)
                }
                if (renderedTexts != state.texts) {
                    renderedTexts = state.texts
                    drawingCanvas.setTexts(state.texts)
                }
                if (renderedImages != state.images) {
                    renderedImages = state.images
                    drawingCanvas.setImages(state.images, emptyMap())
                    renderCanvasImages(state.images, state.viewport.scale, force = true)
                } else {
                    renderCanvasImages(state.images, state.viewport.scale)
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
            noteTitle.setTextIfChanged(getString(R.string.canvas_title))
            exportStatus.isVisible = false
            exportStatus.setTextIfChanged(null)
            renderHistoryControls(canUndo = false, canRedo = false)
            renderPageControls(PageControlsRenderState())
            pageIndicator.setTextIfChanged(null)
            renderZoomControls(MIN_ZOOM, controlsEnabled = false)
        }
        renderSelectionActionsState()
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

    private fun handleToolButtonClick(tool: DrawingTool) {
        if (currentSettings.tool == tool) {
            showToolSettingsPopup()
        } else {
            dismissToolSettingsPopup()
            selectDrawingTool(tool)
        }
    }

    private fun handleShapeToolClick() {
        if (currentSettings.tool in SHAPE_TOOLS) {
            showToolSettingsPopup()
        } else {
            selectDrawingTool(DrawingTool.LINE)
            binding.shapeToolButton.post { showToolSettingsPopup() }
        }
    }

    private fun showToolSettingsPopup() {
        if (currentSettings.tool !in TOOL_SETTINGS_TOOLS) return
        if (toolSettingsPopup?.isShowing == true) {
            dismissToolSettingsPopup()
            return
        }
        val panel = PopupToolSettingsBinding.inflate(layoutInflater)
        setupToolSettingsPanel(panel)
        renderToolSettingsPanel(panel, currentSettings)
        val popupWidth = resources.getDimensionPixelSize(R.dimen.tool_popup_width)
        val popup = PopupWindow(
            panel.root,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = resources.getDimension(R.dimen.tool_popup_elevation)
            setOnDismissListener {
                toolSettingsBinding = null
                toolSettingsPopup = null
            }
        }
        toolSettingsBinding = panel
        toolSettingsPopup = popup
        val anchor = binding.toolSettingsButton
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        binding.root.getLocationInWindow(rootLocation)
        val anchorX = anchorLocation[0] - rootLocation[0]
        val rootWidth = binding.root.width
        val horizontalMargin = resources.getDimensionPixelSize(R.dimen.spacing_small)
        val desiredX = (anchorX + anchor.width / 2f - popupWidth / 2f)
            .roundToInt()
            .coerceIn(horizontalMargin, (rootWidth - popupWidth - horizontalMargin).coerceAtLeast(horizontalMargin))
        val xOffset = desiredX - anchorX
        val yOffset = resources.getDimensionPixelSize(R.dimen.tool_popup_vertical_offset)
        popup.showAsDropDown(anchor, xOffset, yOffset)
    }

    private fun setupToolSettingsPanel(panel: PopupToolSettingsBinding) = with(panel) {
        val checkableButtons = listOf(
            blackColorButton,
            blueColorButton,
            redColorButton,
            greenColorButton,
            thinButton,
            mediumButton,
            thickButton,
            strokeEraserModeButton,
            areaEraserModeButton,
            lineShapeButton,
            rectangleShapeButton,
            circleShapeButton,
        )
        checkableButtons.forEach { it.isCheckable = true }
        blackColorButton.setOnClickListener { selectColorSlot(0) }
        blueColorButton.setOnClickListener { selectColorSlot(1) }
        redColorButton.setOnClickListener { selectColorSlot(2) }
        greenColorButton.setOnClickListener { selectColorSlot(3) }
        thinButton.setOnClickListener { selectThicknessSlot(0) }
        mediumButton.setOnClickListener { selectThicknessSlot(1) }
        thickButton.setOnClickListener { selectThicknessSlot(2) }
        strokeEraserModeButton.setOnClickListener {
            viewModel.selectEraserMode(EraserMode.STROKE)
            performHapticFeedback()
        }
        areaEraserModeButton.setOnClickListener {
            viewModel.selectEraserMode(EraserMode.AREA)
            performHapticFeedback()
        }
        lineShapeButton.setOnClickListener { selectDrawingTool(DrawingTool.LINE) }
        rectangleShapeButton.setOnClickListener { selectDrawingTool(DrawingTool.RECTANGLE) }
        circleShapeButton.setOnClickListener { selectDrawingTool(DrawingTool.CIRCLE) }
        addColorButton.setOnClickListener { showColorPickerDialog() }
        manageColorsButton.setOnClickListener { showCustomColorPaletteDialog() }
        listOf(
            lineShapeButton to R.string.line_tool,
            rectangleShapeButton to R.string.rectangle_tool,
            circleShapeButton to R.string.circle_tool,
        ).forEach { (button, label) -> TooltipCompat.setTooltipText(button, getString(label)) }
    }

    private fun dismissToolSettingsPopup() {
        toolSettingsPopup?.dismiss()
        toolSettingsPopup = null
        toolSettingsBinding = null
    }

    private fun showMoreMenu() {
        val state = currentState as? CanvasUiState.Ready
        PopupMenu(requireContext(), binding.moreButton).apply {
            menu.add(0, MORE_INSERT_IMAGE_ID, 0, R.string.insert_image).isEnabled =
                state != null && !state.isBusy
            menu.add(0, MORE_EXPORT_ID, 1, R.string.export).isEnabled = state != null && !state.isBusy
            menu.add(0, MORE_SHORTCUTS_ID, 2, R.string.keyboard_shortcuts)
            menu.add(0, MORE_VERSION_HISTORY_ID, 3, R.string.version_history).isEnabled =
                state != null && !state.isBusy
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MORE_INSERT_IMAGE_ID -> {
                        imagePickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                        true
                    }
                    MORE_EXPORT_ID -> {
                        showExportFormatDialog()
                        true
                    }
                    MORE_SHORTCUTS_ID -> {
                        showKeyboardShortcutsDialog()
                        true
                    }
                    MORE_VERSION_HISTORY_ID -> {
                        showVersionHistoryDialog()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showVersionHistoryDialog() {
        val versions = viewModel.pageVersions.value
        if (versions.isEmpty()) {
            Snackbar.make(binding.root, R.string.version_history_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val labels = versions.map { version ->
            getString(
                R.string.version_history_item,
                formatter.format(Date(version.createdAt)),
                version.elementCount,
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.version_history)
            .setItems(labels.toTypedArray()) { _, index -> showVersionPreview(versions[index]) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showVersionPreview(version: PageVersion) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = viewModel.loadVersionPreview(version)
            val preview = ImageView(requireContext()).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(24, 24, 24, 24)
                setImageBitmap(bitmap)
                contentDescription = getString(R.string.version_preview)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.version_preview)
                .setView(preview)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore) { _, _ -> viewModel.restoreVersion(version.id) }
                .show()
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
        dismissToolSettingsPopup()
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
        renderToolSettingsState()
        renderSelectionActionsState()
        updateInputEnabled()
    }

    private fun renderAppSettings(settings: AppSettings) = with(binding) {
        currentAppSettings = settings
        root.keepScreenOn = settings.keepScreenOn
        drawingCanvas.isPageSwipeEnabled = settings.pageSwipeEnabled
        drawingCanvas.canvasAppearance = settings.canvasAppearance
        drawingCanvas.canvasInputMode = settings.canvasInputMode
    }

    private fun renderToolSettingsState() = with(binding) {
        val settings = currentSettings
        setToolChecked(penToolButton, settings.tool == DrawingTool.PEN)
        setToolChecked(highlighterToolButton, settings.tool == DrawingTool.HIGHLIGHTER)
        setToolChecked(eraserToolButton, settings.tool == DrawingTool.ERASER)
        setToolChecked(lassoToolButton, settings.tool == DrawingTool.LASSO)
        setToolChecked(shapeToolButton, settings.tool in SHAPE_TOOLS)
        setToolChecked(textToolButton, settings.tool == DrawingTool.TEXT)
        renderShapeToolButton(settings.tool)
        toolSettingsButton.isVisible = settings.tool in TOOL_SETTINGS_TOOLS
        renderCurrentToolStyle(settings)
        toolSettingsBinding?.let { renderToolSettingsPanel(it, settings) }
    }

    private fun renderSelectionActionsState() = with(binding) {
        val settings = currentSettings
        val state = currentState as? CanvasUiState.Ready
        val isLasso = settings.tool == DrawingTool.LASSO
        val hasSelection = state?.hasSelection == true
        val canPaste = state?.canPaste == true
        val isBusy = state?.isBusy != false
        val selection = drawingCanvas.currentSelection()
        val hasSingleTextSelection = settings.tool == DrawingTool.TEXT &&
            selection.texts.size == 1 && selection.strokes.isEmpty() && selection.images.isEmpty()
        val showSelectionActions = (isLasso && hasSelection) || hasSingleTextSelection
        val renderState = SelectionActionsRenderState(
            showCopy = showSelectionActions,
            showPaste = isLasso && canPaste,
            showDelete = showSelectionActions,
            showEdit = (isLasso || settings.tool == DrawingTool.TEXT) &&
                selection.texts.size == 1 && selection.strokes.isEmpty() && selection.images.isEmpty(),
            enabled = !isBusy,
        )
        if (renderedSelectionActions == renderState) return@with
        renderedSelectionActions = renderState
        copySelectionButton.isVisible = renderState.showCopy
        deleteSelectionButton.isVisible = renderState.showDelete
        pasteSelectionButton.isVisible = renderState.showPaste
        editTextButton.isVisible = renderState.showEdit
        listOf(copySelectionButton, pasteSelectionButton, deleteSelectionButton, editTextButton)
            .forEach { it.setEnabledIfChanged(renderState.enabled) }
        selectionActionsBar.isVisible = renderState.isVisible
        if (selectionActionsBar.isVisible) positionSelectionActions()
    }

    private fun renderHistoryControls(state: CanvasUiState.Ready) {
        val isTransientSave = state.isSaving && !state.isPageChanging && !state.isExporting
        if (!isTransientSave) renderHistoryControls(state.canUndo, state.canRedo)
    }

    private fun renderHistoryControls(canUndo: Boolean, canRedo: Boolean) = with(binding) {
        val renderState = HistoryControlsRenderState(canUndo, canRedo)
        if (renderedHistoryControls == renderState) return@with
        renderedHistoryControls = renderState
        undoButton.setEnabledIfChanged(canUndo)
        redoButton.setEnabledIfChanged(canRedo)
        setAvailabilityState(undoButton, canUndo)
        setAvailabilityState(redoButton, canRedo)
    }

    private fun renderPageControls(state: CanvasUiState.Ready) {
        val controlsLocked = state.isPageChanging || state.isExporting
        renderPageControls(
            PageControlsRenderState(
                canGoPrevious = !controlsLocked && state.pagePosition > 0,
                canGoNext = !controlsLocked && state.pagePosition < state.pages.lastIndex,
                canAddPage = !controlsLocked,
                canOpenMore = !controlsLocked,
            ),
        )
    }

    private fun renderPageControls(renderState: PageControlsRenderState) = with(binding) {
        if (renderedPageControls == renderState) return@with
        renderedPageControls = renderState
        previousPageButton.setEnabledIfChanged(renderState.canGoPrevious)
        nextPageButton.setEnabledIfChanged(renderState.canGoNext)
        addPageButton.setEnabledIfChanged(renderState.canAddPage)
        moreButton.setEnabledIfChanged(renderState.canOpenMore)
    }

    private fun renderCurrentToolStyle(settings: DrawingSettings) = with(binding) {
        val isEraser = settings.tool == DrawingTool.ERASER
        currentStylePreview.isVisible = !isEraser
        currentToolSettingIcon.isVisible = isEraser
        if (isEraser) {
            val modeLabel = getString(
                if (settings.eraserMode == EraserMode.STROKE) R.string.stroke_eraser_mode
                else R.string.area_eraser_mode,
            )
            toolSettingsButton.contentDescription = getString(
                R.string.current_eraser_settings,
                getString(R.string.eraser_settings),
                modeLabel,
            )
            return@with
        }
        if (settings.tool !in DRAWING_OPTION_TOOLS) return@with
        val isHighlighter = settings.tool == DrawingTool.HIGHLIGHTER
        val selectedArgb = if (isHighlighter) settings.highlighter.colorArgb else settings.pen.colorArgb
        currentColorSwatch.backgroundTintList = ColorStateList.valueOf(selectedArgb)
        currentThicknessPreview.backgroundTintList = ColorStateList.valueOf(selectedArgb)
        val optionIndex = when {
            isHighlighter -> settings.highlighter.thickness.ordinal
            settings.tool == DrawingTool.TEXT -> settings.textSize.ordinal
            else -> settings.pen.thickness.ordinal
        }
        currentThicknessPreview.layoutParams = currentThicknessPreview.layoutParams.apply {
            height = resources.getDimensionPixelSize(TOOL_PREVIEW_DIMENSIONS[optionIndex])
        }
        val customColor = if (isHighlighter) {
            settings.highlighter.customColorArgb
        } else {
            settings.pen.customColorArgb
        }
        val colorLabel = customColor?.let { colorHexLabel(it) } ?: getString(
            colorLabelResources(isHighlighter)[
                if (isHighlighter) settings.highlighter.color.ordinal else settings.pen.color.ordinal
            ],
        )
        val optionLabel = getString(
            if (settings.tool == DrawingTool.TEXT) TEXT_SIZE_LABELS[optionIndex]
            else THICKNESS_LABELS[optionIndex],
        )
        toolSettingsButton.contentDescription = getString(
            R.string.current_tool_settings,
            getString(toolSettingsTitleResource(settings.tool)),
            colorLabel,
            optionLabel,
        )
    }

    private fun renderToolSettingsPanel(
        panel: PopupToolSettingsBinding,
        settings: DrawingSettings,
    ) = with(panel) {
        val isEraser = settings.tool == DrawingTool.ERASER
        val isShape = settings.tool in SHAPE_TOOLS
        val showInkOptions = settings.tool in DRAWING_OPTION_TOOLS
        toolSettingsTitle.setText(toolSettingsTitleResource(settings.tool))
        shapeOptions.isVisible = isShape
        colorLabel.isVisible = showInkOptions
        colorOptions.isVisible = showInkOptions
        thicknessLabel.isVisible = showInkOptions
        thicknessOptions.isVisible = showInkOptions
        eraserOptions.isVisible = isEraser
        thicknessLabel.setText(
            if (settings.tool == DrawingTool.TEXT) R.string.text_size else R.string.thickness,
        )

        lineShapeButton.isChecked = settings.tool == DrawingTool.LINE
        rectangleShapeButton.isChecked = settings.tool == DrawingTool.RECTANGLE
        circleShapeButton.isChecked = settings.tool == DrawingTool.CIRCLE
        listOf(lineShapeButton, rectangleShapeButton, circleShapeButton).forEach {
            setSelectionState(it, it.isChecked)
        }
        strokeEraserModeButton.isChecked = settings.eraserMode == EraserMode.STROKE
        areaEraserModeButton.isChecked = settings.eraserMode == EraserMode.AREA
        setSelectionState(strokeEraserModeButton, strokeEraserModeButton.isChecked)
        setSelectionState(areaEraserModeButton, areaEraserModeButton.isChecked)
        if (showInkOptions) {
            renderColorAndThickness(panel, settings)
            renderCustomColors(panel, settings)
        } else {
            customColorScroll.isVisible = false
            manageColorsButton.isVisible = false
        }
    }

    private fun renderColorAndThickness(
        panel: PopupToolSettingsBinding,
        settings: DrawingSettings,
    ) {
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
            settings.highlighter.colorArgb
        } else settings.pen.colorArgb
        val hasCustomColor = if (settings.tool == DrawingTool.HIGHLIGHTER) {
            settings.highlighter.customColorArgb != null
        } else {
            settings.pen.customColorArgb != null
        }
        val strokeWidth = resources.getDimensionPixelSize(R.dimen.pen_selected_stroke_width)
        panelColorButtons(panel).zip(colors).forEach { (button, color) ->
            val selected = !hasCustomColor && color.first == selectedArgb
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
            button.iconSize = resources.getDimensionPixelSize(R.dimen.tool_swatch_check_size)
            setSelectionState(button, selected)
        }

        val selectedThickness = if (settings.tool == DrawingTool.HIGHLIGHTER) {
            settings.highlighter.thickness.ordinal
        } else if (settings.tool == DrawingTool.TEXT) {
            settings.textSize.ordinal
        } else settings.pen.thickness.ordinal
        panelThicknessButtons(panel).forEachIndexed { index, button ->
            if (settings.tool == DrawingTool.TEXT) {
                button.icon = null
                button.text = getString(R.string.text_size_preview)
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, TextSize.entries[index].sizeSp)
                button.contentDescription = getString(TEXT_SIZE_LABELS[index])
            } else {
                button.text = null
                button.setIconResource(THICKNESS_ICONS[index])
                button.contentDescription = getString(THICKNESS_LABELS[index])
            }
            button.isChecked = index == selectedThickness
            setSelectionState(button, button.isChecked)
        }
    }

    private fun colorLabelResources(isHighlighter: Boolean): IntArray = if (isHighlighter) {
        intArrayOf(
            R.string.highlighter_color_yellow,
            R.string.highlighter_color_green,
            R.string.highlighter_color_pink,
            R.string.highlighter_color_blue,
        )
    } else {
        intArrayOf(
            R.string.pen_color_black,
            R.string.pen_color_blue,
            R.string.pen_color_red,
            R.string.pen_color_green,
        )
    }

    private fun renderCustomColors(
        panel: PopupToolSettingsBinding,
        settings: DrawingSettings,
    ) = with(panel) {
        customColorOptions.removeAllViews()
        val selectedCustomColor = if (settings.tool == DrawingTool.HIGHLIGHTER) {
            settings.highlighter.customColorArgb
        } else {
            settings.pen.customColorArgb
        }
        currentCustomColors.forEach { color ->
            val button = layoutInflater.inflate(
                R.layout.item_custom_color_swatch,
                customColorOptions,
                false,
            ) as MaterialButton
            val selected = selectedCustomColor != null &&
                (selectedCustomColor and 0x00FFFFFF) == (color and 0x00FFFFFF)
            button.backgroundTintList = ColorStateList.valueOf(color)
            button.contentDescription = getString(R.string.custom_color, colorHexLabel(color))
            button.isCheckable = true
            button.isChecked = selected
            button.strokeWidth = if (selected) {
                resources.getDimensionPixelSize(R.dimen.pen_selected_stroke_width)
            } else {
                0
            }
            button.icon = if (selected) {
                AppCompatResources.getDrawable(requireContext(), R.drawable.ic_check)
            } else {
                null
            }
            val checkColor = if (
                ColorUtils.calculateContrast(Color.BLACK, color) >=
                ColorUtils.calculateContrast(Color.WHITE, color)
            ) Color.BLACK else Color.WHITE
            button.iconTint = ColorStateList.valueOf(checkColor)
            button.iconSize = resources.getDimensionPixelSize(R.dimen.tool_swatch_check_size)
            button.setOnClickListener {
                viewModel.selectCustomColor(color)
                performHapticFeedback()
            }
            button.setOnLongClickListener {
                confirmCustomColorDeletion(color)
                true
            }
            setSelectionState(button, selected)
            customColorOptions.addView(button)
        }
        customColorScroll.isVisible = currentCustomColors.isNotEmpty()
        manageColorsButton.isVisible = currentCustomColors.isNotEmpty()
    }

    private fun showColorPickerDialog() {
        val panel = DialogColorPickerBinding.inflate(layoutInflater)
        val initialColor = opaqueColor(
            if (currentSettings.tool == DrawingTool.HIGHLIGHTER) {
                currentSettings.highlighter.colorArgb
            } else {
                currentSettings.pen.colorArgb
            },
        )
        var updatingHex = false
        panel.colorPicker.setColor(initialColor)
        panel.currentColorPreview.backgroundTintList = ColorStateList.valueOf(initialColor)
        panel.selectedColorPreview.backgroundTintList = ColorStateList.valueOf(initialColor)
        panel.hexInput.setText(colorHex(initialColor))
        panel.colorPicker.onColorChanged = { color ->
            val normalized = opaqueColor(color)
            panel.selectedColorPreview.backgroundTintList = ColorStateList.valueOf(normalized)
            updatingHex = true
            panel.hexInput.setText(colorHex(normalized))
            panel.hexInput.setSelection(panel.hexInput.text?.length ?: 0)
            updatingHex = false
            panel.hexInputLayout.error = null
        }
        panel.hexInput.doAfterTextChanged { editable ->
            if (updatingHex) return@doAfterTextChanged
            parseColorHex(editable?.toString()).onSuccess { color ->
                panel.hexInputLayout.error = null
                panel.colorPicker.setColor(color)
                panel.selectedColorPreview.backgroundTintList = ColorStateList.valueOf(color)
            }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.color_picker_title)
            .setView(panel.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply_color, null)
            .setNeutralButton(R.string.apply_and_save_color, null)
            .create()
        dialog.setOnShowListener {
            fun apply(register: Boolean) {
                parseColorHex(panel.hexInput.text?.toString())
                    .onSuccess { color ->
                        viewModel.selectCustomColor(color)
                        if (register) viewModel.addCustomColor(color)
                        performHapticFeedback()
                        dialog.dismiss()
                    }
                    .onFailure {
                        panel.hexInputLayout.error = getString(R.string.invalid_color_hex)
                    }
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                apply(register = false)
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                apply(register = true)
            }
        }
        dialog.show()
    }

    private fun showCustomColorPaletteDialog() {
        val panel = DialogCustomColorPaletteBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.custom_color_palette_title)
            .setView(panel.root)
            .setPositiveButton(R.string.close, null)
            .create()

        fun renderColors() {
            panel.colorList.removeAllViews()
            if (viewModel.customColors.value.isEmpty()) {
                panel.colorList.addView(android.widget.TextView(requireContext()).apply {
                    setText(R.string.custom_color_palette_empty)
                    setTextAppearance(R.style.TextAppearance_NoteUp_Body)
                    setPadding(0, resources.getDimensionPixelSize(R.dimen.spacing_medium), 0, 0)
                })
                return
            }
            viewModel.customColors.value.forEach { color ->
                val row = ItemCustomColorBinding.inflate(layoutInflater, panel.colorList, false)
                row.colorSwatch.backgroundTintList = ColorStateList.valueOf(color)
                row.colorHex.text = colorHexLabel(color)
                row.deleteButton.setOnClickListener {
                    confirmCustomColorDeletion(color, ::renderColors)
                }
                panel.colorList.addView(row.root)
            }
        }
        renderColors()
        dialog.show()
    }

    private fun confirmCustomColorDeletion(color: Int, onDeleted: () -> Unit = {}) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_custom_color_title)
            .setMessage(getString(R.string.delete_custom_color_message, colorHexLabel(color)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.removeCustomColor(color)
                onDeleted()
            }
            .show()
            .applyCriticalPositiveAction()
    }

    private fun colorHex(color: Int): String = String.format(
        Locale.US,
        "%06X",
        color and 0x00FFFFFF,
    )

    private fun colorHexLabel(color: Int): String = "#${colorHex(color)}"

    private fun parseColorHex(value: String?): Result<Int> = runCatching {
        val normalized = value.orEmpty().trim().removePrefix("#")
        require(normalized.length == COLOR_HEX_LENGTH)
        opaqueColor(normalized.toLong(COLOR_HEX_RADIX).toInt())
    }

    private fun toolSettingsTitleResource(tool: DrawingTool): Int = when (tool) {
        DrawingTool.PEN -> R.string.pen_settings
        DrawingTool.HIGHLIGHTER -> R.string.highlighter_settings
        DrawingTool.ERASER -> R.string.eraser_settings
        DrawingTool.LINE, DrawingTool.RECTANGLE, DrawingTool.CIRCLE -> R.string.shape_settings
        DrawingTool.TEXT -> R.string.text_settings
        DrawingTool.LASSO -> R.string.tool_settings
    }

    private fun positionSelectionActions() = with(binding) {
        if (!selectionActionsBar.isVisible) return@with
        selectionActionsBar.doOnLayout { actions ->
            val canvasWidth = drawingCanvas.width.toFloat()
            val canvasHeight = drawingCanvas.height.toFloat()
            if (canvasWidth <= 0f || canvasHeight <= 0f) return@doOnLayout
            val margin = resources.getDimension(R.dimen.selection_toolbar_margin)
            val bounds = drawingCanvas.selectionBoundsInView()
            val targetCenterX = bounds?.centerX() ?: canvasWidth / 2f
            val preferredTop = bounds?.top?.minus(actions.height + margin) ?: margin
            val maximumX = (canvasWidth - actions.width - margin).coerceAtLeast(margin)
            val maximumY = (canvasHeight - actions.height - margin).coerceAtLeast(margin)
            actions.translationX = (targetCenterX - actions.width / 2f).coerceIn(margin, maximumX)
            actions.translationY = preferredTop.coerceIn(margin, maximumY)
        }
    }

    private fun setToolChecked(button: MaterialButton, selected: Boolean) {
        if (button.isChecked != selected) button.isChecked = selected
        setSelectionState(button, selected)
    }

    private fun setSelectionState(view: View, selected: Boolean) {
        val description = getString(
            if (selected) R.string.accessibility_selected
            else R.string.accessibility_not_selected,
        )
        if (ViewCompat.getStateDescription(view) != description) {
            ViewCompat.setStateDescription(view, description)
        }
    }

    private fun setAvailabilityState(view: View, available: Boolean) {
        val description = getString(
            if (available) R.string.accessibility_available
            else R.string.accessibility_unavailable,
        )
        if (ViewCompat.getStateDescription(view) != description) {
            ViewCompat.setStateDescription(view, description)
        }
    }

    private fun View.setEnabledIfChanged(value: Boolean) {
        if (isEnabled != value) isEnabled = value
    }

    private fun TextView.setTextIfChanged(value: CharSequence?) {
        if (!android.text.TextUtils.equals(text, value)) text = value
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
        val toolChanged = currentSettings.tool != tool
        val remainsInShapeGroup = currentSettings.tool in SHAPE_TOOLS && tool in SHAPE_TOOLS
        if (toolChanged) binding.drawingCanvas.clearSelection()
        if (toolChanged && !remainsInShapeGroup) dismissToolSettingsPopup()
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
                    colorArgb = currentSettings.pen.colorArgb,
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
            val images = state?.images.orEmpty()
            renderedStrokes = strokes
            renderedTexts = texts
            renderedImages = images
            binding.drawingCanvas.refreshVisibleStrokes(strokes)
            binding.drawingCanvas.setTexts(texts)
            binding.drawingCanvas.setImages(images, emptyMap())
            renderCanvasImages(images, state?.viewport?.scale ?: MIN_ZOOM, force = true)
        }
        CanvasEvent.SaveDelayed -> Snackbar.make(
            binding.root, R.string.save_queue_delayed, Snackbar.LENGTH_SHORT,
        ).show()
        CanvasEvent.RecoveryJournalPreserved -> Snackbar.make(
            binding.root, R.string.save_failed_recovery_preserved, Snackbar.LENGTH_LONG,
        ).show()
        CanvasEvent.VersionRestored -> Snackbar.make(
            binding.root, R.string.version_restored, Snackbar.LENGTH_SHORT,
        ).show()
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

    private fun renderCanvasImages(
        images: List<CanvasImage>,
        scale: Float,
        force: Boolean = false,
    ) {
        if (_binding == null) return
        if (images.isEmpty()) {
            imageRenderJob?.cancel()
            imageRenderJob = null
            imageRenderKey = null
            binding.drawingCanvas.setImages(emptyList(), emptyMap())
            return
        }
        val canvasEdge = maxOf(binding.drawingCanvas.width, binding.drawingCanvas.height, 1024)
        val requestedEdge = (canvasEdge * scale.coerceAtLeast(1f)).roundToInt()
            .coerceAtMost(MAX_IMAGE_RENDER_EDGE)
        val bucket = ((requestedEdge + IMAGE_RENDER_BUCKET - 1) / IMAGE_RENDER_BUCKET) *
            IMAGE_RENDER_BUCKET
        val key = images.joinToString(separator = ",", postfix = ":$bucket") {
            "${it.id}:${it.storageName}:${it.updatedAt}"
        }
        if (!force && imageRenderKey == key) return
        imageRenderKey = key
        imageRenderGeneration += 1
        val generation = imageRenderGeneration
        val pageId = renderedPageId
        imageRenderJob?.cancel()
        imageRenderJob = viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as NoteUpApplication).container.canvasImageStore
            val bitmaps = images.mapNotNull { image ->
                store.load(image.storageName, bucket)?.let { image.id to it }
            }.toMap()
            if (generation != imageRenderGeneration || renderedPageId != pageId || _binding == null) {
                return@launch
            }
            binding.drawingCanvas.setImages(images, bitmaps)
        }
    }

    private fun preloadAdjacentPages(state: CanvasUiState.Ready) {
        val adjacent = listOfNotNull(
            state.pages.getOrNull(state.pagePosition - 1),
            state.pages.getOrNull(state.pagePosition + 1),
        )
        val key = adjacent.joinToString { "${it.id}:${it.updatedAt}" }
        if (pagePreloadKey == key) return
        pagePreloadKey = key
        pagePreloadJob?.cancel()
        pagePreloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val container = (requireActivity().application as NoteUpApplication).container
            adjacent.forEach { page ->
                page.pdfBackground?.let { background ->
                    runCatching { container.pdfPageRenderStore.renderDisplay(background, PRELOAD_EDGE) }
                }
                runCatching { container.noteRepository.getImages(page.id) }
                    .getOrDefault(emptyList())
                    .forEach { image ->
                        runCatching {
                            container.canvasImageStore.load(image.storageName, PRELOAD_IMAGE_EDGE)
                        }
                    }
            }
        }
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

    private fun renderPdfTiles(page: Page, viewport: CanvasViewport) {
        val background = page.pdfBackground
        if (background == null || viewport.scale < PDF_TILE_MIN_SCALE ||
            binding.drawingCanvas.width <= 0 || binding.drawingCanvas.height <= 0
        ) {
            pdfTileJob?.cancel()
            pdfTileJob = null
            pdfTileKey = null
            binding.drawingCanvas.setPdfTiles(emptyList())
            return
        }
        val gridSize = when {
            viewport.scale >= 3.5f -> 8
            viewport.scale >= 2.25f -> 4
            else -> 2
        }
        val canvasWidth = binding.drawingCanvas.width.toFloat()
        val canvasHeight = binding.drawingCanvas.height.toFloat()
        val pageRatio = background.widthPoints.toFloat() / background.heightPoints.coerceAtLeast(1)
        val canvasRatio = canvasWidth / canvasHeight.coerceAtLeast(1f)
        val pageWidth: Float
        val pageHeight: Float
        val pageLeft: Float
        val pageTop: Float
        if (canvasRatio >= pageRatio) {
            pageHeight = canvasHeight
            pageWidth = pageHeight * pageRatio
            pageLeft = (canvasWidth - pageWidth) / 2f
            pageTop = 0f
        } else {
            pageWidth = canvasWidth
            pageHeight = pageWidth / pageRatio
            pageLeft = 0f
            pageTop = (canvasHeight - pageHeight) / 2f
        }
        val visibleLeft = ((-viewport.offsetX) / viewport.scale - pageLeft) / pageWidth
        val visibleTop = ((-viewport.offsetY) / viewport.scale - pageTop) / pageHeight
        val visibleRight = ((canvasWidth - viewport.offsetX) / viewport.scale - pageLeft) / pageWidth
        val visibleBottom = ((canvasHeight - viewport.offsetY) / viewport.scale - pageTop) / pageHeight
        val firstX = (floor(visibleLeft.coerceIn(0f, 1f) * gridSize).toInt() - 1)
            .coerceIn(0, gridSize - 1)
        val lastX = (floor(visibleRight.coerceIn(0f, 0.9999f) * gridSize).toInt() + 1)
            .coerceIn(0, gridSize - 1)
        val firstY = (floor(visibleTop.coerceIn(0f, 1f) * gridSize).toInt() - 1)
            .coerceIn(0, gridSize - 1)
        val lastY = (floor(visibleBottom.coerceIn(0f, 0.9999f) * gridSize).toInt() + 1)
            .coerceIn(0, gridSize - 1)
        val coordinates = buildList {
            for (y in firstY..lastY) for (x in firstX..lastX) add(x to y)
        }
        val key = "${page.id}:$gridSize:${coordinates.joinToString()}"
        if (pdfTileKey == key) return
        pdfTileKey = key
        pdfTileGeneration += 1
        val generation = pdfTileGeneration
        pdfTileJob?.cancel()
        pdfTileJob = viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as NoteUpApplication).container.pdfPageRenderStore
            val tiles = coordinates.mapNotNull { (x, y) ->
                runCatching { store.renderDisplayTile(background, x, y, gridSize) }.getOrNull()
            }
            if (generation == pdfTileGeneration && renderedPageId == page.id && _binding != null) {
                binding.drawingCanvas.setPdfTiles(tiles)
            }
        }
    }

    private fun panelColorButtons(panel: PopupToolSettingsBinding): List<MaterialButton> = with(panel) {
        listOf(blackColorButton, blueColorButton, redColorButton, greenColorButton)
    }

    private fun panelThicknessButtons(panel: PopupToolSettingsBinding): List<MaterialButton> = with(panel) {
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
        dismissToolSettingsPopup()
        binding.root.keepScreenOn = false
        pdfRenderGeneration += 1
        pdfRenderKey = null
        pdfDisplayedPageId = null
        pdfRenderJob?.cancel()
        pdfRenderJob = null
        pdfTileGeneration += 1
        pdfTileKey = null
        pdfTileJob?.cancel()
        pdfTileJob = null
        imageRenderGeneration += 1
        imageRenderKey = null
        imageRenderJob?.cancel()
        imageRenderJob = null
        pagePreloadKey = null
        pagePreloadJob?.cancel()
        pagePreloadJob = null
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
        renderedImages = emptyList()
        renderedPageId = null
        renderedHistoryControls = null
        renderedPageControls = null
        renderedSelectionActions = null
        _binding = null
        super.onDestroyView()
    }

    private data class HistoryControlsRenderState(
        val canUndo: Boolean,
        val canRedo: Boolean,
    )

    private data class PageControlsRenderState(
        val canGoPrevious: Boolean = false,
        val canGoNext: Boolean = false,
        val canAddPage: Boolean = false,
        val canOpenMore: Boolean = false,
    )

    private data class SelectionActionsRenderState(
        val showCopy: Boolean,
        val showPaste: Boolean,
        val showDelete: Boolean,
        val showEdit: Boolean,
        val enabled: Boolean,
    ) {
        val isVisible: Boolean
            get() = showCopy || showPaste || showDelete || showEdit
    }

    private companion object {
        const val PDF_RENDER_BUCKET = 512
        const val PDF_RENDER_DEBOUNCE_MILLIS = 120L
        const val PDF_RENDER_OVERSAMPLE = 1.25f
        const val PDF_TILE_MIN_SCALE = 1.5f
        const val NOTE_ID_ARGUMENT = "noteId"
        const val INVALID_NOTE_ID = -1L
        const val DEFAULT_TEXT_WIDTH = 0.35f
        const val PASTE_OFFSET_DP = 24f
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val ZOOM_STEP = 0.25f
        const val ZOOM_EPSILON = 0.001f
        const val COLOR_HEX_LENGTH = 6
        const val COLOR_HEX_RADIX = 16
        const val MORE_EXPORT_ID = 10
        const val MORE_SHORTCUTS_ID = 11
        const val MORE_INSERT_IMAGE_ID = 12
        const val MORE_VERSION_HISTORY_ID = 13
        const val IMAGE_RENDER_BUCKET = 512
        const val MAX_IMAGE_RENDER_EDGE = 4096
        const val PRELOAD_EDGE = 1024
        const val PRELOAD_IMAGE_EDGE = 512
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
        val TOOL_SETTINGS_TOOLS = DRAWING_OPTION_TOOLS + DrawingTool.ERASER
        val TOOL_PREVIEW_DIMENSIONS = intArrayOf(
            R.dimen.tool_preview_thin,
            R.dimen.tool_preview_medium,
            R.dimen.tool_preview_thick,
        )
        val THICKNESS_ICONS = intArrayOf(
            R.drawable.ic_thickness_thin,
            R.drawable.ic_thickness_medium,
            R.drawable.ic_thickness_thick,
        )
        val THICKNESS_LABELS = intArrayOf(
            R.string.pen_thin,
            R.string.pen_medium,
            R.string.pen_thick,
        )
        val TEXT_SIZE_LABELS = intArrayOf(
            R.string.text_small,
            R.string.text_medium,
            R.string.text_large,
        )
    }
}
