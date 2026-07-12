package com.kotlinsun.noteup

import android.app.Application

class NoteUpApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
