package com.kotlinsun.noteup.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentSettingsWritingBinding
import com.kotlinsun.noteup.domain.model.CanvasInputMode
import com.kotlinsun.noteup.domain.model.PageTemplate
import kotlinx.coroutines.launch

class WritingSettingsFragment : Fragment() {

    private var _binding: FragmentSettingsWritingBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by settingsViewModel()
    private var currentState = SettingsUiState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsWritingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentState = viewModel.uiState.value
        render(currentState)
        setupActions()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun setupActions() = with(binding) {
        backButton.setOnClickListener { popBackStackFrom(R.id.writingSettingsFragment) }
        defaultTemplateRow.setOnClickListener { showDefaultTemplateDialog() }
        canvasInputModeRow.setOnClickListener { showCanvasInputModeDialog() }
        behaviorSection.pageSwipeRow.setOnClickListener {
            behaviorSection.pageSwipeSwitch.toggle()
        }
        behaviorSection.keepScreenOnRow.setOnClickListener {
            behaviorSection.keepScreenOnSwitch.toggle()
        }
        behaviorSection.hapticFeedbackRow.setOnClickListener {
            behaviorSection.hapticFeedbackSwitch.toggle()
        }
        behaviorSection.pageSwipeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != currentState.settings.pageSwipeEnabled) {
                viewModel.setPageSwipeEnabled(checked)
            }
        }
        behaviorSection.keepScreenOnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != currentState.settings.keepScreenOn) {
                viewModel.setKeepScreenOn(checked)
            }
        }
        behaviorSection.hapticFeedbackSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != currentState.settings.hapticFeedbackEnabled) {
                viewModel.setHapticFeedbackEnabled(checked)
            }
        }
    }

    private fun render(state: SettingsUiState) = with(binding) {
        currentState = state
        defaultTemplateSummary.setText(state.settings.defaultPageTemplate.labelRes())
        canvasInputModeSummary.setText(state.settings.canvasInputMode.labelRes())
        behaviorSection.pageSwipeSwitch.isChecked = state.settings.pageSwipeEnabled
        behaviorSection.keepScreenOnSwitch.isChecked = state.settings.keepScreenOn
        behaviorSection.hapticFeedbackSwitch.isChecked = state.settings.hapticFeedbackEnabled
        defaultTemplateRow.contentDescription = getString(
            R.string.accessibility_setting_value,
            getString(R.string.default_template_title),
            defaultTemplateSummary.text,
        )
        canvasInputModeRow.contentDescription = getString(
            R.string.accessibility_setting_value,
            getString(R.string.canvas_input_mode_title),
            canvasInputModeSummary.text,
        )
        listOf(defaultTemplateSummary, canvasInputModeSummary).forEach { summary ->
            ViewCompat.setImportantForAccessibility(
                summary,
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
            )
        }
    }

    private fun showDefaultTemplateDialog() {
        val values = listOf(PageTemplate.BLANK, PageTemplate.LINED, PageTemplate.GRID)
        showSingleChoiceDialog(
            R.string.default_template_title,
            values.map { getString(it.labelRes()) },
            values.indexOf(currentState.settings.defaultPageTemplate),
        ) { viewModel.setDefaultPageTemplate(values[it]) }
    }

    private fun showCanvasInputModeDialog() {
        val values = CanvasInputMode.entries
        showSingleChoiceDialog(
            R.string.canvas_input_mode_title,
            values.map { getString(it.labelRes()) },
            values.indexOf(currentState.settings.canvasInputMode),
        ) { viewModel.setCanvasInputMode(values[it]) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
