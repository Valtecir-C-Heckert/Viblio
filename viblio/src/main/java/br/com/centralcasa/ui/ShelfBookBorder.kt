package br.com.centralcasa.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import br.com.centralcasa.books.LibraryBook

/** Reading progress follows the cover perimeter clockwise from the top centre. */
class ShelfBookBorder(book: LibraryBook, isCurrent: Boolean, private val density: Float, private val surface: Int, private val themeBorder: Int? = null, private val rectangular:Boolean=false) : Drawable() {
    private val fraction = if (book.complete) 1f else (book.percent / 100f).coerceIn(0f, 1f)
    private val color = when {
        book.complete -> Color.rgb(76, 175, 80)
        isCurrent -> Color.rgb(66, 165, 245)
        else -> Color.rgb(255, 167, 38)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val perimeter = Path()
    private val progress = Path()
    private val measure = PathMeasure()

    override fun draw(canvas: Canvas) {
        val scale=if(rectangular)(bounds.width()/(100f*density)).coerceIn(.55f,1.25f) else 1f
        val inset = 1.5f * density * scale
        val r = RectF(bounds).apply { inset(inset, inset) }
        if (r.width() <= 0 || r.height() <= 0) return
        val radius = if(rectangular)0f else (10f * density - inset).coerceAtMost(minOf(r.width(), r.height()) / 2)
        paint.style = Paint.Style.FILL; paint.color = surface
        canvas.drawRoundRect(r, radius, radius, paint)
        perimeter.reset()
        perimeter.moveTo(r.centerX(), r.top)
        if(rectangular){
            perimeter.lineTo(r.right,r.top);perimeter.lineTo(r.right,r.bottom)
            perimeter.lineTo(r.left,r.bottom);perimeter.lineTo(r.left,r.top)
        }else {
        perimeter.lineTo(r.right - radius, r.top)
        perimeter.arcTo(RectF(r.right - 2 * radius, r.top, r.right, r.top + 2 * radius), -90f, 90f)
        perimeter.lineTo(r.right, r.bottom - radius)
        perimeter.arcTo(RectF(r.right - 2 * radius, r.bottom - 2 * radius, r.right, r.bottom), 0f, 90f)
        perimeter.lineTo(r.left + radius, r.bottom)
        perimeter.arcTo(RectF(r.left, r.bottom - 2 * radius, r.left + 2 * radius, r.bottom), 90f, 90f)
        perimeter.lineTo(r.left, r.top + radius)
        perimeter.arcTo(RectF(r.left, r.top, r.left + 2 * radius, r.top + 2 * radius), 180f, 90f)
        }
        perimeter.close()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density * scale
        paint.strokeCap = if(rectangular)Paint.Cap.BUTT else Paint.Cap.ROUND;paint.strokeJoin=if(rectangular)Paint.Join.MITER else Paint.Join.ROUND; paint.color = themeBorder ?: Color.rgb(97, 97, 97)
        canvas.drawPath(perimeter, paint)
        if (fraction > 0f) {
            paint.color = color; paint.strokeWidth = 3f * density * scale
            if (fraction == 1f) canvas.drawPath(perimeter, paint)
            else {
                measure.setPath(perimeter, true); progress.reset()
                measure.getSegment(0f, measure.length * fraction, progress, true)
                canvas.drawPath(progress, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Framework drawable contract")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
