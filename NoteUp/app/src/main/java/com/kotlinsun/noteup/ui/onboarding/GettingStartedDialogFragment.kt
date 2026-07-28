package com.kotlinsun.noteup.ui.onboarding

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotlinsun.noteup.NoteUpApplication
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.DialogGettingStartedBinding
import com.kotlinsun.noteup.domain.model.CanvasInputMode
import com.kotlinsun.noteup.domain.model.PenThickness

class GettingStartedDialogFragment : DialogFragment() {
    private var currentStep = 0
    private var inputMode = CanvasInputMode.STYLUS_ONLY
    private var thickness = PenThickness.MEDIUM
    private var restoredInputMode: CanvasInputMode? = null
    private var restoredThickness: PenThickness? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentStep = savedInstanceState?.getInt(STATE_STEP) ?: 0
        restoredInputMode = savedInstanceState?.getString(STATE_INPUT_MODE)
            ?.let { runCatching { CanvasInputMode.valueOf(it) }.getOrNull() }
        restoredThickness = savedInstanceState?.getString(STATE_THICKNESS)
            ?.let { runCatching { PenThickness.valueOf(it) }.getOrNull() }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogGettingStartedBinding.inflate(layoutInflater)
        val container = (requireActivity().application as NoteUpApplication).container
        inputMode = restoredInputMode ?: container.appSettingsStore.current().canvasInputMode
        thickness = restoredThickness ?: container.drawingToolSettingsStore.load().pen.thickness

        binding.inputModeGroup.check(
            if (inputMode == CanvasInputMode.STYLUS_ONLY) {
                R.id.stylus_only_option
            } else {
                R.id.touch_input_option
            },
        )
        binding.penThicknessGroup.check(
            when (thickness) {
                PenThickness.THIN -> R.id.thin_pen_option
                PenThickness.MEDIUM -> R.id.medium_pen_option
                PenThickness.THICK -> R.id.thick_pen_option
            },
        )
        binding.penPreview.inputMode = inputMode
        binding.penPreview.thickness = thickness
        binding.inputModeGroup.setOnCheckedChangeListener { _, checkedId ->
            inputMode = if (checkedId == R.id.touch_input_option) {
                CanvasInputMode.STYLUS_AND_TOUCH
            } else {
                CanvasInputMode.STYLUS_ONLY
            }
            binding.penPreview.inputMode = inputMode
        }
        binding.penThicknessGroup.setOnCheckedChangeListener { _, checkedId ->
            thickness = when (checkedId) {
                R.id.thin_pen_option -> PenThickness.THIN
                R.id.thick_pen_option -> PenThickness.THICK
                else -> PenThickness.MEDIUM
            }
            binding.penPreview.thickness = thickness
        }
        binding.clearPreviewButton.setOnClickListener { binding.penPreview.clear() }

        fun renderStep() {
            binding.stepFlipper.displayedChild = currentStep
            binding.stepIndicator.text = getString(
                R.string.onboarding_step,
                currentStep + 1,
                STEP_COUNT,
            )
            binding.previousButton.isVisible = currentStep > 0
            binding.nextButton.setText(
                if (currentStep == STEP_COUNT - 1) R.string.finish else R.string.next,
            )
        }
        binding.previousButton.setOnClickListener {
            if (currentStep > 0) {
                currentStep -= 1
                renderStep()
            }
        }
        binding.nextButton.setOnClickListener {
            if (currentStep < STEP_COUNT - 1) {
                currentStep += 1
                renderStep()
            } else {
                container.appSettingsStore.setCanvasInputMode(inputMode)
                val drawingSettings = container.drawingToolSettingsStore.load()
                container.drawingToolSettingsStore.save(
                    drawingSettings.copy(
                        pen = drawingSettings.pen.copy(thickness = thickness),
                    ),
                )
                container.onboardingPreferencesStore.markCompleted()
                dismiss()
            }
        }
        binding.skipButton.setOnClickListener {
            container.onboardingPreferencesStore.markCompleted()
            dismiss()
        }
        renderStep()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.getting_started_title)
            .setView(binding.root)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_STEP, currentStep)
        outState.putString(STATE_INPUT_MODE, inputMode.name)
        outState.putString(STATE_THICKNESS, thickness.name)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val TAG = "getting_started"
        private const val STATE_STEP = "step"
        private const val STATE_INPUT_MODE = "input_mode"
        private const val STATE_THICKNESS = "thickness"
        private const val STEP_COUNT = 3

        fun show(manager: androidx.fragment.app.FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) {
                GettingStartedDialogFragment().show(manager, TAG)
            }
        }
    }
}
