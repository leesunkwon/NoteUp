package com.kotlinsun.noteup.data.version

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kotlinsun.noteup.domain.model.CanvasImage
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.PageSnapshot
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokePoint
import com.kotlinsun.noteup.domain.model.StrokeTool
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PageSnapshotStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    suspend fun write(snapshot: PageSnapshot, preview: Bitmap?): StoredPageSnapshot =
        withContext(Dispatchers.IO) {
            check(directory.exists() || directory.mkdirs())
            val token = UUID.randomUUID().toString()
            val snapshotName = "snapshot_$token.bin.gz"
            val previewName = "preview_$token.webp"
            val snapshotFile = File(directory, snapshotName)
            val snapshotTemp = File(directory, "$snapshotName.tmp")
            val previewFile = File(directory, previewName)
            val previewTemp = File(directory, "$previewName.tmp")
            try {
                DataOutputStream(GZIPOutputStream(FileOutputStream(snapshotTemp))).use { output ->
                    encode(output, snapshot)
                }
                atomicReplace(snapshotTemp, snapshotFile)
                if (preview != null) {
                    FileOutputStream(previewTemp).use { output ->
                        check(preview.compress(Bitmap.CompressFormat.WEBP, PREVIEW_QUALITY, output))
                        output.fd.sync()
                    }
                    atomicReplace(previewTemp, previewFile)
                }
                StoredPageSnapshot(snapshotName, previewName.takeIf { preview != null }.orEmpty())
            } catch (error: Throwable) {
                snapshotTemp.delete()
                previewTemp.delete()
                snapshotFile.delete()
                previewFile.delete()
                throw error
            }
        }

    suspend fun read(snapshotName: String): PageSnapshot? = withContext(Dispatchers.IO) {
        val source = safeFile(snapshotName).takeIf(File::isFile) ?: return@withContext null
        runCatching {
            DataInputStream(GZIPInputStream(FileInputStream(source))).use(::decode)
        }.getOrNull()
    }

    suspend fun loadPreview(previewName: String): Bitmap? = withContext(Dispatchers.IO) {
        if (previewName.isBlank()) return@withContext null
        safeFile(previewName).takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.path) }
    }

    suspend fun delete(snapshotNames: Collection<String>, previewNames: Collection<String>) =
        withContext(Dispatchers.IO) {
            (snapshotNames + previewNames).filter(String::isNotBlank).forEach { safeFile(it).delete() }
        }

    suspend fun cleanupOrphans(referencedNames: Set<String>) = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".tmp") || it.name !in referencedNames }
            .forEach(File::delete)
    }

    suspend fun referencedImageNames(snapshotNames: Collection<String>): Set<String> =
        snapshotNames.mapNotNull { read(it) }.flatMap { snapshot ->
            snapshot.images.map(CanvasImage::storageName)
        }.toSet()

    fun sizeBytes(): Long = directory.listFiles().orEmpty().sumOf(File::length)

    private fun encode(output: DataOutputStream, snapshot: PageSnapshot) {
        output.writeInt(FORMAT_VERSION)
        output.writeLong(snapshot.pageId)
        output.writeUTF(snapshot.template.name)
        output.writeInt(snapshot.strokes.size)
        snapshot.strokes.forEach { stroke ->
            output.writeLong(stroke.id)
            output.writeLong(stroke.pageId)
            output.writeInt(stroke.strokeIndex)
            output.writeUTF(stroke.tool.name)
            output.writeInt(stroke.colorArgb)
            output.writeFloat(stroke.width)
            output.writeLong(stroke.createdAt)
            output.writeInt(stroke.points.size)
            stroke.points.forEach { point ->
                output.writeFloat(point.x)
                output.writeFloat(point.y)
                output.writeFloat(point.pressure)
                output.writeInt(point.timeOffsetMillis)
            }
        }
        output.writeInt(snapshot.texts.size)
        snapshot.texts.forEach { text ->
            output.writeLong(text.id)
            output.writeLong(text.pageId)
            output.writeInt(text.elementIndex)
            output.writeFloat(text.x)
            output.writeFloat(text.y)
            output.writeFloat(text.boxWidth)
            output.writeString(text.content)
            output.writeInt(text.colorArgb)
            output.writeFloat(text.textSizeSp)
            output.writeLong(text.createdAt)
            output.writeLong(text.updatedAt)
        }
        output.writeInt(snapshot.images.size)
        snapshot.images.forEach { image ->
            output.writeLong(image.id)
            output.writeLong(image.pageId)
            output.writeInt(image.elementIndex)
            output.writeString(image.storageName)
            output.writeInt(image.originalWidth)
            output.writeInt(image.originalHeight)
            output.writeInt(image.orientationDegrees)
            output.writeFloat(image.x)
            output.writeFloat(image.y)
            output.writeFloat(image.boxWidth)
            output.writeFloat(image.boxHeight)
            output.writeLong(image.createdAt)
            output.writeLong(image.updatedAt)
        }
    }

    private fun decode(input: DataInputStream): PageSnapshot {
        require(input.readInt() == FORMAT_VERSION)
        val pageId = input.readLong()
        val template = PageTemplate.valueOf(input.readUTF())
        val strokes = List(input.readSafeCount(MAX_ELEMENTS)) {
            Stroke(
                id = input.readLong(),
                pageId = input.readLong(),
                strokeIndex = input.readInt(),
                tool = StrokeTool.valueOf(input.readUTF()),
                colorArgb = input.readInt(),
                width = input.readFloat(),
                createdAt = input.readLong(),
                points = List(input.readSafeCount(MAX_POINTS)) {
                    StrokePoint(input.readFloat(), input.readFloat(), input.readFloat(), input.readInt())
                },
            )
        }
        val texts = List(input.readSafeCount(MAX_ELEMENTS)) {
            CanvasText(
                input.readLong(), input.readLong(), input.readInt(), input.readFloat(),
                input.readFloat(), input.readFloat(), input.readString(), input.readInt(),
                input.readFloat(), input.readLong(), input.readLong(),
            )
        }
        val images = List(input.readSafeCount(MAX_ELEMENTS)) {
            CanvasImage(
                input.readLong(), input.readLong(), input.readInt(), input.readString(),
                input.readInt(), input.readInt(), input.readInt(), input.readFloat(),
                input.readFloat(), input.readFloat(), input.readFloat(), input.readLong(), input.readLong(),
            )
        }
        return PageSnapshot(pageId, template, strokes, texts, images)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readSafeCount(MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readSafeCount(maximum: Int): Int = readInt().also {
        require(it in 0..maximum)
    }

    private fun safeFile(name: String): File {
        require(name.isNotBlank() && name == File(name).name)
        return File(directory, name)
    }

    private fun atomicReplace(temporary: File, target: File) {
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    data class StoredPageSnapshot(val snapshotName: String, val previewName: String)

    private companion object {
        const val DIRECTORY_NAME = "page_versions"
        const val FORMAT_VERSION = 1
        const val PREVIEW_QUALITY = 85
        const val MAX_ELEMENTS = 1_000_000
        const val MAX_POINTS = 2_000_000
        const val MAX_STRING_BYTES = 4 * 1024 * 1024
    }
}
