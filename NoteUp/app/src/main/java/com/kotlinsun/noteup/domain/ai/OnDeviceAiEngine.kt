package com.kotlinsun.noteup.domain.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface OnDeviceAiEngine {
    val state: StateFlow<AiEngineState>

    fun generate(prompt: String): Flow<String>

    fun cancelGeneration()

    suspend fun release()
}
