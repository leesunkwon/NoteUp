package com.kotlinsun.noteup.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.domain.model.CanvasAppearance
import com.kotlinsun.noteup.domain.model.CanvasInputMode
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasImage
import com.kotlinsun.noteup.domain.model.DrawingSettings
import com.kotlinsun.noteup.domain.model.DrawingTool
import com.kotlinsun.noteup.domain.model.EraserMode
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.PenColor
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.model.StrokePoint
import com.kotlinsun.noteup.domain.model.StrokeTool
import com.kotlinsun.noteup.rendering.CanvasTextRenderer
import com.kotlinsun.noteup.rendering.PageRenderer
import com.kotlinsun.noteup.rendering.StrokeRenderer
import com.kotlinsun.noteup.data.pdf.PdfTileRenderResult
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
    var onCanvasSizeChanged: ((Int, Int) -> Unit)? = null
    var onTextRequested: ((Float, Float) -> Unit)? = null
    var onTextEditRequested: ((CanvasText) -> Unit)? = null
    var onSelectionChanged: ((CanvasSelection) -> Unit)? = null
    var onSelectionTransformed: ((SelectionChange) -> Unit)? = null
    var onPageSwipe: ((PageSwipeDirection) -> Unit)? = null
    var isPageSwipeEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) resetPageSwipe()
        }
    var canvasInputMode: CanvasInputMode = CanvasInputMode.STYLUS_ONLY
        set(value) {
            if (field == value) return
            cancelActiveStroke()
            resetPageSwipe()
            isTouchGestureActive = false
            parent?.requestDisallowInterceptTouchEvent(false)
            field = value
        }
    var canvasAppearance: CanvasAppearance = CanvasAppearance.WHITE_PAPER
        set(value) {
            if (field == value) return
            field = value
            clearAreaPreviewBitmap()
            rebuildStrokeBitmap()
            invalidate()
        }
    var drawingSettings: DrawingSettings = DrawingSettings()
        set(value) {
            if (field.tool == DrawingTool.TEXT && value.tool != DrawingTool.TEXT) {
                cancelTextInput(restorePreview = true)
                lastTextTapId = null
            }
            if (field.tool != value.tool &&
                (field.tool == DrawingTool.POINTER || value.tool == DrawingTool.POINTER)
            ) {
                cancelActiveStroke()
                resetNavigationDrag()
                resetPageSwipe()
                isTouchGestureActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            field = value
            updateNavigationPointerIcon(dragging = false)
        }

    private val renderer = StrokeRenderer()
    private val textRenderer = CanvasTextRenderer()
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.noteup_fg_neutral_muted)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val pageRenderer = PageRenderer(renderer)
    private var pdfBackgroundBitmap: Bitmap? = null
    private var pdfTiles: List<PdfTileRenderResult> = emptyList()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    private val storedStrokes = mutableListOf<Stroke>()
    private val storedTexts = mutableListOf<CanvasText>()
    private val storedImages = mutableListOf<CanvasImage>()
    private var imageBitmaps: Map<Long, Bitmap> = emptyMap()
    private val pendingStrokes = mutableListOf<PendingCanvasStroke>()
    private val storedBounds = mutableMapOf<Long, RectF>()
    private val storedSpatialIndex = StrokeSpatialIndex(
        SPATIAL_CELL_DP * resources.displayMetrics.density,
    )
    private val pendingBounds = mutableMapOf<Long, RectF>()
    private val erasedInGesture = linkedMapOf<String, ErasableStroke>()
    private val eraserPath = mutableListOf<Pair<Float, Float>>()
    private var areaPreview: List<AreaEraseReplacement> = emptyList()
    private var strokeBitmap: Bitmap? = null
    private var areaPreviewBitmap: Bitmap? = null
    private val areaPreviewDirtyBounds = RectF()
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeInputIsFinger = false
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
    private var navigationPointerId = MotionEvent.INVALID_POINTER_ID
    private var navigationDownX = 0f
    private var navigationDownY = 0f
    private var navigationLastX = 0f
    private var navigationLastY = 0f
    private var navigationDragging = false
    private var navigationInputToolType = MotionEvent.TOOL_TYPE_UNKNOWN
    private var navigationStartScale = MINIMUM_SCALE
    private var navigationStartOffsetX = 0f
    private var pageSwipePointerId = MotionEvent.INVALID_POINTER_ID
    private var pageSwipeStartX = 0f
    private var pageSwipeStartY = 0f
    private var pageSwipeTracking = false
    private var selection = CanvasSelection()
    private val selectionBounds = RectF()
    private var selectionBeforeTransform: CanvasSelection? = null
    private var selectionTransformMode = SelectionTransformMode.NONE
    private var transformStartX = 0f
    private var transformStartY = 0f
    private val transformBaseBounds = RectF()
    private var textPointerId = MotionEvent.INVALID_POINTER_ID
    private var textTouchTarget: CanvasText? = null
    private var textTouchDownScreenX = 0f
    private var textTouchDownScreenY = 0f
    private var textTouchDownContentX = 0f
    private var textTouchDownContentY = 0f
    private var textTouchMoved = false
    private var lastTextTapId: Long? = null
    private var lastTextTapAt = 0L
    private var lastTextTapX = 0f
    private var lastTextTapY = 0f
    private var temporaryEraserActive = false
    private var suppressStylusUntilUp = false
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val missingImagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.noteup_bg_neutral_weak)
        style = Paint.Style.FILL
    }

    fun setTexts(texts: List<CanvasText>) {
        storedTexts.clear()
        storedTexts.addAll(texts)
        selection = CanvasSelection(
            selection.strokes.mapNotNull { selected -> storedStrokes.firstOrNull { it.id == selected.id } },
            selection.texts.mapNotNull { selected -> texts.firstOrNull { it.id == selected.id } },
            selection.images.mapNotNull { selected -> storedImages.firstOrNull { it.id == selected.id } },
        )
        updateSelectionBounds()
        rebuildStrokeBitmap()
        invalidate()
    }

    fun setImages(images: List<CanvasImage>, bitmaps: Map<Long, Bitmap>) {
        storedImages.clear()
        storedImages.addAll(images)
        val imageIds = images.mapTo(hashSetOf(), CanvasImage::id)
        imageBitmaps = when {
            images.isEmpty() -> emptyMap()
            bitmaps.isNotEmpty() -> bitmaps
            else -> imageBitmaps.filterKeys { it in imageIds }
        }
        selection = CanvasSelection(
            selection.strokes.mapNotNull { selected -> storedStrokes.firstOrNull { it.id == selected.id } },
            selection.texts.mapNotNull { selected -> storedTexts.firstOrNull { it.id == selected.id } },
            selection.images.mapNotNull { selected -> images.firstOrNull { it.id == selected.id } },
        )
        updateSelectionBounds()
        rebuildStrokeBitmap()
        invalidate()
    }

    fun currentSelection(): CanvasSelection = selection

    fun visiblePageCenterNormalized(): PointF {
        val contentX = toContentX(width / 2f)
        val contentY = toContentY(height / 2f)
        return PointF(normalizedPageX(contentX), normalizedPageY(contentY))
    }

    fun selectionBoundsInView(): RectF? = if (selectionBounds.isEmpty) {
        null
    } else {
        RectF(
            selectionBounds.left * viewport.scale + viewport.offsetX,
            selectionBounds.top * viewport.scale + viewport.offsetY,
            selectionBounds.right * viewport.scale + viewport.offsetX,
            selectionBounds.bottom * viewport.scale + viewport.offsetY,
        )
    }

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
            lastTextTapId = null
            pendingStrokes.clear()
            erasedInGesture.clear()
            eraserPath.clear()
            areaPreview = emptyList()
            selection = CanvasSelection()
            storedTexts.clear()
            storedImages.clear()
            imageBitmaps = emptyMap()
            currentPageId = pageId
            pdfBackgroundBitmap = null
            pdfTiles = emptyList()
        }
        pageTemplate = template
        setViewport(viewport, notify = false)
        setStrokes(strokes)
    }

    fun setPdfBackground(bitmap: Bitmap?) {
        if (pdfBackgroundBitmap === bitmap) return
        pdfBackgroundBitmap = bitmap
        rebuildBoundsCache()
        rebuildStrokeBitmap()
        invalidate()
    }

    fun setPdfTiles(tiles: List<PdfTileRenderResult>) {
        pdfTiles = tiles
        invalidate()
    }

    fun setViewport(value: CanvasViewport, notify: Boolean = false) {
        val remapped = remapViewport(value, width, height)
        viewport = remapped
        if (notify || remapped != value) onViewportChanged?.invoke(viewport)
        invalidate()
    }

    fun adjustZoom(delta: Float) {
        if (width <= 0 || height <= 0) return
        zoomAt(
            (viewport.scale + delta).coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE),
            width / 2f,
            height / 2f,
        )
    }

    fun resetZoom() {
        if (viewport == CanvasViewport()) return
        updateViewport(CanvasViewport())
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
            val pageRect = pageContentRect()
            canvas.save()
            canvas.translate(pageRect.left, pageRect.top)
            strokes.drop(previousStrokes.size).forEach { stroke ->
                renderer.draw(
                    canvas, stroke.points, displayColor(stroke.colorArgb), stroke.width,
                    pageRect.width().toInt(), pageRect.height().toInt(),
                    resources.displayMetrics.density, stroke.tool,
                )
            }
            canvas.restore()
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
        cancelTextInput(restorePreview = true)
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
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidateDirtyBounds(dirtyBounds)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val previous = viewport
        viewport = remapViewport(viewport, width, height, oldWidth, oldHeight)
        if (viewport != previous) onViewportChanged?.invoke(viewport)
        rebuildBoundsCache()
        rebuildStrokeBitmap()
        if (width > 0 && height > 0) onCanvasSizeChanged?.invoke(width, height)
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
                DrawingTool.POINTER, DrawingTool.ERASER, DrawingTool.LASSO, DrawingTool.TEXT,
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
        if (drawingSettings.tool == DrawingTool.POINTER) {
            return handleNavigationToolEvent(event)
        }
        if (suppressStylusUntilUp) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) suppressStylusUntilUp = false
            return true
        }
        val hasHardwareEraser = containsHardwareEraser(event)
        val wantsTemporaryEraser = hasHardwareEraser && drawingSettings.tool != DrawingTool.ERASER
        val isHardwareToolTransition = event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        if (activePointerId != MotionEvent.INVALID_POINTER_ID &&
            temporaryEraserActive != wantsTemporaryEraser &&
            isHardwareToolTransition
        ) {
            finishInput(event)
            if (wantsTemporaryEraser) return startInput(event)
            suppressStylusUntilUp = true
            return true
        }
        if (drawingSettings.tool == DrawingTool.TEXT && !hasHardwareEraser) {
            return handleTextToolEvent(event)
        }
        if (activePointerId != MotionEvent.INVALID_POINTER_ID &&
            activeInputIsFinger && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            val newPointerType = event.getToolType(event.actionIndex)
            cancelActiveStroke()
            return if (isStylusInput(newPointerType)) {
                startInput(event)
            } else {
                handleTouchGesture(event)
            }
        }
        if (activePointerId == MotionEvent.INVALID_POINTER_ID && !containsStylusInput(event)) {
            if (canvasInputMode == CanvasInputMode.STYLUS_AND_TOUCH &&
                event.actionMasked == MotionEvent.ACTION_DOWN &&
                event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_FINGER
            ) {
                scaleDetector.onTouchEvent(event)
                return startInput(event)
            }
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

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isInputEnabled && drawingSettings.tool == DrawingTool.POINTER &&
            event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val wheelDelta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (wheelDelta != 0f) {
                zoomAt(
                    (viewport.scale + wheelDelta * MOUSE_WHEEL_ZOOM_STEP)
                        .coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE),
                    event.x,
                    event.y,
                )
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (drawingSettings.tool == DrawingTool.POINTER) {
            updateNavigationPointerIcon(dragging = navigationDragging)
            return true
        }
        return super.onHoverEvent(event)
    }

    private fun startInput(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val toolType = event.getToolType(actionIndex)
        val acceptsFinger = canvasInputMode == CanvasInputMode.STYLUS_AND_TOUCH &&
            toolType == MotionEvent.TOOL_TYPE_FINGER
        if ((!isStylusInput(toolType) && !acceptsFinger) || width == 0 || height == 0) {
            return false
        }
        suppressStylusUntilUp = false
        val screenX = event.getX(actionIndex)
        val screenY = event.getY(actionIndex)
        val contentX = toContentX(screenX)
        val contentY = toContentY(screenY)
        if (!pageContentRect().contains(contentX, contentY)) return false
        activePointerId = event.getPointerId(actionIndex)
        activeInputIsFinger = toolType == MotionEvent.TOOL_TYPE_FINGER
        // Hardware erasing changes only this gesture snapshot, not the persisted toolbar tool.
        temporaryEraserActive = isHardwareEraser(event, actionIndex) &&
            drawingSettings.tool != DrawingTool.ERASER
        activeSettings = if (temporaryEraserActive) {
            drawingSettings.copy(tool = DrawingTool.ERASER)
        } else {
            drawingSettings
        }
        strokeStartedAt = event.eventTime
        activePoints.clear()
        erasedInGesture.clear()
        eraserPath.clear()
        if (areaPreview.isNotEmpty()) {
            areaPreview = emptyList()
            rebuildStrokeBitmap()
        }
        lastX = screenX
        lastY = screenY
        activeBounds.set(screenX, screenY, screenX, screenY)
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
            addPoint(contentX, contentY, inputPressure(event.getPressure(actionIndex)), event.eventTime)
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
        else addPoint(contentX, contentY, inputPressure(pressure), eventTime)
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
        val pageRect = pageContentRect()
        val localX = x - pageRect.left
        val localY = y - pageRect.top
        val candidateIds = storedSpatialIndex.query(
            RectF(x - radius, y - radius, x + radius, y + radius),
        )
        val hitStored = storedStrokes.filter { stroke ->
            stroke.id in candidateIds && "stored:${stroke.id}" !in erasedInGesture &&
                boundsHits(storedBounds[stroke.id], x, y, radius) && StrokeHitTester.hits(
                stroke.points, stroke.tool, stroke.width, localX, localY, radius,
                pageRect.width().toInt(), pageRect.height().toInt(),
                resources.displayMetrics.density,
            )
        }
        val hitPending = pendingStrokes.filter { pending ->
            "pending:${pending.token}" !in erasedInGesture &&
                boundsHits(pendingBounds[pending.token], x, y, radius) && StrokeHitTester.hits(
                pending.draft.points, pending.draft.tool, pending.draft.width, localX, localY, radius,
                pageRect.width().toInt(), pageRect.height().toInt(), resources.displayMetrics.density,
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

    private fun handleTextToolEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            cancelTextInput(restorePreview = true)
            return handleTouchGesture(event, dispatchScaleEvent = false)
        }
        if (isTouchGestureActive || event.pointerCount >= 2) {
            return handleTouchGesture(event, dispatchScaleEvent = false)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startTextInput(event)
            MotionEvent.ACTION_MOVE -> continueTextInput(event)
            MotionEvent.ACTION_UP -> finishTextInput(event)
            MotionEvent.ACTION_CANCEL -> {
                cancelTextInput(restorePreview = true)
                true
            }
            else -> true
        }
    }

    private fun startTextInput(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        val actionIndex = event.actionIndex
        val toolType = event.getToolType(actionIndex)
        if (toolType != MotionEvent.TOOL_TYPE_FINGER && toolType != MotionEvent.TOOL_TYPE_STYLUS) {
            return false
        }
        textPointerId = event.getPointerId(actionIndex)
        textTouchDownScreenX = event.getX(actionIndex)
        textTouchDownScreenY = event.getY(actionIndex)
        textTouchDownContentX = toContentX(textTouchDownScreenX)
        textTouchDownContentY = toContentY(textTouchDownScreenY)
        if (!pageContentRect().contains(textTouchDownContentX, textTouchDownContentY)) {
            textPointerId = MotionEvent.INVALID_POINTER_ID
            return false
        }
        textTouchMoved = false
        textTouchTarget = findTopTextAt(textTouchDownContentX, textTouchDownContentY)
        textTouchTarget?.let { target ->
            selectElements(CanvasSelection(texts = listOf(target)))
            selectionBeforeTransform = selection
            transformBaseBounds.set(selectionBounds)
            transformStartX = textTouchDownContentX
            transformStartY = textTouchDownContentY
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun continueTextInput(event: MotionEvent): Boolean {
        val pointerIndex = event.findPointerIndex(textPointerId)
        if (pointerIndex < 0) return true
        val screenX = event.getX(pointerIndex)
        val screenY = event.getY(pointerIndex)
        if (!textTouchMoved && distance(
                screenX, screenY, textTouchDownScreenX, textTouchDownScreenY,
            ) >= touchSlop
        ) {
            textTouchMoved = true
            lastTextTapId = null
            if (textTouchTarget != null) selectionTransformMode = SelectionTransformMode.MOVE
        }
        if (textTouchMoved && selectionTransformMode == SelectionTransformMode.MOVE) {
            updateSelectionTransform(toContentX(screenX), toContentY(screenY))
        }
        return true
    }

    private fun finishTextInput(event: MotionEvent): Boolean {
        val pointerIndex = event.findPointerIndex(textPointerId)
        if (pointerIndex < 0) {
            cancelTextInput(restorePreview = true)
            return true
        }
        val screenX = event.getX(pointerIndex)
        val screenY = event.getY(pointerIndex)
        val target = textTouchTarget
        if (textTouchMoved && target == null && isHorizontalPageSwipe(
                textTouchDownScreenX, textTouchDownScreenY, screenX, screenY,
            )
        ) {
            dispatchPageSwipe(textTouchDownScreenX, screenX)
        } else if (textTouchMoved && selectionTransformMode == SelectionTransformMode.MOVE) {
            updateSelectionTransform(toContentX(screenX), toContentY(screenY))
            val before = selectionBeforeTransform
            if (before != null && before != selection) {
                onSelectionTransformed?.invoke(SelectionChange(before, selection))
            }
        } else if (!textTouchMoved && target != null) {
            val isDoubleTap = lastTextTapId == target.id &&
                event.eventTime - lastTextTapAt in 1L..ViewConfiguration.getDoubleTapTimeout().toLong() &&
                distance(screenX, screenY, lastTextTapX, lastTextTapY) <= doubleTapSlop
            if (isDoubleTap) {
                lastTextTapId = null
                storedTexts.firstOrNull { it.id == target.id }?.let { onTextEditRequested?.invoke(it) }
            } else {
                lastTextTapId = target.id
                lastTextTapAt = event.eventTime
                lastTextTapX = screenX
                lastTextTapY = screenY
            }
        } else if (!textTouchMoved) {
            lastTextTapId = null
            clearSelection()
            onTextRequested?.invoke(
                normalizedPageX(toContentX(screenX)),
                normalizedPageY(toContentY(screenY)),
            )
        }
        resetTextInput()
        return true
    }

    private fun cancelTextInput(restorePreview: Boolean) {
        if (textPointerId == MotionEvent.INVALID_POINTER_ID) return
        if (restorePreview && textTouchMoved) {
            selectionBeforeTransform?.let(::restoreSelectionPreview)
        }
        lastTextTapId = null
        resetTextInput()
    }

    private fun resetTextInput() {
        textPointerId = MotionEvent.INVALID_POINTER_ID
        textTouchTarget = null
        textTouchMoved = false
        selectionBeforeTransform = null
        selectionTransformMode = SelectionTransformMode.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun restoreSelectionPreview(value: CanvasSelection) {
        value.texts.forEach { original ->
            val index = storedTexts.indexOfFirst { it.id == original.id }
            if (index >= 0) storedTexts[index] = original
        }
        selection = value
        updateSelectionBounds()
        rebuildStrokeBitmap()
        invalidate()
    }

    private fun findTopTextAt(x: Float, y: Float): CanvasText? = storedTexts
        .asSequence()
        .filter { textBounds(it).contains(x, y) }
        .maxByOrNull(CanvasText::elementIndex)

    private fun handleTouchGesture(event: MotionEvent, dispatchScaleEvent: Boolean = true): Boolean {
        if (dispatchScaleEvent) scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val actionIndex = event.actionIndex
                if (isPageSwipeEnabled &&
                    event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_FINGER
                ) {
                    pageSwipePointerId = event.getPointerId(actionIndex)
                    pageSwipeStartX = event.getX(actionIndex)
                    pageSwipeStartY = event.getY(actionIndex)
                    pageSwipeTracking = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                pageSwipeTracking = false
                val (focusX, focusY) = gestureFocus(event)
                lastGestureFocusX = focusX
                lastGestureFocusY = focusY
                isTouchGestureActive = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouchGestureActive && event.pointerCount >= 2) {
                    val (focusX, focusY) = gestureFocus(event)
                    updateViewport(
                        viewport.copy(
                            offsetX = viewport.offsetX + focusX - lastGestureFocusX,
                            offsetY = viewport.offsetY + focusY - lastGestureFocusY,
                        ),
                    )
                    lastGestureFocusX = focusX
                    lastGestureFocusY = focusY
                } else if (pageSwipeTracking) {
                    val pointerIndex = event.findPointerIndex(pageSwipePointerId)
                    if (pointerIndex >= 0) {
                        val dx = event.getX(pointerIndex) - pageSwipeStartX
                        val dy = event.getY(pointerIndex) - pageSwipeStartY
                        if (kotlin.math.abs(dx) > touchSlop &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * PAGE_SWIPE_DIRECTION_RATIO
                        ) parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.findPointerIndex(pageSwipePointerId)
                if (pageSwipeTracking && pointerIndex >= 0 && isHorizontalPageSwipe(
                        pageSwipeStartX,
                        pageSwipeStartY,
                        event.getX(pointerIndex),
                        event.getY(pointerIndex),
                    )
                ) dispatchPageSwipe(pageSwipeStartX, event.getX(pointerIndex))
                resetPageSwipe()
                isTouchGestureActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                resetPageSwipe()
                if (event.pointerCount <= 2) {
                    isTouchGestureActive = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }

    private fun handleNavigationToolEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            isTouchGestureActive || event.pointerCount >= 2
        ) {
            resetNavigationDrag(releaseParent = false)
            return handleTouchGesture(event, dispatchScaleEvent = false)
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startNavigationDrag(event)
            MotionEvent.ACTION_MOVE -> continueNavigationDrag(event)
            MotionEvent.ACTION_UP -> finishNavigationDrag(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == navigationPointerId) {
                    finishNavigationDrag(event)
                } else true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetNavigationDrag()
                true
            }
            else -> navigationPointerId != MotionEvent.INVALID_POINTER_ID
        }
    }

    private fun startNavigationDrag(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val actionIndex = event.actionIndex
        val toolType = event.getToolType(actionIndex)
        if (toolType == MotionEvent.TOOL_TYPE_MOUSE &&
            event.buttonState != 0 && event.buttonState and MotionEvent.BUTTON_PRIMARY == 0
        ) return false
        navigationPointerId = event.getPointerId(actionIndex)
        navigationDownX = event.getX(actionIndex)
        navigationDownY = event.getY(actionIndex)
        navigationLastX = navigationDownX
        navigationLastY = navigationDownY
        navigationDragging = false
        navigationInputToolType = toolType
        navigationStartScale = viewport.scale
        navigationStartOffsetX = viewport.offsetX
        resetPageSwipe()
        parent?.requestDisallowInterceptTouchEvent(true)
        updateNavigationPointerIcon(dragging = true)
        return true
    }

    private fun continueNavigationDrag(event: MotionEvent): Boolean {
        val pointerIndex = event.findPointerIndex(navigationPointerId)
        if (pointerIndex < 0) return false
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (!navigationDragging) {
            val dx = x - navigationDownX
            val dy = y - navigationDownY
            if (dx * dx + dy * dy < touchSlop * touchSlop) return true
            navigationDragging = true
        }
        updateViewport(
            viewport.copy(
                offsetX = viewport.offsetX + x - navigationLastX,
                offsetY = viewport.offsetY + y - navigationLastY,
            ),
        )
        navigationLastX = x
        navigationLastY = y
        updateNavigationPointerIcon(dragging = true)
        return true
    }

    private fun finishNavigationDrag(event: MotionEvent): Boolean {
        if (navigationPointerId == MotionEvent.INVALID_POINTER_ID) return false
        val pointerIndex = event.findPointerIndex(navigationPointerId)
        if (event.actionMasked == MotionEvent.ACTION_UP) continueNavigationDrag(event)
        val endX = pointerIndex.takeIf { it >= 0 }?.let { event.getX(it) } ?: navigationLastX
        val endY = pointerIndex.takeIf { it >= 0 }?.let { event.getY(it) } ?: navigationLastY
        val startX = navigationDownX
        val shouldChangePage = shouldDispatchNavigationPageSwipe(endX, endY)
        resetNavigationDrag()
        if (shouldChangePage) dispatchPageSwipe(startX, endX)
        return true
    }

    private fun shouldDispatchNavigationPageSwipe(endX: Float, endY: Float): Boolean {
        if (!isPageSwipeEnabled || navigationInputToolType != MotionEvent.TOOL_TYPE_FINGER) {
            return false
        }
        if (!isHorizontalPageSwipe(navigationDownX, navigationDownY, endX, endY)) return false
        if (navigationStartScale <= MINIMUM_SCALE + VIEWPORT_SCALE_EPSILON) return true
        val dx = endX - navigationDownX
        val minimumOffsetX = width * (1f - navigationStartScale)
        return if (dx < 0f) {
            navigationStartOffsetX <= minimumOffsetX + VIEWPORT_EDGE_EPSILON
        } else {
            navigationStartOffsetX >= -VIEWPORT_EDGE_EPSILON
        }
    }

    private fun resetNavigationDrag(releaseParent: Boolean = true) {
        navigationPointerId = MotionEvent.INVALID_POINTER_ID
        navigationDragging = false
        navigationInputToolType = MotionEvent.TOOL_TYPE_UNKNOWN
        navigationStartScale = MINIMUM_SCALE
        navigationStartOffsetX = 0f
        if (releaseParent) parent?.requestDisallowInterceptTouchEvent(false)
        updateNavigationPointerIcon(dragging = false)
    }

    private fun updateNavigationPointerIcon(dragging: Boolean) {
        pointerIcon = if (drawingSettings.tool == DrawingTool.POINTER) {
            PointerIcon.getSystemIcon(
                context,
                if (dragging) PointerIcon.TYPE_GRABBING else PointerIcon.TYPE_GRAB,
            )
        } else null
    }

    private fun isHorizontalPageSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): Boolean {
        val dx = endX - startX
        val dy = endY - startY
        val minimumDistance = maxOf(
            touchSlop * PAGE_SWIPE_SLOP_MULTIPLIER,
            width * PAGE_SWIPE_WIDTH_RATIO,
        )
        return kotlin.math.abs(dx) >= minimumDistance &&
            kotlin.math.abs(dx) > kotlin.math.abs(dy) * PAGE_SWIPE_DIRECTION_RATIO
    }

    private fun dispatchPageSwipe(startX: Float, endX: Float) {
        onPageSwipe?.invoke(
            if (endX < startX) PageSwipeDirection.NEXT else PageSwipeDirection.PREVIOUS,
        )
    }

    private fun resetPageSwipe() {
        pageSwipePointerId = MotionEvent.INVALID_POINTER_ID
        pageSwipeTracking = false
    }

    private fun updateViewport(value: CanvasViewport) {
        viewport = clampViewport(value)
        onViewportChanged?.invoke(viewport)
        invalidate()
    }

    private fun zoomAt(newScale: Float, focusX: Float, focusY: Float) {
        val oldScale = viewport.scale
        val scale = newScale.coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)
        if (scale == oldScale) return
        val ratio = scale / oldScale
        updateViewport(
            CanvasViewport(
                scale = scale,
                offsetX = focusX - (focusX - viewport.offsetX) * ratio,
                offsetY = focusY - (focusY - viewport.offsetY) * ratio,
                referenceWidth = width,
                referenceHeight = height,
            ),
        )
    }

    private fun clampViewport(value: CanvasViewport): CanvasViewport {
        val scale = value.scale.coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)
        if (scale == MINIMUM_SCALE) return CanvasViewport()
        if (width <= 0 || height <= 0) return value.copy(scale = scale)
        return CanvasViewport(
            scale = scale,
            offsetX = value.offsetX.coerceIn(width * (1f - scale), 0f),
            offsetY = value.offsetY.coerceIn(height * (1f - scale), 0f),
            referenceWidth = width,
            referenceHeight = height,
        )
    }

    private fun remapViewport(
        value: CanvasViewport,
        newWidth: Int,
        newHeight: Int,
        fallbackWidth: Int = 0,
        fallbackHeight: Int = 0,
    ): CanvasViewport {
        val scale = value.scale.coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)
        if (scale == MINIMUM_SCALE) return CanvasViewport()
        if (newWidth <= 0 || newHeight <= 0) return value.copy(scale = scale)
        val oldWidth = value.referenceWidth.takeIf { it > 0 }
            ?: fallbackWidth.takeIf { it > 0 }
            ?: newWidth
        val oldHeight = value.referenceHeight.takeIf { it > 0 }
            ?: fallbackHeight.takeIf { it > 0 }
            ?: newHeight
        if (oldWidth == newWidth && oldHeight == newHeight) {
            return clampViewport(
                value.copy(referenceWidth = newWidth, referenceHeight = newHeight),
            )
        }
        val focusX = ((oldWidth / 2f - value.offsetX) / scale / oldWidth).coerceIn(0f, 1f)
        val focusY = ((oldHeight / 2f - value.offsetY) / scale / oldHeight).coerceIn(0f, 1f)
        return clampViewport(
            CanvasViewport(
                scale = scale,
                offsetX = newWidth / 2f - focusX * newWidth * scale,
                offsetY = newHeight / 2f - focusY * newHeight * scale,
                referenceWidth = newWidth,
                referenceHeight = newHeight,
            ),
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

    private fun containsStylusInput(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { isStylusInput(event.getToolType(it)) }

    private fun containsHardwareEraser(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { isHardwareEraser(event, it) }

    private fun isStylusInput(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun inputPressure(value: Float): Float =
        if (activeInputIsFinger) DEFAULT_TOUCH_PRESSURE else value

    private fun isHardwareEraser(event: MotionEvent, pointerIndex: Int): Boolean =
        event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER ||
            (event.buttonState and STYLUS_ERASER_BUTTONS) != 0

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
        val queryBounds = RectF(
            eraserPath.minOf { it.first } - eraserRadiusPx(),
            eraserPath.minOf { it.second } - eraserRadiusPx(),
            eraserPath.maxOf { it.first } + eraserRadiusPx(),
            eraserPath.maxOf { it.second } + eraserRadiusPx(),
        )
        val candidateIds = storedSpatialIndex.query(queryBounds)
        val persisted = storedStrokes.filter {
            it.id in candidateIds && areaPathHits(storedBounds[it.id])
        }
            .map { ErasableStroke.Persisted(it) to it.toDraft() }
        val pending = pendingStrokes.filter { areaPathHits(pendingBounds[it.token]) }
            .map { ErasableStroke.Pending(it) to it.draft }
        return (persisted + pending).mapNotNull { (target, draft) ->
            val keptGroups = mutableListOf<MutableList<StrokePoint>>()
            draft.points.forEach { point ->
                val px = pageX(point.x)
                val py = pageY(point.y)
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
            activeSettings.highlighter.colorArgb,
            activeSettings.highlighter.thickness.widthDp,
            points,
        )
        DrawingTool.LINE, DrawingTool.RECTANGLE, DrawingTool.CIRCLE -> StrokeDraft(
            when (activeSettings.tool) {
                DrawingTool.LINE -> StrokeTool.LINE
                DrawingTool.RECTANGLE -> StrokeTool.RECTANGLE
                else -> StrokeTool.CIRCLE
            },
            activeSettings.pen.colorArgb,
            activeSettings.pen.thickness.widthDp,
            shapePoints(points),
        )
        else -> StrokeDraft(
            StrokeTool.PEN,
            activeSettings.pen.colorArgb,
            activeSettings.pen.thickness.widthDp,
            points,
        )
    }

    private fun shapePoints(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size < 2) return points
        val start = points.first()
        val rawEnd = points.last()
        if (activeSettings.tool != DrawingTool.CIRCLE) return listOf(start, rawEnd)
        val pageRect = pageContentRect()
        val dx = (rawEnd.x - start.x) * pageRect.width()
        val dy = (rawEnd.y - start.y) * pageRect.height()
        val size = min(kotlin.math.abs(dx), kotlin.math.abs(dy))
        return listOf(
            start,
            rawEnd.copy(
                x = (start.x + kotlin.math.sign(dx) * size / pageRect.width()).coerceIn(0f, 1f),
                y = (start.y + kotlin.math.sign(dy) * size / pageRect.height()).coerceIn(0f, 1f),
            ),
        )
    }

    private fun completeLassoSelection() {
        if (activePoints.size < 3) return
        val lassoBounds = RectF(
            pageX(activePoints.minOf { it.x }),
            pageY(activePoints.minOf { it.y }),
            pageX(activePoints.maxOf { it.x }),
            pageY(activePoints.maxOf { it.y }),
        )
        val polygon = activePoints.map { pageX(it.x) to pageY(it.y) }
        val lassoCandidates = storedSpatialIndex.query(lassoBounds)
        val strokes = storedStrokes.filter { stroke ->
            stroke.id in lassoCandidates &&
            storedBounds[stroke.id]?.let { RectF.intersects(it, lassoBounds) } == true &&
                (stroke.points.any { pointInPolygon(pageX(it.x), pageY(it.y), polygon) } ||
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
        val images = storedImages.filter { image ->
            val bounds = imageBounds(image)
            RectF.intersects(bounds, lassoBounds) && (
                listOf(
                    bounds.left to bounds.top, bounds.right to bounds.top,
                    bounds.right to bounds.bottom, bounds.left to bounds.bottom,
                ).any { (x, y) -> pointInPolygon(x, y, polygon) } ||
                    polygon.any { (x, y) -> bounds.contains(x, y) }
                )
        }
        selectElements(CanvasSelection(strokes, texts, images))
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
        selection.images.forEach { selectionBounds.union(imageBounds(it)) }
    }

    private fun textBounds(value: CanvasText): RectF = RectF(
        pageX(value.x),
        pageY(value.y),
        pageX(value.x + value.boxWidth),
        pageY(value.y) + textRenderer.height(
            value, pageContentRect().width().toInt(), resources.displayMetrics.density,
        ),
    )

    private fun imageBounds(value: CanvasImage): RectF = RectF(
        pageX(value.x),
        pageY(value.y),
        pageX(value.x + value.boxWidth),
        pageY(value.y + value.boxHeight),
    )

    private fun updateSelectionTransform(x: Float, y: Float) {
        val source = selectionBeforeTransform ?: return
        val pageRect = pageContentRect()
        val pageWidth = pageRect.width()
        val pageHeight = pageRect.height()
        val dx = (x - transformStartX) / pageWidth
        val dy = (y - transformStartY) / pageHeight
        val maximumScale = min(
            (pageRect.right - transformBaseBounds.left) / transformBaseBounds.width().coerceAtLeast(1f),
            (pageRect.bottom - transformBaseBounds.top) / transformBaseBounds.height().coerceAtLeast(1f),
        ).coerceAtLeast(0.1f)
        val scale = if (selectionTransformMode == SelectionTransformMode.RESIZE) {
            val base = max(transformBaseBounds.width(), transformBaseBounds.height()).coerceAtLeast(1f)
            (1f + max(x - transformStartX, y - transformStartY) / base)
                .coerceIn(0.1f, maximumScale)
        } else 1f
        val translatedX = if (selectionTransformMode == SelectionTransformMode.MOVE) {
            dx.coerceIn(
                (pageRect.left - transformBaseBounds.left) / pageWidth,
                (pageRect.right - transformBaseBounds.right) / pageWidth,
            )
        } else 0f
        val translatedY = if (selectionTransformMode == SelectionTransformMode.MOVE) {
            dy.coerceIn(
                (pageRect.top - transformBaseBounds.top) / pageHeight,
                (pageRect.bottom - transformBaseBounds.bottom) / pageHeight,
            )
        } else 0f
        val originX = (transformBaseBounds.left - pageRect.left) / pageWidth
        val originY = (transformBaseBounds.top - pageRect.top) / pageHeight
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
        val images = source.images.map { image ->
            image.copy(
                x = originX + (image.x - originX) * scale + translatedX,
                y = originY + (image.y - originY) * scale + translatedY,
                boxWidth = (image.boxWidth * scale).coerceAtLeast(MINIMUM_IMAGE_SIZE),
                boxHeight = (image.boxHeight * scale).coerceAtLeast(MINIMUM_IMAGE_SIZE),
                updatedAt = System.currentTimeMillis(),
            )
        }
        strokes.forEach { changed -> storedStrokes.indexOfFirst { it.id == changed.id }.takeIf { it >= 0 }?.let { storedStrokes[it] = changed } }
        texts.forEach { changed -> storedTexts.indexOfFirst { it.id == changed.id }.takeIf { it >= 0 }?.let { storedTexts[it] = changed } }
        images.forEach { changed -> storedImages.indexOfFirst { it.id == changed.id }.takeIf { it >= 0 }?.let { storedImages[it] = changed } }
        selection = CanvasSelection(strokes, texts, images)
        rebuildBoundsCache()
        rebuildStrokeBitmap()
        updateSelectionBounds()
        invalidate()
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        if (activeSettings.tool == DrawingTool.LASSO && activePoints.size > 1 && selectionTransformMode == SelectionTransformMode.NONE) {
            val path = android.graphics.Path()
            path.moveTo(pageX(activePoints.first().x), pageY(activePoints.first().y))
            activePoints.drop(1).forEach { path.lineTo(pageX(it.x), pageY(it.y)) }
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
        color = context.getColor(R.color.noteup_fg_brand)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.noteup_bg_brand)
    }

    private fun addPoint(x: Float, y: Float, pressure: Float, eventTime: Long) {
        activePoints += StrokePoint(
            normalizedPageX(x),
            normalizedPageY(y),
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
            val pageRect = pageContentRect()
            canvas.save()
            canvas.translate(pageRect.left, pageRect.top)
            val elements: List<Pair<Int, Any>> = storedStrokes.map { it.strokeIndex to it as Any } +
                storedTexts.map { it.elementIndex to it as Any } +
                storedImages.map { it.elementIndex to it as Any }
            elements.sortedBy { it.first }.forEach { (_, element) ->
                when (element) {
                    is Stroke -> renderer.draw(
                        canvas, element.points, displayColor(element.colorArgb), element.width,
                        pageRect.width().toInt(), pageRect.height().toInt(),
                        resources.displayMetrics.density, element.tool,
                    )
                    is CanvasText -> textRenderer.draw(
                        canvas, element.copy(colorArgb = displayColor(element.colorArgb)),
                        pageRect.width().toInt(), pageRect.height().toInt(),
                        resources.displayMetrics.density,
                    )
                    is CanvasImage -> drawImage(canvas, element, pageRect.width(), pageRect.height())
                }
            }
            canvas.restore()
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
        val elements: List<Pair<Int, Any>> = storedStrokes
            .filterNot { it.id in hiddenIds }
            .filter { storedBounds[it.id]?.let { bounds -> RectF.intersects(bounds, currentDirty) } == true }
            .map { it.strokeIndex to it as Any } +
            storedTexts.filter { RectF.intersects(textBounds(it), currentDirty) }
                .map { it.elementIndex to it as Any } +
            storedImages.filter { RectF.intersects(imageBounds(it), currentDirty) }
                .map { it.elementIndex to it as Any }
        val pageRect = pageContentRect()
        canvas.save()
        canvas.translate(pageRect.left, pageRect.top)
        elements.sortedBy { it.first }.forEach { (_, element) ->
            when (element) {
                is Stroke -> renderer.draw(
                    canvas, element.points, displayColor(element.colorArgb), element.width,
                    pageRect.width().toInt(), pageRect.height().toInt(),
                    resources.displayMetrics.density, element.tool,
                )
                is CanvasText -> textRenderer.draw(
                    canvas, element.copy(colorArgb = displayColor(element.colorArgb)),
                    pageRect.width().toInt(), pageRect.height().toInt(),
                    resources.displayMetrics.density,
                )
                is CanvasImage -> drawImage(canvas, element, pageRect.width(), pageRect.height())
            }
        }
        canvas.restore()
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
        storedSpatialIndex.clear()
        storedStrokes.forEach {
            val bounds = boundsFor(it.toDraft())
            storedBounds[it.id] = bounds
            storedSpatialIndex.insert(it.id, bounds)
        }
        pendingBounds.clear()
        pendingStrokes.forEach { pendingBounds[it.token] = boundsFor(it.draft) }
    }

    private fun boundsFor(draft: StrokeDraft): RectF {
        if (draft.points.isEmpty()) return RectF()
        val padding = areaCollisionRadius(draft, resources.displayMetrics.density) - eraserRadiusPx()
        return RectF(
            pageX(draft.points.minOf { it.x }) - padding,
            pageY(draft.points.minOf { it.y }) - padding,
            pageX(draft.points.maxOf { it.x }) + padding,
            pageY(draft.points.maxOf { it.y }) + padding,
        )
    }

    private fun boundsHits(bounds: RectF?, x: Float, y: Float, radius: Float): Boolean =
        bounds != null && x >= bounds.left - radius && x <= bounds.right + radius &&
            y >= bounds.top - radius && y <= bounds.bottom + radius

    private fun areaPathHits(bounds: RectF?): Boolean = bounds != null && eraserPath.any { (x, y) ->
        boundsHits(bounds, x, y, eraserRadiusPx())
    }

    private fun drawTemplate(canvas: Canvas) {
        val background = pdfBackgroundBitmap
        if (background == null) {
            canvas.drawColor(
                context.getColor(
                    if (usesDarkPaper()) R.color.noteup_dark_page else R.color.noteup_page,
                ),
            )
            pageRenderer.drawTemplate(
                canvas, width, height, resources.displayMetrics.density, pageTemplate,
                context.getColor(
                    if (usesDarkPaper()) {
                        R.color.noteup_dark_template_line
                    } else {
                        R.color.noteup_template_line
                    },
                ),
            )
        } else {
            canvas.drawColor(context.getColor(R.color.noteup_page))
            val pageRect = pageRenderer.fitCenterRect(width, height, background)
            canvas.drawBitmap(background, null, pageRect, null)
            pdfTiles.forEach { tile ->
                if (tile.bitmap.isRecycled) return@forEach
                val fraction = 1f / tile.gridSize
                val target = RectF(
                    pageRect.left + pageRect.width() * tile.tileX * fraction,
                    pageRect.top + pageRect.height() * tile.tileY * fraction,
                    pageRect.left + pageRect.width() * (tile.tileX + 1) * fraction,
                    pageRect.top + pageRect.height() * (tile.tileY + 1) * fraction,
                )
                canvas.drawBitmap(tile.bitmap, null, target, imagePaint)
            }
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: StrokeDraft) {
        val pageRect = pageContentRect()
        canvas.save()
        canvas.translate(pageRect.left, pageRect.top)
        renderer.draw(
            canvas, stroke.points, displayColor(stroke.colorArgb), stroke.width,
            pageRect.width().toInt(), pageRect.height().toInt(),
            resources.displayMetrics.density, stroke.tool,
        )
        canvas.restore()
    }

    private fun drawImage(canvas: Canvas, image: CanvasImage, pageWidth: Float, pageHeight: Float) {
        val target = RectF(
            image.x * pageWidth,
            image.y * pageHeight,
            (image.x + image.boxWidth) * pageWidth,
            (image.y + image.boxHeight) * pageHeight,
        )
        val bitmap = imageBitmaps[image.id]?.takeUnless { it.isRecycled }
        if (bitmap == null) canvas.drawRect(target, missingImagePaint)
        else canvas.drawBitmap(bitmap, null, target, imagePaint)
    }

    private fun usesDarkPaper(): Boolean =
        canvasAppearance == CanvasAppearance.DARK_PAPER && pdfBackgroundBitmap == null

    private fun displayColor(colorArgb: Int): Int {
        if (!usesDarkPaper()) return colorArgb
        val mapped = when (colorArgb) {
            PenColor.BLACK.argb, Color.BLACK -> context.getColor(R.color.noteup_dark_pen_black)
            PenColor.BLUE.argb -> context.getColor(R.color.noteup_dark_pen_blue)
            PenColor.RED.argb -> context.getColor(R.color.noteup_dark_pen_red)
            PenColor.GREEN.argb -> context.getColor(R.color.noteup_dark_pen_green)
            else -> return colorArgb
        }
        return Color.argb(
            Color.alpha(colorArgb),
            Color.red(mapped),
            Color.green(mapped),
            Color.blue(mapped),
        )
    }

    private fun pageContentRect(): RectF {
        val background = pdfBackgroundBitmap
        return if (background == null || width <= 0 || height <= 0) {
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        } else pageRenderer.fitCenterRect(width, height, background)
    }

    private fun pageX(normalized: Float): Float {
        val rect = pageContentRect()
        return rect.left + normalized * rect.width()
    }

    private fun pageY(normalized: Float): Float {
        val rect = pageContentRect()
        return rect.top + normalized * rect.height()
    }

    private fun normalizedPageX(value: Float): Float {
        val rect = pageContentRect()
        return ((value - rect.left) / rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun normalizedPageY(value: Float): Float {
        val rect = pageContentRect()
        return ((value - rect.top) / rect.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
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
        activeInputIsFinger = false
        temporaryEraserActive = false
        suppressStylusUntilUp = false
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
        const val MOUSE_WHEEL_ZOOM_STEP = 0.15f
        const val VIEWPORT_EDGE_EPSILON = 1f
        const val VIEWPORT_SCALE_EPSILON = 0.001f
        const val HANDLE_RADIUS_DP = 10f
        const val PAGE_SWIPE_SLOP_MULTIPLIER = 4
        const val PAGE_SWIPE_WIDTH_RATIO = 0.14f
        const val PAGE_SWIPE_DIRECTION_RATIO = 1.25f
        const val DEFAULT_TOUCH_PRESSURE = 0.5f
        const val MINIMUM_IMAGE_SIZE = 0.03f
        const val SPATIAL_CELL_DP = 128f
        val STYLUS_ERASER_BUTTONS =
            MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
    }

    private enum class SelectionTransformMode { NONE, MOVE, RESIZE }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(
                viewport.scale * detector.scaleFactor,
                detector.focusX,
                detector.focusY,
            )
            return true
        }
    }
}

enum class PageSwipeDirection {
    PREVIOUS,
    NEXT,
}
