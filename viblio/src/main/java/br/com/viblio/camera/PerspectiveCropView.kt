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
import kotlin.math.hypot

/**
 * 4-corner perspective cropper: dimmed outside, accent border, dashed 3×3 inner grid and
 * draggable corners. While a corner moves, a 2.5× circular loupe with a crosshair shows the
 * exact point in the opposite top corner.
 */
class PerspectiveCropView(context: Context, private val accent: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private var bitmap: Bitmap? = null
    private var quad: List<QuadPoint> = emptyList()
    private var active = -1
    private val fit = RectF()
    private var scale = 1f
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt(); style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.2f * density }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (accent and 0xffffff) or 0xB0000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 1.6f * density }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val loupeMatrix = Matrix()
    private val path = Path()
    private val loupeClip = Path()
    var onChanged: (() -> Unit)? = null

    init {
        isFocusable = true
        contentDescription = "Ajuste os quatro cantos arrastando os pontos"
    }

    fun setImage(image: Bitmap, corners: List<QuadPoint>) {
        bitmap = image
        loupePaint.shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        quad = PerspectiveGeometry.orderQuad(corners.map { PerspectiveGeometry.clamp(it, image.width, image.height) })
        active = -1
        computeFit(); invalidate()
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
        val pad = 22 * density
        val availW = (width - 2 * pad).coerceAtLeast(1f); val availH = (height - 2 * pad).coerceAtLeast(1f)
        scale = minOf(availW / image.width, availH / image.height)
        val w = image.width * scale; val h = image.height * scale
        fit.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun sx(p: QuadPoint) = fit.left + p.x * scale
    private fun sy(p: QuadPoint) = fit.top + p.y * scale
    private fun lerp(a: QuadPoint, b: QuadPoint, t: Float) = QuadPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

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
        for (i in 0..3) { val a = quad[i]; val b = quad[(i + 1) % 4]; canvas.drawLine(sx(a), sy(a), sx(b), sy(b), edgePaint) }
        val r = 9 * density
        quad.forEachIndexed { i, p ->
            val radius = if (i == active) r * 1.25f else r
            canvas.drawCircle(sx(p), sy(p), radius, handleFill); canvas.drawCircle(sx(p), sy(p), radius, handleStroke)
        }
        if (active in 0..3) drawLoupe(canvas, quad[active])
    }

    private fun drawLoupe(canvas: Canvas, point: QuadPoint) {
        val radius = 58 * density; val margin = 14 * density
        val px = sx(point); val py = sy(point)
        // Opposite top corner so the finger never hides the magnified point.
        val cx = if (px < width / 2f) width - margin - radius else margin + radius
        val cy = margin + radius
        val zoom = scale * 2.5f
        loupeMatrix.reset(); loupeMatrix.setScale(zoom, zoom); loupeMatrix.postTranslate(cx - point.x * zoom, cy - point.y * zoom)
        loupePaint.shader?.setLocalMatrix(loupeMatrix)
        loupeClip.reset(); loupeClip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(loupeClip)
        canvas.drawColor(Color.BLACK)
        canvas.drawCircle(cx, cy, radius, loupePaint)
        val prev = quad[(active + 3) % 4]; val next = quad[(active + 1) % 4]
        fun lx(p: QuadPoint) = cx + (p.x - point.x) * zoom
        fun ly(p: QuadPoint) = cy + (p.y - point.y) * zoom
        canvas.drawLine(cx, cy, lx(prev), ly(prev), edgePaint); canvas.drawLine(cx, cy, lx(next), ly(next), edgePaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val arm = 11 * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint); canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        if (quad.size != 4) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = 36 * density
                active = quad.indices.minByOrNull { hypot(sx(quad[it]) - event.x, sy(quad[it]) - event.y) }
                    ?.takeIf { hypot(sx(quad[it]) - event.x, sy(quad[it]) - event.y) <= hit } ?: -1
                if (active < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return false
                val moved = PerspectiveGeometry.clamp(QuadPoint((event.x - fit.left) / scale, (event.y - fit.top) / scale), image.width, image.height)
                val candidate = quad.toMutableList().also { it[active] = moved }
                if (PerspectiveGeometry.isConvex(candidate, minArea = 64f)) { quad = candidate; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active < 0) return false
                active = -1
                val ordered = PerspectiveGeometry.orderQuad(quad)
                if (PerspectiveGeometry.isConvex(ordered)) quad = ordered
                parent?.requestDisallowInterceptTouchEvent(false); invalidate(); onChanged?.invoke()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
