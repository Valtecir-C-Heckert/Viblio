package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Fine typographic goal ring (3dp rounded stroke) with serif count in the center.
 * Used in the Galeria editorial Metas hero, driven 100% by the active book cover palette.
 */
class GoalArcView(
    context: Context,
    private val ui: DisplayUi,
    private val fraction: Float,
    private val primaryValue: String,
    private val subtitle: String,
    private val arcColor: Int = ui.accent,
) : View(context) {
    private val strokePx = ui.dp(3).coerceAtLeast(3).toFloat()
    private val arcBounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = ui.galleryHairline(100)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx * 1.12f
        strokeCap = Paint.Cap.ROUND
        color = arcColor
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arcColor
        textAlign = Paint.Align.CENTER
        textSize = ui.dp(24).toFloat()
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.muted
        textAlign = Paint.Align.CENTER
        textSize = ui.dp(10).toFloat()
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = ui.dp(88)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = strokePx * 1.4f
        val side = minOf(width, height).toFloat()
        val ox = (width - side) * 0.5f
        val oy = (height - side) * 0.5f
        arcBounds.set(ox + inset, oy + inset, ox + side - inset, oy + side - inset)

        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        val sweep = (fraction.coerceIn(0f, 1f) * 360f)
        if (sweep > 0.5f) {
            canvas.drawArc(arcBounds, -90f, sweep, false, fillPaint)
        }

        val cx = width * 0.5f
        val cy = height * 0.5f
        val vMetrics = valuePaint.fontMetrics
        val sMetrics = subPaint.fontMetrics
        val gap = ui.dp(2).toFloat()
        val valueH = vMetrics.descent - vMetrics.ascent
        val subH = if (subtitle.isNotBlank()) sMetrics.descent - sMetrics.ascent else 0f
        val totalH = valueH + (if (subtitle.isNotBlank()) gap + subH else 0f)
        val topY = cy - totalH * 0.5f

        canvas.drawText(primaryValue, cx, topY - vMetrics.ascent, valuePaint)
        if (subtitle.isNotBlank()) {
            canvas.drawText(subtitle, cx, topY + valueH + gap - sMetrics.ascent, subPaint)
        }
    }
}
