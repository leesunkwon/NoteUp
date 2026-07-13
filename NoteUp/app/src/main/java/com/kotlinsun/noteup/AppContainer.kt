package com.kotlinsun.noteup

import android.content.Context
import androidx.room.Room
import com.kotlinsun.noteup.data.local.NoteUpDatabase
import com.kotlinsun.noteup.data.local.DatabaseMigrations
import com.kotlinsun.noteup.data.repository.LocalNoteRepository
import com.kotlinsun.noteup.data.preferences.DrawingToolSettingsStore
import com.kotlinsun.noteup.domain.repository.NoteRepository
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailService
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
import com.kotlinsun.noteup.data.preferences.TrashRetentionStore
import com.kotlinsun.noteup.data.trash.TrashCleanupService

class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        NoteUpDatabase::class.java,
        "noteup.db",
    ).addMigrations(
        DatabaseMigrations.MIGRATION_1_2,
        DatabaseMigrations.MIGRATION_2_3,
        DatabaseMigrations.MIGRATION_3_4,
    ).build()

    val noteRepository: NoteRepository = LocalNoteRepository(database)
    val pageThumbnailStore = PageThumbnailStore(context)
    val pageThumbnailService = PageThumbnailService(noteRepository, pageThumbnailStore)
    val trashRetentionStore = TrashRetentionStore(context)
    val trashCleanupService = TrashCleanupService(
        noteRepository, trashRetentionStore, pageThumbnailService,
    ).also { it.request() }
    val drawingToolSettingsStore = DrawingToolSettingsStore(context)
}
