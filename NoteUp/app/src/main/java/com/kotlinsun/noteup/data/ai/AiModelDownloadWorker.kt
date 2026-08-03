package com.kotlinsun.noteup.data.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotlinsun.noteup.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AiModelDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val model = AiModelCatalog.defaultModel
    private val files = AiModelFiles(appContext)
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadGeneration = inputData.getLong(KEY_DOWNLOAD_GENERATION, 0L)
        try {
            createNotificationChannel()
            notificationManager.cancel(TERMINAL_NOTIFICATION_ID)
            setForeground(createForegroundInfo(files.partialBytes(model), model.expectedBytes))

            if (
                inputData.getString(KEY_MODEL_ID) != model.id ||
                downloadGeneration <= 0L ||
                !files.isDownloadAttemptActive(downloadGeneration)
            ) {
                files.cleanupCancelledDownload(model)
                notifyTerminal(NotificationResult.CANCELLED)
                return@withContext failure(AiModelErrorKind.CANCELLED)
            }

            files.trackedReadyFile(model)?.let { readyFile ->
                notifyTerminal(NotificationResult.COMPLETE)
                return@withContext Result.success(successData(readyFile))
            }

            files.ensureModelDirectory()
            val existingFinal = files.finalFile(model)
            if (existingFinal.exists()) {
                publishVerifying()
                val verificationContext = currentCoroutineContext()
                when (
                    val verification = files.verifyInstalled(model) {
                        verificationContext.ensureActive()
                        ensureDownloadAttemptActive(downloadGeneration)
                    }
                ) {
                    is InstalledModelVerification.Ready -> {
                        files.deletePartialForAttemptOrThrow(model, downloadGeneration)
                        notifyTerminal(NotificationResult.COMPLETE)
                        return@withContext Result.success(successData(verification.file))
                    }

                    InstalledModelVerification.Changed -> {
                        ensureDownloadAttemptActive(downloadGeneration)
                        throw IOException("The installed model changed during verification")
                    }

                    InstalledModelVerification.Invalid,
                    InstalledModelVerification.Missing -> Unit
                }
            }

            ensureStorageCapacity()
            publishDownloadProgress(files.partialBytes(model))
            downloadModel(downloadGeneration)

            publishVerifying()
            val verificationContext = currentCoroutineContext()
            val verifiedPartial = files.verifyPartial(model) {
                verificationContext.ensureActive()
                ensureDownloadAttemptActive(downloadGeneration)
            }
            if (verifiedPartial == null) {
                ensureDownloadAttemptActive(downloadGeneration)
                files.deletePartialForAttemptOrThrow(model, downloadGeneration)
                throw VerificationFailure("The downloaded model failed SHA-256 verification")
            }

            currentCoroutineContext().ensureActive()
            ensureDownloadAttemptActive(downloadGeneration)
            val installedFile = files.promoteVerifiedPartial(
                artifact = model,
                verifiedFile = verifiedPartial,
                downloadGeneration = downloadGeneration,
            )
            notifyTerminal(NotificationResult.COMPLETE)
            Result.success(successData(installedFile))
        } catch (exception: CancellationException) {
            if (files.isCancelCleanupRequested()) {
                notifyTerminal(NotificationResult.CANCELLED)
            }
            throw exception
        } catch (exception: AiModelCancellationException) {
            if (files.isCancelCleanupRequested()) {
                notifyTerminal(NotificationResult.CANCELLED)
            }
            failure(AiModelErrorKind.CANCELLED)
        } catch (exception: Exception) {
            if (!files.isDownloadAttemptActive(downloadGeneration)) {
                if (files.isCancelCleanupRequested()) {
                    notifyTerminal(NotificationResult.CANCELLED)
                }
                failure(AiModelErrorKind.CANCELLED)
            } else {
                currentCoroutineContext().ensureActive()
                when (exception) {
                    is InsufficientStorageFailure -> {
                        notifyTerminal(NotificationResult.FAILED)
                        failure(AiModelErrorKind.INSUFFICIENT_STORAGE)
                    }

                    is TransientNetworkFailure -> {
                        if (runAttemptCount < MAX_RETRY_ATTEMPTS && !isStopped) {
                            Result.retry()
                        } else {
                            notifyTerminal(NotificationResult.FAILED)
                            failure(AiModelErrorKind.NETWORK)
                        }
                    }

                    is HttpFailure -> {
                        if (
                            exception.isTransient &&
                            runAttemptCount < MAX_RETRY_ATTEMPTS &&
                            !isStopped
                        ) {
                            Result.retry()
                        } else {
                            notifyTerminal(NotificationResult.FAILED)
                            failure(
                                if (exception.isTransient) AiModelErrorKind.NETWORK
                                else AiModelErrorKind.HTTP,
                            )
                        }
                    }

                    is VerificationFailure -> {
                        notifyTerminal(NotificationResult.FAILED)
                        failure(AiModelErrorKind.VERIFICATION)
                    }

                    is IOException,
                    is SecurityException,
                    is IllegalStateException -> {
                        notifyTerminal(NotificationResult.FAILED)
                        failure(AiModelErrorKind.FILE_SYSTEM)
                    }

                    else -> {
                        notifyTerminal(NotificationResult.FAILED)
                        failure(AiModelErrorKind.UNKNOWN)
                    }
                }
            }
        } finally {
            runCatching { files.cleanupCancelledDownload(model) }
        }
    }

    private suspend fun downloadModel(downloadGeneration: Long) {
        val partial = files.partialFile(model)
        if (partial.length() > model.expectedBytes) {
            files.deletePartialForAttemptOrThrow(model, downloadGeneration)
        }

        var requestedOffset = partial.length()
        var rangeResetPerformed = false
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureDownloadAttemptActive(downloadGeneration)
            ensureStorageCapacity()

            val connection = openFollowingRedirects(model.downloadUrl, requestedOffset)
            try {
                val responseCode = try {
                    connection.responseCode
                } catch (exception: IOException) {
                    throw TransientNetworkFailure(exception)
                }
                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        validateFullResponse(connection)
                        writeResponse(
                            connection = connection,
                            append = false,
                            initialBytes = 0L,
                            responseEndExclusive = model.expectedBytes,
                            downloadGeneration = downloadGeneration,
                        )
                        return
                    }

                    HttpURLConnection.HTTP_PARTIAL -> {
                        val responseEndExclusive = validatePartialResponse(connection, requestedOffset)
                        writeResponse(
                            connection = connection,
                            append = requestedOffset > 0L,
                            initialBytes = requestedOffset,
                            responseEndExclusive = responseEndExclusive,
                            downloadGeneration = downloadGeneration,
                        )
                        requestedOffset = partial.length()
                        if (requestedOffset == model.expectedBytes) return
                    }

                    HTTP_RANGE_NOT_SATISFIABLE -> {
                        if (partial.length() == model.expectedBytes) return
                        if (rangeResetPerformed) {
                            throw HttpFailure(responseCode, isTransient = false)
                        }
                        files.deletePartialForAttemptOrThrow(model, downloadGeneration)
                        requestedOffset = 0L
                        rangeResetPerformed = true
                    }

                    else -> throw HttpFailure(
                        responseCode = responseCode,
                        isTransient = responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
                            responseCode == HTTP_TOO_EARLY ||
                            responseCode == HTTP_TOO_MANY_REQUESTS ||
                            responseCode in 500..599,
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    @Throws(TransientNetworkFailure::class, HttpFailure::class)
    private fun openFollowingRedirects(url: String, rangeStart: Long): HttpURLConnection {
        var currentUrl = try {
            URL(url)
        } catch (exception: MalformedURLException) {
            throw HttpFailure(0, isTransient = false, cause = exception)
        }

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!currentUrl.protocol.equals("https", ignoreCase = true)) {
                throw HttpFailure(0, isTransient = false)
            }

            val connection = try {
                (currentUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (rangeStart > 0L) setRequestProperty("Range", "bytes=$rangeStart-")
                }
            } catch (exception: IOException) {
                throw TransientNetworkFailure(exception)
            }

            val responseCode = try {
                connection.responseCode
            } catch (exception: IOException) {
                connection.disconnect()
                throw TransientNetworkFailure(exception)
            }

            if (responseCode !in REDIRECT_RESPONSE_CODES) return connection
            if (redirectCount == MAX_REDIRECTS) {
                connection.disconnect()
                throw HttpFailure(responseCode, isTransient = false)
            }

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw HttpFailure(responseCode, isTransient = false)
            currentUrl = try {
                URL(currentUrl, location)
            } catch (exception: MalformedURLException) {
                throw HttpFailure(responseCode, isTransient = false, cause = exception)
            }
        }
        throw HttpFailure(0, isTransient = false)
    }

    @Throws(VerificationFailure::class)
    private fun validateFullResponse(connection: HttpURLConnection) {
        val contentLength = connection.contentLengthLong
        if (contentLength >= 0L && contentLength != model.expectedBytes) {
            throw VerificationFailure("The server reported an unexpected model size")
        }
    }

    @Throws(VerificationFailure::class)
    private fun validatePartialResponse(
        connection: HttpURLConnection,
        requestedOffset: Long,
    ): Long {
        val header = connection.getHeaderField("Content-Range")
            ?: throw VerificationFailure("A partial response omitted Content-Range")
        val match = CONTENT_RANGE_PATTERN.matchEntire(header.trim())
            ?: throw VerificationFailure("The server returned an invalid Content-Range")
        val responseStart = match.groupValues[1].toLongOrNull()
        val responseEnd = match.groupValues[2].toLongOrNull()
        val responseTotal = match.groupValues[3].toLongOrNull()
        if (
            responseStart != requestedOffset ||
            responseEnd == null ||
            responseEnd < requestedOffset ||
            responseEnd >= model.expectedBytes ||
            responseTotal != model.expectedBytes
        ) {
            throw VerificationFailure("The partial response did not match the requested model range")
        }

        val contentLength = connection.contentLengthLong
        val expectedResponseBytes = responseEnd - requestedOffset + 1L
        if (contentLength >= 0L && contentLength != expectedResponseBytes) {
            throw VerificationFailure("The partial response reported an unexpected size")
        }
        return responseEnd + 1L
    }

    private suspend fun writeResponse(
        connection: HttpURLConnection,
        append: Boolean,
        initialBytes: Long,
        responseEndExclusive: Long,
        downloadGeneration: Long,
    ) {
        val input = try {
            connection.inputStream
        } catch (exception: IOException) {
            throw TransientNetworkFailure(exception)
        }

        val output = try {
            files.openPartialOutput(model, downloadGeneration, append)
        } catch (exception: IOException) {
            runCatching { input.close() }
            throw exception
        }

        var downloadedBytes = initialBytes
        var lastPublishedBytes = initialBytes
        var lastPublishedAt = SystemClock.elapsedRealtime()
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        try {
            while (downloadedBytes < responseEndExclusive) {
                currentCoroutineContext().ensureActive()
                ensureDownloadAttemptActive(downloadGeneration)
                val remainingBytes = responseEndExclusive - downloadedBytes
                val requestedBytes = minOf(buffer.size.toLong(), remainingBytes).toInt()
                val count = try {
                    input.read(buffer, 0, requestedBytes)
                } catch (exception: IOException) {
                    throw TransientNetworkFailure(exception)
                }
                if (count < 0) {
                    throw TransientNetworkFailure(IOException("The model response ended early"))
                }
                try {
                    output.write(buffer, 0, count)
                } catch (exception: IOException) {
                    throw exception
                }
                downloadedBytes += count

                val now = SystemClock.elapsedRealtime()
                if (
                    downloadedBytes - lastPublishedBytes >= PROGRESS_STEP_BYTES ||
                    now - lastPublishedAt >= PROGRESS_MAX_INTERVAL_MILLIS
                ) {
                    ensureStorageCapacity()
                    publishDownloadProgress(downloadedBytes)
                    lastPublishedBytes = downloadedBytes
                    lastPublishedAt = now
                }
            }

            val unexpectedByte = try {
                input.read()
            } catch (exception: IOException) {
                throw TransientNetworkFailure(exception)
            }
            if (unexpectedByte >= 0) {
                throw VerificationFailure("The server returned more bytes than expected")
            }
            try {
                output.flush()
                output.fd.sync()
            } catch (exception: IOException) {
                throw exception
            }
            ensureStorageCapacity()
            publishDownloadProgress(downloadedBytes)
        } finally {
            runCatching { output.close() }
            runCatching { input.close() }
        }
    }

    private fun ensureStorageCapacity() {
        if (!files.hasDownloadCapacity(model)) {
            throw InsufficientStorageFailure()
        }
    }

    private fun ensureDownloadAttemptActive(downloadGeneration: Long) {
        if (!files.isDownloadAttemptActive(downloadGeneration)) {
            throw AiModelCancellationException()
        }
    }

    private suspend fun publishDownloadProgress(downloadedBytes: Long) {
        setProgress(
            workDataOf(
                KEY_STAGE to STAGE_DOWNLOADING,
                KEY_DOWNLOADED_BYTES to downloadedBytes,
                KEY_TOTAL_BYTES to model.expectedBytes,
            ),
        )
        setForeground(createForegroundInfo(downloadedBytes, model.expectedBytes))
    }

    private suspend fun publishVerifying() {
        setProgress(
            workDataOf(
                KEY_STAGE to STAGE_VERIFYING,
                KEY_DOWNLOADED_BYTES to model.expectedBytes,
                KEY_TOTAL_BYTES to model.expectedBytes,
            ),
        )
        setForeground(createForegroundInfo(model.expectedBytes, model.expectedBytes, verifying = true))
    }

    private fun successData(file: File) = workDataOf(KEY_MODEL_PATH to file.absolutePath)

    private fun failure(errorKind: AiModelErrorKind) = Result.failure(
        workDataOf(KEY_ERROR_KIND to errorKind.name),
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.ai_model_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(
                R.string.ai_model_notification_channel_description,
            )
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(
        downloadedBytes: Long,
        totalBytes: Long,
        verifying: Boolean = false,
    ): ForegroundInfo {
        val percent = if (totalBytes <= 0L) {
            0
        } else {
            ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        }
        val contentText = if (verifying) {
            applicationContext.getString(R.string.ai_model_notification_verifying)
        } else {
            applicationContext.getString(R.string.ai_model_notification_progress, percent)
        }
        val notification = notificationBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(contentText)
            .setProgress(100, percent, verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            FOREGROUND_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun notifyTerminal(result: NotificationResult) {
        val textResource = when (result) {
            NotificationResult.COMPLETE -> R.string.ai_model_notification_complete
            NotificationResult.FAILED -> R.string.ai_model_notification_failed
            NotificationResult.CANCELLED -> R.string.ai_model_notification_cancelled
        }
        val icon = if (result == NotificationResult.COMPLETE) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }
        val notification = notificationBuilder()
            .setSmallIcon(icon)
            .setContentText(applicationContext.getString(textResource))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        runCatching { notificationManager.notify(TERMINAL_NOTIFICATION_ID, notification) }
    }

    private fun notificationBuilder(): NotificationCompat.Builder {
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.ai_model_notification_download_title))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
    }

    private enum class NotificationResult {
        COMPLETE,
        FAILED,
        CANCELLED,
    }

    private class TransientNetworkFailure(cause: IOException) : Exception(cause)

    private class HttpFailure(
        val responseCode: Int,
        val isTransient: Boolean,
        cause: Throwable? = null,
    ) : Exception("HTTP $responseCode", cause)

    private class VerificationFailure(message: String) : Exception(message)

    private class InsufficientStorageFailure : RuntimeException()

    companion object {
        const val UNIQUE_WORK_NAME = "ai_model_download"
        const val WORK_TAG = "ai_model_download"
        const val KEY_ALLOW_METERED = "allow_metered"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOAD_GENERATION = "download_generation"
        const val KEY_STAGE = "stage"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR_KIND = "error_kind"
        const val KEY_MODEL_PATH = "model_path"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_VERIFYING = "verifying"

        fun generationTag(generation: Long): String = "$WORK_GENERATION_TAG_PREFIX$generation"

        fun generationFromTag(tag: String): Long? = tag
            .takeIf { it.startsWith(WORK_GENERATION_TAG_PREFIX) }
            ?.removePrefix(WORK_GENERATION_TAG_PREFIX)
            ?.toLongOrNull()

        private const val NOTIFICATION_CHANNEL_ID = "ai_model_download"
        private const val WORK_GENERATION_TAG_PREFIX = "ai_model_download_generation_"
        private const val FOREGROUND_NOTIFICATION_ID = 5_104
        private const val TERMINAL_NOTIFICATION_ID = 5_105
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val MAX_REDIRECTS = 8
        private const val MAX_RETRY_ATTEMPTS = 4
        private const val HTTP_TOO_EARLY = 425
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val DOWNLOAD_BUFFER_BYTES = 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 8L * 1024L * 1024L
        private const val PROGRESS_MAX_INTERVAL_MILLIS = 1_000L
        private const val USER_AGENT = "NoteUp-Android/1.0"
        private val REDIRECT_RESPONSE_CODES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE_PATTERN = Regex(
            pattern = "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
            option = RegexOption.IGNORE_CASE,
        )
    }
}
