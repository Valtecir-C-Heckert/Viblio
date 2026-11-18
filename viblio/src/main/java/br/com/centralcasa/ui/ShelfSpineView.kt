package br.com.centralcasa.ui

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.ImageView
import br.com.centralcasa.books.CoverColors
import br.com.centralcasa.books.ShelfSpines

/** A binding drawn independently of the cover: neighbouring spines touch at their edges. */
class ShelfSpineView(context:Context,private val title:String,private val subtitle:String="",private val author:String="",private val materials:ShelfMaterials?=null):ImageView(context) {
    var spineColor:Int=0xff404944.toInt()
        private set
    private var faceColor=ShelfSpines.materialFace(spineColor)
    private var inkColor=ShelfSpines.materialInk(faceColor)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {typeface=Typeface.create("serif",Typeface.BOLD)}
    private val authorPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {typeface=Typeface.create("serif",Typeface.NORMAL)}
    private val subtitlePaint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    private var titleLayout:StaticLayout?=null
    private var subtitleLayout:StaticLayout?=null
    private var blockHeight=0f
    private var authorLayout:StaticLayout?=null
    private var authorTop=0f
    private var dividerY=0f
    private var bodyTop=0f
    private var bodyBottom=0f
    private val surfacePath=Path()
    private val texturePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader=materials?.leather?.let {BitmapShader(it,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).apply {setLocalMatrix(Matrix().apply {setScale(density/3f,density/3f)})}}
    }
    override fun setImageBitmap(bitmap:Bitmap?){
        if(bitmap==null)return
        val width=minOf(32,bitmap.width);val height=minOf(32,bitmap.height)
        val pixels=IntArray(width*height)
        for(y in 0 until height)for(x in 0 until width)pixels[y*width+x]=bitmap.getPixel(x*bitmap.width/width,y*bitmap.height/height)
        spineColor=CoverColors.dominant(pixels) ?: 0xff404944.toInt();faceColor=ShelfSpines.materialFace(spineColor);inkColor=ShelfSpines.materialInk(faceColor);invalidate()
    }
    @Suppress("DEPRECATION")
    private fun fit(value:String,brush:TextPaint,length:Int,cross:Float,preferred:Float,wholeWords:Boolean=false):StaticLayout {
        fun layout(size:Float):StaticLayout {brush.textSize=size;return StaticLayout(value,brush,length,Layout.Alignment.ALIGN_CENTER,1f,0f,false)}
        fun fits(block:StaticLayout):Boolean {
            if(block.height>cross)return false
            return !wholeWords || (0 until block.lineCount).all {line->
                val end=block.getLineEnd(line)
                end==0 || end>=value.length || value[end-1].isWhitespace() || value[end].isWhitespace()
            }
        }
        val initial=layout(preferred);if(fits(initial))return initial
        var low=.01f;var high=preferred
        repeat(16){val middle=(low+high)/2;if(fits(layout(middle)))low=middle else high=middle}
        return layout(low)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        val scale=h/(220*density)
        val inset=5*density*scale
        val authorValue=ShelfSpines.authorLines(author)
        authorTop=12*density*scale
        val authorSpace=if(authorValue.isBlank())0f else 36*density*scale
        val authorWidth=(w-2*inset).toInt().coerceAtLeast(1)
        val preferredAuthor=13*density*scale*resources.configuration.fontScale
        authorPaint.textSize=preferredAuthor
        val unbroken=listOf(authorValue.substringBefore('\n'))+authorValue.substringAfter('\n',"").split(Regex("\\s+"))
        val longest=unbroken.maxOfOrNull {authorPaint.measureText(it)} ?: 0f
        val authorSize=preferredAuthor*minOf(1f,authorWidth*.99f/longest.coerceAtLeast(1f))
        authorLayout=if(authorValue.isBlank())null else fit(authorValue,authorPaint,authorWidth,authorSpace,authorSize,wholeWords=true)
        dividerY=if(authorLayout==null)12*density*scale else authorTop+authorSpace+5*density*scale
        bodyTop=dividerY+10*density*scale;bodyBottom=h-14*density*scale
        val length=(bodyBottom-bodyTop).toInt().coerceAtLeast(1)
        val cross=(w-2*inset).coerceAtLeast(1f)
        val gap=if(subtitle.isBlank())0f else 3*density*scale
        subtitleLayout=if(subtitle.isBlank())null else fit(subtitle,subtitlePaint,length,cross*.35f,9*density*scale*resources.configuration.fontScale)
        val preferredTitle=20*density*scale*resources.configuration.fontScale
        text.textSize=preferredTitle
        val singleLine=preferredTitle*minOf(1f,length*.98f/text.measureText(title).coerceAtLeast(1f))
        val titleSize=if(singleLine>=8*density*scale)singleLine else preferredTitle
        titleLayout=fit(title,text,length,(cross-(subtitleLayout?.height ?: 0)-gap).coerceAtLeast(1f),titleSize,wholeWords=true)
        blockHeight=(titleLayout?.height ?: 0)+(subtitleLayout?.height ?: 0)+gap
        surfacePath.reset();surfacePath.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),minOf(3*density,w*.1f),3*density,Path.Direction.CW)
    }
    override fun onDraw(canvas:Canvas){
        val w=width.toFloat();val h=height.toFloat();if(w<=0 || h<=0)return
        val face=faceColor
        val color=inkColor
        canvas.save();canvas.clipPath(surfacePath)
        paint.color=face;paint.shader=null;canvas.drawRect(0f,0f,w,h,paint)
        if(texturePaint.shader!=null)canvas.drawRect(0f,0f,w,h,texturePaint)
        val edge=minOf(3*density,w*.12f)/w
        paint.shader=LinearGradient(0f,0f,w,0f,intArrayOf(0x77000000,0x00000000,0x00000000,0x66000000),floatArrayOf(0f,edge,1-edge,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint);paint.shader=null
        paint.color=0x18ffffff;canvas.drawRect(density,3*density,2*density,h-3*density,paint)
        text.color=color;subtitlePaint.color=color;authorPaint.color=color
        authorLayout?.let {block->
            canvas.save();canvas.translate((w-block.width)/2,authorTop+(36*density*h/(220*density)-block.height)/2);block.draw(canvas);canvas.restore()
        }
        paint.color=(color and 0xffffff) or 0x99000000.toInt();paint.strokeWidth=density*.7f
        canvas.drawLine(5*density,dividerY,w-5*density,dividerY,paint)
        val titleBlock=titleLayout
        if(titleBlock!=null){
            canvas.save();canvas.translate(w/2,(bodyTop+bodyBottom)/2);canvas.rotate(90f)
            canvas.translate(-titleBlock.width/2f,-blockHeight/2f);titleBlock.draw(canvas)
            subtitleLayout?.let {canvas.translate(0f,titleBlock.height+3*density*h/(220*density));it.draw(canvas)}
            canvas.restore()
        }
        canvas.restore()
    }
}
