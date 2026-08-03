package com.kotlinsun.noteup.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentSettingsAboutBinding
import com.kotlinsun.noteup.domain.model.ThemeMode
import com.kotlinsun.noteup.nightMode
import com.kotlinsun.noteup.ui.common.applyCriticalPositiveAction
import com.kotlinsun.noteup.ui.onboarding.GettingStartedDialogFragment

class AboutSettingsFragment : Fragment() {

    private var _binding: FragmentSettingsAboutBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by settingsViewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderVersion()
        setupActions()
    }

    private fun setupActions() = with(binding) {
        backButton.setOnClickListener { popBackStackFrom(R.id.aboutSettingsFragment) }
        gettingStartedRow.setOnClickListener {
            GettingStartedDialogFragment.show(childFragmentManager)
        }
        resetSettingsRow.setOnClickListener { confirmReset() }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset_settings_dialog_title)
            .setMessage(R.string.reset_settings_dialog_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset) { _, _ ->
                viewModel.reset()
                Snackbar.make(
                    binding.root,
                    R.string.settings_reset_complete,
                    Snackbar.LENGTH_SHORT,
                ).show()
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
