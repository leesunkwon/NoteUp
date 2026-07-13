package com.kotlinsun.noteup.ui.canvas

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.kotlinsun.noteup.domain.model.DrawingSettings
import com.kotlinsun.noteup.domain.model.DrawingTool
import com.kotlinsun.noteup.domain.model.EraserMode
import com.kotlinsun.noteup.domain.model.HighlighterColor
import com.kotlinsun.noteup.domain.model.HighlighterThickness
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.PenThickness
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasTextDraft
import com.kotlinsun.noteup.domain.model.TextSize
import kotlinx.coroutines.launch

class CanvasFragment : Fragment() {
    private var _binding: FragmentCanvasBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var renderedStrokes: List<Stroke> = emptyList()
    private var renderedTexts: List<CanvasText> = emptyList()
    private var renderedPageId: Long? = null
    private var currentSettings = DrawingSettings()
    private var currentState: CanvasUiState = CanvasUiState.Loading
    private val pageAdapter by lazy {
        val store = (requireActivity().application as NoteUpApplication).container.pageThumbnailStore
        PageThumbnailAdapter(store, viewModel::selectPage, ::confirmPageDeletion, viewModel::reorderPages)
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
        binding.backButton.setOnClickListener { findNavController().popBackStack() }
        binding.drawingCanvas.onStrokeCompleted = viewModel::addStroke
        binding.drawingCanvas.onStrokesErased = viewModel::eraseStrokes
        binding.drawingCanvas.onAreaErased = viewModel::eraseArea
        binding.drawingCanvas.onViewportChanged = viewModel::updateViewport
        binding.drawingCanvas.onTextRequested = ::showNewTextDialog
        binding.drawingCanvas.onSelectionChanged = viewModel::updateSelection
        binding.drawingCanvas.onSelectionTransformed = viewModel::transformSelection
        setupToolbar()
        setupPagePanel()
        observeState()
    }

    private fun setupToolbar() = with(binding) {
        listOf(
            penToolButton, highlighterToolButton, eraserToolButton, lassoToolButton,
            lineToolButton, rectangleToolButton, circleToolButton, textToolButton,
            thinButton, mediumButton, thickButton,
            strokeEraserModeButton, areaEraserModeButton,
        ).forEach { it.isCheckable = true }
        penToolButton.setOnClickListener { selectDrawingTool(DrawingTool.PEN) }
        highlighterToolButton.setOnClickListener { selectDrawingTool(DrawingTool.HIGHLIGHTER) }
        eraserToolButton.setOnClickListener { selectDrawingTool(DrawingTool.ERASER) }
        lassoToolButton.setOnClickListener { viewModel.selectTool(DrawingTool.LASSO) }
        lineToolButton.setOnClickListener { selectDrawingTool(DrawingTool.LINE) }
        rectangleToolButton.setOnClickListener { selectDrawingTool(DrawingTool.RECTANGLE) }
        circleToolButton.setOnClickListener { selectDrawingTool(DrawingTool.CIRCLE) }
        textToolButton.setOnClickListener { selectDrawingTool(DrawingTool.TEXT) }
        copySelectionButton.setOnClickListener { viewModel.copySelection() }
        pasteSelectionButton.setOnClickListener {
            val offset = PASTE_OFFSET_DP * resources.displayMetrics.density
            viewModel.pasteSelection(
                offset / drawingCanvas.width.coerceAtLeast(1),
                offset / drawingCanvas.height.coerceAtLeast(1),
            )
        }
        deleteSelectionButton.setOnClickListener {
            viewModel.deleteSelection()
            drawingCanvas.clearSelection()
        }
        editTextButton.setOnClickListener {
            drawingCanvas.currentSelection().texts.singleOrNull()?.let(::showEditTextDialog)
        }
        strokeEraserModeButton.setOnClickListener { viewModel.selectEraserMode(EraserMode.STROKE) }
        areaEraserModeButton.setOnClickListener { viewModel.selectEraserMode(EraserMode.AREA) }
        blackColorButton.setOnClickListener { selectColorSlot(0) }
        blueColorButton.setOnClickListener { selectColorSlot(1) }
        redColorButton.setOnClickListener { selectColorSlot(2) }
        greenColorButton.setOnClickListener { selectColorSlot(3) }
        thinButton.setOnClickListener { selectThicknessSlot(0) }
        mediumButton.setOnClickListener { selectThicknessSlot(1) }
        thickButton.setOnClickListener { selectThicknessSlot(2) }
        undoButton.setOnClickListener { viewModel.undo() }
        redoButton.setOnClickListener { viewModel.redo() }
        previousPageButton.setOnClickListener { viewModel.selectPreviousPage() }
        nextPageButton.setOnClickListener { viewModel.selectNextPage() }
        addPageButton.setOnClickListener { showPageTemplateDialog() }
        pageListButton.setOnClickListener { pagePanel.isVisible = !pagePanel.isVisible }
        closePagePanelButton.setOnClickListener { pagePanel.isVisible = false }
    }

    private fun setupPagePanel() = with(binding) {
        pageList.layoutManager = LinearLayoutManager(requireContext())
        pageList.adapter = pageAdapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
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

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.settings.collect(::renderSettings) }
                launch {
                    viewModel.errors.collect {
                        Snackbar.make(binding.root, R.string.stroke_operation_error, Snackbar.LENGTH_SHORT).show()
                    }
                }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun render(state: CanvasUiState) = with(binding) {
        currentState = state
        loadingIndicator.isVisible = state == CanvasUiState.Loading
        notFoundState.isVisible = state == CanvasUiState.NotFound
        if (state is CanvasUiState.Ready) {
            noteTitle.text = state.note.title
            saveStatus.text = getString(if (state.isSaving) R.string.saving else R.string.saved)
            undoButton.isEnabled = state.canUndo
            redoButton.isEnabled = state.canRedo
            previousPageButton.isEnabled = !state.isBusy && state.pagePosition > 0
            nextPageButton.isEnabled = !state.isBusy && state.pagePosition < state.pages.lastIndex
            addPageButton.isEnabled = !state.isBusy
            pageIndicator.text = getString(
                R.string.page_indicator,
                state.pagePosition + 1,
                state.pages.size,
            )
            pageAdapter.submitPages(state.pages, state.page.id, state.thumbnailRevisions)
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
            copySelectionButton.isVisible = state.hasSelection
            deleteSelectionButton.isVisible = state.hasSelection
            pasteSelectionButton.isVisible = state.canPaste
            copySelectionButton.isEnabled = !state.isBusy
            pasteSelectionButton.isEnabled = !state.isBusy
            deleteSelectionButton.isEnabled = !state.isBusy
            editTextButton.isEnabled = !state.isBusy
            if (drawingCanvas.currentSelection() != state.selection) {
                drawingCanvas.syncSelection(state.selection)
            }
            editTextButton.isVisible = drawingCanvas.currentSelection().texts.size == 1 &&
                drawingCanvas.currentSelection().strokes.isEmpty()
        } else {
            noteTitle.text = getString(R.string.canvas_title)
            saveStatus.text = null
            undoButton.isEnabled = false
            redoButton.isEnabled = false
            previousPageButton.isEnabled = false
            nextPageButton.isEnabled = false
            addPageButton.isEnabled = false
            pageIndicator.text = null
        }
        updateInputEnabled()
    }

    private fun renderSettings(settings: DrawingSettings) = with(binding) {
        currentSettings = settings
        drawingCanvas.drawingSettings = settings
        penToolButton.isChecked = settings.tool == DrawingTool.PEN
        highlighterToolButton.isChecked = settings.tool == DrawingTool.HIGHLIGHTER
        eraserToolButton.isChecked = settings.tool == DrawingTool.ERASER
        lassoToolButton.isChecked = settings.tool == DrawingTool.LASSO
        lineToolButton.isChecked = settings.tool == DrawingTool.LINE
        rectangleToolButton.isChecked = settings.tool == DrawingTool.RECTANGLE
        circleToolButton.isChecked = settings.tool == DrawingTool.CIRCLE
        textToolButton.isChecked = settings.tool == DrawingTool.TEXT
        penToolButton.alpha = selectionAlpha(penToolButton.isChecked)
        highlighterToolButton.alpha = selectionAlpha(highlighterToolButton.isChecked)
        eraserToolButton.alpha = selectionAlpha(eraserToolButton.isChecked)
        listOf(lassoToolButton, lineToolButton, rectangleToolButton, circleToolButton, textToolButton)
            .forEach { it.alpha = selectionAlpha(it.isChecked) }

        val showSettings = settings.tool !in setOf(DrawingTool.ERASER, DrawingTool.LASSO)
        colorButtons().forEach { it.isVisible = showSettings }
        thicknessButtons().forEach { it.isVisible = showSettings }
        strokeEraserModeButton.isVisible = settings.tool == DrawingTool.ERASER
        areaEraserModeButton.isVisible = settings.tool == DrawingTool.ERASER
        strokeEraserModeButton.isChecked = settings.eraserMode == EraserMode.STROKE
        areaEraserModeButton.isChecked = settings.eraserMode == EraserMode.AREA
        strokeEraserModeButton.alpha = selectionAlpha(strokeEraserModeButton.isChecked)
        areaEraserModeButton.alpha = selectionAlpha(areaEraserModeButton.isChecked)
        if (showSettings) renderColorAndThickness(settings)
        updateInputEnabled()
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
            button.alpha = selectionAlpha(selected)
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
            button.alpha = selectionAlpha(button.isChecked)
        }
    }

    private fun selectColorSlot(index: Int) {
        if (currentSettings.tool == DrawingTool.HIGHLIGHTER) {
            viewModel.selectHighlighterColor(HighlighterColor.entries[index])
        } else {
            viewModel.selectPenColor(PenColor.entries[index])
        }
    }

    private fun selectThicknessSlot(index: Int) {
        if (currentSettings.tool == DrawingTool.HIGHLIGHTER) {
            viewModel.selectHighlighterThickness(HighlighterThickness.entries[index])
        } else if (currentSettings.tool == DrawingTool.TEXT) {
            viewModel.selectTextSize(TextSize.entries[index])
        } else {
            viewModel.selectPenThickness(PenThickness.entries[index])
        }
    }

    private fun selectDrawingTool(tool: DrawingTool) {
        binding.drawingCanvas.clearSelection()
        viewModel.selectTool(tool)
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
        val inputLayout = TextInputLayout(requireContext()).apply {
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
        var selectedIndex = 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_page_template)
            .setSingleChoiceItems(templates, selectedIndex) { _, which -> selectedIndex = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val template = when (selectedIndex) {
                    1 -> PageTemplate.LINED
                    2 -> PageTemplate.GRID
                    else -> PageTemplate.BLANK
                }
                viewModel.createPage(template)
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
    }

    private fun handleEvent(event: CanvasEvent) = when (event) {
        is CanvasEvent.PendingPersisted -> Unit
        is CanvasEvent.PendingDiscarded -> binding.drawingCanvas.discardPendingStroke(event.token)
        CanvasEvent.RefreshStrokes -> {
            val strokes = (currentState as? CanvasUiState.Ready)?.strokes.orEmpty()
            renderedStrokes = strokes
            binding.drawingCanvas.refreshVisibleStrokes(strokes)
        }
    }

    private fun updateInputEnabled() {
        val state = currentState as? CanvasUiState.Ready
        binding.drawingCanvas.isInputEnabled = state != null && !state.isPageChanging &&
            !(state.isBusy && currentSettings.tool in setOf(
                DrawingTool.ERASER, DrawingTool.LASSO, DrawingTool.TEXT,
            ))
    }

    private fun colorButtons(): List<MaterialButton> = with(binding) {
        listOf(blackColorButton, blueColorButton, redColorButton, greenColorButton)
    }

    private fun thicknessButtons(): List<MaterialButton> = with(binding) {
        listOf(thinButton, mediumButton, thickButton)
    }

    private fun selectionAlpha(selected: Boolean) = if (selected) 1f else 0.55f

    override fun onStop() {
        binding.drawingCanvas.cancelActiveStroke()
        super.onStop()
    }

    override fun onDestroyView() {
        binding.drawingCanvas.onStrokeCompleted = null
        binding.drawingCanvas.onStrokesErased = null
        binding.drawingCanvas.onAreaErased = null
        binding.drawingCanvas.onViewportChanged = null
        binding.drawingCanvas.onTextRequested = null
        binding.drawingCanvas.onSelectionChanged = null
        binding.drawingCanvas.onSelectionTransformed = null
        binding.pageList.adapter = null
        renderedStrokes = emptyList()
        renderedTexts = emptyList()
        renderedPageId = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val NOTE_ID_ARGUMENT = "noteId"
        const val INVALID_NOTE_ID = -1L
        const val DEFAULT_TEXT_WIDTH = 0.35f
        const val PASTE_OFFSET_DP = 24f
    }
}
