package com.kotlinsun.noteup.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.model.StrokePoint
import com.kotlinsun.noteup.domain.model.StrokeTool
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var isInputEnabled: Boolean = false
    var onStrokeCompleted: ((StrokeDraft) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val storedStrokes = mutableListOf<Stroke>()
    private val pendingPaths = mutableListOf<Path>()
    private var strokeBitmap: Bitmap? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var activePath: Path? = null
    private val activePoints = mutableListOf<StrokePoint>()
    private val activeBounds = RectF()
    private var strokeStartedAt = 0L
    private var lastX = 0f
    private var lastY = 0f

    fun setStrokes(strokes: List<Stroke>) {
        val newlyPersistedCount = (strokes.size - storedStrokes.size)
            .coerceIn(0, pendingPaths.size)
        repeat(newlyPersistedCount) { pendingPaths.removeAt(0) }
        storedStrokes.clear()
        storedStrokes.addAll(strokes)
        rebuildStrokeBitmap()
        invalidate()
    }

    fun cancelActiveStroke() {
        if (activePath == null) return
        val dirtyBounds = RectF(activeBounds)
        resetActiveStroke()
        invalidateDirtyBounds(dirtyBounds)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildStrokeBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokeBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        configurePaint(DEFAULT_COLOR_ARGB, DEFAULT_WIDTH_DP)
        pendingPaths.forEach { canvas.drawPath(it, paint) }
        activePath?.let { canvas.drawPath(it, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInputEnabled) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startStroke(event)
            MotionEvent.ACTION_MOVE -> continueStroke(event)
            MotionEvent.ACTION_UP -> finishStroke(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    finishStroke(event)
                } else {
                    activePointerId != MotionEvent.INVALID_POINTER_ID
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActiveStroke()
                true
            }
            else -> activePointerId != MotionEvent.INVALID_POINTER_ID
        }
    }

    private fun startStroke(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS || width == 0 || height == 0) {
            return false
        }
        activePointerId = event.getPointerId(0)
        strokeStartedAt = event.eventTime
        activePoints.clear()
        activePath = Path().apply { moveTo(event.x, event.y) }
        lastX = event.x
        lastY = event.y
        activeBounds.set(event.x, event.y, event.x, event.y)
        addPoint(event.x, event.y, event.pressure, event.eventTime)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidateDirtySegment(event.x, event.y, event.x, event.y)
        return true
    }

    private fun continueStroke(event: MotionEvent): Boolean {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0 || activePath == null) return false

        for (historyIndex in 0 until event.historySize) {
            appendSample(
                x = event.getHistoricalX(pointerIndex, historyIndex),
                y = event.getHistoricalY(pointerIndex, historyIndex),
                pressure = event.getHistoricalPressure(pointerIndex, historyIndex),
                eventTime = event.getHistoricalEventTime(historyIndex),
            )
        }
        appendSample(
            x = event.getX(pointerIndex),
            y = event.getY(pointerIndex),
            pressure = event.getPressure(pointerIndex),
            eventTime = event.eventTime,
        )
        return true
    }

    private fun finishStroke(event: MotionEvent): Boolean {
        val pointerIndex = event.findPointerIndex(activePointerId)
        val path = activePath ?: return false
        if (pointerIndex >= 0) {
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)
            appendSample(x, y, event.getPressure(pointerIndex), event.eventTime)
            path.lineTo(x, y)
            if (activeBounds.isEmpty) {
                path.rLineTo(DOT_PATH_OFFSET, DOT_PATH_OFFSET)
            }
        }

        val completedPoints = activePoints.toList()
        val completedBounds = RectF(activeBounds)
        if (completedPoints.size >= MINIMUM_POINT_COUNT) {
            pendingPaths += Path(path)
            onStrokeCompleted?.invoke(
                StrokeDraft(
                    tool = StrokeTool.PEN,
                    colorArgb = DEFAULT_COLOR_ARGB,
                    width = DEFAULT_WIDTH_DP,
                    points = completedPoints,
                ),
            )
        }
        resetActiveStroke()
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidateDirtyBounds(completedBounds)
        return true
    }

    private fun appendSample(x: Float, y: Float, pressure: Float, eventTime: Long) {
        val path = activePath ?: return
        val midpointX = (lastX + x) / 2f
        val midpointY = (lastY + y) / 2f
        path.quadTo(lastX, lastY, midpointX, midpointY)
        addPoint(x, y, pressure, eventTime)
        activeBounds.union(x, y)
        invalidateDirtySegment(lastX, lastY, x, y)
        lastX = x
        lastY = y
    }

    private fun addPoint(x: Float, y: Float, pressure: Float, eventTime: Long) {
        activePoints += StrokePoint(
            x = (x / width).coerceIn(0f, 1f),
            y = (y / height).coerceIn(0f, 1f),
            pressure = pressure.coerceIn(0f, 1f),
            timeOffsetMillis = (eventTime - strokeStartedAt)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt(),
        )
    }

    private fun rebuildStrokeBitmap() {
        strokeBitmap?.recycle()
        strokeBitmap = null
        if (width <= 0 || height <= 0) return

        strokeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            storedStrokes.forEach { stroke ->
                if (stroke.points.isEmpty()) return@forEach
                configurePaint(stroke.colorArgb, stroke.width)
                canvas.drawPath(buildPath(stroke), paint)
            }
        }
    }

    private fun buildPath(stroke: Stroke): Path {
        val first = stroke.points.first()
        var previousX = first.x * width
        var previousY = first.y * height
        return Path().apply {
            moveTo(previousX, previousY)
            stroke.points.drop(1).forEach { point ->
                val x = point.x * width
                val y = point.y * height
                quadTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
                previousX = x
                previousY = y
            }
            lineTo(previousX, previousY)
            if (stroke.points.all { it.x == first.x && it.y == first.y }) {
                rLineTo(DOT_PATH_OFFSET, DOT_PATH_OFFSET)
            }
        }
    }

    private fun configurePaint(colorArgb: Int, widthDp: Float) {
        paint.color = colorArgb
        paint.strokeWidth = widthDp * resources.displayMetrics.density
    }

    private fun invalidateDirtySegment(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val padding = dirtyPadding()
        invalidate(
            (min(fromX, toX) - padding).toInt(),
            (min(fromY, toY) - padding).toInt(),
            ceil(max(fromX, toX) + padding).toInt(),
            ceil(max(fromY, toY) + padding).toInt(),
        )
    }

    private fun invalidateDirtyBounds(bounds: RectF) {
        val padding = dirtyPadding()
        invalidate(
            (bounds.left - padding).toInt(),
            (bounds.top - padding).toInt(),
            ceil(bounds.right + padding).toInt(),
            ceil(bounds.bottom + padding).toInt(),
        )
    }

    private fun dirtyPadding() = DEFAULT_WIDTH_DP * resources.displayMetrics.density + 2f

    private fun resetActiveStroke() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activePath = null
        activePoints.clear()
        activeBounds.setEmpty()
    }

    override fun onDetachedFromWindow() {
        strokeBitmap?.recycle()
        strokeBitmap = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DEFAULT_COLOR_ARGB = Color.BLACK
        const val DEFAULT_WIDTH_DP = 3f
        const val MINIMUM_POINT_COUNT = 2
        const val DOT_PATH_OFFSET = 0.01f
    }
}
