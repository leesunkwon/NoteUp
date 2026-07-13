package com.kotlinsun.noteup.data.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.kotlinsun.noteup.domain.model.PdfImportError
import com.kotlinsun.noteup.domain.model.PdfImportPage
import com.kotlinsun.noteup.domain.model.PdfImportPreview
import com.kotlinsun.noteup.domain.repository.NoteRepository
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfImportException(val reason: PdfImportError, cause: Throwable? = null) : Exception(cause)

class PdfImportService(
    context: Context,
    private val repository: NoteRepository,
    private val documentStore: PdfDocumentStore,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun inspect(uri: Uri): PdfImportPreview = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        try {
            val header = resolver.openInputStream(uri)?.use { input ->
                ByteArray(PDF_HEADER_SCAN_BYTES).let { bytes ->
                    val count = input.read(bytes)
                    if (count <= 0) throw PdfImportException(PdfImportError.INVALID)
                    String(bytes, 0, count, Charsets.ISO_8859_1)
                }
            } ?: throw PdfImportException(PdfImportError.UNREADABLE)
            if (!header.contains(PDF_HEADER)) throw PdfImportException(PdfImportError.INVALID)
            val descriptor = resolver.openFileDescriptor(uri, "r")
                ?: throw PdfImportException(PdfImportError.UNREADABLE)
            descriptor.use { fileDescriptor ->
                PdfRenderer(fileDescriptor).use { renderer ->
                    if (renderer.pageCount == 0) throw PdfImportException(PdfImportError.EMPTY)
                    val pages = (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            PdfImportPage(index, page.width, page.height)
                        }
                    }
                    PdfImportPreview(uri, displayName, noteTitle(displayName), pages)
                }
            }
        } catch (error: PdfImportException) {
            throw error
        } catch (error: SecurityException) {
            throw PdfImportException(PdfImportError.PROTECTED, error)
        } catch (error: FileNotFoundException) {
            throw PdfImportException(PdfImportError.UNREADABLE, error)
        } catch (error: IOException) {
            throw PdfImportException(PdfImportError.INVALID, error)
        } catch (error: IllegalStateException) {
            throw PdfImportException(PdfImportError.INVALID, error)
        }
    }

    suspend fun import(preview: PdfImportPreview): Long {
        val storageName = try {
            documentStore.copyFrom(preview.uri)
        } catch (error: Throwable) {
            throw PdfImportException(PdfImportError.STORAGE, error)
        }
        return try {
            repository.createImportedPdfNote(
                preview.noteTitle, storageName, preview.displayName, preview.pages,
            )
        } catch (error: Throwable) {
            documentStore.delete(storageName)
            throw error
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf(String::isNotBlank) ?: DEFAULT_DISPLAY_NAME
    }

    private fun noteTitle(displayName: String): String {
        val withoutExtension = displayName.replace(PDF_EXTENSION, "")
        val sanitized = withoutExtension.replace(CONTROL_CHARACTERS, " ").trim().take(MAX_TITLE_LENGTH)
        return sanitized.ifBlank { DEFAULT_TITLE }
    }

    private companion object {
        const val DEFAULT_DISPLAY_NAME = "document.pdf"
        const val DEFAULT_TITLE = "PDF 문서"
        const val MAX_TITLE_LENGTH = 80
        val PDF_EXTENSION = Regex("\\.pdf$", RegexOption.IGNORE_CASE)
        val CONTROL_CHARACTERS = Regex("[\\x00-\\x1F\\x7F]")
        const val PDF_HEADER = "%PDF-"
        const val PDF_HEADER_SCAN_BYTES = 1024
    }
}
