package com.kotlinsun.noteup.domain.model

data class PenSettings(
    val color: PenColor = PenColor.BLACK,
    val thickness: PenThickness = PenThickness.MEDIUM,
    val customColorArgb: Int? = null,
) {
    val colorArgb: Int get() = customColorArgb ?: color.argb
}

data class DrawingSettings(
    val tool: DrawingTool = DrawingTool.PEN,
    val pen: PenSettings = PenSettings(),
    val highlighter: HighlighterSettings = HighlighterSettings(),
    val eraserMode: EraserMode = EraserMode.STROKE,
    val textSize: TextSize = TextSize.MEDIUM,
)

enum class EraserMode { STROKE, AREA }

data class HighlighterSettings(
    val color: HighlighterColor = HighlighterColor.YELLOW,
    val thickness: HighlighterThickness = HighlighterThickness.MEDIUM,
    val customColorArgb: Int? = null,
) {
    val colorArgb: Int get() = customColorArgb ?: color.argb
}

fun opaqueColor(argb: Int): Int = argb or 0xFF000000.toInt()

fun highlighterColor(rgb: Int): Int = (rgb and 0x00FFFFFF) or 0x66000000

enum class DrawingTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    LINE,
    RECTANGLE,
    CIRCLE,
    TEXT,
}

enum class TextSize(val sizeSp: Float) { SMALL(16f), MEDIUM(24f), LARGE(32f) }

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
