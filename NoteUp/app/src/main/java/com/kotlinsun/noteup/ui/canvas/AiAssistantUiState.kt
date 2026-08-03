package com.kotlinsun.noteup.ui.canvas

import com.kotlinsun.noteup.domain.ai.AiEngineState
import com.kotlinsun.noteup.domain.ai.AiModelState

data class AiAssistantUiState(
    val isOpen: Boolean = false,
    val prompt: String = "",
    val contextText: String = "",
    val response: String = "",
    val requestPageId: Long? = null,
    val requestPageNumber: Int? = null,
    val insertionCenterX: Float = 0.5f,
    val insertionCenterY: Float = 0.5f,
    val modelState: AiModelState = AiModelState.Checking,
    val engineState: AiEngineState = AiEngineState.Unloaded,
    val phase: AiAssistantPhase = AiAssistantPhase.IDLE,
    val isInserting: Boolean = false,
)

enum class AiAssistantPhase {
    IDLE,
    GENERATING,
    COMPLETE,
    CANCELLED,
    FAILED,
}
