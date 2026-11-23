package br.com.viblio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import br.com.centralcasa.ui.DisplayUi

data class ClubTrackMember(
    val monogram: String,
    val page: Long,
    val isMe: Boolean,
)

data class ClubTrackStage(
    val number: Int,
    val pageLimit: Long,
    val isCurrent: Boolean,
)

/** Pure-Kotlin positioning math for the club reading track (JVM unit-testable). */
object ClubReadingTrackLayout {
    fun fraction(page: Long, totalPages: Long): Float {
        if (totalPages <= 0L) return 0f
        return (page.coerceIn(0L, totalPages).toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
    }

    /** Computes X fractions and vertical lane offsets (-1, 0, +1) so clustered avatars stay readable. */
    fun place(members: List<ClubTrackMember>, totalPages: Long, clusterThreshold: Float = 0.06f): List<Pair<Float, Int>> {
        val sorted = members.mapIndexed { idx, m -> Triple(idx, fraction(m.page, totalPages), m.isMe) }
            .sortedWith(compareBy<Triple<Int, Float, Boolean>> { it.second }.thenBy { if (it.third) 1 else 0 })
        val out = Array(members.size) { 0f to 0 }
        var lastFrac = -1f
        var laneStep = 0
        sorted.forEach { (origIdx, frac, isMe) ->
            val lane = if (isMe) {
                0
            } else if (lastFrac >= 0f && (frac - lastFrac) < clusterThreshold) {
                laneStep = if (laneStep <= 0) 1 else -1
                laneStep
            } else {
                laneStep = 0
                0
            }
            out[origIdx] = frac to lane
            if (!isMe) lastFrac = frac
        }
        return out.toList()
    }
}

/**
 * Editorial club reading track: horizontal spine (0..totalPages) with stage markers
 * and circular participant avatars placed at their exact current reading page.
 */
class ClubReadingTrackView(
    context: Context,
    private val ui: DisplayUi,
    private val totalPages: Long,
    private val members: List<ClubTrackMember>,
    private val stages: List<ClubTrackStage>,
) : View(context) {
    private val meRadius = ui.dp(13).toFloat()
    private val peerRadius = ui.dp(10).toFloat()
    private val padX = meRadius + ui.dp(4)
    private val trackY = ui.dp(24).toFloat()
    private val labelY = ui.dp(50).toFloat()

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.galleryHairline(130)
        strokeWidth = ui.dp(2).coerceAtLeast(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.tint(ui.accent, 115)
        strokeWidth = ui.dp(2).coerceAtLeast(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = ui.dp(2).coerceAtLeast(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val fillCircle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(2).coerceAtLeast(2).toFloat()
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * context.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(ui.dp(200))
        setMeasuredDimension(w, ui.dp(56))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = padX
        val right = (width - padX).coerceAtLeast(left + 1f)
        val span = right - left

        // Base rail
        canvas.drawLine(left, trackY, right, trackY, railPaint)

        // Subtle fill up to current user's page (if present)
        members.firstOrNull { it.isMe }?.let { me ->
            val mx = left + span * ClubReadingTrackLayout.fraction(me.page, totalPages)
            if (mx > left + 1f) canvas.drawLine(left, trackY, mx, trackY, fillPaint)
        }

        // Stage tick marks + sparse non-overlapping labels
        val drawnLabels = mutableListOf<Float>()
        fun drawTickLabel(x: Float, text: String, color: Int) {
            if (drawnLabels.any { kotlin.math.abs(it - x) < ui.dp(38) }) return
            drawnLabels.add(x)
            labelPaint.color = color
            val clampedX = x.coerceIn(ui.dp(18).toFloat(), (width - ui.dp(18)).toFloat())
            canvas.drawText(text, clampedX, labelY, labelPaint)
        }

        // Always draw current stage first so it wins label space
        stages.sortedByDescending { if (it.isCurrent) 1 else 0 }.forEach { st ->
            val f = ClubReadingTrackLayout.fraction(st.pageLimit, totalPages)
            val sx = left + span * f
            val halfH = ui.dp(if (st.isCurrent) 8 else 6).toFloat()
            tickPaint.color = if (st.isCurrent) ui.accent else ui.galleryHairline(180)
            canvas.drawLine(sx, trackY - halfH, sx, trackY + halfH, tickPaint)
            drawTickLabel(sx, "E${st.number}·${st.pageLimit}", if (st.isCurrent) ui.accent else ui.tint(ui.muted, 175))
        }
        drawTickLabel(left, "p.0", ui.tint(ui.muted, 155))
        drawTickLabel(right, totalPages.toString(), ui.tint(ui.muted, 155))

        // Draw peer avatars first, then current user on top
        val placements = ClubReadingTrackLayout.place(members, totalPages)
        fun drawMember(m: ClubTrackMember, frac: Float, lane: Int) {
            val r = if (m.isMe) meRadius else peerRadius
            val cx = left + span * frac
            val cy = trackY + lane * ui.dp(5).toFloat()
            fillCircle.color = if (m.isMe) ui.accent else ui.surface
            canvas.drawCircle(cx, cy, r, fillCircle)
            ringPaint.color = if (m.isMe) ui.text else ui.galleryHairline(200)
            canvas.drawCircle(cx, cy, r, ringPaint)

            monoPaint.color = if (m.isMe) ui.background else ui.text
            monoPaint.textSize = (if (m.isMe) 10.5f else 9f) * context.resources.displayMetrics.scaledDensity
            monoPaint.typeface = Typeface.create(if (m.isMe) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            val fm = monoPaint.fontMetrics
            val dy = -(fm.ascent + fm.descent) * 0.5f
            canvas.drawText(m.monogram.take(1), cx, cy + dy, monoPaint)
        }

        members.forEachIndexed { i, m -> if (!m.isMe) drawMember(m, placements[i].first, placements[i].second) }
        members.forEachIndexed { i, m -> if (m.isMe) drawMember(m, placements[i].first, placements[i].second) }
    }
}
