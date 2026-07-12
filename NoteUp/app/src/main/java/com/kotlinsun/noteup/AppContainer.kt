package com.kotlinsun.noteup

import android.content.Context
import androidx.room.Room
import com.kotlinsun.noteup.data.local.NoteUpDatabase
import com.kotlinsun.noteup.data.repository.LocalNoteRepository
import com.kotlinsun.noteup.domain.repository.NoteRepository

class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        NoteUpDatabase::class.java,
        "noteup.db",
    ).build()

    val noteRepository: NoteRepository = LocalNoteRepository(database)
}
