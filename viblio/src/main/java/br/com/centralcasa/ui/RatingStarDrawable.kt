package br.com.centralcasa.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

class RatingStarDrawable(private val fraction: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds; val radius = minOf(b.width(), b.height()) * .46f
        val path = Path()
        repeat(10) { i ->
            val angle = Math.PI * i / 5 - Math.PI / 2
            val r = if (i % 2 == 0) radius else radius * .45f
            val x = b.exactCenterX() + cos(angle).toFloat() * r
            val y = b.exactCenterY() + sin(angle).toFloat() * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }; path.close()
        paint.color = Color.rgb(112, 112, 112); paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
        val save = canvas.save(); canvas.clipRect(b.left.toFloat(), b.top.toFloat(), b.left + b.width() * fraction.coerceIn(0f, 1f), b.bottom.toFloat())
        paint.color = Color.rgb(239, 181, 95); canvas.drawPath(path, paint); canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
