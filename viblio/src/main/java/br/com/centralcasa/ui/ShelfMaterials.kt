package br.com.centralcasa.ui

import android.content.Context
import android.graphics.*
import android.view.View

/** Local materials sampled from the approved mockup, never from a remote URL. */
class ShelfMaterials private constructor(val leather:Bitmap,val wood:Bitmap) {
    companion object {
        @Volatile private var cached:ShelfMaterials?=null
        @Synchronized fun load(context:Context):ShelfMaterials? {
            cached?.let {return it}
            val source=runCatching {context.assets.open("shelf-materials.png").use {BitmapFactory.decodeStream(it)}}.getOrNull() ?: return null
            try {
                if(source.width!=1024 || source.height!=1536)return null
                // These regions contain material only: no lettering or book silhouettes.
                val crop=Bitmap.createBitmap(source,75,680,120,160)
                val pixels=IntArray(crop.width*crop.height);crop.getPixels(pixels,0,crop.width,0,0,crop.width,crop.height)
                fun light(c:Int)=.2126*Color.red(c)+.7152*Color.green(c)+.0722*Color.blue(c)
                val mean=pixels.sumOf(::light)/pixels.size
                for(i in pixels.indices){
                    val delta=((light(pixels[i])-mean)/mean).coerceIn(-.08,.08)
                    pixels[i]=(kotlin.math.abs(delta)*255).toInt().shl(24) or if(delta>0)0x00ffffff else 0
                }
                val leather=Bitmap.createBitmap(pixels,crop.width,crop.height,Bitmap.Config.ARGB_8888);crop.recycle()
                val wood=Bitmap.createBitmap(source,26,1330,973,48)
                return ShelfMaterials(leather,wood).also {cached=it}
            }finally {source.recycle()}
        }
    }
}

/** The wooden lip uses the original mockup's grain, highlight and rounded ends. */
class ShelfWoodView(context:Context,private val materials:ShelfMaterials?):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val wood=materials?.wood
        if(wood!=null)canvas.drawBitmap(wood,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)
        else {paint.color=0xff75431f.toInt();canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),3f,3f,paint)}
    }
}
