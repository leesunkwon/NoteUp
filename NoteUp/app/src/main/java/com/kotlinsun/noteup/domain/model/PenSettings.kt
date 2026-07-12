package com.kotlinsun.noteup.domain.model

data class PenSettings(
    val color: PenColor = PenColor.BLACK,
    val thickness: PenThickness = PenThickness.MEDIUM,
)

data class DrawingSettings(
    val tool: DrawingTool = DrawingTool.PEN,
    val pen: PenSettings = PenSettings(),
    val highlighter: HighlighterSettings = HighlighterSettings(),
    val eraserMode: EraserMode = EraserMode.STROKE,
)

enum class EraserMode { STROKE, AREA }

data class HighlighterSettings(
    val color: HighlighterColor = HighlighterColor.YELLOW,
    val thickness: HighlighterThickness = HighlighterThickness.MEDIUM,
)

enum class DrawingTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
}

enum class PenColor(val argb: Int) {
    BLACK(0xFF202124.toInt()),
    BLUE(0xFF2563EB.toInt()),
    RED(0xFFDC2626.toInt()),
    GREEN(0xFF16803C.toInt()),
}

enum class PenThickness(val widthDp: Float) {
    THIN(2f),
    MEDIUM(4f),
    THICK(7f),
}

enum class HighlighterColor(val argb: Int) {
    YELLOW(0x66FFEB3B),
    GREEN(0x6666BB6A),
    PINK(0x66EC407A),
    BLUE(0x6642A5F5),
}

enum class HighlighterThickness(val widthDp: Float) {
    THIN(14f),
    MEDIUM(20f),
    THICK(28f),
}
