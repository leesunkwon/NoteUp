package com.kotlinsun.noteup.data.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AiModelInstallState {
    data object Checking : AiModelInstallState

    data object Unsupported : AiModelInstallState

    data class NotInstalled(
        val availableBytes: Long,
        val partialBytes: Long,
    ) : AiModelInstallState

    data object Queued : AiModelInstallState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AiModelInstallState

    data object Verifying : AiModelInstallState

    data class Ready(val path: String) : AiModelInstallState

    data class Error(
        val errorKind: AiModelErrorKind,
        val availableBytes: Long,
    ) : AiModelInstallState
}

enum class AiModelErrorKind {
    INSUFFICIENT_STORAGE,
    NETWORK,
    HTTP,
    VERIFICATION,
    FILE_SYSTEM,
    CANCELLED,
    UNKNOWN,
}

class AiModelManager(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val model = AiModelCatalog.defaultModel
    private val files = AiModelFiles(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AiModelInstallState>(AiModelInstallState.Checking)
    private val diskStateMutex = Mutex()
    private val cancellationInProgress = AtomicBoolean(false)

    val state: StateFlow<AiModelInstallState> = mutableState.asStateFlow()
    val compatibility: AiModelCompatibility = AiModelCatalog.deviceCompatibility()

    init {
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(AiModelDownloadWorker.UNIQUE_WORK_NAME)
                .catch {
                    refreshFromDisk()
                }
                .collectLatest(::applyWorkState)
        }
    }

    fun startDownload(allowMetered: Boolean) {
        if (!compatibility.isSupported) {
            mutableState.value = AiModelInstallState.Unsupported
            return
        }
        readyModelPath()?.let { path ->
            mutableState.value = AiModelInstallState.Ready(path)
            return
        }
        if (
            mutableState.value is AiModelInstallState.Queued ||
            mutableState.value is AiModelInstallState.Downloading ||
            mutableState.value is AiModelInstallState.Verifying ||
            mutableState.value is AiModelInstallState.Checking
        ) {
            return
        }

        val availableBytes = try {
            files.cleanupCancelledDownload(model)
            files.availableBytes()
        } catch (exception: IOException) {
            mutableState.value = AiModelInstallState.Error(
                errorKind = AiModelErrorKind.FILE_SYSTEM,
                availableBytes = 0L,
            )
            return
        }
        if (availableBytes < files.requiredAvailableBytes(model)) {
            mutableState.value = AiModelInstallState.Error(
                errorKind = AiModelErrorKind.INSUFFICIENT_STORAGE,
                availableBytes = availableBytes,
            )
            return
        }

        val downloadGeneration = try {
            files.beginDownloadAttempt()
        } catch (exception: IOException) {
            mutableState.value = AiModelInstallState.Error(
                errorKind = AiModelErrorKind.FILE_SYSTEM,
                availableBytes = availableBytes,
            )
            return
        }

        val requiredNetworkType = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(requiredNetworkType)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<AiModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    AiModelDownloadWorker.KEY_MODEL_ID to model.id,
                    AiModelDownloadWorker.KEY_ALLOW_METERED to allowMetered,
                    AiModelDownloadWorker.KEY_DOWNLOAD_GENERATION to downloadGeneration,
                ),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MINIMUM_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(AiModelDownloadWorker.WORK_TAG)
            .addTag(AiModelDownloadWorker.generationTag(downloadGeneration))
            .build()

        mutableState.value = AiModelInstallState.Queued
        workManager.enqueueUniqueWork(
            AiModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelDownload() {
        if (
            mutableState.value !is AiModelInstallState.Queued &&
            mutableState.value !is AiModelInstallState.Downloading &&
            mutableState.value !is AiModelInstallState.Verifying
        ) {
            return
        }
        cancellationInProgress.set(true)
        try {
            files.requestCancelCleanup()
        } catch (exception: IOException) {
            cancellationInProgress.set(false)
            mutableState.value = AiModelInstallState.Error(
                errorKind = AiModelErrorKind.FILE_SYSTEM,
                availableBytes = availableBytesOrZero(),
            )
            return
        }
        mutableState.value = AiModelInstallState.Checking
        val cancellation = workManager.cancelUniqueWork(AiModelDownloadWorker.UNIQUE_WORK_NAME)
        scope.launch {
            val cancellationFailure = runCatching { cancellation.result.get() }.exceptionOrNull()
            val cleanupFailure = runCatching {
                files.cleanupCancelledDownload(model)
            }.exceptionOrNull()
            val failure = cleanupFailure ?: cancellationFailure
            cancellationInProgress.set(false)
            if (failure == null) {
                refreshFromDisk()
            } else {
                mutableState.value = AiModelInstallState.Error(
                    errorKind = AiModelErrorKind.FILE_SYSTEM,
                    availableBytes = availableBytesOrZero(),
                )
            }
        }
    }

    suspend fun deleteModel() {
        cancellationInProgress.set(true)
        mutableState.value = AiModelInstallState.Checking
        try {
            withContext(NonCancellable) {
                files.requestCancelCleanup()
                diskStateMutex.withLock {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            workManager.cancelUniqueWork(
                                AiModelDownloadWorker.UNIQUE_WORK_NAME,
                            ).result.get()
                        }
                        files.deleteModelFiles(model)
                    }
                    refreshFromDiskLocked()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = AiModelInstallState.Error(
                errorKind = AiModelErrorKind.FILE_SYSTEM,
                availableBytes = availableBytesOrZero(),
            )
            throw if (failure is IOException) {
                failure
            } else {
                IOException("Unable to delete the AI model", failure)
            }
        } finally {
            cancellationInProgress.set(false)
        }
    }

    fun readyModelPath(): String? {
        if (!compatibility.isSupported) return null
        return files.trackedReadyFile(model)?.absolutePath
    }

    private suspend fun applyWorkState(workInfos: List<WorkInfo>) {
        if (cancellationInProgress.get()) return
        if (!compatibility.isSupported) {
            mutableState.value = AiModelInstallState.Unsupported
            return
        }
        val workInfo = workInfos.maxByOrNull { workInfo ->
            workInfo.tags.firstNotNullOfOrNull { tag ->
                AiModelDownloadWorker.generationFromTag(tag)
            } ?: Long.MIN_VALUE
        }?.takeIf { workInfo ->
            workInfo.tags.any { AiModelDownloadWorker.generationFromTag(it) != null }
        }
        if (workInfo == null) {
            refreshFromDisk()
            return
        }
        val workGeneration = workInfo.tags.firstNotNullOfOrNull { tag ->
            AiModelDownloadWorker.generationFromTag(tag)
        }
        if (workGeneration == null || !files.isDownloadAttemptActive(workGeneration)) {
            if (!workInfo.state.isFinished) workManager.cancelWorkById(workInfo.id)
            refreshFromDisk()
            return
        }
        readyModelPath()?.let { path ->
            if (!workInfo.state.isFinished) workManager.cancelWorkById(workInfo.id)
            mutableState.value = AiModelInstallState.Ready(path)
            return
        }
        if (
            (workInfo.state == WorkInfo.State.ENQUEUED ||
                workInfo.state == WorkInfo.State.BLOCKED) &&
            files.finalFile(model).exists()
        ) {
            refreshFromDisk()
            val reconciledState = mutableState.value
            if (reconciledState is AiModelInstallState.Ready) {
                if (!workInfo.state.isFinished) workManager.cancelWorkById(workInfo.id)
                return
            }
        }

        when (workInfo.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> mutableState.value = AiModelInstallState.Queued

            WorkInfo.State.RUNNING -> {
                val progress = workInfo.progress
                if (progress.getString(AiModelDownloadWorker.KEY_STAGE) == AiModelDownloadWorker.STAGE_VERIFYING) {
                    mutableState.value = AiModelInstallState.Verifying
                } else {
                    val downloadedBytes = progress.getLong(
                        AiModelDownloadWorker.KEY_DOWNLOADED_BYTES,
                        files.partialBytes(model),
                    )
                    val totalBytes = progress.getLong(
                        AiModelDownloadWorker.KEY_TOTAL_BYTES,
                        model.expectedBytes,
                    )
                    mutableState.value = AiModelInstallState.Downloading(
                        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                        totalBytes = totalBytes.takeIf { it > 0L } ?: model.expectedBytes,
                    )
                }
            }

            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.CANCELLED -> refreshFromDisk()

            WorkInfo.State.FAILED -> {
                refreshFromDisk()
                when (val diskState = mutableState.value) {
                    is AiModelInstallState.Ready -> return
                    is AiModelInstallState.Error -> if (
                        diskState.errorKind == AiModelErrorKind.FILE_SYSTEM ||
                        diskState.errorKind == AiModelErrorKind.VERIFICATION
                    ) {
                        return
                    }

                    else -> Unit
                }
                val errorName = workInfo.outputData.getString(AiModelDownloadWorker.KEY_ERROR_KIND)
                val errorKind = errorName?.let { name ->
                    AiModelErrorKind.entries.firstOrNull { it.name == name }
                } ?: AiModelErrorKind.UNKNOWN
                if (errorKind == AiModelErrorKind.CANCELLED) {
                    return
                } else {
                    mutableState.value = AiModelInstallState.Error(
                        errorKind = errorKind,
                        availableBytes = availableBytesOrZero(),
                    )
                }
            }
        }
    }

    private suspend fun refreshFromDisk() = diskStateMutex.withLock {
        refreshFromDiskLocked()
    }

    private suspend fun refreshFromDiskLocked() {
        if (!compatibility.isSupported) {
            mutableState.value = AiModelInstallState.Unsupported
            return
        }

        try {
            files.cleanupCancelledDownload(model)
        } catch (exception: IOException) {
            mutableState.value = AiModelInstallState.Error(
                errorKind = AiModelErrorKind.FILE_SYSTEM,
                availableBytes = availableBytesOrZero(),
            )
            return
        }

        files.trackedReadyFile(model)?.let { readyFile ->
            mutableState.value = AiModelInstallState.Ready(readyFile.absolutePath)
            return
        }

        val finalFile = files.finalFile(model)
        if (finalFile.exists()) {
            mutableState.value = AiModelInstallState.Checking
            val verificationContext = currentCoroutineContext()
            val verification = try {
                files.verifyInstalled(model) { verificationContext.ensureActive() }
            } catch (exception: IOException) {
                mutableState.value = AiModelInstallState.Error(
                    errorKind = AiModelErrorKind.FILE_SYSTEM,
                    availableBytes = availableBytesOrZero(),
                )
                return
            }

            when (verification) {
                is InstalledModelVerification.Ready -> {
                    mutableState.value = AiModelInstallState.Ready(
                        verification.file.absolutePath,
                    )
                    return
                }

                InstalledModelVerification.Invalid -> {
                    mutableState.value = AiModelInstallState.Error(
                        errorKind = AiModelErrorKind.VERIFICATION,
                        availableBytes = availableBytesOrZero(),
                    )
                    return
                }

                InstalledModelVerification.Changed -> {
                    mutableState.value = AiModelInstallState.Checking
                    scope.launch { refreshFromDisk() }
                    return
                }

                InstalledModelVerification.Missing -> Unit
            }
        }

        mutableState.value = AiModelInstallState.NotInstalled(
            availableBytes = availableBytesOrZero(),
            partialBytes = files.partialBytes(model),
        )
    }

    private fun availableBytesOrZero(): Long = try {
        files.availableBytes()
    } catch (exception: IOException) {
        0L
    }

    companion object {
        private const val MINIMUM_BACKOFF_MILLIS = 10_000L
    }
}
