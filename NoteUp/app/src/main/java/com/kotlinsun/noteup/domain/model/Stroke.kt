package com.kotlinsun.noteup.domain.model

data class Stroke(
    val id: Long,
    val pageId: Long,
    val strokeIndex: Int,
    val tool: StrokeTool,
    val colorArgb: Int,
    val width: Float,
    val points: List<StrokePoint>,
    val createdAt: Long,
)

data class StrokeDraft(
    val tool: StrokeTool,
    val colorArgb: Int,
    val width: Float,
    val points: List<StrokePoint>,
)

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timeOffsetMillis: Int,
)

enum class StrokeTool {
    PEN,
    HIGHLIGHTER,
    LINE,
    RECTANGLE,
    CIRCLE,
}
