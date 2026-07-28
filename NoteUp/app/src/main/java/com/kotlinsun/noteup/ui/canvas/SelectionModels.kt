package com.kotlinsun.noteup.ui.canvas

import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.CanvasImage

data class CanvasSelection(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<CanvasText> = emptyList(),
    val images: List<CanvasImage> = emptyList(),
) {
    val isEmpty get() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty()
}

data class SelectionChange(val before: CanvasSelection, val after: CanvasSelection)
