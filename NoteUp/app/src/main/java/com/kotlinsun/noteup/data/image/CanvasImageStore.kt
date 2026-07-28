package com.kotlinsun.noteup.data.image

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import com.kotlinsun.noteup.domain.model.ImportedCanvasImage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CanvasImageStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val directory = File(applicationContext.noBackupFilesDir, DIRECTORY_NAME)
    private val cache = object : LruCache<String, Bitmap>(cacheBytes(context)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun importImage(uri: Uri): ImportedCanvasImage = withContext(Dispatchers.IO) {
        check(directory.exists() || directory.mkdirs())
        val storageName = "image_${UUID.randomUUID()}.bin"
        val destination = file(storageName)
        val temporary = File(directory, "$storageName.tmp")
        runCatching {
            val resolver = applicationContext.contentResolver
            val mimeType = resolver.getType(uri)
            require(mimeType == null || mimeType.startsWith("image/"))
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: error("Unable to open selected image")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
            check(temporary.renameTo(destination))
            ImportedCanvasImage(
                storageName = storageName,
                width = options.outWidth,
                height = options.outHeight,
                orientationDegrees = readOrientation(destination),
            )
        }.getOrElse { error ->
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    suspend fun load(storageName: String, requestedLongEdge: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val edgeBucket = requestedLongEdge.coerceIn(MIN_EDGE, MAX_EDGE)
                .let { ((it + EDGE_BUCKET - 1) / EDGE_BUCKET) * EDGE_BUCKET }
            val key = "$storageName:$edgeBucket"
            synchronized(cache) { cache.get(key) }
                ?.takeUnless { it.isRecycled }
                ?.let { return@withContext it }
            val source = file(storageName).takeIf(File::isFile) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= edgeBucket) {
                sampleSize *= 2
            }
            val orientation = readOrientation(source)
            val bitmap = decodeWithFallback(source, sampleSize, orientation)
                ?: return@withContext null
            synchronized(cache) { cache.put(key, bitmap) }
            bitmap
        }

    fun delete(storageName: String) {
        synchronized(cache) {
            cache.snapshot().keys.filter { it.startsWith("$storageName:") }.forEach(cache::remove)
        }
        file(storageName).delete()
    }

    suspend fun cleanupOrphans(referencedStorageNames: Set<String>) = withContext(Dispatchers.IO) {
        if (!directory.exists()) return@withContext
        directory.listFiles().orEmpty().filter { file ->
            file.name.endsWith(".tmp") || file.name !in referencedStorageNames
        }.forEach(File::delete)
    }

    fun file(storageName: String): File = File(directory, storageName)

    private fun decodeWithFallback(
        source: File,
        initialSampleSize: Int,
        orientation: Int,
    ): Bitmap? {
        var sampleSize = initialSampleSize
        repeat(MAXIMUM_DECODE_ATTEMPTS) { attempt ->
            var decoded: Bitmap? = null
            try {
                val sourceBitmap = BitmapFactory.decodeFile(
                    source.path,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ) ?: return null
                decoded = sourceBitmap
                if (orientation == 0) return sourceBitmap
                return Bitmap.createBitmap(
                    sourceBitmap,
                    0,
                    0,
                    sourceBitmap.width,
                    sourceBitmap.height,
                    Matrix().apply { postRotate(orientation.toFloat()) },
                    true,
                ).also { if (it !== sourceBitmap) sourceBitmap.recycle() }
            } catch (error: OutOfMemoryError) {
                decoded?.recycle()
                if (attempt == MAXIMUM_DECODE_ATTEMPTS - 1) throw error
                sampleSize *= 2
            }
        }
        return null
    }

    private fun readOrientation(file: File): Int = runCatching {
        when (ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun cacheBytes(context: Context): Int {
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return (manager.memoryClass * BYTES_PER_MEGABYTE / CACHE_DIVISOR)
            .coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
    }

    private companion object {
        const val DIRECTORY_NAME = "canvas_images"
        const val MIN_EDGE = 256
        const val MAX_EDGE = 4096
        const val EDGE_BUCKET = 256
        const val CACHE_DIVISOR = 8
        const val BYTES_PER_MEGABYTE = 1024 * 1024
        const val MIN_CACHE_BYTES = 16 * 1024 * 1024
        const val MAX_CACHE_BYTES = 64 * 1024 * 1024
        const val MAXIMUM_DECODE_ATTEMPTS = 3
    }
}
