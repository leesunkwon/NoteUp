package com.kotlinsun.noteup.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.RectF
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
        pdfBackground: Bitmap? = null,
    ) {
        canvas.drawColor(Color.WHITE)
        val contentRect = if (pdfBackground == null) {
            drawTemplate(canvas, width, height, density, template)
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        } else {
            fitCenterRect(width, height, pdfBackground).also { rect ->
                canvas.drawBitmap(pdfBackground, null, rect, null)
            }
        }
        canvas.save()
        canvas.translate(contentRect.left, contentRect.top)
        val elements: List<Pair<Int, Any>> = strokes.map { it.strokeIndex to it as Any } +
            texts.map { it.elementIndex to it as Any }
        elements.sortedBy { it.first }.forEach { (_, element) ->
            when (element) {
                is Stroke -> {
                strokeRenderer.draw(
                    canvas, element.points, element.colorArgb, element.width,
                    contentRect.width().toInt(), contentRect.height().toInt(),
                    density, element.tool,
                )
                }
                is CanvasText -> textRenderer.draw(
                    canvas, element, contentRect.width().toInt(), contentRect.height().toInt(), density,
                )
            }
        }
        canvas.restore()
    }

    fun fitCenterRect(width: Int, height: Int, bitmap: Bitmap): RectF {
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val targetWidth = bitmap.width * scale
        val targetHeight = bitmap.height * scale
        val left = (width - targetWidth) / 2f
        val top = (height - targetHeight) / 2f
        return RectF(left, top, left + targetWidth, top + targetHeight)
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
