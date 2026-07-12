package com.kotlinsun.noteup.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kotlinsun.noteup.domain.model.PenSettings
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
    var penSettings: PenSettings = PenSettings()

    private val renderer = StrokeRenderer()
    private val storedStrokes = mutableListOf<Stroke>()
    private val pendingStrokes = mutableListOf<StrokeDraft>()
    private var strokeBitmap: Bitmap? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val activePoints = mutableListOf<StrokePoint>()
    private val activeBounds = RectF()
    private var activePenSettings = PenSettings()
    private var strokeStartedAt = 0L
    private var lastX = 0f
    private var lastY = 0f

    fun setStrokes(strokes: List<Stroke>) {
        val newlyPersistedCount = (strokes.size - storedStrokes.size)
            .coerceIn(0, pendingStrokes.size)
        repeat(newlyPersistedCount) { pendingStrokes.removeAt(0) }
        storedStrokes.clear()
        storedStrokes.addAll(strokes)
        rebuildStrokeBitmap()
        invalidate()
    }

    fun cancelActiveStroke() {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
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
        pendingStrokes.forEach { stroke ->
            drawStroke(canvas, stroke.points, stroke.colorArgb, stroke.width)
        }
        if (activePoints.isNotEmpty()) {
            drawStroke(
                canvas,
                activePoints,
                activePenSettings.color.argb,
                activePenSettings.thickness.widthDp,
            )
        }
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
        activePenSettings = penSettings
        strokeStartedAt = event.eventTime
        activePoints.clear()
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
        if (pointerIndex < 0 || activePoints.isEmpty()) return false

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
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex >= 0) {
            appendSample(
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                event.getPressure(pointerIndex),
                event.eventTime,
            )
        }

        val completedBounds = RectF(activeBounds)
        if (activePoints.size >= MINIMUM_POINT_COUNT) {
            val completedStroke = StrokeDraft(
                tool = StrokeTool.PEN,
                colorArgb = activePenSettings.color.argb,
                width = activePenSettings.thickness.widthDp,
                points = activePoints.toList(),
            )
            pendingStrokes += completedStroke
            onStrokeCompleted?.invoke(completedStroke)
        }
        resetActiveStroke()
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidateDirtyBounds(completedBounds)
        return true
    }

    private fun appendSample(x: Float, y: Float, pressure: Float, eventTime: Long) {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
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
            pressure = pressure,
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
                drawStroke(canvas, stroke.points, stroke.colorArgb, stroke.width)
            }
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        points: List<StrokePoint>,
        colorArgb: Int,
        baseWidthDp: Float,
    ) {
        renderer.draw(
            canvas = canvas,
            points = points,
            colorArgb = colorArgb,
            baseWidthDp = baseWidthDp,
            canvasWidth = width,
            canvasHeight = height,
            density = resources.displayMetrics.density,
        )
    }

    private fun invalidateDirtySegment(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val padding = dirtyPadding()
        postInvalidateOnAnimation(
            (min(fromX, toX) - padding).toInt(),
            (min(fromY, toY) - padding).toInt(),
            ceil(max(fromX, toX) + padding).toInt(),
            ceil(max(fromY, toY) + padding).toInt(),
        )
    }

    private fun invalidateDirtyBounds(bounds: RectF) {
        val padding = dirtyPadding()
        postInvalidateOnAnimation(
            (bounds.left - padding).toInt(),
            (bounds.top - padding).toInt(),
            ceil(bounds.right + padding).toInt(),
            ceil(bounds.bottom + padding).toInt(),
        )
    }

    private fun dirtyPadding() =
        renderer.maximumStrokeWidthPx(resources.displayMetrics.density) / 2f + 2f

    private fun resetActiveStroke() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activePoints.clear()
        activeBounds.setEmpty()
    }

    override fun onDetachedFromWindow() {
        strokeBitmap?.recycle()
        strokeBitmap = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MINIMUM_POINT_COUNT = 2
    }
}
