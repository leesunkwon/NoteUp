package com.kotlinsun.noteup.domain.ai

sealed interface AiEngineState {
    data object Unloaded : AiEngineState
    data object Loading : AiEngineState
    data object Ready : AiEngineState
    data object Generating : AiEngineState
    data class Failed(val message: String? = null) : AiEngineState
}
