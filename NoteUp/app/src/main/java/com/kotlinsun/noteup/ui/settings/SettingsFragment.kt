package com.kotlinsun.noteup.ui.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by settingsViewModel()
    private val versionLabel by lazy(LazyThreadSafetyMode.NONE) { currentVersionLabel() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render(viewModel.uiState.value)
        setupActions()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun setupActions() = with(binding) {
        backButton.setOnClickListener { popBackStackFrom(R.id.settingsFragment) }
        displaySettingsRow.setOnClickListener {
            navigateToCategory(R.id.action_settings_to_display)
        }
        writingSettingsRow.setOnClickListener {
            navigateToCategory(R.id.action_settings_to_writing)
        }
        aiSettingsRow.setOnClickListener {
            navigateToCategory(R.id.action_settings_to_ai)
        }
        storageSettingsRow.setOnClickListener {
            navigateToCategory(R.id.action_settings_to_storage)
        }
        aboutSettingsRow.setOnClickListener {
            navigateToCategory(R.id.action_settings_to_about)
        }
    }

    private fun navigateToCategory(@IdRes actionId: Int) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.settingsFragment) {
            navController.navigate(actionId)
        }
    }

    private fun render(state: SettingsUiState) = with(binding) {
        displaySettingsSummary.setText(state.settings.themeMode.labelRes())
        writingSettingsSummary.setText(state.settings.canvasInputMode.labelRes())
        aiSettingsSummary.setText(
            if (state.aiModelDeleting) R.string.ai_model_status_deleting
            else state.aiModelState.statusLabelRes(),
        )
        storageSettingsSummary.text = getString(
            R.string.storage_usage_summary,
            Formatter.formatFileSize(requireContext(), state.storageUsage.totalBytes),
            Formatter.formatFileSize(requireContext(), state.storageUsage.cacheBytes),
            Formatter.formatFileSize(requireContext(), state.storageUsage.availableBytes),
        )
        aboutSettingsSummary.text = versionLabel

        setRowAccessibility(
            displaySettingsRow,
            R.string.settings_category_display,
            displaySettingsSummary,
        )
        setRowAccessibility(
            writingSettingsRow,
            R.string.settings_category_writing,
            writingSettingsSummary,
        )
        setRowAccessibility(
            aiSettingsRow,
            R.string.settings_category_ai,
            aiSettingsSummary,
        )
        setRowAccessibility(
            storageSettingsRow,
            R.string.settings_category_storage,
            storageSettingsSummary,
        )
        setRowAccessibility(
            aboutSettingsRow,
            R.string.settings_category_about,
            aboutSettingsSummary,
        )
    }

    private fun setRowAccessibility(
        row: View,
        titleRes: Int,
        summary: TextView,
    ) {
        row.contentDescription = getString(
            R.string.accessibility_setting_value,
            getString(titleRes),
            summary.text,
        )
        ViewCompat.setImportantForAccessibility(
            summary,
            ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
        )
    }

    @Suppress("DEPRECATION")
    private fun currentVersionLabel(): String {
        val context = requireContext()
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
        return getString(R.string.app_version, version)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
