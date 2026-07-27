package com.kotlinsun.noteup.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kotlinsun.noteup.data.preferences.AppSettingsStore
import com.kotlinsun.noteup.data.preferences.TrashRetention
import com.kotlinsun.noteup.data.preferences.TrashRetentionStore
import com.kotlinsun.noteup.data.trash.TrashCleanupService
import com.kotlinsun.noteup.domain.model.AppSettings
import com.kotlinsun.noteup.domain.model.CanvasAppearance
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val trashRetention: TrashRetention = TrashRetention.DAYS_30,
)

class SettingsViewModel(
    private val appSettingsStore: AppSettingsStore,
    private val trashRetentionStore: TrashRetentionStore,
    private val trashCleanupService: TrashCleanupService,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = combine(
        appSettingsStore.settings,
        trashRetentionStore.retention,
    ) { settings, retention -> SettingsUiState(settings, retention) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(appSettingsStore.current(), trashRetentionStore.current()),
        )

    fun setThemeMode(value: ThemeMode) = appSettingsStore.setThemeMode(value)
    fun setCanvasAppearance(value: CanvasAppearance) = appSettingsStore.setCanvasAppearance(value)
    fun setDefaultPageTemplate(value: PageTemplate) = appSettingsStore.setDefaultPageTemplate(value)
    fun setPageSwipeEnabled(value: Boolean) = appSettingsStore.setPageSwipeEnabled(value)
    fun setKeepScreenOn(value: Boolean) = appSettingsStore.setKeepScreenOn(value)
    fun setHapticFeedbackEnabled(value: Boolean) = appSettingsStore.setHapticFeedbackEnabled(value)

    fun setTrashRetention(value: TrashRetention) {
        trashRetentionStore.set(value)
        trashCleanupService.request()
    }

    fun reset() {
        appSettingsStore.reset()
        trashRetentionStore.set(TrashRetention.DAYS_30)
        trashCleanupService.request()
    }

    class Factory(
        private val appSettingsStore: AppSettingsStore,
        private val trashRetentionStore: TrashRetentionStore,
        private val trashCleanupService: TrashCleanupService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            appSettingsStore,
            trashRetentionStore,
            trashCleanupService,
        ) as T
    }
}
