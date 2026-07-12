package com.kotlinsun.noteup.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kotlinsun.noteup.domain.model.DrawingSettings
import com.kotlinsun.noteup.domain.model.DrawingTool
import com.kotlinsun.noteup.domain.model.EraserMode
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
    var onStrokeCompleted: ((PendingCanvasStroke) -> Unit)? = null
    var onStrokesErased: ((List<ErasableStroke>) -> Unit)? = null
    var onAreaErased: ((List<AreaEraseReplacement>) -> Unit)? = null
    var drawingSettings: DrawingSettings = DrawingSettings()

    private val renderer = StrokeRenderer()
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val storedStrokes = mutableListOf<Stroke>()
    private val pendingStrokes = mutableListOf<PendingCanvasStroke>()
    private val erasedInGesture = linkedMapOf<String, ErasableStroke>()
    private val eraserPath = mutableListOf<Pair<Float, Float>>()
    private var areaPreview: List<AreaEraseReplacement> = emptyList()
    private var strokeBitmap: Bitmap? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val activePoints = mutableListOf<StrokePoint>()
    private val activeBounds = RectF()
    private var activeSettings = DrawingSettings()
    private var strokeStartedAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var eraserX: Float? = null
    private var eraserY: Float? = null
    private var nextToken = System.nanoTime()

    fun setStrokes(strokes: List<Stroke>) {
        val previousIds = storedStrokes.mapTo(hashSetOf(), Stroke::id)
        val newlyStored = strokes.filterNot { it.id in previousIds }
        newlyStored.forEach { saved ->
            val pendingIndex = pendingStrokes.indexOfFirst { pending ->
                pending.draft.matches(saved)
            }
            if (pendingIndex >= 0) pendingStrokes.removeAt(pendingIndex)
        }
        val previewFragments = areaPreview.flatMap(AreaEraseReplacement::fragments)
        if (previewFragments.isEmpty() || newlyStored.containsAllDrafts(previewFragments)) {
            areaPreview = emptyList()
        }
        storedStrokes.clear()
        storedStrokes.addAll(strokes)
        rebuildStrokeBitmap()
        invalidate()
    }

    fun discardPendingStroke(token: Long) {
        pendingStrokes.removeAll { it.token == token }
        invalidate()
    }

    fun refreshVisibleStrokes(strokes: List<Stroke>) {
        areaPreview = emptyList()
        setStrokes(strokes)
    }

    fun cancelActiveStroke() {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
        val dirtyBounds = RectF(activeBounds)
        if (activeSettings.tool == DrawingTool.ERASER) {
            erasedInGesture.values.forEach { target ->
                when (target) {
                    is ErasableStroke.Persisted -> {
                        if (storedStrokes.none { it.id == target.stroke.id }) {
                            storedStrokes += target.stroke
                        }
                    }
                    is ErasableStroke.Pending -> {
                        if (pendingStrokes.none { it.token == target.stroke.token }) {
                            pendingStrokes += target.stroke
                        }
                    }
                }
            }
            rebuildStrokeBitmap()
        }
        resetActiveInput()
        invalidateDirtyBounds(dirtyBounds)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildStrokeBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokeBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val hiddenPendingTokens = areaPreview.mapNotNullTo(hashSetOf()) { replacement ->
            (replacement.target as? ErasableStroke.Pending)?.stroke?.token
        }
        pendingStrokes.filterNot { it.token in hiddenPendingTokens }.forEach {
            drawStroke(canvas, it.draft)
        }
        areaPreview.flatMap(AreaEraseReplacement::fragments).forEach { drawStroke(canvas, it) }
        if (activePoints.isNotEmpty() && activeSettings.tool != DrawingTool.ERASER) {
            drawStroke(canvas, activeDraft(activePoints))
        }
        val cursorX = eraserX
        val cursorY = eraserY
        if (cursorX != null && cursorY != null) {
            canvas.drawCircle(cursorX, cursorY, eraserRadiusPx(), eraserPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInputEnabled) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startInput(event)
            MotionEvent.ACTION_MOVE -> continueInput(event)
            MotionEvent.ACTION_UP -> finishInput(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) finishInput(event)
                else activePointerId != MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActiveStroke()
                true
            }
            else -> activePointerId != MotionEvent.INVALID_POINTER_ID
        }
    }

    private fun startInput(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS || width == 0 || height == 0) {
            return false
        }
        activePointerId = event.getPointerId(0)
        activeSettings = drawingSettings
        strokeStartedAt = event.eventTime
        activePoints.clear()
        erasedInGesture.clear()
        eraserPath.clear()
        if (areaPreview.isNotEmpty()) {
            areaPreview = emptyList()
            rebuildStrokeBitmap()
        }
        lastX = event.x
        lastY = event.y
        activeBounds.set(event.x, event.y, event.x, event.y)
        if (activeSettings.tool == DrawingTool.ERASER) {
            eraseAt(event.x, event.y)
            updateAreaPreviewIfNeeded()
        } else {
            addPoint(event.x, event.y, event.pressure, event.eventTime)
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidateDirtySegment(event.x, event.y, event.x, event.y)
        return true
    }

    private fun continueInput(event: MotionEvent): Boolean {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return false
        for (historyIndex in 0 until event.historySize) {
            appendSample(
                event.getHistoricalX(pointerIndex, historyIndex),
                event.getHistoricalY(pointerIndex, historyIndex),
                event.getHistoricalPressure(pointerIndex, historyIndex),
                event.getHistoricalEventTime(historyIndex),
            )
        }
        appendSample(
            event.getX(pointerIndex),
            event.getY(pointerIndex),
            event.getPressure(pointerIndex),
            event.eventTime,
        )
        updateAreaPreviewIfNeeded()
        return true
    }

    private fun finishInput(event: MotionEvent): Boolean {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex >= 0) {
            appendSample(
                event.getX(pointerIndex),
                event.getY(pointerIndex),
                event.getPressure(pointerIndex),
                event.eventTime,
            )
            updateAreaPreviewIfNeeded()
        }
        val completedBounds = RectF(activeBounds)
        var keepAreaPreview = false
        if (activeSettings.tool == DrawingTool.ERASER) {
            if (activeSettings.eraserMode == EraserMode.AREA) {
                val replacements = areaPreview
                if (replacements.isNotEmpty()) onAreaErased?.invoke(replacements)
                keepAreaPreview = replacements.isNotEmpty()
            } else if (erasedInGesture.isNotEmpty()) {
                onStrokesErased?.invoke(erasedInGesture.values.toList())
            }
        } else if (activePoints.size >= MINIMUM_POINT_COUNT) {
            val pending = PendingCanvasStroke(nextToken++, activeDraft(activePoints.toList()))
            pendingStrokes += pending
            onStrokeCompleted?.invoke(pending)
        }
        resetActiveInput(clearAreaPreview = !keepAreaPreview)
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidateDirtyBounds(completedBounds)
        return true
    }

    private fun appendSample(x: Float, y: Float, pressure: Float, eventTime: Long) {
        if (activeSettings.tool == DrawingTool.ERASER) eraseAt(x, y)
        else addPoint(x, y, pressure, eventTime)
        activeBounds.union(x, y)
        invalidateDirtySegment(lastX, lastY, x, y)
        lastX = x
        lastY = y
    }

    private fun eraseAt(x: Float, y: Float) {
        eraserX = x
        eraserY = y
        if (activeSettings.eraserMode == EraserMode.AREA) {
            eraserPath += x to y
            return
        }
        val radius = eraserRadiusPx()
        val hitStored = storedStrokes.filter { stroke ->
            "stored:${stroke.id}" !in erasedInGesture && StrokeHitTester.hits(
                stroke.points, stroke.tool, stroke.width, x, y, radius, width, height,
                resources.displayMetrics.density,
            )
        }
        hitStored.forEach { erasedInGesture["stored:${it.id}"] = ErasableStroke.Persisted(it) }
        val hitPending = pendingStrokes.filter { pending ->
            "pending:${pending.token}" !in erasedInGesture && StrokeHitTester.hits(
                pending.draft.points, pending.draft.tool, pending.draft.width, x, y, radius,
                width, height, resources.displayMetrics.density,
            )
        }
        hitPending.forEach { erasedInGesture["pending:${it.token}"] = ErasableStroke.Pending(it) }
        if (hitStored.isNotEmpty()) {
            storedStrokes.removeAll { stroke -> hitStored.any { it.id == stroke.id } }
            rebuildStrokeBitmap()
        }
        if (hitPending.isNotEmpty()) {
            pendingStrokes.removeAll { pending -> hitPending.any { it.token == pending.token } }
        }
    }

    private fun updateAreaPreviewIfNeeded() {
        if (activeSettings.tool != DrawingTool.ERASER ||
            activeSettings.eraserMode != EraserMode.AREA
        ) return
        areaPreview = createAreaReplacements()
        rebuildStrokeBitmap()
    }

    private fun StrokeDraft.matches(stroke: Stroke): Boolean =
        tool == stroke.tool && colorArgb == stroke.colorArgb &&
            width == stroke.width && points == stroke.points

    private fun List<Stroke>.containsAllDrafts(drafts: List<StrokeDraft>): Boolean {
        val candidates = toMutableList()
        return drafts.all { draft ->
            val index = candidates.indexOfFirst { stroke -> draft.matches(stroke) }
            if (index < 0) false else {
                candidates.removeAt(index)
                true
            }
        }
    }

    private fun createAreaReplacements(): List<AreaEraseReplacement> {
        if (eraserPath.isEmpty()) return emptyList()
        val density = resources.displayMetrics.density
        val persisted = storedStrokes.map { ErasableStroke.Persisted(it) to it.toDraft() }
        val pending = pendingStrokes.map { ErasableStroke.Pending(it) to it.draft }
        return (persisted + pending).mapNotNull { (target, draft) ->
            val keptGroups = mutableListOf<MutableList<StrokePoint>>()
            draft.points.forEach { point ->
                val px = point.x * width
                val py = point.y * height
                val erased = eraserPath.any { (ex, ey) ->
                    val dx = px - ex
                    val dy = py - ey
                    dx * dx + dy * dy <= areaCollisionRadius(draft, density).let { it * it }
                }
                if (erased) {
                    if (keptGroups.lastOrNull()?.isEmpty() == false) {
                        keptGroups.add(mutableListOf())
                    }
                } else {
                    if (keptGroups.isEmpty()) keptGroups.add(mutableListOf())
                    keptGroups.last().add(point)
                }
            }
            val fragments = keptGroups.filter { it.isNotEmpty() }.map { points ->
                val usablePoints = if (points.size == 1) listOf(points[0], points[0]) else points
                draft.copy(points = usablePoints)
            }
            if (fragments.size == 1 && fragments.single().points == draft.points) null
            else AreaEraseReplacement(target, fragments)
        }
    }

    private fun areaCollisionRadius(draft: StrokeDraft, density: Float): Float {
        val strokeRadius = when (draft.tool) {
            StrokeTool.HIGHLIGHTER -> draft.width * density / 2f
            StrokeTool.PEN -> draft.width * 1.55f * density / 2f
        }
        return eraserRadiusPx() + strokeRadius
    }

    private fun Stroke.toDraft() = StrokeDraft(tool, colorArgb, width, points)

    private fun activeDraft(points: List<StrokePoint>): StrokeDraft = when (activeSettings.tool) {
        DrawingTool.HIGHLIGHTER -> StrokeDraft(
            StrokeTool.HIGHLIGHTER,
            activeSettings.highlighter.color.argb,
            activeSettings.highlighter.thickness.widthDp,
            points,
        )
        else -> StrokeDraft(
            StrokeTool.PEN,
            activeSettings.pen.color.argb,
            activeSettings.pen.thickness.widthDp,
            points,
        )
    }

    private fun addPoint(x: Float, y: Float, pressure: Float, eventTime: Long) {
        activePoints += StrokePoint(
            (x / width).coerceIn(0f, 1f),
            (y / height).coerceIn(0f, 1f),
            pressure,
            (eventTime - strokeStartedAt).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    private fun rebuildStrokeBitmap() {
        strokeBitmap?.recycle()
        strokeBitmap = null
        if (width <= 0 || height <= 0) return
        strokeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val hiddenIds = areaPreview.mapNotNullTo(hashSetOf()) { replacement ->
                (replacement.target as? ErasableStroke.Persisted)?.stroke?.id
            }
            storedStrokes.filterNot { it.id in hiddenIds }
                .sortedBy(Stroke::strokeIndex)
                .forEach { stroke ->
                    renderer.draw(
                        canvas, stroke.points, stroke.colorArgb, stroke.width, width, height,
                        resources.displayMetrics.density, stroke.tool,
                    )
                }
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: StrokeDraft) {
        renderer.draw(
            canvas, stroke.points, stroke.colorArgb, stroke.width, width, height,
            resources.displayMetrics.density, stroke.tool,
        )
    }

    private fun eraserRadiusPx() = ERASER_RADIUS_DP * resources.displayMetrics.density
    private fun dirtyPadding() = maxOf(
        renderer.maximumStrokeWidthPx(resources.displayMetrics.density) / 2f,
        eraserRadiusPx(),
    ) + 2f

    private fun invalidateDirtySegment(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val padding = dirtyPadding()
        postInvalidateOnAnimation(
            (min(fromX, toX) - padding).toInt(), (min(fromY, toY) - padding).toInt(),
            ceil(max(fromX, toX) + padding).toInt(), ceil(max(fromY, toY) + padding).toInt(),
        )
    }

    private fun invalidateDirtyBounds(bounds: RectF) {
        val padding = dirtyPadding()
        postInvalidateOnAnimation(
            (bounds.left - padding).toInt(), (bounds.top - padding).toInt(),
            ceil(bounds.right + padding).toInt(), ceil(bounds.bottom + padding).toInt(),
        )
    }

    private fun resetActiveInput(clearAreaPreview: Boolean = true) {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activePoints.clear()
        erasedInGesture.clear()
        eraserPath.clear()
        if (clearAreaPreview && areaPreview.isNotEmpty()) {
            areaPreview = emptyList()
            rebuildStrokeBitmap()
        }
        eraserX = null
        eraserY = null
        activeBounds.setEmpty()
    }

    override fun onDetachedFromWindow() {
        strokeBitmap?.recycle()
        strokeBitmap = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MINIMUM_POINT_COUNT = 2
        const val ERASER_RADIUS_DP = 12f
    }
}
