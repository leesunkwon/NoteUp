package com.kotlinsun.noteup.data.preferences

import android.content.Context
import com.kotlinsun.noteup.domain.model.AppSettings
import com.kotlinsun.noteup.domain.model.CanvasAppearance
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _settings = MutableStateFlow(load())
    val settings = _settings.asStateFlow()

    fun current(): AppSettings = _settings.value

    fun setThemeMode(value: ThemeMode) = update { copy(themeMode = value) }

    fun setCanvasAppearance(value: CanvasAppearance) = update {
        copy(canvasAppearance = value)
    }

    fun setDefaultPageTemplate(value: PageTemplate) = update {
        copy(defaultPageTemplate = value)
    }

    fun setPageSwipeEnabled(value: Boolean) = update { copy(pageSwipeEnabled = value) }

    fun setKeepScreenOn(value: Boolean) = update { copy(keepScreenOn = value) }

    fun setHapticFeedbackEnabled(value: Boolean) = update {
        copy(hapticFeedbackEnabled = value)
    }

    fun reset() {
        preferences.edit().clear().apply()
        _settings.value = AppSettings()
    }

    private fun update(transform: AppSettings.() -> AppSettings) {
        val updated = _settings.value.transform()
        preferences.edit()
            .putString(KEY_THEME_MODE, updated.themeMode.name)
            .putString(KEY_CANVAS_APPEARANCE, updated.canvasAppearance.name)
            .putString(KEY_DEFAULT_PAGE_TEMPLATE, updated.defaultPageTemplate.name)
            .putBoolean(KEY_PAGE_SWIPE, updated.pageSwipeEnabled)
            .putBoolean(KEY_KEEP_SCREEN_ON, updated.keepScreenOn)
            .putBoolean(KEY_HAPTIC_FEEDBACK, updated.hapticFeedbackEnabled)
            .apply()
        _settings.value = updated
    }

    private fun load() = AppSettings(
        themeMode = enumValueOrDefault(KEY_THEME_MODE, ThemeMode.SYSTEM),
        canvasAppearance = enumValueOrDefault(
            KEY_CANVAS_APPEARANCE,
            CanvasAppearance.WHITE_PAPER,
        ),
        defaultPageTemplate = enumValueOrDefault(
            KEY_DEFAULT_PAGE_TEMPLATE,
            PageTemplate.BLANK,
        ),
        pageSwipeEnabled = preferences.getBoolean(KEY_PAGE_SWIPE, true),
        keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false),
        hapticFeedbackEnabled = preferences.getBoolean(KEY_HAPTIC_FEEDBACK, true),
    )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(key: String, default: T): T =
        preferences.getString(key, null)
            ?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
            ?: default

    private companion object {
        const val PREFERENCES_NAME = "app_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_CANVAS_APPEARANCE = "canvas_appearance"
        const val KEY_DEFAULT_PAGE_TEMPLATE = "default_page_template"
        const val KEY_PAGE_SWIPE = "page_swipe_enabled"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_HAPTIC_FEEDBACK = "haptic_feedback_enabled"
    }
}
