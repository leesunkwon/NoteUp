package com.kotlinsun.noteup.data.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.kotlinsun.noteup.domain.ai.AiEngineState
import com.kotlinsun.noteup.domain.ai.OnDeviceAiEngine
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LiteRtOnDeviceAiEngine(
    private val modelPathProvider: () -> String?,
    private val cacheDir: String,
) : OnDeviceAiEngine {

    constructor(
        modelPathProvider: () -> String?,
        cacheDir: File,
    ) : this(modelPathProvider, cacheDir.absolutePath)

    private val engineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val generationMutex = Mutex()

    private val _state = MutableStateFlow<AiEngineState>(AiEngineState.Unloaded)
    override val state: StateFlow<AiEngineState> = _state.asStateFlow()

    private var engine: Engine? = null
    private var activeConversation: Conversation? = null

    @Volatile
    private var activeGenerationJob: Job? = null

    init {
        require(cacheDir.isNotBlank()) { "Cache directory must not be blank." }
    }

    override fun generate(prompt: String): Flow<String> = flow {
        require(prompt.isNotBlank()) { "Prompt must not be blank." }

        generationMutex.withLock {
            val generationJob = currentCoroutineContext()[Job]
            activeGenerationJob = generationJob
            try {
                currentCoroutineContext().ensureActive()
                val loadedEngine = getOrInitializeEngine()
                currentCoroutineContext().ensureActive()

                loadedEngine.createConversation().use { conversation ->
                    activeConversation = conversation
                    _state.value = AiEngineState.Generating
                    try {
                        // LiteRT-LM 0.15.0의 Flow 어댑터는 완료 콜백에서 런타임에
                        // 존재하지 않는 SendChannel.close$default를 호출하므로 콜백 API를 사용한다.
                        val responseChannel = Channel<Message>(Channel.BUFFERED)
                        try {
                            conversation.sendMessageAsync(
                                prompt,
                                object : MessageCallback {
                                    override fun onMessage(message: Message) {
                                        responseChannel.trySend(message)
                                    }

                                    override fun onDone() {
                                        responseChannel.close(null)
                                    }

                                    override fun onError(throwable: Throwable) {
                                        responseChannel.close(throwable)
                                    }
                                },
                                emptyMap(),
                            )

                            for (message in responseChannel) {
                                emit(message.toString())
                            }
                        } finally {
                            responseChannel.cancel(null)
                        }
                    } catch (cancellation: CancellationException) {
                        cancelConversation(conversation)
                        throw cancellation
                    } finally {
                        activeConversation = null
                    }
                }

                _state.value = AiEngineState.Ready
            } catch (cancellation: CancellationException) {
                _state.value = if (engine == null) {
                    AiEngineState.Unloaded
                } else {
                    AiEngineState.Ready
                }
                throw cancellation
            } catch (failure: Throwable) {
                _state.value = AiEngineState.Failed(failure.describe())
                throw failure
            } finally {
                if (activeGenerationJob === generationJob) {
                    activeGenerationJob = null
                }
            }
        }
    }.flowOn(engineDispatcher)

    override fun cancelGeneration() {
        activeGenerationJob?.cancel()
        engineScope.launch {
            activeConversation?.let(::cancelConversation)
        }
    }

    override suspend fun release() {
        withContext(NonCancellable + engineDispatcher) {
            activeGenerationJob?.cancel()
            activeConversation?.let(::cancelConversation)

            generationMutex.withLock {
                val loadedEngine = engine

                if (loadedEngine == null) {
                    _state.value = AiEngineState.Unloaded
                    return@withLock
                }

                try {
                    if (loadedEngine.isInitialized()) {
                        loadedEngine.close()
                    }
                    engine = null
                    _state.value = AiEngineState.Unloaded
                } catch (failure: Throwable) {
                    _state.value = AiEngineState.Failed(failure.describe())
                    throw failure
                }
            }
        }
    }

    private fun getOrInitializeEngine(): Engine {
        engine?.let { return it }

        _state.value = AiEngineState.Loading
        val modelPath = modelPathProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("On-device AI model is not available.")
        val engineCacheDirectory = File(cacheDir)
        if (
            !engineCacheDirectory.isDirectory &&
            (engineCacheDirectory.exists() || !engineCacheDirectory.mkdirs())
        ) {
            throw IllegalStateException("Unable to create the LiteRT-LM cache directory.")
        }
        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = cacheDir,
            ),
        )

        try {
            newEngine.initialize()
        } catch (failure: Throwable) {
            runCatching { newEngine.close() }
            throw failure
        }

        engine = newEngine
        _state.value = AiEngineState.Ready
        return newEngine
    }

    private fun cancelConversation(conversation: Conversation) {
        runCatching { conversation.cancelProcess() }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        const val THREAD_NAME = "NoteUp-LiteRT-LM"
    }
}
