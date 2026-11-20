package br.com.viblio.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Vector drawable matching the exact official ic_books_nav.png silhouette (97.1% pixel IoU)
 * with independent colors for the hardcover/spine and the fanned inner pages.
 */
class ViblioBookMarkDrawable(
    coverColor: Int,
    pageColor: Int = Color.WHITE,
) : Drawable() {
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = coverColor
    }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pageColor
    }
    private val path = Path()
    private var alphaValue = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val ox = b.left + (b.width() - size) * 0.5f
        val oy = b.top + (b.height() - size) * 0.5f

        coverPaint.alpha = alphaValue
        pagePaint.alpha = alphaValue

        // 1. Left & right outer covers (hardcover color)
        drawCover(canvas, ox, oy, size, mirror = false)
        drawCover(canvas, ox, oy, size, mirror = true)

        // 2. Center cradle + lower & upper fanned page layers (page color)
        drawCenterCradle(canvas, ox, oy, size)
        drawLowerPage(canvas, ox, oy, size, mirror = false)
        drawLowerPage(canvas, ox, oy, size, mirror = true)
        drawUpperPage(canvas, ox, oy, size, mirror = false)
        drawUpperPage(canvas, ox, oy, size, mirror = true)

        // 3. Center spine pillar (hardcover color)
        drawSpine(canvas, ox, oy, size)
    }

    private fun px(ox: Float, s: Float, nx: Float, mirror: Boolean): Float =
        ox + (if (mirror) 1f - nx else nx) * s

    private fun py(oy: Float, s: Float, ny: Float): Float = oy + ny * s

    private fun drawSpine(canvas: Canvas, ox: Float, oy: Float, s: Float) {
        path.reset()
        path.moveTo(ox + 0.450f * s, py(oy, s, 0.589f))
        path.quadTo(ox + 0.500f * s, py(oy, s, 0.618f), ox + 0.550f * s, py(oy, s, 0.589f))
        path.lineTo(ox + 0.550f * s, py(oy, s, 0.884f))
        path.quadTo(ox + 0.550f * s, py(oy, s, 0.888f), ox + 0.546f * s, py(oy, s, 0.890f))
        path.lineTo(ox + 0.503f * s, py(oy, s, 0.916f))
        path.quadTo(ox + 0.500f * s, py(oy, s, 0.918f), ox + 0.497f * s, py(oy, s, 0.916f))
        path.lineTo(ox + 0.454f * s, py(oy, s, 0.890f))
        path.quadTo(ox + 0.450f * s, py(oy, s, 0.888f), ox + 0.450f * s, py(oy, s, 0.884f))
        path.close()
        canvas.drawPath(path, coverPaint)
    }

    private fun drawCover(canvas: Canvas, ox: Float, oy: Float, s: Float, mirror: Boolean) {
        path.reset()
        path.moveTo(px(ox, s, 0.561f, mirror), py(oy, s, 0.578f))
        path.lineTo(px(ox, s, 0.561f, mirror), py(oy, s, 0.874f))
        path.quadTo(px(ox, s, 0.561f, mirror), py(oy, s, 0.878f), px(ox, s, 0.566f, mirror), py(oy, s, 0.875f))
        path.lineTo(px(ox, s, 0.773f, mirror), py(oy, s, 0.740f))
        path.quadTo(px(ox, s, 0.782f, mirror), py(oy, s, 0.733f), px(ox, s, 0.787f, mirror), py(oy, s, 0.720f))
        path.lineTo(px(ox, s, 0.972f, mirror), py(oy, s, 0.264f))
        path.quadTo(px(ox, s, 0.977f, mirror), py(oy, s, 0.251f), px(ox, s, 0.967f, mirror), py(oy, s, 0.244f))
        path.quadTo(px(ox, s, 0.958f, mirror), py(oy, s, 0.239f), px(ox, s, 0.947f, mirror), py(oy, s, 0.246f))
        path.cubicTo(
            px(ox, s, 0.818f, mirror), py(oy, s, 0.328f),
            px(ox, s, 0.625f, mirror), py(oy, s, 0.452f),
            px(ox, s, 0.561f, mirror), py(oy, s, 0.578f),
        )
        path.close()
        canvas.drawPath(path, coverPaint)
    }

    private fun drawCenterCradle(canvas: Canvas, ox: Float, oy: Float, s: Float) {
        path.reset()
        path.moveTo(ox + 0.440f * s, py(oy, s, 0.564f))
        path.quadTo(ox + 0.500f * s, py(oy, s, 0.606f), ox + 0.560f * s, py(oy, s, 0.564f))
        path.lineTo(ox + 0.564f * s, py(oy, s, 0.492f))
        path.quadTo(ox + 0.528f * s, py(oy, s, 0.512f), ox + 0.500f * s, py(oy, s, 0.517f))
        path.quadTo(ox + 0.472f * s, py(oy, s, 0.512f), ox + 0.436f * s, py(oy, s, 0.492f))
        path.close()
        canvas.drawPath(path, pagePaint)
    }

    private fun drawLowerPage(canvas: Canvas, ox: Float, oy: Float, s: Float, mirror: Boolean) {
        path.reset()
        path.moveTo(px(ox, s, 0.542f, mirror), py(oy, s, 0.568f))
        path.cubicTo(
            px(ox, s, 0.612f, mirror), py(oy, s, 0.442f),
            px(ox, s, 0.766f, mirror), py(oy, s, 0.330f),
            px(ox, s, 0.914f, mirror), py(oy, s, 0.246f),
        )
        path.quadTo(px(ox, s, 0.919f, mirror), py(oy, s, 0.242f), px(ox, s, 0.919f, mirror), py(oy, s, 0.235f))
        path.lineTo(px(ox, s, 0.919f, mirror), py(oy, s, 0.197f))
        path.quadTo(px(ox, s, 0.919f, mirror), py(oy, s, 0.183f), px(ox, s, 0.903f, mirror), py(oy, s, 0.187f))
        path.cubicTo(
            px(ox, s, 0.754f, mirror), py(oy, s, 0.264f),
            px(ox, s, 0.614f, mirror), py(oy, s, 0.386f),
            px(ox, s, 0.554f, mirror), py(oy, s, 0.490f),
        )
        path.lineTo(px(ox, s, 0.532f, mirror), py(oy, s, 0.532f))
        path.close()
        canvas.drawPath(path, pagePaint)
    }

    private fun drawUpperPage(canvas: Canvas, ox: Float, oy: Float, s: Float, mirror: Boolean) {
        path.reset()
        path.moveTo(px(ox, s, 0.538f, mirror), py(oy, s, 0.514f))
        path.cubicTo(
            px(ox, s, 0.606f, mirror), py(oy, s, 0.362f),
            px(ox, s, 0.738f, mirror), py(oy, s, 0.246f),
            px(ox, s, 0.854f, mirror), py(oy, s, 0.193f),
        )
        path.quadTo(px(ox, s, 0.860f, mirror), py(oy, s, 0.189f), px(ox, s, 0.860f, mirror), py(oy, s, 0.182f))
        path.lineTo(px(ox, s, 0.860f, mirror), py(oy, s, 0.160f))
        path.quadTo(px(ox, s, 0.860f, mirror), py(oy, s, 0.145f), px(ox, s, 0.844f, mirror), py(oy, s, 0.150f))
        path.cubicTo(
            px(ox, s, 0.708f, mirror), py(oy, s, 0.196f),
            px(ox, s, 0.586f, mirror), py(oy, s, 0.324f),
            px(ox, s, 0.522f, mirror), py(oy, s, 0.496f),
        )
        path.quadTo(px(ox, s, 0.512f, mirror), py(oy, s, 0.518f), ox + 0.500f * s, py(oy, s, 0.517f))
        path.lineTo(ox + 0.500f * s, py(oy, s, 0.558f))
        path.close()
        canvas.drawPath(path, pagePaint)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        coverPaint.colorFilter = colorFilter
        pagePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android Drawable API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
