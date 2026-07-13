package com.kotlinsun.noteup.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class PageThumbnailStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val memoryCache = object : LruCache<Long, Bitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }
    private val _revisions = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val revisions = _revisions.asStateFlow()

    suspend fun load(pageId: Long): Bitmap? = withContext(Dispatchers.IO) {
        memoryCache.get(pageId)?.takeUnless { it.isRecycled } ?: run {
            val bitmap = BitmapFactory.decodeFile(file(pageId).absolutePath)
            if (bitmap != null) memoryCache.put(pageId, bitmap)
            bitmap
        }
    }

    suspend fun write(pageId: Long, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = file(pageId)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, output))
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        memoryCache.put(pageId, bitmap)
        _revisions.update { it + (pageId to System.currentTimeMillis()) }
    }

    suspend fun delete(pageId: Long) = withContext(Dispatchers.IO) {
        memoryCache.remove(pageId)
        file(pageId).delete()
        _revisions.update { it - pageId }
    }

    fun exists(pageId: Long): Boolean = file(pageId).isFile

    private fun file(pageId: Long) = File(directory, "page_$pageId.webp")

    private companion object {
        const val DIRECTORY_NAME = "page_thumbnails"
        const val MEMORY_CACHE_KB = 16 * 1024
        const val WEBP_QUALITY = 85
    }
}
