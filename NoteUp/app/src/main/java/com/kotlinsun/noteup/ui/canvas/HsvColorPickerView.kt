package com.kotlinsun.noteup.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kotlinsun.noteup.R

class HsvColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var onColorChanged: ((Int) -> Unit)? = null

    val selectedColor: Int get() = Color.HSVToColor(floatArrayOf(hue, saturation, brightness))

    private var hue = 0f
    private var saturation = 1f
    private var brightness = 1f
    private val density = resources.displayMetrics.density
    private val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val hueColors = intArrayOf(
        Color.RED,
        Color.YELLOW,
        Color.GREEN,
        Color.CYAN,
        Color.BLUE,
        Color.MAGENTA,
        Color.RED,
    )

    init {
        contentDescription = context.getString(R.string.color_picker_area)
        isFocusable = true
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val hueHeight = HUE_BAR_HEIGHT_DP * density
        val gap = SECTION_GAP_DP * density
        val hueBottom = (height - paddingBottom).toFloat()
        val hueTop = hueBottom - hueHeight
        val squareBottom = (hueTop - gap).coerceAtLeast(top)
        if (right <= left || squareBottom <= top) return

        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val saturationShader = LinearGradient(
            left,
            top,
            right,
            top,
            Color.WHITE,
            hueColor,
            Shader.TileMode.CLAMP,
        )
        val brightnessShader = LinearGradient(
            left,
            top,
            left,
            squareBottom,
            Color.WHITE,
            Color.BLACK,
            Shader.TileMode.CLAMP,
        )
        colorPaint.shader = ComposeShader(
            saturationShader,
            brightnessShader,
            PorterDuff.Mode.MULTIPLY,
        )
        canvas.drawRoundRect(left, top, right, squareBottom, CORNER_DP * density, CORNER_DP * density, colorPaint)

        colorPaint.shader = LinearGradient(
            left,
            hueTop,
            right,
            hueTop,
            hueColors,
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(left, hueTop, right, hueBottom, hueHeight / 2f, hueHeight / 2f, colorPaint)
        colorPaint.shader = null

        drawMarker(
            canvas,
            left + saturation * (right - left),
            top + (1f - brightness) * (squareBottom - top),
        )
        drawMarker(canvas, left + hue / FULL_HUE * (right - left), (hueTop + hueBottom) / 2f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            parent?.requestDisallowInterceptTouchEvent(false)
            performClick()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_MOVE
        ) return false
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val hueHeight = HUE_BAR_HEIGHT_DP * density
        val gap = SECTION_GAP_DP * density
        val hueBottom = (height - paddingBottom).toFloat()
        val hueTop = hueBottom - hueHeight
        val squareBottom = hueTop - gap
        if (right <= left || squareBottom <= top) return false
        if (event.y >= hueTop - gap / 2f) {
            hue = ((event.x - left) / (right - left)).coerceIn(0f, 1f) * FULL_HUE
        } else {
            saturation = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
            brightness = (1f - (event.y - top) / (squareBottom - top)).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(selectedColor)
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun drawMarker(canvas: Canvas, x: Float, y: Float) {
        markerPaint.color = Color.WHITE
        markerPaint.strokeWidth = 4f * density
        canvas.drawCircle(x, y, MARKER_RADIUS_DP * density, markerPaint)
        markerPaint.color = Color.BLACK
        markerPaint.strokeWidth = density
        canvas.drawCircle(x, y, MARKER_RADIUS_DP * density, markerPaint)
    }

    private companion object {
        const val FULL_HUE = 360f
        const val HUE_BAR_HEIGHT_DP = 24f
        const val SECTION_GAP_DP = 16f
        const val CORNER_DP = 8f
        const val MARKER_RADIUS_DP = 9f
    }
}
