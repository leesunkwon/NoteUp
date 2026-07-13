package com.kotlinsun.noteup.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.domain.model.DrawingSettings
import com.kotlinsun.noteup.domain.model.DrawingTool
import com.kotlinsun.noteup.domain.model.EraserMode
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.model.StrokePoint
import com.kotlinsun.noteup.domain.model.StrokeTool
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.rendering.CanvasTextRenderer
import com.kotlinsun.noteup.rendering.StrokeRenderer
import com.kotlinsun.noteup.rendering.PageRenderer
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
    var onViewportChanged: ((CanvasViewport) -> Unit)? = null
    var onTextRequested: ((Float, Float) -> Unit)? = null
    var onSelectionChanged: ((CanvasSelection) -> Unit)? = null
    var onSelectionTransformed: ((SelectionChange) -> Unit)? = null
    var drawingSettings: DrawingSettings = DrawingSettings()

    private val renderer = StrokeRenderer()
    private val textRenderer = CanvasTextRenderer()
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val pageRenderer = PageRenderer(renderer)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val storedStrokes = mutableListOf<Stroke>()
    private val storedTexts = mutableListOf<CanvasText>()
    private val pendingStrokes = mutableListOf<PendingCanvasStroke>()
    private val storedBounds = mutableMapOf<Long, RectF>()
    private val pendingBounds = mutableMapOf<Long, RectF>()
    private val erasedInGesture = linkedMapOf<String, ErasableStroke>()
    private val eraserPath = mutableListOf<Pair<Float, Float>>()
    private var areaPreview: List<AreaEraseReplacement> = emptyList()
    private var strokeBitmap: Bitmap? = null
    private var areaPreviewBitmap: Bitmap? = null
    private val areaPreviewDirtyBounds = RectF()
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
    private var currentPageId: Long? = null
    private var pageTemplate = PageTemplate.BLANK
    private var viewport = CanvasViewport()
    private var lastGestureFocusX = 0f
    private var lastGestureFocusY = 0f
    private var isTouchGestureActive = false
    private var selection = CanvasSelection()
    private val selectionBounds = RectF()
    private var selectionBeforeTransform: CanvasSelection? = null
    private var selectionTransformMode = SelectionTransformMode.NONE
    private var transformStartX = 0f
    private var transformStartY = 0f
    private val transformBaseBounds = RectF()

    fun setTexts(texts: List<CanvasText>) {
        storedTexts.clear()
        storedTexts.addAll(texts)
        selection = CanvasSelection(
            selection.strokes.mapNotNull { selected -> storedStrokes.firstOrNull { it.id == selected.id } },
            selection.texts.mapNotNull { selected -> texts.firstOrNull { it.id == selected.id } },
        )
        updateSelectionBounds()
        rebuildStrokeBitmap()
        invalidate()
    }

    fun currentSelection(): CanvasSelection = selection

    fun selectElements(value: CanvasSelection) {
        selection = value
        updateSelectionBounds()
        onSelectionChanged?.invoke(selection)
        invalidate()
    }

    fun syncSelection(value: CanvasSelection) {
        selection = value
        updateSelectionBounds()
        invalidate()
    }

    fun clearSelection() = selectElements(CanvasSelection())

    fun showPage(
        pageId: Long,
        template: PageTemplate,
        strokes: List<Stroke>,
        viewport: CanvasViewport,
    ) {
        if (currentPageId != pageId) {
            cancelActiveStroke()
            pendingStrokes.clear()
            erasedInGesture.clear()
            eraserPath.clear()
            areaPreview = emptyList()
            selection = CanvasSelection()
            storedTexts.clear()
            currentPageId = pageId
        }
        pageTemplate = template
        setViewport(viewport, notify = false)
        setStrokes(strokes)
    }

    fun setViewport(value: CanvasViewport, notify: Boolean = false) {
        viewport = clampViewport(value)
        if (notify) onViewportChanged?.invoke(viewport)
        invalidate()
    }

    fun setStrokes(strokes: List<Stroke>) {
        val previousStrokes = storedStrokes.toList()
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
            clearAreaPreviewBitmap()
        }
        storedStrokes.clear()
        storedStrokes.addAll(strokes)
        rebuildBoundsCache()
        val appended = areaPreview.isEmpty() && strokeBitmap != null &&
            strokes.size >= previousStrokes.size &&
            strokes.take(previousStrokes.size).map(Stroke::id) == previousStrokes.map(Stroke::id)
        if (appended) {
            val canvas = Canvas(checkNotNull(strokeBitmap))
            strokes.drop(previousStrokes.size).forEach { stroke ->
                renderer.draw(
                    canvas, stroke.points, stroke.colorArgb, stroke.width, width, height,
                    resources.displayMetrics.density, stroke.tool,
                )
            }
        } else rebuildStrokeBitmap()
        invalidate()
    }

    fun discardPendingStroke(token: Long) {
        pendingStrokes.removeAll { it.token == token }
        pendingBounds.remove(token)
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
            rebuildBoundsCache()
            rebuildStrokeBitmap()
        }
        resetActiveInput()
        invalidateDirtyBounds(dirtyBounds)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        viewport = clampViewport(viewport)
        rebuildBoundsCache()
        rebuildStrokeBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(viewport.offsetX, viewport.offsetY)
        canvas.scale(viewport.scale, viewport.scale)
        drawTemplate(canvas)
        (areaPreviewBitmap ?: strokeBitmap)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val hiddenPendingTokens = areaPreview.mapNotNullTo(hashSetOf()) { replacement ->
            (replacement.target as? ErasableStroke.Pending)?.stroke?.token
        }
        pendingStrokes.filterNot { it.token in hiddenPendingTokens }.forEach {
            drawStroke(canvas, it.draft)
        }
        areaPreview.filter { it.target is ErasableStroke.Pending }
            .flatMap(AreaEraseReplacement::fragments).forEach { drawStroke(canvas, it) }
        if (activePoints.isNotEmpty() && activeSettings.tool !in setOf(
                DrawingTool.ERASER, DrawingTool.LASSO, DrawingTool.TEXT,
            )
        ) {
            drawStroke(canvas, activeDraft(activePoints))
        }
        drawSelectionOverlay(canvas)
        val cursorX = eraserX
        val cursorY = eraserY
        if (cursorX != null && cursorY != null) {
            canvas.drawCircle(cursorX, cursorY, eraserRadiusPx(), eraserPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInputEnabled) return false
        if (activePointerId == MotionEvent.INVALID_POINTER_ID && !containsStylus(event)) {
            return handleTouchGesture(event)
        }
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
        val actionIndex = event.actionIndex
        if (event.getToolType(actionIndex) != MotionEvent.TOOL_TYPE_STYLUS || width == 0 || height == 0) {
            return false
        }
        activePointerId = event.getPointerId(actionIndex)
        activeSettings = drawingSettings
        strokeStartedAt = event.eventTime
        activePoints.clear()
        erasedInGesture.clear()
        eraserPath.clear()
        if (areaPreview.isNotEmpty()) {
            areaPreview = emptyList()
            rebuildStrokeBitmap()
        }
        val screenX = event.getX(actionIndex)
        val screenY = event.getY(actionIndex)
        lastX = screenX
        lastY = screenY
        activeBounds.set(screenX, screenY, screenX, screenY)
        val contentX = toContentX(screenX)
        val contentY = toContentY(screenY)
        if (activeSettings.tool == DrawingTool.TEXT) {
            onTextRequested?.invoke(
                (contentX / width).coerceIn(0f, 1f),
                (contentY / height).coerceIn(0f, 1f),
            )
            resetActiveInput()
            return true
        }
        if (activeSettings.tool == DrawingTool.LASSO && !selection.isEmpty) {
            val handleRadius = HANDLE_RADIUS_DP * resources.displayMetrics.density
            selectionTransformMode = when {
                distance(contentX, contentY, selectionBounds.right, selectionBounds.bottom) <= handleRadius ->
                    SelectionTransformMode.RESIZE
                selectionBounds.contains(contentX, contentY) -> SelectionTransformMode.MOVE
                else -> SelectionTransformMode.NONE
            }
            if (selectionTransformMode != SelectionTransformMode.NONE) {
                selectionBeforeTransform = selection
                transformBaseBounds.set(selectionBounds)
                transformStartX = contentX
                transformStartY = contentY
                return true
            }
            clearSelection()
        }
        if (activeSettings.tool == DrawingTool.ERASER) {
            eraseAt(contentX, contentY)
            updateAreaPreviewIfNeeded()
        } else {
            addPoint(contentX, contentY, event.getPressure(actionIndex), event.eventTime)
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidateDirtySegment(event.x, event.y, event.x, event.y)
        return true
    }

    private fun continueInput(event: MotionEvent): Boolean {
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return false
        if (selectionTransformMode != SelectionTransformMode.NONE) {
            updateSelectionTransform(
                toContentX(event.getX(pointerIndex)), toContentY(event.getY(pointerIndex)),
            )
            return true
        }
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
        if (selectionTransformMode != SelectionTransformMode.NONE) {
            if (pointerIndex >= 0) updateSelectionTransform(
                toContentX(event.getX(pointerIndex)), toContentY(event.getY(pointerIndex)),
            )
            val before = selectionBeforeTransform
            if (before != null && before != selection) {
                onSelectionTransformed?.invoke(SelectionChange(before, selection))
            }
            selectionBeforeTransform = null
            selectionTransformMode = SelectionTransformMode.NONE
            resetActiveInput()
            return true
        }
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
        } else if (activeSettings.tool == DrawingTool.LASSO) {
            completeLassoSelection()
        } else if (activePoints.size >= MINIMUM_POINT_COUNT) {
            val pending = PendingCanvasStroke(nextToken++, activeDraft(activePoints.toList()))
            pendingStrokes += pending
            pendingBounds[pending.token] = boundsFor(pending.draft)
            onStrokeCompleted?.invoke(pending)
        }
        resetActiveInput(clearAreaPreview = !keepAreaPreview)
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidateDirtyBounds(completedBounds)
        return true
    }

    private fun appendSample(x: Float, y: Float, pressure: Float, eventTime: Long) {
        val contentX = toContentX(x)
        val contentY = toContentY(y)
        if (activeSettings.tool == DrawingTool.ERASER) eraseAt(contentX, contentY)
        else addPoint(contentX, contentY, pressure, eventTime)
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
            "stored:${stroke.id}" !in erasedInGesture &&
                boundsHits(storedBounds[stroke.id], x, y, radius) && StrokeHitTester.hits(
                stroke.points, stroke.tool, stroke.width, x, y, radius, width, height,
                resources.displayMetrics.density,
            )
        }
        val hitPending = pendingStrokes.filter { pending ->
            "pending:${pending.token}" !in erasedInGesture &&
                boundsHits(pendingBounds[pending.token], x, y, radius) && StrokeHitTester.hits(
                pending.draft.points, pending.draft.tool, pending.draft.width, x, y, radius,
                width, height, resources.displayMetrics.density,
            )
        }
        hitStored.forEach { erasedInGesture["stored:${it.id}"] = ErasableStroke.Persisted(it) }
        hitPending.forEach { erasedInGesture["pending:${it.token}"] = ErasableStroke.Pending(it) }
        if (hitStored.isNotEmpty()) {
            storedStrokes.removeAll { stroke -> hitStored.any { it.id == stroke.id } }
            rebuildStrokeBitmap()
        }
        if (hitPending.isNotEmpty()) {
            pendingStrokes.removeAll { pending -> hitPending.any { it.token == pending.token } }
            hitPending.forEach { pendingBounds.remove(it.token) }
        }
    }

    private fun handleTouchGesture(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                val (focusX, focusY) = gestureFocus(event)
                lastGestureFocusX = focusX
                lastGestureFocusY = focusY
                isTouchGestureActive = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (isTouchGestureActive && event.pointerCount >= 2) {
                val (focusX, focusY) = gestureFocus(event)
                updateViewport(
                    viewport.copy(
                        offsetX = viewport.offsetX + focusX - lastGestureFocusX,
                        offsetY = viewport.offsetY + focusY - lastGestureFocusY,
                    ),
                )
                lastGestureFocusX = focusX
                lastGestureFocusY = focusY
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 2) {
                    isTouchGestureActive = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }

    private fun updateViewport(value: CanvasViewport) {
        viewport = clampViewport(value)
        onViewportChanged?.invoke(viewport)
        invalidate()
    }

    private fun clampViewport(value: CanvasViewport): CanvasViewport {
        val scale = value.scale.coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)
        if (scale == MINIMUM_SCALE) return CanvasViewport()
        if (width <= 0 || height <= 0) return value.copy(scale = scale)
        return CanvasViewport(
            scale = scale,
            offsetX = value.offsetX.coerceIn(width * (1f - scale), 0f),
            offsetY = value.offsetY.coerceIn(height * (1f - scale), 0f),
        )
    }

    private fun gestureFocus(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (index in 0 until event.pointerCount) {
            x += event.getX(index)
            y += event.getY(index)
        }
        return x / event.pointerCount to y / event.pointerCount
    }

    private fun containsStylus(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS }

    private fun toContentX(screenX: Float) = (screenX - viewport.offsetX) / viewport.scale
    private fun toContentY(screenY: Float) = (screenY - viewport.offsetY) / viewport.scale

    private fun updateAreaPreviewIfNeeded() {
        if (activeSettings.tool != DrawingTool.ERASER ||
            activeSettings.eraserMode != EraserMode.AREA
        ) return
        areaPreview = createAreaReplacements()
        rebuildAreaPreviewBitmap()
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
        val persisted = storedStrokes.filter { areaPathHits(storedBounds[it.id]) }
            .map { ErasableStroke.Persisted(it) to it.toDraft() }
        val pending = pendingStrokes.filter { areaPathHits(pendingBounds[it.token]) }
            .map { ErasableStroke.Pending(it) to it.draft }
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
            else -> draft.width * 1.55f * density / 2f
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
        DrawingTool.LINE, DrawingTool.RECTANGLE, DrawingTool.CIRCLE -> StrokeDraft(
            when (activeSettings.tool) {
                DrawingTool.LINE -> StrokeTool.LINE
                DrawingTool.RECTANGLE -> StrokeTool.RECTANGLE
                else -> StrokeTool.CIRCLE
            },
            activeSettings.pen.color.argb,
            activeSettings.pen.thickness.widthDp,
            shapePoints(points),
        )
        else -> StrokeDraft(
            StrokeTool.PEN,
            activeSettings.pen.color.argb,
            activeSettings.pen.thickness.widthDp,
            points,
        )
    }

    private fun shapePoints(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size < 2) return points
        val start = points.first()
        val rawEnd = points.last()
        if (activeSettings.tool != DrawingTool.CIRCLE) return listOf(start, rawEnd)
        val dx = (rawEnd.x - start.x) * width
        val dy = (rawEnd.y - start.y) * height
        val size = min(kotlin.math.abs(dx), kotlin.math.abs(dy))
        return listOf(
            start,
            rawEnd.copy(
                x = (start.x + kotlin.math.sign(dx) * size / width).coerceIn(0f, 1f),
                y = (start.y + kotlin.math.sign(dy) * size / height).coerceIn(0f, 1f),
            ),
        )
    }

    private fun completeLassoSelection() {
        if (activePoints.size < 3) return
        val lassoBounds = RectF(
            activePoints.minOf { it.x } * width,
            activePoints.minOf { it.y } * height,
            activePoints.maxOf { it.x } * width,
            activePoints.maxOf { it.y } * height,
        )
        val polygon = activePoints.map { it.x * width to it.y * height }
        val strokes = storedStrokes.filter { stroke ->
            storedBounds[stroke.id]?.let { RectF.intersects(it, lassoBounds) } == true &&
                (stroke.points.any { pointInPolygon(it.x * width, it.y * height, polygon) } ||
                    polygon.any { (x, y) -> storedBounds[stroke.id]?.contains(x, y) == true })
        }
        val texts = storedTexts.filter { text ->
            val bounds = textBounds(text)
            RectF.intersects(bounds, lassoBounds) && (
                listOf(
                    bounds.left to bounds.top, bounds.right to bounds.top,
                    bounds.right to bounds.bottom, bounds.left to bounds.bottom,
                ).any { (x, y) -> pointInPolygon(x, y, polygon) } ||
                    polygon.any { (x, y) -> bounds.contains(x, y) }
                )
        }
        selectElements(CanvasSelection(strokes, texts))
    }

    private fun pointInPolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            if ((current.second > y) != (previous.second > y) &&
                x < (previous.first - current.first) * (y - current.second) /
                (previous.second - current.second) + current.first
            ) inside = !inside
            previous = current
        }
        return inside
    }

    private fun updateSelectionBounds() {
        selectionBounds.setEmpty()
        selection.strokes.forEach { storedBounds[it.id]?.let(selectionBounds::union) }
        selection.texts.forEach { selectionBounds.union(textBounds(it)) }
    }

    private fun textBounds(value: CanvasText): RectF = RectF(
        value.x * width,
        value.y * height,
        (value.x + value.boxWidth) * width,
        value.y * height + textRenderer.height(value, width, resources.displayMetrics.density),
    )

    private fun updateSelectionTransform(x: Float, y: Float) {
        val source = selectionBeforeTransform ?: return
        val dx = (x - transformStartX) / width
        val dy = (y - transformStartY) / height
        val maximumScale = min(
            (width - transformBaseBounds.left) / transformBaseBounds.width().coerceAtLeast(1f),
            (height - transformBaseBounds.top) / transformBaseBounds.height().coerceAtLeast(1f),
        ).coerceAtLeast(0.1f)
        val scale = if (selectionTransformMode == SelectionTransformMode.RESIZE) {
            val base = max(transformBaseBounds.width(), transformBaseBounds.height()).coerceAtLeast(1f)
            (1f + max(x - transformStartX, y - transformStartY) / base)
                .coerceIn(0.1f, maximumScale)
        } else 1f
        val translatedX = if (selectionTransformMode == SelectionTransformMode.MOVE) {
            dx.coerceIn(-transformBaseBounds.left / width, (width - transformBaseBounds.right) / width)
        } else 0f
        val translatedY = if (selectionTransformMode == SelectionTransformMode.MOVE) {
            dy.coerceIn(-transformBaseBounds.top / height, (height - transformBaseBounds.bottom) / height)
        } else 0f
        val originX = transformBaseBounds.left / width
        val originY = transformBaseBounds.top / height
        fun point(p: StrokePoint) = p.copy(
            x = originX + (p.x - originX) * scale + translatedX,
            y = originY + (p.y - originY) * scale + translatedY,
        )
        val strokes = source.strokes.map { stroke ->
            stroke.copy(points = stroke.points.map(::point), width = stroke.width * scale)
        }
        val texts = source.texts.map { text ->
            text.copy(
                x = originX + (text.x - originX) * scale + translatedX,
                y = originY + (text.y - originY) * scale + translatedY,
                boxWidth = (text.boxWidth * scale).coerceIn(0.05f, 1f),
                textSizeSp = (text.textSizeSp * scale).coerceAtLeast(4f),
                updatedAt = System.currentTimeMillis(),
            )
        }
        strokes.forEach { changed -> storedStrokes.indexOfFirst { it.id == changed.id }.takeIf { it >= 0 }?.let { storedStrokes[it] = changed } }
        texts.forEach { changed -> storedTexts.indexOfFirst { it.id == changed.id }.takeIf { it >= 0 }?.let { storedTexts[it] = changed } }
        selection = CanvasSelection(strokes, texts)
        rebuildBoundsCache()
        rebuildStrokeBitmap()
        updateSelectionBounds()
        invalidate()
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        if (activeSettings.tool == DrawingTool.LASSO && activePoints.size > 1 && selectionTransformMode == SelectionTransformMode.NONE) {
            val path = android.graphics.Path()
            path.moveTo(activePoints.first().x * width, activePoints.first().y * height)
            activePoints.drop(1).forEach { path.lineTo(it.x * width, it.y * height) }
            canvas.drawPath(path, selectionPaint)
        }
        if (!selectionBounds.isEmpty) {
            canvas.drawRect(selectionBounds, selectionPaint)
            canvas.drawCircle(selectionBounds.right, selectionBounds.bottom, HANDLE_RADIUS_DP * resources.displayMetrics.density, handlePaint)
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float) =
        kotlin.math.hypot(x1 - x2, y1 - y2)

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 99, 235) }

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
            val elements: List<Pair<Int, Any>> = storedStrokes.map { it.strokeIndex to it as Any } +
                storedTexts.map { it.elementIndex to it as Any }
            elements.sortedBy { it.first }.forEach { (_, element) ->
                when (element) {
                    is Stroke -> renderer.draw(
                        canvas, element.points, element.colorArgb, element.width, width, height,
                        resources.displayMetrics.density, element.tool,
                    )
                    is CanvasText -> textRenderer.draw(
                        canvas, element, width, height, resources.displayMetrics.density,
                    )
                }
            }
        }
    }

    private fun rebuildAreaPreviewBitmap() {
        val base = strokeBitmap ?: return
        if (areaPreviewBitmap == null || areaPreviewBitmap?.width != width ||
            areaPreviewBitmap?.height != height
        ) {
            clearAreaPreviewBitmap()
            areaPreviewBitmap = base.copy(Bitmap.Config.ARGB_8888, true)
        }
        val currentDirty = RectF()
        areaPreview.forEach { replacement ->
            when (val target = replacement.target) {
                is ErasableStroke.Persisted -> storedBounds[target.stroke.id]?.let { currentDirty.union(it) }
                is ErasableStroke.Pending -> Unit
            }
        }
        if (currentDirty.isEmpty) return
        currentDirty.inset(-dirtyPadding(), -dirtyPadding())
        if (!areaPreviewDirtyBounds.isEmpty) currentDirty.union(areaPreviewDirtyBounds)
        areaPreviewDirtyBounds.set(currentDirty)
        val canvas = Canvas(checkNotNull(areaPreviewBitmap))
        canvas.save()
        canvas.clipRect(currentDirty)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val hiddenIds = areaPreview.mapNotNullTo(hashSetOf()) {
            (it.target as? ErasableStroke.Persisted)?.stroke?.id
        }
        storedStrokes.filterNot { it.id in hiddenIds }
            .filter { storedBounds[it.id]?.let { bounds -> RectF.intersects(bounds, currentDirty) } == true }
            .sortedBy(Stroke::strokeIndex)
            .forEach { stroke ->
                renderer.draw(
                    canvas, stroke.points, stroke.colorArgb, stroke.width, width, height,
                    resources.displayMetrics.density, stroke.tool,
                )
            }
        storedTexts.filter { RectF.intersects(textBounds(it), currentDirty) }
            .sortedBy(CanvasText::elementIndex)
            .forEach { textRenderer.draw(canvas, it, width, height, resources.displayMetrics.density) }
        areaPreview.filter { it.target is ErasableStroke.Persisted }
            .flatMap(AreaEraseReplacement::fragments)
            .forEach { drawStroke(canvas, it) }
        canvas.restore()
    }

    private fun clearAreaPreviewBitmap() {
        areaPreviewBitmap?.recycle()
        areaPreviewBitmap = null
        areaPreviewDirtyBounds.setEmpty()
    }

    private fun rebuildBoundsCache() {
        storedBounds.clear()
        storedStrokes.forEach { storedBounds[it.id] = boundsFor(it.toDraft()) }
        pendingBounds.clear()
        pendingStrokes.forEach { pendingBounds[it.token] = boundsFor(it.draft) }
    }

    private fun boundsFor(draft: StrokeDraft): RectF {
        if (draft.points.isEmpty()) return RectF()
        val padding = areaCollisionRadius(draft, resources.displayMetrics.density) - eraserRadiusPx()
        return RectF(
            draft.points.minOf { it.x } * width - padding,
            draft.points.minOf { it.y } * height - padding,
            draft.points.maxOf { it.x } * width + padding,
            draft.points.maxOf { it.y } * height + padding,
        )
    }

    private fun boundsHits(bounds: RectF?, x: Float, y: Float, radius: Float): Boolean =
        bounds != null && x >= bounds.left - radius && x <= bounds.right + radius &&
            y >= bounds.top - radius && y <= bounds.bottom + radius

    private fun areaPathHits(bounds: RectF?): Boolean = bounds != null && eraserPath.any { (x, y) ->
        boundsHits(bounds, x, y, eraserRadiusPx())
    }

    private fun drawTemplate(canvas: Canvas) {
        canvas.drawColor(context.getColor(R.color.noteup_page))
        pageRenderer.drawTemplate(
            canvas, width, height, resources.displayMetrics.density, pageTemplate,
        )
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
    ) * viewport.scale + 2f

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
            clearAreaPreviewBitmap()
        }
        eraserX = null
        eraserY = null
        activeBounds.setEmpty()
    }

    override fun onDetachedFromWindow() {
        strokeBitmap?.recycle()
        strokeBitmap = null
        clearAreaPreviewBitmap()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MINIMUM_POINT_COUNT = 2
        const val ERASER_RADIUS_DP = 12f
        const val MINIMUM_SCALE = 1f
        const val MAXIMUM_SCALE = 4f
        const val HANDLE_RADIUS_DP = 10f
    }

    private enum class SelectionTransformMode { NONE, MOVE, RESIZE }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = viewport.scale
            val newScale = (oldScale * detector.scaleFactor).coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)
            if (newScale == oldScale) return true
            val ratio = newScale / oldScale
            updateViewport(
                CanvasViewport(
                    scale = newScale,
                    offsetX = detector.focusX - (detector.focusX - viewport.offsetX) * ratio,
                    offsetY = detector.focusY - (detector.focusY - viewport.offsetY) * ratio,
                ),
            )
            return true
        }
    }
}
