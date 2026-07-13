package com.kotlinsun.noteup.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.CanvasText

class PageRenderer(
    private val strokeRenderer: StrokeRenderer = StrokeRenderer(),
    private val textRenderer: CanvasTextRenderer = CanvasTextRenderer(),
) {
    private val templatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 224, 230)
        strokeWidth = 1f
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        density: Float,
        template: PageTemplate,
        strokes: List<Stroke>,
        texts: List<CanvasText> = emptyList(),
    ) {
        canvas.drawColor(Color.WHITE)
        drawTemplate(canvas, width, height, density, template)
        val elements: List<Pair<Int, Any>> = strokes.map { it.strokeIndex to it as Any } +
            texts.map { it.elementIndex to it as Any }
        elements.sortedBy { it.first }.forEach { (_, element) ->
            when (element) {
                is Stroke -> {
                strokeRenderer.draw(
                    canvas, element.points, element.colorArgb, element.width, width, height,
                    density, element.tool,
                )
                }
                is CanvasText -> textRenderer.draw(canvas, element, width, height, density)
            }
        }
    }

    fun drawTemplate(
        canvas: Canvas,
        width: Int,
        height: Int,
        density: Float,
        template: PageTemplate,
    ) {
        templatePaint.strokeWidth = density.coerceAtLeast(1f)
        when (template) {
            PageTemplate.BLANK -> Unit
            PageTemplate.LINED -> drawHorizontalLines(canvas, width, height, 32f * density)
            PageTemplate.GRID -> {
                val spacing = 24f * density
                drawHorizontalLines(canvas, width, height, spacing)
                var x = spacing
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height.toFloat(), templatePaint)
                    x += spacing
                }
            }
        }
    }

    private fun drawHorizontalLines(canvas: Canvas, width: Int, height: Int, spacing: Float) {
        var y = spacing
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, templatePaint)
            y += spacing
        }
    }
}
