package com.kotlinsun.noteup.data.recovery

import android.content.Context
import com.kotlinsun.noteup.domain.model.RecoveryEntry
import com.kotlinsun.noteup.domain.model.RecoverySummary
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.model.StrokePoint
import com.kotlinsun.noteup.domain.model.StrokeTool
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecoveryJournal(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    suspend fun write(entry: RecoveryEntry) = withContext(Dispatchers.IO) {
        check(directory.exists() || directory.mkdirs())
        val target = file(entry.operationId)
        val temporary = File(directory, "${target.name}.tmp")
        try {
            DataOutputStream(GZIPOutputStream(FileOutputStream(temporary))).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeUTF(entry.operationId)
                output.writeLong(entry.noteId)
                output.writeLong(entry.pageId)
                output.writeLong(entry.createdAt)
                output.writeUTF(entry.stroke.tool.name)
                output.writeInt(entry.stroke.colorArgb)
                output.writeFloat(entry.stroke.width)
                output.writeInt(entry.stroke.points.size)
                entry.stroke.points.forEach { point ->
                    output.writeFloat(point.x)
                    output.writeFloat(point.y)
                    output.writeFloat(point.pressure)
                    output.writeInt(point.timeOffsetMillis)
                }
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun entries(): List<RecoveryEntry> = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext emptyList()
        directory.listFiles().orEmpty()
            .filter { it.extension == FILE_EXTENSION }
            .mapNotNull(::readOrDeleteCorrupt)
            .sortedBy(RecoveryEntry::createdAt)
    }

    suspend fun summary(): RecoverySummary? {
        val entries = entries()
        if (entries.isEmpty()) return null
        return RecoverySummary(
            entryCount = entries.size,
            noteCount = entries.map(RecoveryEntry::noteId).distinct().size,
            newestAt = entries.maxOf(RecoveryEntry::createdAt),
        )
    }

    suspend fun delete(operationIds: Collection<String>) = withContext(Dispatchers.IO) {
        operationIds.forEach { file(it).delete() }
    }

    suspend fun discardAll() = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty().forEach(File::delete)
    }

    suspend fun cleanupExpired(cutoff: Long) = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty()
            .filter { it.lastModified() in 1 until cutoff }
            .forEach(File::delete)
    }

    fun sizeBytes(): Long = directory.listFiles().orEmpty().sumOf(File::length)

    private fun readOrDeleteCorrupt(file: File): RecoveryEntry? = runCatching {
        DataInputStream(GZIPInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            val operationId = input.readUTF()
            val noteId = input.readLong()
            val pageId = input.readLong()
            val createdAt = input.readLong()
            val tool = StrokeTool.valueOf(input.readUTF())
            val color = input.readInt()
            val width = input.readFloat()
            val pointCount = input.readInt()
            require(pointCount in 2..MAX_POINTS)
            val points = List(pointCount) {
                StrokePoint(
                    x = input.readFloat(),
                    y = input.readFloat(),
                    pressure = input.readFloat(),
                    timeOffsetMillis = input.readInt(),
                )
            }
            RecoveryEntry(
                operationId = operationId,
                noteId = noteId,
                pageId = pageId,
                createdAt = createdAt,
                stroke = StrokeDraft(tool, color, width, points),
            )
        }
    }.getOrElse {
        file.delete()
        null
    }

    private fun file(operationId: String) = File(
        directory,
        operationId.filter { it.isLetterOrDigit() || it == '-' } + ".$FILE_EXTENSION",
    )

    private companion object {
        const val DIRECTORY_NAME = "recovery_journal"
        const val FILE_EXTENSION = "nrj"
        const val FORMAT_VERSION = 1
        const val MAX_POINTS = 1_000_000
    }
}
