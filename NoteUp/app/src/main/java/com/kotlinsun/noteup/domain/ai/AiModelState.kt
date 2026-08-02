package com.kotlinsun.noteup.domain.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class AiModelDescriptor(
    val id: String,
    val displayName: String,
    val revision: String,
    val sizeBytes: Long,
)

data class AiDeviceCompatibility(
    val supported: Boolean,
    val abi: String?,
    val isLowMemoryDevice: Boolean,
    val issue: AiCompatibilityIssue? = null,
)

enum class AiCompatibilityIssue {
    UNSUPPORTED_ABI,
}

enum class AiModelError {
    NETWORK,
    STORAGE,
    INTEGRITY,
    DOWNLOAD,
    UNKNOWN,
}

sealed interface AiModelState {
    data object Checking : AiModelState

    data class Unsupported(
        val issue: AiCompatibilityIssue,
    ) : AiModelState

    data class NotInstalled(
        val availableBytes: Long,
        val partialBytes: Long = 0L,
    ) : AiModelState

    data object Queued : AiModelState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AiModelState

    data object Verifying : AiModelState

    data class Ready(
        val modelPath: String,
        val sizeBytes: Long,
    ) : AiModelState

    data class Failed(
        val error: AiModelError,
        val availableBytes: Long,
    ) : AiModelState
}

interface OnDeviceAiRepository {
    val model: AiModelDescriptor
    val compatibility: AiDeviceCompatibility
    val modelState: StateFlow<AiModelState>
    val engineState: StateFlow<AiEngineState>

    fun downloadModel(allowMetered: Boolean)
    fun cancelModelDownload()
    suspend fun deleteModel()
    fun generate(prompt: String): Flow<String>
    fun cancelGeneration()
    fun trimMemory(level: Int)
}
