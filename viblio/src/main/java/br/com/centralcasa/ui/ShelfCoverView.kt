package br.com.centralcasa.ui

import android.content.Context
import android.widget.ImageView

/** A portrait cover stays 2:3 as its card grows or shrinks; FIT_CENTER preserves the image. */
class ShelfCoverView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((width * 1.5f).toInt(), MeasureSpec.EXACTLY))
    }
}
