package com.kotlinsun.noteup.ui.canvas

import com.kotlinsun.noteup.domain.model.StrokePoint
import com.kotlinsun.noteup.domain.model.StrokeTool
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object StrokeHitTester {
    fun hits(
        points: List<StrokePoint>,
        tool: StrokeTool,
        baseWidthDp: Float,
        eraserX: Float,
        eraserY: Float,
        eraserRadiusPx: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        density: Float,
    ): Boolean {
        if (points.isEmpty()) return false
        val strokeRadius = if (tool == StrokeTool.HIGHLIGHTER) {
            baseWidthDp * density / 2f
        } else {
            baseWidthDp * MAXIMUM_PEN_PRESSURE_FACTOR * density / 2f
        }
        val hitRadius = eraserRadiusPx + strokeRadius
        val screenPoints = points.map { it.x * canvasWidth to it.y * canvasHeight }
        val minX = screenPoints.minOf { it.first } - hitRadius
        val maxX = screenPoints.maxOf { it.first } + hitRadius
        val minY = screenPoints.minOf { it.second } - hitRadius
        val maxY = screenPoints.maxOf { it.second } + hitRadius
        if (eraserX !in minX..maxX || eraserY !in minY..maxY) return false

        if (screenPoints.size >= 2 && tool == StrokeTool.RECTANGLE) {
            val left = screenPoints.minOf { it.first }
            val right = screenPoints.maxOf { it.first }
            val top = screenPoints.minOf { it.second }
            val bottom = screenPoints.maxOf { it.second }
            return listOf(
                floatArrayOf(left, top, right, top),
                floatArrayOf(right, top, right, bottom),
                floatArrayOf(right, bottom, left, bottom),
                floatArrayOf(left, bottom, left, top),
            ).any { edge ->
                distanceToSegment(eraserX, eraserY, edge[0], edge[1], edge[2], edge[3]) <= hitRadius
            }
        }
        if (screenPoints.size >= 2 && tool == StrokeTool.CIRCLE) {
            val centerX = (screenPoints.first().first + screenPoints.last().first) / 2f
            val centerY = (screenPoints.first().second + screenPoints.last().second) / 2f
            val radius = kotlin.math.abs(screenPoints.last().first - screenPoints.first().first) / 2f
            return kotlin.math.abs(distance(eraserX, eraserY, centerX, centerY) - radius) <= hitRadius
        }

        if (screenPoints.size == 1) {
            return distance(eraserX, eraserY, screenPoints[0].first, screenPoints[0].second) <= hitRadius
        }
        return screenPoints.zipWithNext().any { (start, end) ->
            distanceToSegment(
                eraserX,
                eraserY,
                start.first,
                start.second,
                end.first,
                end.second,
            ) <= hitRadius
        }
    }

    private fun distanceToSegment(
        pointX: Float,
        pointY: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): Float {
        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0f) return distance(pointX, pointY, startX, startY)
        val projection = ((pointX - startX) * deltaX + (pointY - startY) * deltaY) /
            lengthSquared
        val ratio = max(0f, min(1f, projection))
        return distance(pointX, pointY, startX + ratio * deltaX, startY + ratio * deltaY)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val deltaX = x1 - x2
        val deltaY = y1 - y2
        return sqrt(deltaX * deltaX + deltaY * deltaY)
    }

    private const val MAXIMUM_PEN_PRESSURE_FACTOR = 1.55f
}
