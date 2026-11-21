package br.com.viblio.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 4-corner + 4-edge perspective cropper: dimmed outside, accent border, dashed 3×3 inner grid,
 * draggable corners and side mid-handles (essential for narrow book spines).
 * While dragging any corner or edge, a 2.5× circular loupe with a crosshair shows the exact point
 * in the opposite top corner.
 */
class PerspectiveCropView(context: Context, private val accent: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private var bitmap: Bitmap? = null
    private var quad: List<QuadPoint> = emptyList()
    private var activeCorner = -1
    private var activeEdge = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val fit = RectF()
    private var scale = 1f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAD000000.toInt(); style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.2f * density }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (accent and 0xffffff) or 0xA8000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.8f * density }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 1.6f * density }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val loupeMatrix = Matrix()
    private val path = Path()
    private val loupeClip = Path()
    private val pillRect = RectF()
    var onChanged: (() -> Unit)? = null

    init {
        isFocusable = true
        setBackgroundColor(0xFF0E0E0E.toInt())
        contentDescription = "Ajuste os quatro cantos ou as bordas arrastando os pontos"
    }

    fun setImage(image: Bitmap, corners: List<QuadPoint>) {
        bitmap = image
        loupePaint.shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        quad = PerspectiveGeometry.orderQuad(corners.map { PerspectiveGeometry.clamp(it, image.width, image.height) })
        activeCorner = -1
        activeEdge = -1
        computeFit(); invalidate()
    }

    fun clearImage() {
        bitmap = null
        loupePaint.shader = null
        quad = emptyList()
        activeCorner = -1
        activeEdge = -1
    }

    fun corners(): List<QuadPoint> = quad

    fun setCorners(corners: List<QuadPoint>) {
        val image = bitmap ?: return
        val next = PerspectiveGeometry.orderQuad(corners.map { PerspectiveGeometry.clamp(it, image.width, image.height) })
        if (!PerspectiveGeometry.isConvex(next)) return
        quad = next; invalidate(); onChanged?.invoke()
    }

    fun fullMargin() { val image = bitmap ?: return; setCorners(PerspectiveGeometry.fullQuad(image.width, image.height)) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); computeFit() }

    private fun computeFit() {
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val pad = 20 * density
        val availW = (width - 2 * pad).coerceAtLeast(1f); val availH = (height - 2 * pad).coerceAtLeast(1f)
        scale = minOf(availW / image.width, availH / image.height)
        val w = image.width * scale; val h = image.height * scale
        fit.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun sx(p: QuadPoint) = fit.left + p.x * scale
    private fun sy(p: QuadPoint) = fit.top + p.y * scale
    private fun lerp(a: QuadPoint, b: QuadPoint, t: Float) = QuadPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    private fun edgeMid(edge: Int): QuadPoint = lerp(quad[edge], quad[(edge + 1) % 4], 0.5f)

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        if (image.isRecycled || quad.size != 4) return
        canvas.drawBitmap(image, null, fit, bitmapPaint)
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.moveTo(sx(quad[0]), sy(quad[0])); for (i in 1..3) path.lineTo(sx(quad[i]), sy(quad[i])); path.close()
        canvas.drawPath(path, dimPaint)

        val (tl, tr, br, bl) = quad
        for (t in floatArrayOf(1f / 3, 2f / 3)) {
            val a = lerp(tl, tr, t); val b = lerp(bl, br, t)
            canvas.drawLine(sx(a), sy(a), sx(b), sy(b), gridPaint)
            val c = lerp(tl, bl, t); val d = lerp(tr, br, t)
            canvas.drawLine(sx(c), sy(c), sx(d), sy(d), gridPaint)
        }
        for (i in 0..3) {
            val a = quad[i]; val b = quad[(i + 1) % 4]
            canvas.drawLine(sx(a), sy(a), sx(b), sy(b), edgePaint)
        }

        // 4 side-edge pill handles (so narrow spines can be adjusted edge-by-edge without hitting corners).
        for (i in 0..3) {
            val a = quad[i]; val b = quad[(i + 1) % 4]
            val ax = sx(a); val ay = sy(a); val bx = sx(b); val by = sy(b)
            val len = hypot(bx - ax, by - ay)
            if (len < 28 * density) continue
            val mx = (ax + bx) / 2f; val my = (ay + by) / 2f
            val angle = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
            val halfLen = minOf(12f * density, len * 0.22f)
            val halfThick = (if (i == activeEdge) 5.2f else 4.2f) * density
            canvas.save()
            canvas.translate(mx, my)
            canvas.rotate(angle)
            pillRect.set(-halfLen, -halfThick, halfLen, halfThick)
            canvas.drawRoundRect(pillRect, halfThick, halfThick, handleFill)
            canvas.drawRoundRect(pillRect, halfThick, halfThick, handleStroke)
            canvas.restore()
        }

        // 4 corner circular handles
        val r = 9 * density
        quad.forEachIndexed { i, p ->
            val radius = if (i == activeCorner) r * 1.25f else r
            canvas.drawCircle(sx(p), sy(p), radius, handleFill)
            canvas.drawCircle(sx(p), sy(p), radius, handleStroke)
        }

        when {
            activeCorner in 0..3 -> drawLoupe(canvas, quad[activeCorner], quad[(activeCorner + 3) % 4], quad[(activeCorner + 1) % 4])
            activeEdge in 0..3 -> drawLoupe(canvas, edgeMid(activeEdge), quad[activeEdge], quad[(activeEdge + 1) % 4])
        }
    }

    private fun drawLoupe(canvas: Canvas, focus: QuadPoint, lineA: QuadPoint, lineB: QuadPoint) {
        val radius = 58 * density; val margin = 14 * density
        val px = sx(focus)
        // Opposite top corner so the finger never hides the magnified point.
        val cx = if (px < width / 2f) width - margin - radius else margin + radius
        val cy = margin + radius
        val zoom = scale * 2.5f
        loupeMatrix.reset(); loupeMatrix.setScale(zoom, zoom); loupeMatrix.postTranslate(cx - focus.x * zoom, cy - focus.y * zoom)
        loupePaint.shader?.setLocalMatrix(loupeMatrix)
        loupeClip.reset(); loupeClip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(loupeClip)
        canvas.drawColor(Color.BLACK)
        canvas.drawCircle(cx, cy, radius, loupePaint)
        fun lx(p: QuadPoint) = cx + (p.x - focus.x) * zoom
        fun ly(p: QuadPoint) = cy + (p.y - focus.y) * zoom
        if (activeCorner in 0..3) {
            canvas.drawLine(cx, cy, lx(lineA), ly(lineA), edgePaint)
            canvas.drawLine(cx, cy, lx(lineB), ly(lineB), edgePaint)
        } else {
            canvas.drawLine(lx(lineA), ly(lineA), lx(lineB), ly(lineB), edgePaint)
        }
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val arm = 11 * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        if (quad.size != 4) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cornerHit = 34 * density
                val edgeHit = 28 * density
                val bestCorner = quad.indices.minByOrNull { hypot(sx(quad[it]) - event.x, sy(quad[it]) - event.y) }
                    ?.takeIf { hypot(sx(quad[it]) - event.x, sy(quad[it]) - event.y) <= cornerHit } ?: -1
                if (bestCorner >= 0) {
                    activeCorner = bestCorner
                    activeEdge = -1
                } else {
                    val bestEdge = (0..3).minByOrNull { val m = edgeMid(it); hypot(sx(m) - event.x, sy(m) - event.y) }
                        ?.takeIf { val m = edgeMid(it); hypot(sx(m) - event.x, sy(m) - event.y) <= edgeHit } ?: -1
                    if (bestEdge < 0) return false
                    activeCorner = -1
                    activeEdge = bestEdge
                }
                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner >= 0) {
                    val moved = PerspectiveGeometry.clamp(QuadPoint((event.x - fit.left) / scale, (event.y - fit.top) / scale), image.width, image.height)
                    val candidate = quad.toMutableList().also { it[activeCorner] = moved }
                    if (PerspectiveGeometry.isConvex(candidate, minArea = 64f)) { quad = candidate; invalidate() }
                    return true
                }
                if (activeEdge >= 0) {
                    val dx = (event.x - lastTouchX) / scale
                    val dy = (event.y - lastTouchY) / scale
                    lastTouchX = event.x
                    lastTouchY = event.y
                    val i1 = activeEdge
                    val i2 = (activeEdge + 1) % 4
                    // Move both endpoints along the edge's perpendicular axis (horizontal for left/right, vertical for top/bottom).
                    val a = quad[i1]; val b = quad[i2]
                    val ex = b.x - a.x; val ey = b.y - a.y
                    val len = hypot(ex, ey).coerceAtLeast(1f)
                    val nx = -ey / len; val ny = ex / len
                    val proj = dx * nx + dy * ny
                    val mx = nx * proj; val my = ny * proj
                    val p1 = PerspectiveGeometry.clamp(QuadPoint(a.x + mx, a.y + my), image.width, image.height)
                    val p2 = PerspectiveGeometry.clamp(QuadPoint(b.x + mx, b.y + my), image.width, image.height)
                    val candidate = quad.toMutableList().also { it[i1] = p1; it[i2] = p2 }
                    if (PerspectiveGeometry.isConvex(candidate, minArea = 64f)) { quad = candidate; invalidate() }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeCorner < 0 && activeEdge < 0) return false
                activeCorner = -1
                activeEdge = -1
                val ordered = PerspectiveGeometry.orderQuad(quad)
                if (PerspectiveGeometry.isConvex(ordered)) quad = ordered
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onChanged?.invoke()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
