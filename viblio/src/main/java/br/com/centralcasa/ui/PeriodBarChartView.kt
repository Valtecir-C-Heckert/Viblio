package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

data class PeriodBarEntry(
    val label: String,
    val value: Long,
    val active: Boolean = false,
)

/**
 * Editorial vertical bar chart for the Periods tab (Year months, Month weeks,
 * Week days, or Day sessions) with peak highlighting and optional tap-to-drill-down.
 */
class PeriodBarChartView(
    context: Context,
    private val ui: DisplayUi,
    private val entries: List<PeriodBarEntry>,
    private val accentColor: Int,
    private val onBarTap: ((Int) -> Unit)? = null,
    private val editorial: Boolean = false,
    private val peakLabel: String? = null,
) : View(context) {
    private val labelAreaH = ui.dp(20).toFloat()
    private val topPad = ui.dp(if (editorial) 20 else 8).toFloat()
    private val minBarH = ui.dp(4).toFloat()
    private val barPath = Path()
    private val barRect = RectF()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (editorial) ui.galleryHairline(105) else ui.lineColor
        strokeWidth = ui.dp(1).coerceAtLeast(1).toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ui.dp(if (editorial) 10 else 9).toFloat()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        textSize = ui.dp(10).toFloat()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(ui.dp(180))
        val h = ui.dp(if (editorial) 128 else 106)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return
        val w = width.toFloat()
        val chartBottom = height - labelAreaH
        val maxBarH = (chartBottom - topPad).coerceAtLeast(ui.dp(20).toFloat())
        val peak = entries.maxOfOrNull { it.value } ?: 0L
        val firstPeakIdx = if (peak > 0L) entries.indexOfFirst { it.value == peak } else -1

        canvas.drawLine(0f, chartBottom, w, chartBottom, baseLinePaint)

        val slotW = w / entries.size.toFloat()
        val barW = if (editorial) {
            (slotW * 0.44f).coerceIn(ui.dp(6).toFloat(), ui.dp(15).toFloat())
        } else {
            (slotW * 0.56f).coerceIn(ui.dp(6).toFloat(), ui.dp(26).toFloat())
        }
        val capR = barW * 0.5f

        entries.forEachIndexed { idx, entry ->
            val cx = idx * slotW + slotW * 0.5f
            val left = cx - barW * 0.5f
            val right = cx + barW * 0.5f
            val isPeak = peak > 0L && entry.value == peak
            val barH = if (peak > 0L && entry.value > 0L) {
                (maxBarH * (entry.value.toFloat() / peak.toFloat())).coerceAtLeast(minBarH)
            } else {
                ui.dp(2).toFloat()
            }
            barPaint.color = when {
                entry.value <= 0L -> ui.galleryHairline(if (editorial) 85 else 95)
                isPeak || entry.active -> accentColor
                else -> ui.tint(accentColor, if (editorial) 115 else 175)
            }
            val barTop = chartBottom - barH
            if (editorial) {
                barPath.reset()
                barRect.set(left, barTop, right, chartBottom)
                val r = minOf(capR, barH * 0.5f)
                barPath.addRoundRect(barRect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(barPath, barPaint)
                if (idx == firstPeakIdx && !peakLabel.isNullOrBlank()) {
                    canvas.drawText(peakLabel, cx, (barTop - ui.dp(5)).coerceAtLeast(ui.dp(11).toFloat()), peakPaint)
                }
            } else {
                canvas.drawRect(left, barTop, right, chartBottom, barPaint)
            }

            labelPaint.color = if (isPeak || entry.active) accentColor else ui.muted
            canvas.drawText(entry.label, cx, height - ui.dp(4).toFloat(), labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cb = onBarTap ?: return super.onTouchEvent(event)
        if (entries.isEmpty()) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && width > 0) {
            val idx = ((event.x / width.toFloat()) * entries.size).toInt().coerceIn(0, entries.lastIndex)
            cb(idx)
            performClick()
            return true
        }
        return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
