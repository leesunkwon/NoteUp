package com.kotlinsun.noteup.data.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import com.kotlinsun.noteup.domain.model.PdfPageBackground
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PdfDisplayRenderResult(
    val bitmap: Bitmap,
    val longEdge: Int,
    val fallbackLevel: Int,
)

data class PdfTileRenderResult(
    val bitmap: Bitmap,
    val tileX: Int,
    val tileY: Int,
    val gridSize: Int,
)

class PdfPageRenderStore(
    context: Context,
    private val documentStore: PdfDocumentStore,
) {
    private val renderMutex = Mutex()
    private val displayCacheBytes = calculateDisplayCacheBytes(context)
    private val displayCache = object : LruCache<String, PdfDisplayRenderResult>(
        displayCacheBytes * DISPLAY_CACHE_SHARE / CACHE_SHARE_TOTAL,
    ) {
        override fun sizeOf(key: String, value: PdfDisplayRenderResult): Int =
            value.bitmap.allocationByteCount
    }
    private val tileCache = object : LruCache<String, Bitmap>(
        displayCacheBytes * TILE_CACHE_SHARE / CACHE_SHARE_TOTAL,
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun renderDisplay(
        background: PdfPageBackground,
        requestedLongEdge: Int,
    ): PdfDisplayRenderResult = withContext(Dispatchers.IO) {
        val targetLongEdge = requestedLongEdge.coerceIn(
            MIN_RENDER_EDGE,
            maximumDisplayLongEdge(background),
        )
        val key = cacheKey(background, targetLongEdge)
        synchronized(displayCache) { displayCache.get(key) }
            ?.takeUnless { it.bitmap.isRecycled }
            ?.let { return@withContext it }

        renderMutex.withLock {
            synchronized(displayCache) { displayCache.get(key) }
                ?.takeUnless { it.bitmap.isRecycled }
                ?.let { return@withLock it }
            val rendered = renderWithFallback(
                background,
                targetLongEdge,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            synchronized(displayCache) { displayCache.put(key, rendered) }
            rendered
        }
    }

    suspend fun renderThumbnail(
        background: PdfPageBackground,
        longEdge: Int,
    ): Bitmap = renderOwned(background, longEdge, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

    suspend fun renderForExport(
        background: PdfPageBackground,
        longEdge: Int,
    ): Bitmap = renderOwned(background, longEdge, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

    suspend fun renderDisplayTile(
        background: PdfPageBackground,
        tileX: Int,
        tileY: Int,
        gridSize: Int,
    ): PdfTileRenderResult = withContext(Dispatchers.IO) {
        require(gridSize in SUPPORTED_TILE_GRIDS && tileX in 0 until gridSize && tileY in 0 until gridSize)
        val key = "tile:${cacheKey(background, gridSize)}:$tileX:$tileY"
        synchronized(tileCache) { tileCache.get(key) }
            ?.takeUnless { it.isRecycled }
            ?.let { return@withContext PdfTileRenderResult(it, tileX, tileY, gridSize) }
        renderMutex.withLock {
            synchronized(tileCache) { tileCache.get(key) }
                ?.takeUnless { it.isRecycled }
                ?.let { return@withLock PdfTileRenderResult(it, tileX, tileY, gridSize) }
            val bitmap = renderTile(background, tileX, tileY, gridSize)
            synchronized(tileCache) { tileCache.put(key, bitmap) }
            PdfTileRenderResult(bitmap, tileX, tileY, gridSize)
        }
    }

    fun evict(storageName: String) {
        synchronized(displayCache) {
            displayCache.snapshot().keys
                .filter { it.startsWith("$storageName:") }
                .forEach(displayCache::remove)
        }
        synchronized(tileCache) {
            tileCache.snapshot().keys
                .filter { it.contains(":$storageName:") }
                .forEach(tileCache::remove)
        }
    }

    fun clearMemory() {
        synchronized(displayCache) { displayCache.evictAll() }
        synchronized(tileCache) { tileCache.evictAll() }
    }

    private fun renderTile(
        background: PdfPageBackground,
        tileX: Int,
        tileY: Int,
        gridSize: Int,
    ): Bitmap {
        val source = documentStore.file(background.storageName)
        android.os.ParcelFileDescriptor.open(
            source,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(background.sourcePageIndex).use { page ->
                    val fullLongEdge = TILE_EDGE * gridSize
                    val ratio = page.width.toFloat() / page.height.coerceAtLeast(1)
                    val fullWidth = if (ratio >= 1f) fullLongEdge else (fullLongEdge * ratio).roundToInt()
                    val fullHeight = if (ratio >= 1f) (fullLongEdge / ratio).roundToInt() else fullLongEdge
                    val left = fullWidth * tileX / gridSize
                    val top = fullHeight * tileY / gridSize
                    val right = fullWidth * (tileX + 1) / gridSize
                    val bottom = fullHeight * (tileY + 1) / gridSize
                    val bitmap = Bitmap.createBitmap(
                        (right - left).coerceAtLeast(1),
                        (bottom - top).coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        val matrix = Matrix().apply {
                            setScale(
                                fullWidth.toFloat() / page.width.coerceAtLeast(1),
                                fullHeight.toFloat() / page.height.coerceAtLeast(1),
                            )
                            postTranslate(-left.toFloat(), -top.toFloat())
                        }
                        page.render(
                            bitmap,
                            Rect(0, 0, bitmap.width, bitmap.height),
                            matrix,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        return bitmap
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
        }
    }

    private suspend fun renderOwned(
        background: PdfPageBackground,
        requestedLongEdge: Int,
        renderMode: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            renderWithFallback(
                background,
                requestedLongEdge.coerceIn(MIN_RENDER_EDGE, ABSOLUTE_MAX_RENDER_EDGE),
                renderMode,
            ).bitmap
        }
    }

    private fun renderWithFallback(
        background: PdfPageBackground,
        initialLongEdge: Int,
        renderMode: Int,
    ): PdfDisplayRenderResult {
        var longEdge = initialLongEdge
        var fallbackLevel = 0
        while (true) {
            try {
                return PdfDisplayRenderResult(
                    bitmap = renderPage(background, longEdge, renderMode),
                    longEdge = longEdge,
                    fallbackLevel = fallbackLevel,
                )
            } catch (error: OutOfMemoryError) {
                if (fallbackLevel >= MAX_OOM_RETRIES || longEdge <= MIN_RENDER_EDGE) throw error
                fallbackLevel += 1
                longEdge = (longEdge * OOM_FALLBACK_RATIO).roundToInt()
                    .coerceAtLeast(MIN_RENDER_EDGE)
            }
        }
    }

    private fun renderPage(
        background: PdfPageBackground,
        longEdge: Int,
        renderMode: Int,
    ): Bitmap {
        val ratio = pageRatio(background)
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
                android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(background.sourcePageIndex).use { page ->
                            page.render(bitmap, null, null, renderMode)
                        }
                    }
                }
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun maximumDisplayLongEdge(background: PdfPageBackground): Int {
        val aspectFactor = min(pageRatio(background), 1f / pageRatio(background))
            .coerceAtLeast(MIN_ASPECT_FACTOR)
        val displayBudgetBytes = displayCacheBytes * DISPLAY_CACHE_SHARE / CACHE_SHARE_TOTAL
        val pageBudgetBytes = displayBudgetBytes * DISPLAY_PAGE_BUDGET_RATIO
        val maximumPixels = pageBudgetBytes / BYTES_PER_PIXEL
        return sqrt((maximumPixels / aspectFactor).toDouble()).toInt()
            .coerceIn(MIN_RENDER_EDGE, ABSOLUTE_MAX_RENDER_EDGE)
    }

    private fun pageRatio(background: PdfPageBackground): Float =
        background.widthPoints.toFloat() / background.heightPoints.coerceAtLeast(1)

    private fun cacheKey(background: PdfPageBackground, longEdge: Int) =
        "${background.storageName}:${background.sourcePageIndex}:$longEdge"

    private fun calculateDisplayCacheBytes(context: Context): Int {
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryBytes = manager.memoryClass.toLong() * BYTES_PER_MEGABYTE
        return (memoryBytes / DISPLAY_CACHE_MEMORY_DIVISOR)
            .coerceIn(MIN_DISPLAY_CACHE_BYTES.toLong(), MAX_DISPLAY_CACHE_BYTES.toLong())
            .toInt()
    }

    private companion object {
        const val MIN_RENDER_EDGE = 512
        const val ABSOLUTE_MAX_RENDER_EDGE = 8192
        const val MAX_OOM_RETRIES = 3
        const val OOM_FALLBACK_RATIO = 0.75f
        const val DISPLAY_CACHE_MEMORY_DIVISOR = 4
        const val DISPLAY_PAGE_BUDGET_RATIO = 0.75f
        const val BYTES_PER_PIXEL = 4f
        const val BYTES_PER_MEGABYTE = 1024L * 1024L
        const val MIN_DISPLAY_CACHE_BYTES = 48 * 1024 * 1024
        const val MAX_DISPLAY_CACHE_BYTES = 128 * 1024 * 1024
        const val MIN_ASPECT_FACTOR = 0.1f
        const val TILE_EDGE = 1024
        const val DISPLAY_CACHE_SHARE = 2
        const val TILE_CACHE_SHARE = 1
        const val CACHE_SHARE_TOTAL = DISPLAY_CACHE_SHARE + TILE_CACHE_SHARE
        val SUPPORTED_TILE_GRIDS = setOf(2, 4, 8)
    }
}
