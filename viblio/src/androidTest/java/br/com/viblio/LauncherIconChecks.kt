package br.com.viblio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/** Verify the Android 8+ adaptive icon fills the launcher mask with a properly scaled book glyph. */
object LauncherIconChecks {
    fun run(test:Instrumentation){
        check(android.os.Build.VERSION.SDK_INT>=26)
        val context=test.targetContext;val pm=context.packageManager
        val current=pm.getApplicationIcon(context.packageName)
        check(current is AdaptiveIconDrawable){"Expected AdaptiveIconDrawable on API 26+, got ${current::class.java.name}"}
        fun render(drawable:Drawable,size:Int):Bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also {drawable.setBounds(0,0,size,size);drawable.draw(Canvas(it))}
        fun book(bitmap:Bitmap):Rect {
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            var left=bitmap.width;var right=-1;var top=bitmap.height;var bottom=-1
            pixels.forEachIndexed {i,color->if(Color.alpha(color)>0 && (Color.red(color)>32 || Color.green(color)>32 || Color.blue(color)>32)){val x=i%bitmap.width;val y=i/bitmap.width;left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)}}
            check(right>=left);return Rect(left,top,right+1,bottom+1)
        }
        val ratios=mutableListOf<Float>()
        for(size in listOf(108,216,324)){
            val rendered=render(current,size)
            val bounds=book(rendered)
            ratios.add(bounds.width().toFloat()/size)
            rendered.recycle()
        }
        check(ratios.all {it in .46f.. .58f}){"Adaptive book scale outside safe target range: $ratios"}
        check(ratios.maxOrNull()!!-ratios.minOrNull()!!<.02f){"Scale depends on render size: $ratios"}
    }
}
