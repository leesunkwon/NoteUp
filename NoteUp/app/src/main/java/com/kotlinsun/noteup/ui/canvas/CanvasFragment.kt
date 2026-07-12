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
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
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
import com.kotlinsun.noteup.domain.model.Stroke
import kotlinx.coroutines.launch

class CanvasFragment : Fragment() {
    private var _binding: FragmentCanvasBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var renderedStrokes: List<Stroke> = emptyList()
    private var currentSettings = DrawingSettings()
    private var currentState: CanvasUiState = CanvasUiState.Loading

    private val noteId: Long by lazy {
        requireArguments().getLong(NOTE_ID_ARGUMENT, INVALID_NOTE_ID)
    }
    private val viewModel: CanvasViewModel by viewModels {
        val container = (requireActivity().application as NoteUpApplication).container
        CanvasViewModel.Factory(noteId, container.noteRepository, container.drawingToolSettingsStore)
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
        setupToolbar()
        observeState()
    }

    private fun setupToolbar() = with(binding) {
        listOf(
            penToolButton, highlighterToolButton, eraserToolButton,
            thinButton, mediumButton, thickButton,
            strokeEraserModeButton, areaEraserModeButton,
        ).forEach { it.isCheckable = true }
        penToolButton.setOnClickListener { viewModel.selectTool(DrawingTool.PEN) }
        highlighterToolButton.setOnClickListener { viewModel.selectTool(DrawingTool.HIGHLIGHTER) }
        eraserToolButton.setOnClickListener { viewModel.selectTool(DrawingTool.ERASER) }
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
            if (renderedStrokes != state.strokes) {
                renderedStrokes = state.strokes
                drawingCanvas.setStrokes(state.strokes)
            }
        } else {
            noteTitle.text = getString(R.string.canvas_title)
            saveStatus.text = null
            undoButton.isEnabled = false
            redoButton.isEnabled = false
        }
        updateInputEnabled()
    }

    private fun renderSettings(settings: DrawingSettings) = with(binding) {
        currentSettings = settings
        drawingCanvas.drawingSettings = settings
        penToolButton.isChecked = settings.tool == DrawingTool.PEN
        highlighterToolButton.isChecked = settings.tool == DrawingTool.HIGHLIGHTER
        eraserToolButton.isChecked = settings.tool == DrawingTool.ERASER
        penToolButton.alpha = selectionAlpha(penToolButton.isChecked)
        highlighterToolButton.alpha = selectionAlpha(highlighterToolButton.isChecked)
        eraserToolButton.alpha = selectionAlpha(eraserToolButton.isChecked)

        val showSettings = settings.tool != DrawingTool.ERASER
        colorButtons().forEach { it.isVisible = showSettings }
        thicknessButtons().forEach { it.isVisible = showSettings }
        strokeEraserModeButton.isVisible = !showSettings
        areaEraserModeButton.isVisible = !showSettings
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
        } else settings.pen.thickness.ordinal
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
        } else {
            viewModel.selectPenThickness(PenThickness.entries[index])
        }
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
        binding.drawingCanvas.isInputEnabled = state != null &&
            !(state.isBusy && currentSettings.tool == DrawingTool.ERASER)
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
        renderedStrokes = emptyList()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val NOTE_ID_ARGUMENT = "noteId"
        const val INVALID_NOTE_ID = -1L
    }
}
