package br.com.viblio

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.io.File

/** Compare actual packaged drawables against the published APK, not nominal XML margins. */
object LauncherIconChecks {
    fun run(test:Instrumentation){
        check(android.os.Build.VERSION.SDK_INT>=26)
        val context=test.targetContext;val pm=context.packageManager
        val folder=context.getExternalFilesDir("validation")!!;folder.mkdirs()
        val baseline=File(folder,"viblio-icon-baseline.apk");check(baseline.isFile)
        val info=pm.getPackageArchiveInfo(baseline.absolutePath,0) ?: error("Missing baseline manifest")
        val app=info.applicationInfo!!;app.sourceDir=baseline.absolutePath;app.publicSourceDir=baseline.absolutePath
        val old=pm.getResourcesForApplication(app).getDrawable(app.icon,null)
        val current=pm.getApplicationIcon(context.packageName)
        fun render(drawable:Drawable,size:Int):Bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also {drawable.setBounds(0,0,size,size);drawable.draw(Canvas(it))}
        fun book(bitmap:Bitmap):Rect {
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            var left=bitmap.width;var right=-1;var top=bitmap.height;var bottom=-1
            pixels.forEachIndexed {i,color->if(Color.alpha(color)>0 && (Color.red(color)>21 || Color.green(color)>21 || Color.blue(color)>21)){val x=i%bitmap.width;val y=i/bitmap.width;left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)}}
            check(right>=left);return Rect(left,top,right+1,bottom+1)
        }
        val ratios=mutableListOf<Float>()
        for(size in listOf(108,216,324)){
            val before=render(old,size);val after=render(current,size)
            val oldBook=book(before);val newBook=book(after)
            ratios.add(newBook.width().toFloat()/size)
            check(newBook.width()<oldBook.width()*.85f){"Book did not shrink: $size $oldBook $newBook"}
            // Every frame pixel outside either book stays unchanged, including rounded corners.
            for(y in 0 until size)for(x in 0 until size)if(!oldBook.contains(x,y) && !newBook.contains(x,y))check(before.getPixel(x,y)==after.getPixel(x,y)){"Background changed at $x,$y"}
            if(size==216){
                File(folder,"launcher-icon-before.png").outputStream().use {before.compress(Bitmap.CompressFormat.PNG,100,it)}
                File(folder,"launcher-icon-after.png").outputStream().use {after.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            before.recycle();after.recycle()
        }
        check(ratios.all {it in .37f.. .42f}){"Book is not 40% of the icon: $ratios"}
        check(ratios.maxOrNull()!!-ratios.minOrNull()!!<.02f){"Scale depends on render size: $ratios"}
    }
}
