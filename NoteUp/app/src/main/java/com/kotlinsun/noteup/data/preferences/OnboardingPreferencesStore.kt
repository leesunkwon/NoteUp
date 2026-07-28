package com.kotlinsun.noteup.data.preferences

import android.content.Context

class OnboardingPreferencesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun shouldShow(): Boolean = !preferences.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        preferences.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "getting_started"
        const val KEY_COMPLETED = "completed"
    }
}
