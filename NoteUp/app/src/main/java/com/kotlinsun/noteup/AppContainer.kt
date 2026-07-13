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
import com.kotlinsun.noteup.data.export.NoteExportService
import com.kotlinsun.noteup.data.pdf.PdfDocumentStore
import com.kotlinsun.noteup.data.pdf.PdfImportService
import com.kotlinsun.noteup.data.pdf.PdfPageRenderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        NoteUpDatabase::class.java,
        "noteup.db",
    ).addMigrations(
        DatabaseMigrations.MIGRATION_1_2,
        DatabaseMigrations.MIGRATION_2_3,
        DatabaseMigrations.MIGRATION_3_4,
        DatabaseMigrations.MIGRATION_4_5,
    ).build()

    val noteRepository: NoteRepository = LocalNoteRepository(database)
    val pdfDocumentStore = PdfDocumentStore(context)
    val pdfPageRenderStore = PdfPageRenderStore(pdfDocumentStore)
    val pdfImportService = PdfImportService(context, noteRepository, pdfDocumentStore)
    val pageThumbnailStore = PageThumbnailStore(context)
    val pageThumbnailService = PageThumbnailService(
        noteRepository, pageThumbnailStore, pdfPageRenderStore,
    )
    val noteExportService = NoteExportService(context, noteRepository, pdfPageRenderStore)
    val trashRetentionStore = TrashRetentionStore(context)
    val trashCleanupService = TrashCleanupService(
        noteRepository, trashRetentionStore, pageThumbnailService, pdfDocumentStore, pdfPageRenderStore,
    ).also { it.request() }
    val drawingToolSettingsStore = DrawingToolSettingsStore(context)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            pdfDocumentStore.cleanupOrphans(noteRepository.getReferencedPdfStorageNames())
        }
    }
}
