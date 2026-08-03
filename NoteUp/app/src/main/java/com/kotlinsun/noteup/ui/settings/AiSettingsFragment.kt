package com.kotlinsun.noteup.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentSettingsAiBinding
import com.kotlinsun.noteup.domain.ai.AiEngineState
import com.kotlinsun.noteup.domain.ai.AiModelError
import com.kotlinsun.noteup.domain.ai.AiModelState
import com.kotlinsun.noteup.ui.common.applyCriticalPositiveAction
import kotlinx.coroutines.launch

class AiSettingsFragment : Fragment() {

    private var _binding: FragmentSettingsAiBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by settingsViewModel()
    private var currentState = SettingsUiState()
    private var pendingDownloadAllowMetered = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val allowMetered = pendingDownloadAllowMetered
        pendingDownloadAllowMetered = false
        viewModel.downloadAiModel(allowMetered)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDownloadAllowMetered = savedInstanceState?.getBoolean(
            STATE_PENDING_DOWNLOAD_ALLOW_METERED,
        ) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsAiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentState = viewModel.uiState.value
        setupActions()
        render(currentState, currentState.aiTestState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val previousTestState = currentState.aiTestState
                    currentState = state
                    render(state, previousTestState)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_PENDING_DOWNLOAD_ALLOW_METERED,
            pendingDownloadAllowMetered,
        )
        super.onSaveInstanceState(outState)
    }

    private fun setupActions() = with(binding) {
        backButton.setOnClickListener { popBackStackFrom(R.id.aiSettingsFragment) }
        aiModelDownloadButton.setOnClickListener { confirmAiModelDownload() }
        aiModelCancelButton.setOnClickListener { viewModel.cancelAiModelDownload() }
        aiModelTestButton.setOnClickListener {
            if (currentState.aiTestState is AiTestUiState.Running) {
                viewModel.cancelAiTest()
            } else {
                viewModel.testAiModel(getString(R.string.ai_model_test_prompt))
            }
        }
        aiModelDeleteButton.setOnClickListener { confirmAiModelDeletion() }
    }

    private fun render(
        state: SettingsUiState,
        previousAiTestState: AiTestUiState,
    ) = with(binding) {
        val modelState = state.aiModelState
        val progressVisible = state.aiModelDeleting ||
            modelState is AiModelState.Checking ||
            modelState is AiModelState.Queued ||
            modelState is AiModelState.Downloading ||
            modelState is AiModelState.Verifying
        val indeterminate = state.aiModelDeleting || modelState !is AiModelState.Downloading
        val testRunning = state.aiTestState is AiTestUiState.Running

        val statusText = buildString {
            append(
                getString(
                    if (state.aiModelDeleting) R.string.ai_model_status_deleting
                    else modelState.statusLabelRes(),
                ),
            )
            val availableBytes = when (modelState) {
                is AiModelState.NotInstalled -> modelState.availableBytes
                is AiModelState.Failed -> modelState.availableBytes.takeIf {
                    it > 0L || modelState.error == AiModelError.STORAGE
                }
                else -> null
            }
            if (availableBytes != null) {
                append('\n')
                append(
                    getString(
                        R.string.ai_model_available_storage,
                        Formatter.formatFileSize(requireContext(), availableBytes),
                    ),
                )
            }
            if (state.aiCompatibility.isLowMemoryDevice) {
                append('\n')
                append(getString(R.string.ai_model_status_low_memory_warning))
            }
        }
        if (aiModelStatusSummary.text.toString() != statusText) {
            aiModelStatusSummary.text = statusText
        }

        aiModelProgress.isVisible = progressVisible
        aiModelProgressSummary.isVisible = progressVisible
        if (progressVisible) {
            aiModelProgress.isIndeterminate = indeterminate
            when (modelState) {
                is AiModelState.Downloading -> {
                    val total = modelState.totalBytes.coerceAtLeast(1L)
                    val percent = ((modelState.downloadedBytes * 100L) / total)
                        .coerceIn(0L, 100L)
                        .toInt()
                    aiModelProgress.setProgressCompat(percent, true)
                    aiModelProgressSummary.text = getString(
                        R.string.ai_model_progress_format,
                        percent,
                        Formatter.formatFileSize(requireContext(), modelState.downloadedBytes),
                        Formatter.formatFileSize(requireContext(), modelState.totalBytes),
                    )
                    aiModelProgress.contentDescription = aiModelProgressSummary.text
                }

                else -> {
                    aiModelProgressSummary.setText(
                        if (state.aiModelDeleting) R.string.ai_model_status_deleting
                        else R.string.ai_model_progress_waiting,
                    )
                    aiModelProgress.contentDescription = aiModelProgressSummary.text
                }
            }
        }

        val canDownload = !state.aiModelDeleting &&
            (modelState is AiModelState.NotInstalled || modelState is AiModelState.Failed)
        aiModelDownloadButton.isVisible = canDownload
        aiModelDownloadButton.setText(
            if (modelState is AiModelState.Failed) R.string.ai_model_retry
            else R.string.ai_model_download,
        )
        aiModelCancelButton.isVisible = !state.aiModelDeleting &&
            (modelState is AiModelState.Queued ||
                modelState is AiModelState.Downloading ||
                modelState is AiModelState.Verifying)
        aiModelTestButton.isVisible = !state.aiModelDeleting && modelState is AiModelState.Ready
        aiModelTestButton.isEnabled = testRunning || (
            state.aiEngineState !is AiEngineState.Loading &&
                state.aiEngineState !is AiEngineState.Generating
        )
        aiModelTestButton.setText(
            if (testRunning) R.string.ai_model_test_cancel else R.string.ai_model_test,
        )
        aiModelDeleteButton.isVisible = !state.aiModelDeleting && modelState is AiModelState.Ready
        aiModelDeleteButton.isEnabled = !testRunning

        when (val testState = state.aiTestState) {
            AiTestUiState.Idle -> {
                aiTestProgress.isVisible = false
                aiTestOutput.isVisible = false
            }

            is AiTestUiState.Running -> {
                ViewCompat.setAccessibilityLiveRegion(
                    aiTestOutput,
                    ViewCompat.ACCESSIBILITY_LIVE_REGION_NONE,
                )
                aiTestProgress.isVisible = true
                aiTestOutput.isVisible = testState.response.isNotBlank()
                if (testState.response.isNotBlank()) {
                    aiTestOutput.text = getString(
                        R.string.ai_model_test_output_format,
                        testState.response,
                    )
                }
            }

            is AiTestUiState.Complete -> {
                aiTestProgress.isVisible = false
                aiTestOutput.isVisible = true
                aiTestOutput.text = getString(
                    R.string.ai_model_test_output_format,
                    testState.response,
                )
                announceAiTestResult(previousAiTestState, testState)
            }

            AiTestUiState.Cancelled -> {
                aiTestProgress.isVisible = false
                aiTestOutput.isVisible = true
                aiTestOutput.setText(R.string.ai_model_test_cancelled)
                announceAiTestResult(previousAiTestState, testState)
            }

            is AiTestUiState.Failed -> {
                aiTestProgress.isVisible = false
                aiTestOutput.isVisible = true
                aiTestOutput.setText(
                    when (testState.reason) {
                        AiTestFailure.MODEL_UNAVAILABLE ->
                            R.string.ai_model_test_model_unavailable
                        AiTestFailure.GENERATION -> R.string.ai_model_test_error
                        AiTestFailure.MODEL_DELETE -> R.string.ai_model_delete_error
                    },
                )
                announceAiTestResult(previousAiTestState, testState)
            }
        }
    }

    private fun announceAiTestResult(
        previousState: AiTestUiState,
        currentState: AiTestUiState,
    ) {
        if (previousState == currentState) return
        binding.aiTestOutput.post {
            _binding?.aiTestOutput?.let { output ->
                if (output.isShown) output.announceForAccessibility(output.text)
            }
        }
    }

    private fun confirmAiModelDownload() {
        val connectivityManager = requireContext().getSystemService(
            Context.CONNECTIVITY_SERVICE,
        ) as ConnectivityManager
        val allowMetered = connectivityManager.activeNetwork != null &&
            connectivityManager.isActiveNetworkMetered
        val message = buildString {
            append(getString(R.string.ai_model_download_confirm_message))
            if (allowMetered) {
                append("\n\n")
                append(getString(R.string.ai_model_download_cellular_message))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_model_download_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_model_download) { _, _ ->
                requestNotificationPermissionAndDownload(allowMetered)
            }
            .show()
    }

    private fun requestNotificationPermissionAndDownload(allowMetered: Boolean) {
        pendingDownloadAllowMetered = allowMetered
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingDownloadAllowMetered = false
            viewModel.downloadAiModel(allowMetered)
        }
    }

    private fun confirmAiModelDeletion() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_model_delete_confirm_title)
            .setMessage(R.string.ai_model_delete_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_model_delete) { _, _ -> viewModel.deleteAiModel() }
            .show()
            .applyCriticalPositiveAction()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val STATE_PENDING_DOWNLOAD_ALLOW_METERED =
            "pending_download_allow_metered"
    }
}
