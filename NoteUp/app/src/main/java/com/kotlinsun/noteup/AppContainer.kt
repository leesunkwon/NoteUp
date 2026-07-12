package com.kotlinsun.noteup

import android.content.Context
import androidx.room.Room
import com.kotlinsun.noteup.data.local.NoteUpDatabase
import com.kotlinsun.noteup.data.local.DatabaseMigrations
import com.kotlinsun.noteup.data.repository.LocalNoteRepository
import com.kotlinsun.noteup.data.preferences.DrawingToolSettingsStore
import com.kotlinsun.noteup.domain.repository.NoteRepository

class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        NoteUpDatabase::class.java,
        "noteup.db",
    ).addMigrations(DatabaseMigrations.MIGRATION_1_2).build()

    val noteRepository: NoteRepository = LocalNoteRepository(database)
    val drawingToolSettingsStore = DrawingToolSettingsStore(context)
}
