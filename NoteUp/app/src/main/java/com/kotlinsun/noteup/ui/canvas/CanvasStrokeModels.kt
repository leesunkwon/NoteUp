package com.kotlinsun.noteup.ui.canvas

import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokeDraft

data class PendingCanvasStroke(
    val token: Long,
    val draft: StrokeDraft,
)

sealed interface ErasableStroke {
    data class Persisted(val stroke: Stroke) : ErasableStroke
    data class Pending(val stroke: PendingCanvasStroke) : ErasableStroke
}
