package com.kotlinsun.noteup.data.pdf

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfDocumentStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    suspend fun copyFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        ensureDirectory()
        val storageName = "${UUID.randomUUID()}.pdf"
        val destination = File(directory, storageName)
        val temporary = File(directory, "$storageName.tmp")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: error("Unable to open PDF")
            check(temporary.length() > 0L)
            check(temporary.renameTo(destination))
            storageName
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    fun file(storageName: String): File {
        require(STORAGE_NAME.matches(storageName))
        return File(directory, storageName)
    }

    suspend fun delete(storageName: String) = withContext(Dispatchers.IO) {
        runCatching { file(storageName).delete() }
    }

    suspend fun cleanupOrphans(referenced: Set<String>) = withContext(Dispatchers.IO) {
        ensureDirectory()
        directory.listFiles().orEmpty().forEach { file ->
            if (file.name.endsWith(".tmp") || file.name !in referenced) file.delete()
        }
    }

    private fun ensureDirectory() = check(directory.exists() || directory.mkdirs())

    private companion object {
        const val DIRECTORY_NAME = "imported_pdfs"
        val STORAGE_NAME = Regex("[0-9a-fA-F-]+\\.pdf")
    }
}
