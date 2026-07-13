package com.kotlinsun.noteup.ui.canvas

import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.Stroke

data class CanvasSelection(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<CanvasText> = emptyList(),
) {
    val isEmpty get() = strokes.isEmpty() && texts.isEmpty()
}

data class SelectionChange(val before: CanvasSelection, val after: CanvasSelection)
