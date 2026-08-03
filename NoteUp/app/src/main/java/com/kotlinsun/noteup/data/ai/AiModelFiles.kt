package com.kotlinsun.noteup.data.ai

import android.content.Context
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal class AiModelFiles(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val modelDirectory: File = File(appContext.noBackupFilesDir, MODEL_DIRECTORY_NAME)

    fun finalFile(artifact: AiModelArtifact): File = File(modelDirectory, artifact.fileName)

    fun partialFile(artifact: AiModelArtifact): File = File(modelDirectory, "${artifact.fileName}.part")

    @Throws(IOException::class)
    fun ensureModelDirectory(): File {
        if (modelDirectory.isDirectory) return modelDirectory
        if (modelDirectory.exists() || !modelDirectory.mkdirs()) {
            throw IOException("Unable to create the AI model directory")
        }
        return modelDirectory
    }

    @Throws(IOException::class)
    fun availableBytes(): Long {
        val directory = ensureModelDirectory()
        return try {
            StatFs(directory.absolutePath).availableBytes
        } catch (exception: IllegalArgumentException) {
            throw IOException("Unable to read available model storage", exception)
        }
    }

    fun partialBytes(artifact: AiModelArtifact): Long = partialFile(artifact).let { file ->
        if (file.isFile) file.length() else 0L
    }

    fun requiredAvailableBytes(artifact: AiModelArtifact): Long {
        val retainedPartialBytes = partialBytes(artifact).coerceIn(0L, artifact.expectedBytes)
        return (artifact.expectedBytes - retainedPartialBytes) + MINIMUM_FREE_SPACE_RESERVE_BYTES
    }

    @Throws(IOException::class)
    fun hasDownloadCapacity(artifact: AiModelArtifact): Boolean =
        availableBytes() >= requiredAvailableBytes(artifact)

    fun trackedReadyFile(artifact: AiModelArtifact): File? = synchronized(FILE_OPERATION_LOCK) {
        if (isCancelCleanupRequestedLocked()) return@synchronized null
        val file = finalFile(artifact)
        if (!file.isFile || file.length() != artifact.expectedBytes) return@synchronized null

        val prefix = verificationPrefix(artifact)
        val isTracked = preferences.getString("${prefix}revision", null) == artifact.revision &&
            preferences.getString("${prefix}sha256", null) == artifact.sha256 &&
            preferences.getLong("${prefix}bytes", -1L) == artifact.expectedBytes &&
            preferences.getLong("${prefix}last_modified", -1L) == file.lastModified()
        file.takeIf { isTracked }
    }

    @Throws(IOException::class)
    fun verifyInstalled(
        artifact: AiModelArtifact,
        ensureActive: () -> Unit = {},
    ): InstalledModelVerification {
        val file = finalFile(artifact)
        val snapshot = synchronized(FILE_OPERATION_LOCK) {
            snapshotLocked(file, artifact.expectedBytes)
        } ?: return InstalledModelVerification.Missing

        val matchesHash = calculateSha256(file, ensureActive)
            .equals(artifact.sha256, ignoreCase = true)

        return synchronized(FILE_OPERATION_LOCK) {
            if (!matchesLocked(snapshot) || isCancelCleanupRequestedLocked()) {
                return@synchronized InstalledModelVerification.Changed
            }
            if (!matchesHash) {
                deleteOrThrowLocked(file, "invalid installed AI model")
                clearVerificationLocked(artifact)
                return@synchronized InstalledModelVerification.Invalid
            }

            markVerifiedLocked(file, artifact)
            InstalledModelVerification.Ready(file)
        }
    }

    @Throws(IOException::class)
    fun verifyPartial(
        artifact: AiModelArtifact,
        ensureActive: () -> Unit = {},
    ): VerifiedModelFile? {
        val file = partialFile(artifact)
        val snapshot = synchronized(FILE_OPERATION_LOCK) {
            snapshotLocked(file, artifact.expectedBytes)
        } ?: return null

        val matchesHash = calculateSha256(file, ensureActive)
            .equals(artifact.sha256, ignoreCase = true)
        if (!matchesHash) return null

        return synchronized(FILE_OPERATION_LOCK) {
            snapshot.takeIf(::matchesLocked)
        }
    }

    @Throws(IOException::class)
    fun beginDownloadAttempt(): Long = synchronized(FILE_OPERATION_LOCK) {
        val generation = nextDownloadGenerationLocked()
        val saved = preferences.edit()
            .putLong(KEY_DOWNLOAD_GENERATION, generation)
            .remove(KEY_CANCEL_CLEANUP)
            .commit()
        if (!saved) throw IOException("Unable to begin the AI model download")
        fileOperationGeneration++
        generation
    }

    @Throws(IOException::class)
    fun requestCancelCleanup() = synchronized(FILE_OPERATION_LOCK) {
        val saved = preferences.edit()
            .putLong(KEY_DOWNLOAD_GENERATION, nextDownloadGenerationLocked())
            .putBoolean(KEY_CANCEL_CLEANUP, true)
            .commit()
        if (!saved) throw IOException("Unable to persist the AI model cancellation")
        fileOperationGeneration++
    }

    fun isDownloadAttemptActive(generation: Long): Boolean = synchronized(FILE_OPERATION_LOCK) {
        !isCancelCleanupRequestedLocked() &&
            preferences.getLong(KEY_DOWNLOAD_GENERATION, 0L) == generation
    }

    fun isCancelCleanupRequested(): Boolean = synchronized(FILE_OPERATION_LOCK) {
        isCancelCleanupRequestedLocked()
    }

    @Throws(IOException::class)
    fun openPartialOutput(
        artifact: AiModelArtifact,
        downloadGeneration: Long,
        append: Boolean,
    ): FileOutputStream = synchronized(FILE_OPERATION_LOCK) {
        ensureDownloadAttemptActiveLocked(downloadGeneration)
        FileOutputStream(partialFile(artifact), append)
    }

    @Throws(IOException::class)
    fun cleanupCancelledDownload(artifact: AiModelArtifact) {
        synchronized(FILE_OPERATION_LOCK) {
            if (!isCancelCleanupRequestedLocked()) return
            deleteOrThrowLocked(partialFile(artifact), "partial AI model")
            deleteOrThrowLocked(finalFile(artifact), "installed AI model")
            clearVerificationLocked(artifact)
            clearCancelCleanupLocked()
        }
    }

    @Throws(IOException::class)
    fun promoteVerifiedPartial(
        artifact: AiModelArtifact,
        verifiedFile: VerifiedModelFile,
        downloadGeneration: Long,
    ): File = synchronized(FILE_OPERATION_LOCK) {
        if (
            !isDownloadAttemptActiveLocked(downloadGeneration) ||
            !matchesLocked(verifiedFile)
        ) {
            throw AiModelCancellationException()
        }

        val partial = partialFile(artifact)
        val destination = finalFile(artifact)
        if (partial.absolutePath != verifiedFile.absolutePath) {
            throw IOException("The verified model path changed before installation")
        }
        if (partial.parentFile != destination.parentFile) {
            throw IOException("The model must be renamed within its download directory")
        }

        try {
            Os.rename(partial.absolutePath, destination.absolutePath)
        } catch (exception: ErrnoException) {
            throw IOException("Unable to atomically install the verified model", exception)
        }
        fileOperationGeneration++
        markVerifiedLocked(destination, artifact)
        destination
    }

    @Throws(IOException::class)
    fun deletePartialForAttemptOrThrow(
        artifact: AiModelArtifact,
        downloadGeneration: Long,
    ) {
        synchronized(FILE_OPERATION_LOCK) {
            ensureDownloadAttemptActiveLocked(downloadGeneration)
            deleteOrThrowLocked(partialFile(artifact), "partial AI model")
        }
    }

    @Throws(IOException::class)
    fun deleteModelFiles(artifact: AiModelArtifact) {
        synchronized(FILE_OPERATION_LOCK) {
            deleteOrThrowLocked(partialFile(artifact), "partial AI model")
            deleteOrThrowLocked(finalFile(artifact), "installed AI model")
            clearVerificationLocked(artifact)
            clearCancelCleanupLocked()
        }
    }

    @Throws(IOException::class)
    private fun calculateSha256(file: File, ensureActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        FileInputStream(file).buffered(HASH_BUFFER_BYTES).use { input ->
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    @Throws(IOException::class)
    private fun markVerifiedLocked(file: File, artifact: AiModelArtifact) {
        val prefix = verificationPrefix(artifact)
        val saved = preferences.edit()
            .putString("${prefix}revision", artifact.revision)
            .putString("${prefix}sha256", artifact.sha256)
            .putLong("${prefix}bytes", artifact.expectedBytes)
            .putLong("${prefix}last_modified", file.lastModified())
            .commit()
        if (!saved) throw IOException("Unable to save model verification metadata")
    }

    @Throws(IOException::class)
    private fun clearVerificationLocked(artifact: AiModelArtifact) {
        val prefix = verificationPrefix(artifact)
        val saved = preferences.edit()
            .remove("${prefix}revision")
            .remove("${prefix}sha256")
            .remove("${prefix}bytes")
            .remove("${prefix}last_modified")
            .commit()
        if (!saved) throw IOException("Unable to clear model verification metadata")
    }

    private fun snapshotLocked(file: File, expectedBytes: Long): VerifiedModelFile? {
        if (!file.isFile || file.length() != expectedBytes) return null
        return VerifiedModelFile(
            absolutePath = file.absolutePath,
            operationGeneration = fileOperationGeneration,
            length = file.length(),
            lastModified = file.lastModified(),
        )
    }

    private fun matchesLocked(snapshot: VerifiedModelFile): Boolean {
        val file = File(snapshot.absolutePath)
        return snapshot.operationGeneration == fileOperationGeneration &&
            file.isFile &&
            file.length() == snapshot.length &&
            file.lastModified() == snapshot.lastModified
    }

    private fun isDownloadAttemptActiveLocked(generation: Long): Boolean =
        !isCancelCleanupRequestedLocked() &&
            preferences.getLong(KEY_DOWNLOAD_GENERATION, 0L) == generation

    @Throws(AiModelCancellationException::class)
    private fun ensureDownloadAttemptActiveLocked(generation: Long) {
        if (!isDownloadAttemptActiveLocked(generation)) {
            throw AiModelCancellationException()
        }
    }

    private fun isCancelCleanupRequestedLocked(): Boolean =
        preferences.getBoolean(KEY_CANCEL_CLEANUP, false)

    @Throws(IOException::class)
    private fun clearCancelCleanupLocked() {
        val saved = preferences.edit().remove(KEY_CANCEL_CLEANUP).commit()
        if (!saved) throw IOException("Unable to clear the AI model cancellation")
    }

    private fun nextDownloadGenerationLocked(): Long {
        val current = preferences.getLong(KEY_DOWNLOAD_GENERATION, 0L)
        return if (current == Long.MAX_VALUE) 1L else current + 1L
    }

    @Throws(IOException::class)
    private fun deleteOrThrowLocked(file: File, description: String) {
        if (!file.exists()) return
        if (!file.delete()) throw IOException("Unable to delete the $description")
        fileOperationGeneration++
    }

    private fun verificationPrefix(artifact: AiModelArtifact): String = "verified_${artifact.id}_"

    companion object {
        const val MINIMUM_FREE_SPACE_RESERVE_BYTES = 512L * 1024L * 1024L

        private const val MODEL_DIRECTORY_NAME = "ai_models"
        private const val PREFERENCES_NAME = "ai_model_files"
        private const val KEY_CANCEL_CLEANUP = "cancel_cleanup_requested"
        private const val KEY_DOWNLOAD_GENERATION = "download_generation"
        private const val HASH_BUFFER_BYTES = 1024 * 1024
        private val FILE_OPERATION_LOCK = Any()
        private var fileOperationGeneration = 0L
    }
}

internal data class VerifiedModelFile(
    val absolutePath: String,
    val operationGeneration: Long,
    val length: Long,
    val lastModified: Long,
)

internal sealed interface InstalledModelVerification {
    data class Ready(val file: File) : InstalledModelVerification
    data object Missing : InstalledModelVerification
    data object Invalid : InstalledModelVerification
    data object Changed : InstalledModelVerification
}

internal class AiModelCancellationException : IOException("Model download was cancelled")
