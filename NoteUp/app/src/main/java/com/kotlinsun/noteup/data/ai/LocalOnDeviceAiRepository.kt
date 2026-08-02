package com.kotlinsun.noteup.data.ai

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import java.io.File
import java.io.IOException
import com.kotlinsun.noteup.domain.ai.AiCompatibilityIssue
import com.kotlinsun.noteup.domain.ai.AiDeviceCompatibility
import com.kotlinsun.noteup.domain.ai.AiEngineState
import com.kotlinsun.noteup.domain.ai.AiModelDescriptor
import com.kotlinsun.noteup.domain.ai.AiModelError
import com.kotlinsun.noteup.domain.ai.AiModelState
import com.kotlinsun.noteup.domain.ai.OnDeviceAiEngine
import com.kotlinsun.noteup.domain.ai.OnDeviceAiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalOnDeviceAiRepository(
    context: Context,
    private val modelManager: AiModelManager,
    private val engine: OnDeviceAiEngine,
) : OnDeviceAiRepository {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelLifecycleMutex = Mutex()

    override val model: AiModelDescriptor = AiModelCatalog.defaultModel.let { artifact ->
        AiModelDescriptor(
            id = artifact.id,
            displayName = artifact.displayName,
            revision = artifact.revision,
            sizeBytes = artifact.expectedBytes,
        )
    }

    override val compatibility: AiDeviceCompatibility = AiDeviceCompatibility(
        supported = modelManager.compatibility.isSupported,
        abi = modelManager.compatibility.selectedAbi,
        isLowMemoryDevice = appContext
            .getSystemService(ActivityManager::class.java)
            .isLowRamDevice,
        issue = if (modelManager.compatibility.isSupported) {
            null
        } else {
            AiCompatibilityIssue.UNSUPPORTED_ABI
        },
    )

    override val modelState: StateFlow<AiModelState> = modelManager.state
        .map(::mapModelState)
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            mapModelState(modelManager.state.value),
        )

    override val engineState: StateFlow<AiEngineState> = engine.state

    override fun downloadModel(allowMetered: Boolean) {
        modelManager.startDownload(allowMetered)
    }

    override fun cancelModelDownload() {
        modelManager.cancelDownload()
    }

    override suspend fun deleteModel() {
        engine.cancelGeneration()
        modelLifecycleMutex.withLock {
            engine.release()
            withContext(Dispatchers.IO) {
                val cacheDirectory = File(
                    appContext.cacheDir,
                    AiModelCatalog.ENGINE_CACHE_DIRECTORY_NAME,
                )
                if (cacheDirectory.exists() && !cacheDirectory.deleteRecursively()) {
                    throw IOException("Unable to delete the LiteRT-LM cache directory")
                }
            }
            modelManager.deleteModel()
        }
    }

    override fun generate(prompt: String): Flow<String> = flow {
        modelLifecycleMutex.withLock {
            engine.generate(prompt).collect { chunk -> emit(chunk) }
        }
    }

    override fun cancelGeneration() = engine.cancelGeneration()

    override fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            engine.cancelGeneration()
            scope.launch {
                modelLifecycleMutex.withLock {
                    runCatching { engine.release() }
                }
            }
        }
    }

    private fun mapModelState(state: AiModelInstallState): AiModelState = when (state) {
        AiModelInstallState.Checking -> AiModelState.Checking
        AiModelInstallState.Unsupported -> AiModelState.Unsupported(
            AiCompatibilityIssue.UNSUPPORTED_ABI,
        )

        is AiModelInstallState.NotInstalled -> AiModelState.NotInstalled(
            availableBytes = state.availableBytes,
            partialBytes = state.partialBytes,
        )

        AiModelInstallState.Queued -> AiModelState.Queued
        is AiModelInstallState.Downloading -> AiModelState.Downloading(
            downloadedBytes = state.downloadedBytes,
            totalBytes = state.totalBytes,
        )

        AiModelInstallState.Verifying -> AiModelState.Verifying
        is AiModelInstallState.Ready -> AiModelState.Ready(
            modelPath = state.path,
            sizeBytes = model.sizeBytes,
        )

        is AiModelInstallState.Error -> AiModelState.Failed(
            error = when (state.errorKind) {
                AiModelErrorKind.INSUFFICIENT_STORAGE -> AiModelError.STORAGE
                AiModelErrorKind.NETWORK -> AiModelError.NETWORK
                AiModelErrorKind.VERIFICATION -> AiModelError.INTEGRITY
                AiModelErrorKind.HTTP,
                AiModelErrorKind.FILE_SYSTEM,
                AiModelErrorKind.CANCELLED,
                AiModelErrorKind.UNKNOWN -> AiModelError.DOWNLOAD
            },
            availableBytes = state.availableBytes,
        )
    }
}
