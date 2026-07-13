package com.kotlinsun.noteup.data.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import com.kotlinsun.noteup.domain.model.PdfPageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class PdfPageRenderStore(private val documentStore: PdfDocumentStore) {
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun render(background: PdfPageBackground, requestedLongEdge: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val longEdge = requestedLongEdge.coerceIn(MIN_RENDER_EDGE, MAX_RENDER_EDGE)
            val key = "${background.storageName}:${background.sourcePageIndex}:$longEdge"
            synchronized(cache) { cache.get(key) }?.takeUnless(Bitmap::isRecycled)?.let { return@withContext it }
            mutex.withLock {
                synchronized(cache) { cache.get(key) }?.takeUnless(Bitmap::isRecycled)?.let {
                    return@withLock it
                }
                val ratio = background.widthPoints.toFloat() / background.heightPoints.coerceAtLeast(1)
                val bitmapWidth: Int
                val bitmapHeight: Int
                if (ratio >= 1f) {
                    bitmapWidth = longEdge
                    bitmapHeight = (longEdge / ratio).roundToInt().coerceAtLeast(1)
                } else {
                    bitmapHeight = longEdge
                    bitmapWidth = (longEdge * ratio).roundToInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                try {
                    documentStore.file(background.storageName).let { file ->
                        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                renderer.openPage(background.sourcePageIndex).use { page ->
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                    synchronized(cache) { cache.put(key, bitmap) }
                    bitmap
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                }
            }
        }

    fun evict(storageName: String) {
        synchronized(cache) {
            cache.snapshot().keys.filter { it.startsWith("$storageName:") }.forEach(cache::remove)
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 32 * 1024 * 1024
        const val MIN_RENDER_EDGE = 512
        const val MAX_RENDER_EDGE = 4096
    }
}
