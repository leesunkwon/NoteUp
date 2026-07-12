package com.kotlinsun.noteup.data.preferences

import android.content.Context
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.PenSettings
import com.kotlinsun.noteup.domain.model.PenThickness

class PenSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): PenSettings = PenSettings(
        color = enumValueOrDefault(
            preferences.getString(KEY_COLOR, null),
            PenColor.BLACK,
        ),
        thickness = enumValueOrDefault(
            preferences.getString(KEY_THICKNESS, null),
            PenThickness.MEDIUM,
        ),
    )

    fun save(settings: PenSettings) {
        preferences.edit()
            .putString(KEY_COLOR, settings.color.name)
            .putString(KEY_THICKNESS, settings.thickness.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        default: T,
    ): T = value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFERENCES_NAME = "pen_settings"
        const val KEY_COLOR = "color"
        const val KEY_THICKNESS = "thickness"
    }
}
