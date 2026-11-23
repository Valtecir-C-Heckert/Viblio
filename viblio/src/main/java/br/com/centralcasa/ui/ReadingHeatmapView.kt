package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import br.com.centralcasa.books.MonthHeatmapGrid
import br.com.centralcasa.books.YearHeatmapSummary

/**
 * Full-width monthly 7-column calendar heatmap (Sun..Sat) with day numbers,
 * 4 intensity tiers derived from the current book cover accent, today dot,
 * and tap-to-inspect day selection.
 */
class ReadingHeatmapView(
    context: Context,
    private val ui: DisplayUi,
    private val summary: YearHeatmapSummary,
    private val grid: MonthHeatmapGrid,
    private val accentColor: Int,
    private val todayDay: String,
    private var selectedDay: String?,
    private val bulletJournal: Boolean = false,
    private val onDaySelected: (String) -> Unit,
) : View(context) {
    private val weekdays = arrayOf("D", "S", "T", "Q", "Q", "S", "S")
    private val gapPx = ui.dp(if (bulletJournal) 4 else 5).toFloat()
    private val headerH = ui.dp(20).toFloat()
    private val padX = ui.dp(1).toFloat()
    private val padBottom = ui.dp(2).toFloat()

    private var cellSize = ui.dp(34).toFloat()

    private val emptyFill = if (bulletJournal) Color.TRANSPARENT else if (ui.rectilinear) ui.background else 0xFF1B2922.toInt()
    private val emptyBorder = if (ui.rectilinear) ui.galleryHairline(115) else 0xFF293B31.toInt()
    private val levelColors = intArrayOf(
        emptyFill,
        ui.tint(accentColor, if (bulletJournal) 66 else 82),
        ui.tint(accentColor, if (bulletJournal) 152 else 165),
        accentColor,
    )
    private val darkInk = ui.background
    private val lightInk = ui.text
    private val mutedInk = ui.tint(ui.muted, if (bulletJournal) 185 else 220)

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(1).coerceAtLeast(1).toFloat()
        color = emptyBorder
    }
    private val todayRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(1).coerceAtLeast(1).toFloat()
        color = accentColor
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(2).coerceAtLeast(2).toFloat()
        color = ui.text
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.tint(ui.muted, 185)
        textSize = ui.dp(10).toFloat()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val dayNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (if (bulletJournal) 11.5f else 11f) * context.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(ui.dp(220))
        val usableW = (availW - padX * 2f - gapPx * 6f).coerceAtLeast(ui.dp(140).toFloat())
        cellSize = (usableW / 7f).coerceIn(ui.dp(24).toFloat(), ui.dp(if (bulletJournal) 38 else 44).toFloat())
        val rowsCount = grid.rows.size.coerceAtLeast(4)
        val totalH = (headerH + rowsCount * cellSize + (rowsCount - 1) * gapPx + padBottom).toInt()
        setMeasuredDimension(availW, totalH)
    }

    private fun startX(): Float {
        val gridW = 7f * cellSize + 6f * gapPx
        return ((width - gridW) * 0.5f).coerceAtLeast(padX)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ox = startX()
        val step = cellSize + gapPx

        // Weekday headers
        val headY = headerH - ui.dp(6)
        for (col in 0 until 7) {
            val cx = ox + col * step + cellSize * 0.5f
            canvas.drawText(weekdays[col], cx, headY, headerPaint)
        }

        val fm = dayNumPaint.fontMetrics
        val textCenterOffset = -(fm.ascent + fm.descent) * 0.5f

        grid.rows.forEachIndexed { rowIdx, week ->
            val top = headerH + rowIdx * step
            for (col in 0 until 7) {
                val day = week[col] ?: continue
                val left = ox + col * step
                val right = left + cellSize
                val bottom = top + cellSize
                val cx = left + cellSize * 0.5f
                val cy = top + cellSize * 0.5f
                val lvl = summary.daysByDate[day]?.level?.coerceIn(0, 3) ?: 0
                val dayNumber = day.substring(8, 10).toIntOrNull()?.toString() ?: ""

                if (bulletJournal) {
                    val radius = cellSize * 0.39f
                    if (lvl > 0) {
                        cellPaint.color = levelColors[lvl]
                        canvas.drawCircle(cx, cy, radius, cellPaint)
                    }
                    if (day == todayDay && day != selectedDay) {
                        canvas.drawCircle(cx, cy, radius, todayRingPaint)
                    }
                    if (day == selectedDay) {
                        canvas.drawCircle(cx, cy, radius, selPaint)
                    }
                    dayNumPaint.typeface = Typeface.create(if (lvl > 0 || day == todayDay) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                    dayNumPaint.color = when {
                        lvl > 0 -> if (isLightBlended(levelColors[lvl], ui.background)) darkInk else lightInk
                        day == todayDay -> accentColor
                        else -> mutedInk
                    }
                    canvas.drawText(dayNumber, cx, cy + textCenterOffset, dayNumPaint)
                } else {
                    cellPaint.color = levelColors[lvl]
                    canvas.drawRect(left, top, right, bottom, cellPaint)
                    if (lvl == 0) {
                        canvas.drawRect(left + 0.5f, top + 0.5f, right - 0.5f, bottom - 0.5f, borderPaint)
                    }
                    dayNumPaint.color = when {
                        lvl > 0 -> if (isLightBlended(levelColors[lvl], ui.surface)) darkInk else lightInk
                        else -> mutedInk
                    }
                    canvas.drawText(dayNumber, cx, cy + textCenterOffset, dayNumPaint)

                    if (day == todayDay) {
                        dotPaint.color = if (lvl > 0 && isLightBlended(levelColors[lvl], ui.surface)) darkInk else accentColor
                        canvas.drawCircle(cx, bottom - ui.dp(4).toFloat(), ui.dp(2).coerceAtLeast(2).toFloat(), dotPaint)
                    }
                    if (day == selectedDay) {
                        val inset = selPaint.strokeWidth * 0.5f
                        canvas.drawRect(left + inset, top + inset, right - inset, bottom - inset, selPaint)
                    }
                }
            }
        }
    }

    private fun isLightBlended(fg: Int, bg: Int): Boolean {
        val a = (fg ushr 24) / 255.0
        val r = Color.red(fg) * a + Color.red(bg) * (1.0 - a)
        val g = Color.green(fg) * a + Color.green(bg) * (1.0 - a)
        val b = Color.blue(fg) * a + Color.blue(bg) * (1.0 - a)
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum >= 140.0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val ox = startX()
            val step = cellSize + gapPx
            if (event.x < ox || event.y < headerH) return false
            val col = kotlin.math.floor((event.x - ox) / step).toInt()
            val row = kotlin.math.floor((event.y - headerH) / step).toInt()
            if (col in 0..6 && row in grid.rows.indices) {
                // Ensure tap landed inside the square rather than only in the gap
                val localX = (event.x - ox) - col * step
                val localY = (event.y - headerH) - row * step
                if (localX in 0f..cellSize && localY in 0f..cellSize) {
                    val day = grid.rows[row][col]
                    if (day != null) {
                        selectedDay = day
                        invalidate()
                        onDaySelected(day)
                        performClick()
                        return true
                    }
                }
            }
        }
        return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
