package com.kotlinsun.noteup.domain.model

data class CanvasText(
    val id: Long,
    val pageId: Long,
    val elementIndex: Int,
    val x: Float,
    val y: Float,
    val boxWidth: Float,
    val content: String,
    val colorArgb: Int,
    val textSizeSp: Float,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CanvasTextDraft(
    val x: Float,
    val y: Float,
    val boxWidth: Float,
    val content: String,
    val colorArgb: Int,
    val textSizeSp: Float,
)

sealed interface CanvasElementRef {
    data class StrokeRef(val stroke: Stroke) : CanvasElementRef
    data class TextRef(val text: CanvasText) : CanvasElementRef
}

data class CanvasTransform(val offsetX: Float, val offsetY: Float, val scale: Float = 1f)
