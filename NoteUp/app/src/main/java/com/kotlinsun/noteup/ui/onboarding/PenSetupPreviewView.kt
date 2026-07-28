package com.kotlinsun.noteup.ui.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kotlinsun.noteup.domain.model.CanvasInputMode
import com.kotlinsun.noteup.domain.model.PenThickness
import kotlin.math.max

class PenSetupPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var inputMode: CanvasInputMode = CanvasInputMode.STYLUS_ONLY
    var thickness: PenThickness = PenThickness.MEDIUM

    private val paths = mutableListOf<PreviewPath>()
    private var activePath: PreviewPath? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 33, 36)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paths.forEach { preview ->
            paint.strokeWidth = preview.width
            canvas.drawPath(preview.path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(event.actionIndex)
        val accepted = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            (toolType == MotionEvent.TOOL_TYPE_FINGER && inputMode == CanvasInputMode.STYLUS_AND_TOUCH)
        if (!accepted) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pressure = event.pressure.takeIf { it.isFinite() && it > 0f } ?: 0.5f
                val width = thickness.widthDp * resources.displayMetrics.density *
                    (0.45f + pressure.coerceIn(0.1f, 1f) * 1.1f)
                activePath = PreviewPath(Path().apply { moveTo(event.x, event.y) }, max(width, 1f))
                    .also(paths::add)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                activePath?.path?.lineTo(event.x, event.y)
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                activePath?.path?.lineTo(event.x, event.y)
                activePath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                invalidate()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                activePath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> true
        }
    }

    override fun performClick(): Boolean = super.performClick()

    fun clear() {
        paths.clear()
        activePath = null
        invalidate()
    }

    private data class PreviewPath(val path: Path, val width: Float)
}
