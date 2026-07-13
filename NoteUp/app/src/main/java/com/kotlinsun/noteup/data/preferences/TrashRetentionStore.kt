package com.kotlinsun.noteup.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrashRetention(val days: Int?) {
    DAYS_7(7),
    DAYS_30(30),
    DAYS_90(90),
    NEVER(null),
}

class TrashRetentionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE,
    )
    private val _retention = MutableStateFlow(load())
    val retention = _retention.asStateFlow()

    fun set(value: TrashRetention) {
        preferences.edit().putString(KEY_RETENTION, value.name).apply()
        _retention.value = value
    }

    fun current(): TrashRetention = _retention.value

    private fun load(): TrashRetention = runCatching {
        TrashRetention.valueOf(preferences.getString(KEY_RETENTION, null).orEmpty())
    }.getOrDefault(TrashRetention.DAYS_30)

    private companion object {
        const val PREFERENCES_NAME = "trash_settings"
        const val KEY_RETENTION = "retention"
    }
}
