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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val trashRetention: TrashRetention = TrashRetention.DAYS_30,
    val versionHistory: VersionHistorySettings = VersionHistorySettings(),
    val storageUsage: StorageUsage = StorageUsage(),
)

class SettingsViewModel(
    private val appSettingsStore: AppSettingsStore,
    private val customColorPaletteStore: CustomColorPaletteStore,
    private val trashRetentionStore: TrashRetentionStore,
    private val trashCleanupService: TrashCleanupService,
    private val versionHistoryStore: VersionHistoryStore,
    private val storageUsageService: StorageUsageService,
) : ViewModel() {
    private val storageUsage = MutableStateFlow(StorageUsage())
    val uiState: StateFlow<SettingsUiState> = combine(
        appSettingsStore.settings,
        trashRetentionStore.retention,
        versionHistoryStore.settings,
        storageUsage,
    ) { settings, retention, versions, storage ->
        SettingsUiState(settings, retention, versions, storage)
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(
                appSettingsStore.current(),
                trashRetentionStore.current(),
                versionHistoryStore.current(),
            ),
        )

    init { refreshStorageUsage() }

    fun setThemeMode(value: ThemeMode) = appSettingsStore.setThemeMode(value)
    fun setCanvasAppearance(value: CanvasAppearance) = appSettingsStore.setCanvasAppearance(value)
    fun setCanvasInputMode(value: CanvasInputMode) = appSettingsStore.setCanvasInputMode(value)
    fun setDefaultPageTemplate(value: PageTemplate) = appSettingsStore.setDefaultPageTemplate(value)
    fun setPageSwipeEnabled(value: Boolean) = appSettingsStore.setPageSwipeEnabled(value)
    fun setKeepScreenOn(value: Boolean) = appSettingsStore.setKeepScreenOn(value)
    fun setHapticFeedbackEnabled(value: Boolean) = appSettingsStore.setHapticFeedbackEnabled(value)
    fun setVersionHistoryEnabled(value: Boolean) = versionHistoryStore.setEnabled(value)
    fun setVersionHistoryMaximum(value: Int) = versionHistoryStore.setMaximumVersionsPerPage(value)

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            appSettingsStore,
            customColorPaletteStore,
            trashRetentionStore,
            trashCleanupService,
            versionHistoryStore,
            storageUsageService,
        ) as T
    }
}
