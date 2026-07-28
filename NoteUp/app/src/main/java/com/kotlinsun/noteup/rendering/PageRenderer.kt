package com.kotlinsun.noteup.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.RectF
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasImage

class PageRenderer(
    private val strokeRenderer: StrokeRenderer = StrokeRenderer(),
    private val textRenderer: CanvasTextRenderer = CanvasTextRenderer(),
) {
    private val templatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_TEMPLATE_LINE_COLOR
        strokeWidth = 1f
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val missingImagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 238, 241)
        style = Paint.Style.FILL
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
        images: List<CanvasImage> = emptyList(),
        imageBitmaps: Map<Long, Bitmap> = emptyMap(),
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
            texts.map { it.elementIndex to it as Any } +
            images.map { it.elementIndex to it as Any }
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
                is CanvasImage -> {
                    val target = imageRect(element, contentRect.width(), contentRect.height())
                    val bitmap = imageBitmaps[element.id]
                    if (bitmap == null || bitmap.isRecycled) {
                        canvas.drawRect(target, missingImagePaint)
                    } else {
                        canvas.drawBitmap(bitmap, null, target, imagePaint)
                    }
                }
            }
        }
        canvas.restore()
    }

    fun imageRect(image: CanvasImage, width: Float, height: Float): RectF = RectF(
        image.x * width,
        image.y * height,
        (image.x + image.boxWidth) * width,
        (image.y + image.boxHeight) * height,
    )

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
        lineColor: Int = DEFAULT_TEMPLATE_LINE_COLOR,
    ) {
        templatePaint.color = lineColor
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

    private companion object {
        val DEFAULT_TEMPLATE_LINE_COLOR: Int = Color.rgb(220, 224, 230)
    }
}
