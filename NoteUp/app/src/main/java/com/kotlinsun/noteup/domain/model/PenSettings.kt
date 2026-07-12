package com.kotlinsun.noteup.domain.model

data class PenSettings(
    val color: PenColor = PenColor.BLACK,
    val thickness: PenThickness = PenThickness.MEDIUM,
)

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
