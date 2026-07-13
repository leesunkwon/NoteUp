package com.kotlinsun.noteup.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.kotlinsun.noteup.domain.model.ExportArtifact
import com.kotlinsun.noteup.domain.model.ExportFormat
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.repository.NoteRepository
import com.kotlinsun.noteup.rendering.PageRenderer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NoteExportService(
    context: Context,
    private val repository: NoteRepository,
    private val renderer: PageRenderer = PageRenderer(),
) {
    private val applicationContext = context.applicationContext
    private val directory = File(applicationContext.cacheDir, DIRECTORY_NAME)

    suspend fun exportPage(
        note: Note,
        pageId: Long,
        pageNumber: Int,
        format: ExportFormat,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ExportArtifact = withContext(Dispatchers.IO) {
        require(format != ExportFormat.PDF)
        prepareDirectory()
        val page = requireNotNull(repository.getPage(pageId))
        val strokes = repository.getStrokes(pageId)
        val texts = repository.getTexts(pageId)
        val displayName = "${safeTitle(note.title)}_페이지_${pageNumber}_${timestamp()}.${format.extension}"
        val destination = File(directory, displayName)
        val temporary = File(directory, "$displayName.tmp")
        runCatching {
            val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                renderer.draw(
                    Canvas(bitmap), IMAGE_WIDTH, IMAGE_HEIGHT, IMAGE_DENSITY,
                    page.templateType, strokes, texts,
                )
                FileOutputStream(temporary).use { output ->
                    val compressFormat = if (format == ExportFormat.PNG) {
                        Bitmap.CompressFormat.PNG
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    check(bitmap.compress(compressFormat, WEBP_QUALITY, output))
                    output.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            replaceAtomically(temporary, destination)
            onProgress(1, 1)
            ExportArtifact(destination, displayName, format.mimeType, format)
        }.getOrElse {
            temporary.delete()
            destination.delete()
            throw it
        }
    }

    suspend fun exportNotePdf(
        note: Note,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ExportArtifact = withContext(Dispatchers.IO) {
        prepareDirectory()
        val pages = repository.getPages(note.id).sortedBy { it.pageIndex }
        require(pages.isNotEmpty())
        val displayName = "${safeTitle(note.title)}_${timestamp()}.pdf"
        val destination = File(directory, displayName)
        val temporary = File(directory, "$displayName.tmp")
        val document = PdfDocument()
        try {
            runCatching {
            pages.forEachIndexed { index, page ->
                val strokes = repository.getStrokes(page.id)
                val texts = repository.getTexts(page.id)
                val info = PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, index + 1).create()
                val pdfPage = document.startPage(info)
                try {
                    pdfPage.canvas.drawColor(Color.WHITE)
                    pdfPage.canvas.save()
                    try {
                        pdfPage.canvas.translate(0f, PDF_CONTENT_TOP)
                        renderer.draw(
                            pdfPage.canvas,
                            PDF_WIDTH,
                            PDF_CONTENT_HEIGHT,
                            PDF_EXPORT_DENSITY,
                            page.templateType,
                            strokes,
                            texts,
                        )
                    } finally {
                        pdfPage.canvas.restore()
                    }
                } finally {
                    document.finishPage(pdfPage)
                }
                onProgress(index + 1, pages.size)
            }
            FileOutputStream(temporary).use { output ->
                document.writeTo(output)
                output.fd.sync()
            }
            replaceAtomically(temporary, destination)
            ExportArtifact(destination, displayName, ExportFormat.PDF.mimeType, ExportFormat.PDF)
            }.getOrElse {
                temporary.delete()
                destination.delete()
                throw it
            }
        } finally {
            document.close()
        }
    }

    suspend fun copyTo(artifact: ExportArtifact, uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = applicationContext.contentResolver
        runCatching {
            resolver.openOutputStream(uri, "w")?.use { output ->
                artifact.file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open destination")
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            throw it
        }
    }

    private fun prepareDirectory() {
        check(directory.exists() || directory.mkdirs())
        val now = System.currentTimeMillis()
        directory.listFiles().orEmpty().filter { file ->
            file.name.endsWith(".tmp") || now - file.lastModified() >= MAX_CACHE_AGE_MILLIS
        }.forEach(File::delete)
        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .drop(MAX_CACHE_FILES - 1)
            .forEach(File::delete)
    }

    private fun replaceAtomically(temporary: File, destination: File) {
        if (destination.exists()) check(destination.delete())
        check(temporary.renameTo(destination))
    }

    private fun safeTitle(value: String): String {
        val sanitized = value.replace(INVALID_FILE_CHARACTERS, "_")
            .replace(CONTROL_CHARACTERS, "_")
            .trim(' ', '.')
            .take(MAX_TITLE_LENGTH)
        return sanitized.ifBlank { DEFAULT_FILE_NAME }
    }

    private fun timestamp(): String = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date())

    private companion object {
        const val DIRECTORY_NAME = "note_exports"
        const val IMAGE_WIDTH = 2560
        const val IMAGE_HEIGHT = 1440
        const val IMAGE_DENSITY = 2f
        const val WEBP_QUALITY = 90
        const val PDF_WIDTH = 842
        const val PDF_HEIGHT = 595
        const val PDF_CONTENT_HEIGHT = 474
        const val PDF_CONTENT_TOP = (PDF_HEIGHT - PDF_CONTENT_HEIGHT) / 2f
        const val PDF_EXPORT_DENSITY = PDF_WIDTH / 1280f
        const val MAX_CACHE_FILES = 20
        const val MAX_CACHE_AGE_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_TITLE_LENGTH = 80
        const val DEFAULT_FILE_NAME = "NoteUp"
        const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"
        val INVALID_FILE_CHARACTERS = Regex("[\\\\/:*?\"<>|]")
        val CONTROL_CHARACTERS = Regex("[\\x00-\\x1F\\x7F]")
    }
}
