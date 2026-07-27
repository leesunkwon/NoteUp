package com.kotlinsun.noteup.data.preferences

import android.content.Context
import com.kotlinsun.noteup.domain.model.DrawingSettings
import com.kotlinsun.noteup.domain.model.DrawingTool
import com.kotlinsun.noteup.domain.model.EraserMode
import com.kotlinsun.noteup.domain.model.HighlighterColor
import com.kotlinsun.noteup.domain.model.HighlighterSettings
import com.kotlinsun.noteup.domain.model.HighlighterThickness
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.PenSettings
import com.kotlinsun.noteup.domain.model.PenThickness
import com.kotlinsun.noteup.domain.model.TextSize
import com.kotlinsun.noteup.domain.model.highlighterColor
import com.kotlinsun.noteup.domain.model.opaqueColor

class DrawingToolSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): DrawingSettings = DrawingSettings(
        tool = enumValueOrDefault(
            preferences.getString(KEY_TOOL, null),
            DrawingTool.PEN,
        ),
        pen = PenSettings(
            color = enumValueOrDefault(
                preferences.getString(KEY_PEN_COLOR, null),
                PenColor.BLACK,
            ),
            thickness = enumValueOrDefault(
                preferences.getString(KEY_PEN_THICKNESS, null),
                PenThickness.MEDIUM,
            ),
            customColorArgb = preferences.takeIf { it.contains(KEY_PEN_CUSTOM_COLOR) }
                ?.getInt(KEY_PEN_CUSTOM_COLOR, PenColor.BLACK.argb)
                ?.let(::opaqueColor),
        ),
        highlighter = HighlighterSettings(
            color = enumValueOrDefault(
                preferences.getString(KEY_HIGHLIGHTER_COLOR, null),
                HighlighterColor.YELLOW,
            ),
            thickness = enumValueOrDefault(
                preferences.getString(KEY_HIGHLIGHTER_THICKNESS, null),
                HighlighterThickness.MEDIUM,
            ),
            customColorArgb = preferences.takeIf { it.contains(KEY_HIGHLIGHTER_CUSTOM_COLOR) }
                ?.getInt(KEY_HIGHLIGHTER_CUSTOM_COLOR, HighlighterColor.YELLOW.argb)
                ?.let(::highlighterColor),
        ),
        eraserMode = enumValueOrDefault(
            preferences.getString(KEY_ERASER_MODE, null),
            EraserMode.STROKE,
        ),
        textSize = enumValueOrDefault(
            preferences.getString(KEY_TEXT_SIZE, null),
            TextSize.MEDIUM,
        ),
    )

    fun save(settings: DrawingSettings) {
        val editor = preferences.edit()
            .putString(KEY_TOOL, settings.tool.name)
            .putString(KEY_PEN_COLOR, settings.pen.color.name)
            .putString(KEY_PEN_THICKNESS, settings.pen.thickness.name)
            .putString(KEY_HIGHLIGHTER_COLOR, settings.highlighter.color.name)
            .putString(KEY_HIGHLIGHTER_THICKNESS, settings.highlighter.thickness.name)
            .putString(KEY_ERASER_MODE, settings.eraserMode.name)
            .putString(KEY_TEXT_SIZE, settings.textSize.name)
        settings.pen.customColorArgb?.let { editor.putInt(KEY_PEN_CUSTOM_COLOR, it) }
            ?: editor.remove(KEY_PEN_CUSTOM_COLOR)
        settings.highlighter.customColorArgb?.let {
            editor.putInt(KEY_HIGHLIGHTER_CUSTOM_COLOR, it)
        } ?: editor.remove(KEY_HIGHLIGHTER_CUSTOM_COLOR)
        editor.apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        default: T,
    ): T = value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFERENCES_NAME = "pen_settings"
        const val KEY_TOOL = "tool"
        const val KEY_PEN_COLOR = "color"
        const val KEY_PEN_THICKNESS = "thickness"
        const val KEY_PEN_CUSTOM_COLOR = "pen_custom_color_argb"
        const val KEY_HIGHLIGHTER_COLOR = "highlighter_color"
        const val KEY_HIGHLIGHTER_THICKNESS = "highlighter_thickness"
        const val KEY_HIGHLIGHTER_CUSTOM_COLOR = "highlighter_custom_color_argb"
        const val KEY_ERASER_MODE = "eraser_mode"
        const val KEY_TEXT_SIZE = "text_size"
    }
}
