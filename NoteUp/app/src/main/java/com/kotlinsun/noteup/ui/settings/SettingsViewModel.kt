package com.kotlinsun.noteup.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kotlinsun.noteup.data.preferences.AppSettingsStore
import com.kotlinsun.noteup.data.preferences.CustomColorPaletteStore
import com.kotlinsun.noteup.data.preferences.TrashRetention
import com.kotlinsun.noteup.data.preferences.TrashRetentionStore
import com.kotlinsun.noteup.data.trash.TrashCleanupService
import com.kotlinsun.noteup.data.preferences.VersionHistorySettings
import com.kotlinsun.noteup.data.preferences.VersionHistoryStore
import com.kotlinsun.noteup.data.storage.StorageUsageService
import com.kotlinsun.noteup.domain.model.AppSettings
import com.kotlinsun.noteup.domain.model.CanvasAppearance
import com.kotlinsun.noteup.domain.model.CanvasInputMode
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.ThemeMode
import com.kotlinsun.noteup.domain.model.StorageUsage
import com.kotlinsun.noteup.domain.ai.AiEngineState
import com.kotlinsun.noteup.domain.ai.AiDeviceCompatibility
import com.kotlinsun.noteup.domain.ai.AiModelState
import com.kotlinsun.noteup.domain.ai.OnDeviceAiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AiTestUiState {
    data object Idle : AiTestUiState
    data class Running(val response: String) : AiTestUiState
    data class Complete(val response: String) : AiTestUiState
    data object Cancelled : AiTestUiState
    data class Failed(val reason: AiTestFailure) : AiTestUiState
}

enum class AiTestFailure {
    MODEL_UNAVAILABLE,
    GENERATION,
    MODEL_DELETE,
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val trashRetention: TrashRetention = TrashRetention.DAYS_30,
    val versionHistory: VersionHistorySettings = VersionHistorySettings(),
    val storageUsage: StorageUsage = StorageUsage(),
    val aiModelState: AiModelState = AiModelState.Checking,
    val aiEngineState: AiEngineState = AiEngineState.Unloaded,
    val aiTestState: AiTestUiState = AiTestUiState.Idle,
    val aiModelDeleting: Boolean = false,
    val aiCompatibility: AiDeviceCompatibility = AiDeviceCompatibility(
        supported = true,
        abi = null,
        isLowMemoryDevice = false,
    ),
)

class SettingsViewModel(
    private val appSettingsStore: AppSettingsStore,
    private val customColorPaletteStore: CustomColorPaletteStore,
    private val trashRetentionStore: TrashRetentionStore,
    private val trashCleanupService: TrashCleanupService,
    private val versionHistoryStore: VersionHistoryStore,
    private val storageUsageService: StorageUsageService,
    private val onDeviceAiRepository: OnDeviceAiRepository,
) : ViewModel() {
    private val storageUsage = MutableStateFlow(StorageUsage())
    private val aiTestState = MutableStateFlow<AiTestUiState>(AiTestUiState.Idle)
    private val aiModelDeleting = MutableStateFlow(false)
    private var aiTestJob: Job? = null
    private var aiTestRequestId = 0L

    private val baseUiState = combine(
        appSettingsStore.settings,
        trashRetentionStore.retention,
        versionHistoryStore.settings,
        storageUsage,
    ) { settings, retention, versions, storage ->
        SettingsUiState(settings, retention, versions, storage)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        baseUiState,
        onDeviceAiRepository.modelState,
        onDeviceAiRepository.engineState,
        aiTestState,
        aiModelDeleting,
    ) { base, modelState, engineState, testState, modelDeleting ->
        base.copy(
            aiModelState = modelState,
            aiEngineState = engineState,
            aiTestState = testState,
            aiModelDeleting = modelDeleting,
            aiCompatibility = onDeviceAiRepository.compatibility,
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(
                appSettingsStore.current(),
                trashRetentionStore.current(),
                versionHistoryStore.current(),
                aiModelState = onDeviceAiRepository.modelState.value,
                aiEngineState = onDeviceAiRepository.engineState.value,
                aiCompatibility = onDeviceAiRepository.compatibility,
            ),
        )

    init {
        refreshStorageUsage()
        viewModelScope.launch {
            onDeviceAiRepository.modelState
                .mapNotNull { state ->
                    when (state) {
                        is AiModelState.Ready -> "ready"
                        is AiModelState.NotInstalled -> "not_installed"
                        is AiModelState.Failed -> "failed"
                        else -> null
                    }
                }
                .collect { refreshStorageUsage() }
        }
    }

    fun setThemeMode(value: ThemeMode) = appSettingsStore.setThemeMode(value)
    fun setCanvasAppearance(value: CanvasAppearance) = appSettingsStore.setCanvasAppearance(value)
    fun setCanvasInputMode(value: CanvasInputMode) = appSettingsStore.setCanvasInputMode(value)
    fun setDefaultPageTemplate(value: PageTemplate) = appSettingsStore.setDefaultPageTemplate(value)
    fun setPageSwipeEnabled(value: Boolean) = appSettingsStore.setPageSwipeEnabled(value)
    fun setKeepScreenOn(value: Boolean) = appSettingsStore.setKeepScreenOn(value)
    fun setHapticFeedbackEnabled(value: Boolean) = appSettingsStore.setHapticFeedbackEnabled(value)
    fun setVersionHistoryEnabled(value: Boolean) = versionHistoryStore.setEnabled(value)
    fun setVersionHistoryMaximum(value: Int) = versionHistoryStore.setMaximumVersionsPerPage(value)
    fun downloadAiModel(allowMetered: Boolean) {
        if (aiModelDeleting.value) return
        aiTestState.value = AiTestUiState.Idle
        onDeviceAiRepository.downloadModel(allowMetered)
    }

    fun cancelAiModelDownload() = onDeviceAiRepository.cancelModelDownload()

    fun deleteAiModel() {
        if (aiModelDeleting.value) return
        cancelAiTest(updateState = false)
        aiTestState.value = AiTestUiState.Idle
        aiModelDeleting.value = true
        viewModelScope.launch {
            try {
                onDeviceAiRepository.deleteModel()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                aiTestState.value = AiTestUiState.Failed(AiTestFailure.MODEL_DELETE)
            } finally {
                aiModelDeleting.value = false
                refreshStorageUsage()
            }
        }
    }

    fun testAiModel(prompt: String) {
        if (
            aiModelDeleting.value ||
            onDeviceAiRepository.modelState.value !is AiModelState.Ready ||
            onDeviceAiRepository.engineState.value is AiEngineState.Loading ||
            onDeviceAiRepository.engineState.value is AiEngineState.Generating
        ) {
            aiTestState.value = AiTestUiState.Failed(AiTestFailure.MODEL_UNAVAILABLE)
            return
        }

        cancelAiTest(updateState = false)
        val requestId = ++aiTestRequestId
        aiTestState.value = AiTestUiState.Running("")
        aiTestJob = viewModelScope.launch {
            val response = StringBuilder()
            try {
                onDeviceAiRepository.generate(prompt).collect { chunk ->
                    response.append(chunk)
                    if (requestId == aiTestRequestId) {
                        aiTestState.value = AiTestUiState.Running(response.toString())
                    }
                }
                if (requestId == aiTestRequestId) {
                    aiTestState.value = if (response.isEmpty()) {
                        AiTestUiState.Failed(AiTestFailure.GENERATION)
                    } else {
                        AiTestUiState.Complete(response.toString())
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestId == aiTestRequestId) aiTestState.value = AiTestUiState.Cancelled
                throw cancelled
            } catch (_: Throwable) {
                if (requestId == aiTestRequestId) {
                    aiTestState.value = AiTestUiState.Failed(AiTestFailure.GENERATION)
                }
            }
        }
    }

    fun cancelAiTest() = cancelAiTest(updateState = true)

    private fun cancelAiTest(updateState: Boolean) {
        aiTestRequestId++
        aiTestJob?.takeIf { it.isActive }?.cancel()
        aiTestJob = null
        if (updateState && aiTestState.value is AiTestUiState.Running) {
            aiTestState.value = AiTestUiState.Cancelled
        }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch { storageUsage.value = storageUsageService.measure() }
    }

    fun clearCaches() {
        viewModelScope.launch {
            storageUsageService.clearRegenerableCaches()
            storageUsage.value = storageUsageService.measure()
        }
    }

    fun setTrashRetention(value: TrashRetention) {
        trashRetentionStore.set(value)
        trashCleanupService.request()
    }

    fun reset() {
        appSettingsStore.reset()
        customColorPaletteStore.reset()
        trashRetentionStore.set(TrashRetention.DAYS_30)
        versionHistoryStore.setEnabled(true)
        versionHistoryStore.setMaximumVersionsPerPage(20)
        trashCleanupService.request()
    }

    class Factory(
        private val appSettingsStore: AppSettingsStore,
        private val customColorPaletteStore: CustomColorPaletteStore,
        private val trashRetentionStore: TrashRetentionStore,
        private val trashCleanupService: TrashCleanupService,
        private val versionHistoryStore: VersionHistoryStore,
        private val storageUsageService: StorageUsageService,
        private val onDeviceAiRepository: OnDeviceAiRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            appSettingsStore,
            customColorPaletteStore,
            trashRetentionStore,
            trashCleanupService,
            versionHistoryStore,
            storageUsageService,
            onDeviceAiRepository,
        ) as T
    }

    override fun onCleared() {
        cancelAiTest(updateState = false)
        super.onCleared()
    }
}
