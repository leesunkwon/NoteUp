package com.kotlinsun.noteup

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.kotlinsun.noteup.data.preferences.AppSettingsStore
import com.kotlinsun.noteup.domain.model.ThemeMode

class NoteUpApplication : Application() {
    val appSettingsStore by lazy { AppSettingsStore(this) }
    val container: AppContainer by lazy { AppContainer(this, appSettingsStore) }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(appSettingsStore.current().themeMode.nightMode())
    }
}

fun ThemeMode.nightMode(): Int = when (this) {
    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}
