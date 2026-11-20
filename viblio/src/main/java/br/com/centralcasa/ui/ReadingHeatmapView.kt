package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import br.com.centralcasa.books.AnnualPlanCalculator
import br.com.centralcasa.books.YearHeatmapSummary

/**
 * Lightweight custom Canvas view for the 52x7 yearly reading heatmap.
 * Renders all 365/366 days + month headers in a single draw pass with tap-to-inspect day selection.
 */
class ReadingHeatmapView(
    context: Context,
    private val ui: DisplayUi,
    private val summary: YearHeatmapSummary,
    private val accentColor: Int,
    private var selectedDay: String?,
    private val onDaySelected: (String) -> Unit,
) : View(context) {
    private val cellPx = ui.dp(12).toFloat()
    private val gapPx = ui.dp(3).toFloat()
    private val stepPx = cellPx + gapPx
    private val headerH = ui.dp(18).toFloat()
    private val padX = ui.dp(2).toFloat()
    private val padBottom = ui.dp(4).toFloat()

    private val emptyColor = if (ui.rectilinear) 0xFF202020.toInt() else 0xFF1E2D25.toInt()
    private val levelColors = intArrayOf(
        emptyColor,
        Color.argb(82, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
        Color.argb(165, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
        accentColor,
    )

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(2).coerceAtLeast(2).toFloat()
        color = ui.text
    }
    private val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.muted
        textSize = ui.dp(9).toFloat()
        letterSpacing = 0.06f
    }

    fun xForMonth(month: Int): Int {
        val mPrefix = String.format(java.util.Locale.US, "%04d-%02d-", summary.year, month.coerceIn(1, 12))
        val col = summary.weeks.indexOfFirst { week -> week.any { it != null && it.startsWith(mPrefix) } }
        return if (col > 0) (col * stepPx).toInt() else 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (padX * 2 + summary.weeks.size * stepPx).toInt().coerceAtLeast(ui.dp(200))
        val h = (headerH + 7 * stepPx + padBottom).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var lastDrawnMonth = 0
        summary.weeks.forEachIndexed { col, week ->
            val left = padX + col * stepPx
            val firstDayInCol = week.firstOrNull { it != null }
            val monthNum = firstDayInCol?.substring(5, 7)?.toIntOrNull() ?: 0
            if (monthNum in 1..12 && monthNum != lastDrawnMonth) {
                lastDrawnMonth = monthNum
                canvas.drawText(AnnualPlanCalculator.shortName(monthNum), left, headerH - ui.dp(5), monthPaint)
            }
            for (row in 0 until 7) {
                val day = week[row] ?: continue
                val top = headerH + row * stepPx
                val lvl = summary.daysByDate[day]?.level?.coerceIn(0, 3) ?: 0
                cellPaint.color = levelColors[lvl]
                canvas.drawRect(left, top, left + cellPx, top + cellPx, cellPaint)
                if (day == selectedDay) {
                    canvas.drawRect(left, top, left + cellPx, top + cellPx, selPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (event.x < padX || event.y < headerH) return false
            val col = kotlin.math.floor((event.x - padX) / stepPx).toInt()
            val row = kotlin.math.floor((event.y - headerH) / stepPx).toInt()
            if (col in summary.weeks.indices && row in 0..6) {
                val day = summary.weeks[col][row]
                if (day != null) {
                    selectedDay = day
                    invalidate()
                    onDaySelected(day)
                    performClick()
                    return true
                }
            }
        }
        return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
