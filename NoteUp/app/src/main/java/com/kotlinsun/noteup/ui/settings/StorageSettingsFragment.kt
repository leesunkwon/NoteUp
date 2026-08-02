package com.kotlinsun.noteup.ui.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.data.preferences.TrashRetention
import com.kotlinsun.noteup.data.preferences.VersionHistoryStore
import com.kotlinsun.noteup.databinding.FragmentSettingsStorageBinding
import kotlinx.coroutines.launch

class StorageSettingsFragment : Fragment() {

    private var _binding: FragmentSettingsStorageBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by settingsViewModel()
    private var currentState = SettingsUiState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsStorageBinding.inflate(inflater, container, false)
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
        backButton.setOnClickListener { popBackStackFrom(R.id.storageSettingsFragment) }
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
    }

    private fun render(state: SettingsUiState) = with(binding) {
        currentState = state
        trashRetentionSummary.setText(state.trashRetention.labelRes())
        versionHistorySwitch.isChecked = state.versionHistory.enabled
        versionHistorySummary.text = if (state.versionHistory.enabled) {
            getString(
                R.string.version_history_limit_summary,
                state.versionHistory.maximumVersionsPerPage,
            )
        } else {
            getString(R.string.version_history_disabled)
        }
        storageUsageSummary.text = getString(
            R.string.storage_usage_summary,
            Formatter.formatFileSize(requireContext(), state.storageUsage.totalBytes),
            Formatter.formatFileSize(requireContext(), state.storageUsage.cacheBytes),
            Formatter.formatFileSize(requireContext(), state.storageUsage.availableBytes),
        )
        trashRetentionCard.contentDescription = getString(
            R.string.accessibility_setting_value,
            getString(R.string.trash_retention_title),
            trashRetentionSummary.text,
        )
        ViewCompat.setImportantForAccessibility(
            trashRetentionSummary,
            ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
        )
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
