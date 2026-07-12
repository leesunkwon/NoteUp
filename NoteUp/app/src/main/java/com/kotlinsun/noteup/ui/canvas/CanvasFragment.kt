package com.kotlinsun.noteup.ui.canvas

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
import com.google.android.material.snackbar.Snackbar
import com.kotlinsun.noteup.NoteUpApplication
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentCanvasBinding
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.PenSettings
import com.kotlinsun.noteup.domain.model.PenThickness
import kotlinx.coroutines.launch

class CanvasFragment : Fragment() {

    private var _binding: FragmentCanvasBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var renderedStrokes: List<Stroke> = emptyList()

    private val noteId: Long by lazy {
        requireArguments().getLong(NOTE_ID_ARGUMENT, INVALID_NOTE_ID)
    }
    private val viewModel: CanvasViewModel by viewModels {
        val application = requireActivity().application as NoteUpApplication
        CanvasViewModel.Factory(
            noteId,
            application.container.noteRepository,
            application.container.penSettingsStore,
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
        binding.drawingCanvas.onStrokeCompleted = viewModel::saveStroke
        setupPenToolbar()
        observeState()
    }

    private fun setupPenToolbar() = with(binding) {
        penToolButton.isCheckable = true
        thinButton.isCheckable = true
        mediumButton.isCheckable = true
        thickButton.isCheckable = true
        penToolButton.isChecked = true
        penToolButton.setOnClickListener { penToolButton.isChecked = true }
        blackColorButton.setOnClickListener { viewModel.selectColor(PenColor.BLACK) }
        blueColorButton.setOnClickListener { viewModel.selectColor(PenColor.BLUE) }
        redColorButton.setOnClickListener { viewModel.selectColor(PenColor.RED) }
        greenColorButton.setOnClickListener { viewModel.selectColor(PenColor.GREEN) }
        thinButton.setOnClickListener { viewModel.selectThickness(PenThickness.THIN) }
        mediumButton.setOnClickListener { viewModel.selectThickness(PenThickness.MEDIUM) }
        thickButton.setOnClickListener { viewModel.selectThickness(PenThickness.THICK) }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.penSettings.collect(::renderPenSettings) }
                launch {
                    viewModel.errors.collect {
                        Snackbar.make(
                            binding.root,
                            R.string.stroke_save_error,
                            Snackbar.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private fun renderPenSettings(settings: PenSettings) = with(binding) {
        drawingCanvas.penSettings = settings
        val selectedStrokeWidth = resources.getDimensionPixelSize(
            R.dimen.pen_selected_stroke_width,
        )
        listOf(
            blackColorButton to PenColor.BLACK,
            blueColorButton to PenColor.BLUE,
            redColorButton to PenColor.RED,
            greenColorButton to PenColor.GREEN,
        ).forEach { (button, color) ->
            val isSelected = settings.color == color
            button.strokeWidth = if (isSelected) selectedStrokeWidth else 0
            button.alpha = if (isSelected) SELECTED_ALPHA else UNSELECTED_ALPHA
            button.isActivated = isSelected
        }

        thinButton.isChecked = settings.thickness == PenThickness.THIN
        mediumButton.isChecked = settings.thickness == PenThickness.MEDIUM
        thickButton.isChecked = settings.thickness == PenThickness.THICK
        thinButton.alpha = selectionAlpha(thinButton.isChecked)
        mediumButton.alpha = selectionAlpha(mediumButton.isChecked)
        thickButton.alpha = selectionAlpha(thickButton.isChecked)
    }

    private fun selectionAlpha(isSelected: Boolean) =
        if (isSelected) SELECTED_ALPHA else UNSELECTED_ALPHA

    private fun render(state: CanvasUiState) = with(binding) {
        loadingIndicator.isVisible = state == CanvasUiState.Loading
        notFoundState.isVisible = state == CanvasUiState.NotFound
        drawingCanvas.isInputEnabled = state is CanvasUiState.Ready

        if (state is CanvasUiState.Ready) {
            noteTitle.text = state.note.title
            saveStatus.text = getString(if (state.isSaving) R.string.saving else R.string.saved)
            if (renderedStrokes != state.strokes) {
                renderedStrokes = state.strokes
                drawingCanvas.setStrokes(state.strokes)
            }
        } else {
            noteTitle.text = getString(R.string.canvas_title)
            saveStatus.text = null
        }
    }

    override fun onStop() {
        binding.drawingCanvas.cancelActiveStroke()
        super.onStop()
    }

    override fun onDestroyView() {
        binding.drawingCanvas.onStrokeCompleted = null
        renderedStrokes = emptyList()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val NOTE_ID_ARGUMENT = "noteId"
        const val INVALID_NOTE_ID = -1L
        const val SELECTED_ALPHA = 1f
        const val UNSELECTED_ALPHA = 0.55f
    }
}
