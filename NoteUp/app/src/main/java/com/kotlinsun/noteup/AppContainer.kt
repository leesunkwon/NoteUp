package com.kotlinsun.noteup

import android.content.Context
import androidx.room.Room
import com.kotlinsun.noteup.data.local.DatabaseMigrations
import com.kotlinsun.noteup.data.local.NoteUpDatabase
import com.kotlinsun.noteup.data.preferences.AppSettingsStore
import com.kotlinsun.noteup.data.preferences.CustomColorPaletteStore
import com.kotlinsun.noteup.data.preferences.DrawingToolSettingsStore
import com.kotlinsun.noteup.data.preferences.OnboardingPreferencesStore
import com.kotlinsun.noteup.data.repository.LocalNoteRepository
import com.kotlinsun.noteup.domain.repository.NoteRepository
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailService
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
import com.kotlinsun.noteup.data.preferences.TrashRetentionStore
import com.kotlinsun.noteup.data.trash.TrashCleanupService
import com.kotlinsun.noteup.data.export.NoteExportService
import com.kotlinsun.noteup.data.pdf.PdfDocumentStore
import com.kotlinsun.noteup.data.pdf.PdfImportService
import com.kotlinsun.noteup.data.pdf.PdfPageRenderStore
import com.kotlinsun.noteup.data.image.CanvasImageStore
import com.kotlinsun.noteup.data.preferences.VersionHistoryStore
import com.kotlinsun.noteup.data.recovery.RecoveryJournal
import com.kotlinsun.noteup.data.recovery.RecoveryService
import com.kotlinsun.noteup.data.version.PageSnapshotStore
import com.kotlinsun.noteup.data.version.PageVersionService
import com.kotlinsun.noteup.data.storage.StorageUsageService
import com.kotlinsun.noteup.data.ai.AiModelManager
import com.kotlinsun.noteup.data.ai.AiModelCatalog
import com.kotlinsun.noteup.data.ai.LiteRtOnDeviceAiEngine
import com.kotlinsun.noteup.data.ai.LocalOnDeviceAiRepository
import com.kotlinsun.noteup.domain.ai.OnDeviceAiRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(
    context: Context,
    val appSettingsStore: AppSettingsStore,
) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        NoteUpDatabase::class.java,
        "noteup.db",
    ).addMigrations(
        DatabaseMigrations.MIGRATION_1_2,
        DatabaseMigrations.MIGRATION_2_3,
        DatabaseMigrations.MIGRATION_3_4,
        DatabaseMigrations.MIGRATION_4_5,
        DatabaseMigrations.MIGRATION_5_6,
        DatabaseMigrations.MIGRATION_6_7,
    ).build()

    val noteRepository: NoteRepository = LocalNoteRepository(database)
    val pdfDocumentStore = PdfDocumentStore(context)
    val pdfPageRenderStore = PdfPageRenderStore(context, pdfDocumentStore)
    val pdfImportService = PdfImportService(context, noteRepository, pdfDocumentStore)
    val canvasImageStore = CanvasImageStore(context)
    val pageThumbnailStore = PageThumbnailStore(context)
    val pageThumbnailService = PageThumbnailService(
        noteRepository, pageThumbnailStore, pdfPageRenderStore, canvasImageStore,
    )
    val versionHistoryStore = VersionHistoryStore(context)
    val pageSnapshotStore = PageSnapshotStore(context)
    val pageVersionService = PageVersionService(
        noteRepository,
        pageSnapshotStore,
        pageThumbnailStore,
        pageThumbnailService,
        versionHistoryStore,
    )
    val recoveryJournal = RecoveryJournal(context)
    val recoveryService = RecoveryService(
        noteRepository, recoveryJournal, pageThumbnailService, pageVersionService,
    )
    val noteExportService = NoteExportService(
        context, noteRepository, pdfPageRenderStore, canvasImageStore,
    )
    val storageUsageService = StorageUsageService(
        context,
        pageThumbnailStore,
        noteExportService,
        pdfPageRenderStore,
        canvasImageStore,
        recoveryJournal,
        pageVersionService,
    )
    val trashRetentionStore = TrashRetentionStore(context)
    val trashCleanupService = TrashCleanupService(
        noteRepository, trashRetentionStore, pageThumbnailService, pdfDocumentStore,
        pdfPageRenderStore, canvasImageStore,
    ).also { it.request() }
    val drawingToolSettingsStore = DrawingToolSettingsStore(context)
    val customColorPaletteStore = CustomColorPaletteStore(context)
    val onboardingPreferencesStore = OnboardingPreferencesStore(context)
    private val aiModelManager = AiModelManager(context)
    private val onDeviceAiEngine = LiteRtOnDeviceAiEngine(
        modelPathProvider = aiModelManager::readyModelPath,
        cacheDir = File(context.cacheDir, AiModelCatalog.ENGINE_CACHE_DIRECTORY_NAME),
    )
    val onDeviceAiRepository: OnDeviceAiRepository = LocalOnDeviceAiRepository(
        context = context,
        modelManager = aiModelManager,
        engine = onDeviceAiEngine,
    )

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            pageVersionService.cleanupOrphans()
            pdfDocumentStore.cleanupOrphans(noteRepository.getReferencedPdfStorageNames())
            canvasImageStore.cleanupOrphans(
                noteRepository.getReferencedImageStorageNames() +
                    pageVersionService.referencedImageStorageNames(),
            )
            recoveryJournal.cleanupExpired(System.currentTimeMillis() - RECOVERY_RETENTION_MILLIS)
            noteRepository.pruneAppliedRecoveryOperations(
                System.currentTimeMillis() - RECOVERY_RETENTION_MILLIS,
            )
        }
    }

    private companion object {
        const val RECOVERY_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    fun trimMemory(level: Int) {
        storageUsageService.trimMemory()
        onDeviceAiRepository.trimMemory(level)
    }
}
