package com.kotlinsun.noteup.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentSettingsDisplayBinding
import com.kotlinsun.noteup.domain.model.CanvasAppearance
import com.kotlinsun.noteup.domain.model.ThemeMode
import com.kotlinsun.noteup.nightMode
import kotlinx.coroutines.launch

class DisplaySettingsFragment : Fragment() {

    private var _binding: FragmentSettingsDisplayBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by settingsViewModel()
    private var currentState = SettingsUiState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsDisplayBinding.inflate(inflater, container, false)
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
        backButton.setOnClickListener { popBackStackFrom(R.id.displaySettingsFragment) }
        themeModeRow.setOnClickListener { showThemeModeDialog() }
        canvasAppearanceRow.setOnClickListener { showCanvasAppearanceDialog() }
    }

    private fun render(state: SettingsUiState) = with(binding) {
        currentState = state
        themeModeSummary.setText(state.settings.themeMode.labelRes())
        canvasAppearanceSummary.setText(state.settings.canvasAppearance.labelRes())
        themeModeRow.contentDescription = getString(
            R.string.accessibility_setting_value,
            getString(R.string.theme_mode_title),
            themeModeSummary.text,
        )
        canvasAppearanceRow.contentDescription = getString(
            R.string.accessibility_setting_value,
            getString(R.string.canvas_appearance_title),
            canvasAppearanceSummary.text,
        )
        listOf(themeModeSummary, canvasAppearanceSummary).forEach { summary ->
            ViewCompat.setImportantForAccessibility(
                summary,
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
            )
        }
    }

    private fun showThemeModeDialog() {
        val values = ThemeMode.entries
        showSingleChoiceDialog(
            R.string.theme_mode_title,
            values.map { getString(it.labelRes()) },
            values.indexOf(currentState.settings.themeMode),
        ) { index ->
            val mode = values[index]
            viewModel.setThemeMode(mode)
            AppCompatDelegate.setDefaultNightMode(mode.nightMode())
        }
    }

    private fun showCanvasAppearanceDialog() {
        val values = CanvasAppearance.entries
        showSingleChoiceDialog(
            R.string.canvas_appearance_title,
            values.map { getString(it.labelRes()) },
            values.indexOf(currentState.settings.canvasAppearance),
        ) { viewModel.setCanvasAppearance(values[it]) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
