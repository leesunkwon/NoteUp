package com.kotlinsun.noteup.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VersionHistorySettings(
    val enabled: Boolean = true,
    val maximumVersionsPerPage: Int = 20,
)

class VersionHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _settings = MutableStateFlow(load())
    val settings = _settings.asStateFlow()

    fun current(): VersionHistorySettings = _settings.value

    fun setEnabled(enabled: Boolean) = update(_settings.value.copy(enabled = enabled))

    fun setMaximumVersionsPerPage(value: Int) {
        require(value in SUPPORTED_LIMITS)
        update(_settings.value.copy(maximumVersionsPerPage = value))
    }

    private fun update(value: VersionHistorySettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putInt(KEY_MAXIMUM, value.maximumVersionsPerPage)
            .apply()
        _settings.value = value
    }

    private fun load(): VersionHistorySettings {
        val maximum = preferences.getInt(KEY_MAXIMUM, DEFAULT_MAXIMUM)
            .takeIf { it in SUPPORTED_LIMITS } ?: DEFAULT_MAXIMUM
        return VersionHistorySettings(
            enabled = preferences.getBoolean(KEY_ENABLED, true),
            maximumVersionsPerPage = maximum,
        )
    }

    companion object {
        val SUPPORTED_LIMITS = setOf(10, 20, 50)
        private const val PREFERENCES_NAME = "version_history"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAXIMUM = "maximum_versions_per_page"
        private const val DEFAULT_MAXIMUM = 20
    }
}
