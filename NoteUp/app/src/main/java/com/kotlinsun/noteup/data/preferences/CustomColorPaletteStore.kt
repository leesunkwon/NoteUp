package com.kotlinsun.noteup.data.preferences

import android.content.Context
import com.kotlinsun.noteup.domain.model.opaqueColor
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CustomColorPaletteStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _colors = MutableStateFlow(load())
    val colors = _colors.asStateFlow()

    fun add(color: Int) {
        val normalized = opaqueColor(color)
        if (normalized in _colors.value) return
        save(_colors.value + normalized)
    }

    fun remove(color: Int) {
        val normalized = opaqueColor(color)
        save(_colors.value.filterNot { it == normalized })
    }

    fun reset() = save(emptyList())

    private fun save(colors: List<Int>) {
        preferences.edit()
            .putString(KEY_COLORS, colors.joinToString(SEPARATOR, transform = ::encode))
            .apply()
        _colors.value = colors
    }

    private fun load(): List<Int> = preferences.getString(KEY_COLORS, null)
        ?.split(SEPARATOR)
        ?.mapNotNull(::decode)
        ?.distinct()
        .orEmpty()

    private fun encode(color: Int): String = String.format(
        Locale.US,
        "%06X",
        color and 0x00FFFFFF,
    )

    private fun decode(value: String): Int? = value
        .takeIf { it.length == HEX_LENGTH }
        ?.toLongOrNull(HEX_RADIX)
        ?.toInt()
        ?.let(::opaqueColor)

    private companion object {
        const val PREFERENCES_NAME = "custom_color_palette"
        const val KEY_COLORS = "ordered_colors"
        const val SEPARATOR = ","
        const val HEX_LENGTH = 6
        const val HEX_RADIX = 16
    }
}
