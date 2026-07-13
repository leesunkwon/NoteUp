package com.kotlinsun.noteup.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.kotlinsun.noteup.domain.model.CanvasText

class CanvasTextRenderer {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, value: CanvasText, width: Int, height: Int, density: Float) {
        if (width <= 0 || height <= 0 || value.content.isEmpty()) return
        paint.color = value.colorArgb
        paint.textSize = value.textSizeSp * density
        val boxWidth = (value.boxWidth * width).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(value.content, 0, value.content.length, paint, boxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(value.x * width, value.y * height)
        layout.draw(canvas)
        canvas.restore()
    }

    fun height(value: CanvasText, width: Int, density: Float): Float {
        paint.textSize = value.textSizeSp * density
        return StaticLayout.Builder.obtain(
            value.content, 0, value.content.length, paint,
            (value.boxWidth * width).toInt().coerceAtLeast(1),
        ).setIncludePad(false).build().height.toFloat()
    }
}
