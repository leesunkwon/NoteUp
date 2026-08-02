package com.kotlinsun.noteup.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.kotlinsun.noteup.NoteUpApplication
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.data.preferences.TrashRetention
import com.kotlinsun.noteup.data.preferences.VersionHistoryStore
import com.kotlinsun.noteup.databinding.FragmentSettingsBinding
import com.kotlinsun.noteup.domain.model.CanvasAppearance
import com.kotlinsun.noteup.domain.model.CanvasInputMode
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.ThemeMode
import com.kotlinsun.noteup.nightMode
import com.kotlinsun.noteup.ui.common.applyCriticalPositiveAction
import com.kotlinsun.noteup.ui.onboarding.GettingStartedDialogFragment
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by viewModels {
        val container = (requireActivity().application as NoteUpApplication).container
        SettingsViewModel.Factory(
            container.appSettingsStore,
            container.customColorPaletteStore,
            container.trashRetentionStore,
            container.trashCleanupService,
            container.versionHistoryStore,
            container.storageUsageService,
        )
    }
    private var currentState = SettingsUiState()

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
        setupActions()
        renderVersion()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun setupActions() = with(binding) {
        backButton.setOnClickListener { findNavController().popBackStack() }
        themeModeRow.setOnClickListener { showThemeModeDialog() }
        canvasAppearanceRow.setOnClickListener { showCanvasAppearanceDialog() }
        defaultTemplateRow.setOnClickListener { showDefaultTemplateDialog() }
        canvasInputModeRow.setOnClickListener { showCanvasInputModeDialog() }
        trashRetentionCard.setOnClickListener { showRetentionDialog() }
        versionHistoryRow.setOnClickListener { showVersionHistoryMaximumDialog() }
        versionHistorySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != currentState.versionHistory.enabled) {
                viewModel.setVersionHistoryEnabled(checked)
            }
        }
        clearCacheRow.setOnClickListener {
            viewModel.clearCaches()
            Snackbar.make(root, R.string.cache_cleared, Snackbar.LENGTH_SHORT).show()
        }
        resetSettingsRow.setOnClickListener { confirmReset() }
        gettingStartedRow.setOnClickListener {
            GettingStartedDialogFragment.show(childFragmentManager)
        }

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
        themeModeSummary.setText(state.settings.themeMode.labelRes())
        canvasAppearanceSummary.setText(state.settings.canvasAppearance.labelRes())
        defaultTemplateSummary.setText(state.settings.defaultPageTemplate.labelRes())
        canvasInputModeSummary.setText(state.settings.canvasInputMode.labelRes())
        trashRetentionSummary.setText(state.trashRetention.labelRes())
        versionHistorySwitch.isChecked = state.versionHistory.enabled
        versionHistorySummary.text = if (state.versionHistory.enabled) {
            getString(R.string.version_history_limit_summary, state.versionHistory.maximumVersionsPerPage)
        } else getString(R.string.version_history_disabled)
        storageUsageSummary.text = getString(
            R.string.storage_usage_summary,
            Formatter.formatFileSize(requireContext(), state.storageUsage.totalBytes),
            Formatter.formatFileSize(requireContext(), state.storageUsage.cacheBytes),
            Formatter.formatFileSize(requireContext(), state.storageUsage.availableBytes),
        )
        behaviorSection.pageSwipeSwitch.isChecked = state.settings.pageSwipeEnabled
        behaviorSection.keepScreenOnSwitch.isChecked = state.settings.keepScreenOn
        behaviorSection.hapticFeedbackSwitch.isChecked = state.settings.hapticFeedbackEnabled
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
        trashRetentionCard.contentDescription = getString(
            R.string.accessibility_setting_value,
            getString(R.string.trash_retention_title),
            trashRetentionSummary.text,
        )
        listOf(
            themeModeSummary,
            canvasAppearanceSummary,
            defaultTemplateSummary,
            canvasInputModeSummary,
            trashRetentionSummary,
        ).forEach {
            ViewCompat.setImportantForAccessibility(it, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO)
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

    private fun showRetentionDialog() {
        val values = TrashRetention.entries
        showSingleChoiceDialog(
            R.string.trash_retention_title,
            values.map { getString(it.labelRes()) },
            values.indexOf(currentState.trashRetention),
        ) { viewModel.setTrashRetention(values[it]) }
    }

    private fun showVersionHistoryMaximumDialog() {
        val values = VersionHistoryStore.SUPPORTED_LIMITS.sorted()
        showSingleChoiceDialog(
            R.string.version_history_limit_title,
            values.map { getString(R.string.version_history_limit_option, it) },
            values.indexOf(currentState.versionHistory.maximumVersionsPerPage),
        ) { viewModel.setVersionHistoryMaximum(values[it]) }
    }

    private fun showSingleChoiceDialog(
        titleRes: Int,
        labels: List<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset_settings_dialog_title)
            .setMessage(R.string.reset_settings_dialog_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                viewModel.reset()
                Snackbar.make(binding.root, R.string.settings_reset_complete, Snackbar.LENGTH_SHORT).show()
                AppCompatDelegate.setDefaultNightMode(ThemeMode.SYSTEM.nightMode())
            }
            .show()
            .applyCriticalPositiveAction()
    }

    @Suppress("DEPRECATION")
    private fun renderVersion() {
        val context = requireContext()
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
        binding.appVersion.text = getString(R.string.app_version, version)
    }

    private fun ThemeMode.labelRes() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    }

    private fun CanvasAppearance.labelRes() = when (this) {
        CanvasAppearance.WHITE_PAPER -> R.string.canvas_appearance_white
        CanvasAppearance.DARK_PAPER -> R.string.canvas_appearance_dark
    }

    private fun CanvasInputMode.labelRes() = when (this) {
        CanvasInputMode.STYLUS_ONLY -> R.string.canvas_input_mode_stylus_only
        CanvasInputMode.STYLUS_AND_TOUCH -> R.string.canvas_input_mode_stylus_touch
    }

    private fun PageTemplate.labelRes() = when (this) {
        PageTemplate.BLANK -> R.string.template_blank
        PageTemplate.LINED -> R.string.template_lined
        PageTemplate.GRID -> R.string.template_grid
    }

    private fun TrashRetention.labelRes() = when (this) {
        TrashRetention.DAYS_7 -> R.string.trash_retention_7_days
        TrashRetention.DAYS_30 -> R.string.trash_retention_30_days
        TrashRetention.DAYS_90 -> R.string.trash_retention_90_days
        TrashRetention.NEVER -> R.string.trash_retention_never
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
