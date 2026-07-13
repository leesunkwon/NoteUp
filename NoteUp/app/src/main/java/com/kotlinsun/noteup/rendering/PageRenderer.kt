package com.kotlinsun.noteup.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke

class PageRenderer(
    private val strokeRenderer: StrokeRenderer = StrokeRenderer(),
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
    ) {
        canvas.drawColor(Color.WHITE)
        drawTemplate(canvas, width, height, density, template)
        strokes.sortedBy(Stroke::strokeIndex).forEach { stroke ->
            strokeRenderer.draw(
                canvas, stroke.points, stroke.colorArgb, stroke.width, width, height,
                density, stroke.tool,
            )
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
