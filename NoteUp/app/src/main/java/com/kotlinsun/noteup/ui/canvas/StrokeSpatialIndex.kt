package com.kotlinsun.noteup.ui.canvas

import android.graphics.RectF
import kotlin.math.floor

class StrokeSpatialIndex(private val cellSize: Float) {
    private val cells = mutableMapOf<Long, MutableSet<Long>>()

    fun clear() = cells.clear()

    fun insert(id: Long, bounds: RectF) {
        if (bounds.isEmpty) return
        forEachCell(bounds) { key -> cells.getOrPut(key) { linkedSetOf() } += id }
    }

    fun query(bounds: RectF): Set<Long> {
        if (bounds.isEmpty) return emptySet()
        val result = linkedSetOf<Long>()
        forEachCell(bounds) { key -> cells[key]?.let(result::addAll) }
        return result
    }

    private inline fun forEachCell(bounds: RectF, action: (Long) -> Unit) {
        val left = coordinate(bounds.left)
        val right = coordinate(bounds.right)
        val top = coordinate(bounds.top)
        val bottom = coordinate(bounds.bottom)
        for (x in left..right) {
            for (y in top..bottom) action(key(x, y))
        }
    }

    private fun coordinate(value: Float): Int = floor(value / cellSize.coerceAtLeast(1f)).toInt()

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
}
