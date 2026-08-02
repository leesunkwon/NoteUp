package com.kotlinsun.noteup.data.storage

import android.content.Context
import android.os.StatFs
import com.kotlinsun.noteup.data.export.NoteExportService
import com.kotlinsun.noteup.data.image.CanvasImageStore
import com.kotlinsun.noteup.data.pdf.PdfPageRenderStore
import com.kotlinsun.noteup.data.recovery.RecoveryJournal
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
import com.kotlinsun.noteup.data.version.PageVersionService
import com.kotlinsun.noteup.domain.model.StorageUsage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageUsageService(
    context: Context,
    private val thumbnailStore: PageThumbnailStore,
    private val exportService: NoteExportService,
    private val pdfRenderStore: PdfPageRenderStore,
    private val imageStore: CanvasImageStore,
    private val recoveryJournal: RecoveryJournal,
    private val versionService: PageVersionService,
) {
    private val applicationContext = context.applicationContext

    suspend fun measure(): StorageUsage = withContext(Dispatchers.IO) {
        val cache = thumbnailStore.sizeBytes() + exportService.cacheSizeBytes()
        val recovery = recoveryJournal.sizeBytes()
        val versions = versionService.sizeBytes()
        StorageUsage(
            totalBytes = directorySize(applicationContext.filesDir) +
                directorySize(applicationContext.noBackupFilesDir) +
                directorySize(applicationContext.cacheDir),
            cacheBytes = cache,
            recoveryBytes = recovery,
            versionBytes = versions,
            availableBytes = StatFs(applicationContext.filesDir.path).availableBytes,
        )
    }

    suspend fun clearRegenerableCaches() {
        thumbnailStore.clearFiles()
        exportService.clearCache()
        pdfRenderStore.clearMemory()
        imageStore.clearMemory()
    }

    fun trimMemory() {
        thumbnailStore.clearMemory()
        pdfRenderStore.clearMemory()
        imageStore.clearMemory()
    }

    private fun directorySize(directory: File): Long = directory.listFiles().orEmpty().sumOf {
        if (it.isDirectory) directorySize(it) else it.length()
    }
}
