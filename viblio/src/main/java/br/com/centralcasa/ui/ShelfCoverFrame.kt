package br.com.centralcasa.ui

import android.content.Context
import android.graphics.Canvas
import android.widget.FrameLayout
import br.com.centralcasa.books.LibraryBook

/** Draw the perimeter over the complete card, independent of foreground padding on Android. */
class ShelfCoverFrame(context: Context, book: LibraryBook, current: Boolean, themeBorder: Int? = null) : FrameLayout(context) {
    private val rectangular=context.packageName=="br.com.viblio"
    private val border = ShelfBookBorder(book, current, resources.displayMetrics.density, android.graphics.Color.TRANSPARENT, themeBorder,rectangular)
    init {
        val inset = (5 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        setPadding(inset, inset, inset, inset)
        clipChildren = true
        clipToPadding = true
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        if(rectangular){
            val density=resources.displayMetrics.density
            val width=MeasureSpec.getSize(widthMeasureSpec)
            if(width>0){val inset=(5*density*(width/(100f*density)).coerceIn(.55f,1.25f)).toInt().coerceAtLeast(1)
                if(paddingLeft!=inset)setPadding(inset,inset,inset,inset)
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        border.setBounds(0, 0, width, height)
        border.draw(canvas)
    }
}
