package com.kotlinsun.noteup.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.kotlinsun.noteup.domain.model.StrokePoint
import com.kotlinsun.noteup.domain.model.StrokeTool

class StrokeRenderer {
    private val segmentPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(
        canvas: Canvas,
        points: List<StrokePoint>,
        colorArgb: Int,
        baseWidthDp: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        density: Float,
        tool: StrokeTool,
    ) {
        if (points.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return

        if (tool == StrokeTool.LINE || tool == StrokeTool.RECTANGLE || tool == StrokeTool.CIRCLE) {
            drawShape(canvas, points, colorArgb, baseWidthDp, canvasWidth, canvasHeight, density, tool)
            return
        }

        if (tool == StrokeTool.HIGHLIGHTER) {
            drawHighlighter(canvas, points, colorArgb, baseWidthDp, canvasWidth, canvasHeight, density)
            return
        }

        paint.color = colorArgb
        val first = points.first()
        val firstX = first.x * canvasWidth
        val firstY = first.y * canvasHeight
        var previousX = firstX
        var previousY = firstY
        var previousEndX = firstX
        var previousEndY = firstY
        var smoothedPressure = normalizePressure(first.pressure)

        if (points.all { it.x == first.x && it.y == first.y }) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(
                firstX,
                firstY,
                calculateWidthPx(baseWidthDp, smoothedPressure, density) / 2f,
                paint,
            )
            paint.style = Paint.Style.STROKE
            return
        }

        for (pointIndex in 1 until points.size) {
            val point = points[pointIndex]
            val x = point.x * canvasWidth
            val y = point.y * canvasHeight
            val midpointX = (previousX + x) / 2f
            val midpointY = (previousY + y) / 2f
            val nextPressure = smoothPressure(smoothedPressure, point.pressure)
            paint.strokeWidth = calculateWidthPx(
                baseWidthDp,
                (smoothedPressure + nextPressure) / 2f,
                density,
            )
            segmentPath.reset()
            segmentPath.moveTo(previousEndX, previousEndY)
            segmentPath.quadTo(previousX, previousY, midpointX, midpointY)
            canvas.drawPath(segmentPath, paint)
            previousX = x
            previousY = y
            previousEndX = midpointX
            previousEndY = midpointY
            smoothedPressure = nextPressure
        }

        paint.strokeWidth = calculateWidthPx(baseWidthDp, smoothedPressure, density)
        segmentPath.reset()
        segmentPath.moveTo(previousEndX, previousEndY)
        segmentPath.quadTo(previousX, previousY, previousX, previousY)
        canvas.drawPath(segmentPath, paint)
    }

    private fun drawShape(
        canvas: Canvas,
        points: List<StrokePoint>,
        colorArgb: Int,
        widthDp: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        density: Float,
        tool: StrokeTool,
    ) {
        if (points.size < 2) return
        val start = points.first()
        val end = points.last()
        val left = minOf(start.x, end.x) * canvasWidth
        val top = minOf(start.y, end.y) * canvasHeight
        val right = maxOf(start.x, end.x) * canvasWidth
        val bottom = maxOf(start.y, end.y) * canvasHeight
        paint.style = Paint.Style.STROKE
        paint.color = colorArgb
        paint.strokeWidth = widthDp * density
        when (tool) {
            StrokeTool.LINE -> canvas.drawLine(
                start.x * canvasWidth, start.y * canvasHeight,
                end.x * canvasWidth, end.y * canvasHeight, paint,
            )
            StrokeTool.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
            StrokeTool.CIRCLE -> canvas.drawOval(RectF(left, top, right, bottom), paint)
            else -> Unit
        }
    }

    private fun drawHighlighter(
        canvas: Canvas,
        points: List<StrokePoint>,
        colorArgb: Int,
        baseWidthDp: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        density: Float,
    ) {
        val first = points.first()
        val firstX = first.x * canvasWidth
        val firstY = first.y * canvasHeight
        paint.color = colorArgb
        paint.strokeWidth = baseWidthDp * density

        if (points.all { it.x == first.x && it.y == first.y }) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(firstX, firstY, paint.strokeWidth / 2f, paint)
            paint.style = Paint.Style.STROKE
            return
        }

        segmentPath.reset()
        segmentPath.moveTo(firstX, firstY)
        var previousX = firstX
        var previousY = firstY
        for (pointIndex in 1 until points.size) {
            val point = points[pointIndex]
            val x = point.x * canvasWidth
            val y = point.y * canvasHeight
            segmentPath.quadTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
            previousX = x
            previousY = y
        }
        segmentPath.lineTo(previousX, previousY)
        canvas.drawPath(segmentPath, paint)
    }

    fun maximumStrokeWidthPx(density: Float): Float =
        maxOf(
            MAXIMUM_PEN_WIDTH_DP * MAXIMUM_PRESSURE_FACTOR,
            MAXIMUM_HIGHLIGHTER_WIDTH_DP,
        ) * density

    private fun smoothPressure(previous: Float, current: Float): Float =
        previous + (normalizePressure(current) - previous) * PRESSURE_SMOOTHING_FACTOR

    private fun normalizePressure(pressure: Float): Float {
        val safePressure = if (pressure.isFinite() && pressure > 0f) {
            pressure
        } else {
            DEFAULT_PRESSURE
        }
        return safePressure.coerceIn(MINIMUM_PRESSURE, MAXIMUM_PRESSURE)
    }

    private fun calculateWidthPx(baseWidthDp: Float, pressure: Float, density: Float): Float {
        val pressureRatio = (pressure - MINIMUM_PRESSURE) /
            (MAXIMUM_PRESSURE - MINIMUM_PRESSURE)
        val factor = MINIMUM_PRESSURE_FACTOR +
            pressureRatio * (MAXIMUM_PRESSURE_FACTOR - MINIMUM_PRESSURE_FACTOR)
        return baseWidthDp * factor * density
    }

    private companion object {
        const val MINIMUM_PRESSURE = 0.1f
        const val MAXIMUM_PRESSURE = 1f
        const val DEFAULT_PRESSURE = 0.5f
        const val MINIMUM_PRESSURE_FACTOR = 0.45f
        const val MAXIMUM_PRESSURE_FACTOR = 1.55f
        const val PRESSURE_SMOOTHING_FACTOR = 0.25f
        const val MAXIMUM_PEN_WIDTH_DP = 7f
        const val MAXIMUM_HIGHLIGHTER_WIDTH_DP = 28f
    }
}
