package br.com.centralcasa.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.*

/** Lightweight native components: no rendering loops or UI dependencies; book images are handled separately. */
class DisplayUi(private val context: Context) {
    val rectilinear=context.packageName=="br.com.viblio"
    private data class ReadingPalette(val background:Int,val surface:Int,val active:Int,val text:Int,val muted:Int,val accent:Int)
    companion object {
        @Volatile private var readingPalette=palette(null)
        private fun palette(color:Int?):ReadingPalette {
            val hsv=FloatArray(3)
            if(color!=null)Color.colorToHSV(color,hsv)
            fun shade(value:Float,saturation:Float,cap:Float)=Color.HSVToColor(floatArrayOf(hsv[0],(hsv[1]*saturation).coerceAtMost(cap),value))
            val background=shade(.035f,.65f,.45f)
            val surface=shade(.12f,.55f,.40f)
            val active=shade(.165f,.50f,.38f)
            val text=shade(.91f,.10f,.08f)
            val muted=br.com.centralcasa.books.CoverColors.border(shade(.65f,.15f,.12f),active,4.6)
            val accent=br.com.centralcasa.books.CoverColors.border(color ?: 0xffc6a15b.toInt(),active,4.6)
            return ReadingPalette(background,surface,active,text,muted,accent)
        }
    }
    private var localPalette:ReadingPalette?=null
    private val currentPalette get()=localPalette ?: readingPalette
    val background get()=if(rectilinear)currentPalette.background else Color.rgb(5,5,5)
    val surface get()=if(rectilinear)currentPalette.surface else Color.rgb(21,21,21)
    val activeSurface get()=if(rectilinear)currentPalette.active else Color.rgb(34,34,34)
    val text get()=if(rectilinear)currentPalette.text else Color.rgb(242,242,242)
    val muted get()=if(rectilinear)currentPalette.muted else Color.rgb(160,160,160)
    fun inheritReadingPalette(other:DisplayUi){localPalette=other.currentPalette}
    fun scopedReadingColor(color:Int?):Boolean {
        val next=palette(color)
        if(localPalette==next)return false
        localPalette=next;return true
    }
    val accent get()=if(rectilinear)currentPalette.accent else Color.rgb(230,230,230)
    val lineColor get()=if(rectilinear)mix(surface,accent,.25f) else 0xff393939.toInt()
    fun tint(color: Int, alpha: Int): Int = br.com.centralcasa.books.CoverColors.tint(color, alpha)
    fun gallerySurface(alpha: Int = 255): Int = tint(surface, alpha)
    fun galleryPill(alpha: Int = 255): Int = tint(activeSurface, alpha)
    fun galleryHairline(alpha: Int = 140): Int = tint(lineColor, alpha)
    fun readingColor(color:Int?):Boolean {
        if(!rectilinear)return false
        val next=palette(color)
        if(next==readingPalette)return false
        readingPalette=next;return true
    }
    private fun mix(a:Int,b:Int,amount:Float)=Color.rgb((Color.red(a)*(1-amount)+Color.red(b)*amount).toInt(),(Color.green(a)*(1-amount)+Color.green(b)*amount).toInt(),(Color.blue(a)*(1-amount)+Color.blue(b)*amount).toInt())
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun radius(value:Int)=dp(if(rectilinear)value.coerceIn(0,1) else value).toFloat()
    fun shape(color: Int, radius: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = this@DisplayUi.radius(radius) }
    fun outlined(color: Int = 0xD91B1B1B.toInt(), stroke: Int = 0xff393939.toInt(), radius: Int = 12): Drawable = if(rectilinear) GradientDrawable().apply {
        setColor(if(color==0xD91B1B1B.toInt())surface else color);cornerRadius=this@DisplayUi.radius(radius)
        setStroke(dp(1).coerceAtLeast(1),if(stroke==0xff393939.toInt())lineColor else stroke)
    } else GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(color,(color and 0x00ffffff) or 0xA6000000.toInt())).apply {
        cornerRadius=this@DisplayUi.radius(radius);setStroke(dp(1).coerceAtLeast(1),stroke)
    }
    fun styleSlider(slider: SeekBar, warm: Boolean = false, color:Int?=null) {
        slider.progressDrawable = SliderTrack(context, warm,color ?: 0xfff3f1eb.toInt())
        slider.thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color ?: 0xfff3f1eb.toInt()); setSize(dp(16), dp(16)) }
        slider.setPadding(dp(8), 0, dp(8), 0)
        slider.progressTintList = null; slider.progressBackgroundTintList = null
        slider.progressDrawable.level = (slider.progress.toLong() * 10000 / slider.max.coerceAtLeast(1)).toInt()
    }
    /** Thin rounded gallery slider matching the editorial mockup (6dp rounded track + subtle 12dp thumb). */
    fun styleGallerySlider(slider: SeekBar, color: Int, trackColor: Int = galleryHairline(130)) {
        val trackHeight = dp(6).coerceAtLeast(4)
        val radius = dp(4).toFloat()
        val bgTrack = GradientDrawable().apply { setColor(trackColor); cornerRadius = radius }
        val fgTrack = GradientDrawable().apply { setColor(color); cornerRadius = radius }
        val clip = android.graphics.drawable.ClipDrawable(fgTrack, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        val layers = object : android.graphics.drawable.LayerDrawable(arrayOf(bgTrack, clip)) {
            override fun onBoundsChange(bounds: Rect) {
                val cy = bounds.centerY()
                val top = cy - trackHeight / 2
                val bottom = top + trackHeight
                val inset = Rect(bounds.left, top, bounds.right, bottom)
                getDrawable(0).bounds = inset
                getDrawable(1).bounds = inset
            }
        }.apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        slider.progressDrawable = layers
        slider.thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(dp(12), dp(12)) }
        slider.thumbOffset = dp(6)
        slider.setPadding(dp(6), 0, dp(6), 0)
        slider.progressTintList = null; slider.progressBackgroundTintList = null
        slider.progressDrawable.level = (slider.progress.toLong() * 10000 / slider.max.coerceAtLeast(1)).toInt()
    }
    fun pillShape(color: Int, radiusDp: Int = 24, strokeColor: Int = Color.TRANSPARENT, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != Color.TRANSPARENT) {
            setStroke((strokeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        }
    }
    fun pillRipple(
        color: Int,
        radiusDp: Int = 24,
        strokeColor: Int = Color.TRANSPARENT,
        strokeDp: Float = 1f,
        darkRipple: Boolean = false,
    ): Drawable = RippleDrawable(
        ColorStateList.valueOf(if (darkRipple) 0x28000000 else 0x28FFFFFF),
        pillShape(color, radiusDp, strokeColor, strokeDp),
        pillShape(Color.WHITE, radiusDp),
    )
    fun ripple(color: Int, radius: Int = 14): Drawable = RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), shape(color, radius), shape(Color.WHITE, radius))
    // Raster icons follow the same path already used by the navigation bar on MIUI.
    fun symbol(name: String, color: Int = text, size: Int = 18): Drawable {
        val bitmap = Bitmap.createBitmap(dp(size).coerceAtLeast(1), dp(size).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        DisplayIcon(name, color).apply { setBounds(0, 0, bitmap.width, bitmap.height); draw(Canvas(bitmap)) }
        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, bitmap.width, bitmap.height) }
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = android.view.Gravity.CENTER_VERTICAL }
    fun label(parent: LinearLayout, value: String, size: Int = 16, color: Int = text, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = value; textSize = when { size >= 30 -> size * .8f; size >= 20 -> size * .85f; size >= 16 -> size * .9f; else -> size.toFloat() }; setTextColor(color); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    }
    fun panel(parent: LinearLayout, padding: Int = 18, color: Int = surface): LinearLayout = column().apply {
        val inset = (padding * .75f).toInt().coerceAtLeast(8); setPadding(dp(inset), dp(inset), dp(inset), dp(inset)); background = shape(color)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    fun action(parent: LinearLayout, value: String, primary: Boolean = false, onClick: () -> Unit): TextView = TextView(context).apply {
        text = value; textSize = 12f; includeFontPadding = true; setTextColor(if (primary) this@DisplayUi.background else if(rectilinear)accent else this@DisplayUi.text); gravity = android.view.Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); minHeight = dp(48)
        setPadding(dp(10), dp(8), dp(10), dp(8)); background = if(rectilinear && !primary) outlined(activeSurface) else ripple(if (primary) accent else activeSurface, 16)
        contentDescription = value
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(2) })
        isFocusable = true; setOnClickListener { onClick() }
    }
    fun icon(parent: LinearLayout, name: String, description: String, size: Int = 48, selected: Boolean = false, round: Boolean = false, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageDrawable(symbol(name, if(rectilinear)accent else Color.WHITE, 24)); imageTintList = null; imageAlpha = 255; alpha = 1f
        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description; isFocusable = true; background = ripple(if (selected) activeSurface else Color.TRANSPARENT, if (round) size / 2 else 16)
        parent.addView(this, LinearLayout.LayoutParams(dp(size), dp(size)).apply { gravity = android.view.Gravity.CENTER })
        setOnClickListener { onClick() }
        setOnLongClickListener { Toast.makeText(context, description, Toast.LENGTH_SHORT).show(); true }
    }
    fun progress(parent: LinearLayout, percent: Int) = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progress = percent.coerceIn(0, 100); progressTintList = ColorStateList.valueOf(accent)
        progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(51, 51, 51))
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(3); bottomMargin = dp(5) })
    }
    fun slider(parent: LinearLayout, value: Int, description: String, onChange: (Int) -> Unit): SeekBar = SeekBar(context).apply {
        max = 100; progress = value; contentDescription = description
        progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    fun art(parent: LinearLayout, book: Boolean, width: Int, height: Int, variant: Int = 0): View = ArtworkView(context, book, variant).apply {
        contentDescription = if (book) "Capa ilustrativa de livro" else "Capa ilustrativa de música"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        parent.addView(this, LinearLayout.LayoutParams(dp(width), dp(height)).apply { marginEnd = dp(16) })
    }
}

class DisplayIcon(private val name: String, private val color: Int) : Drawable() {
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        fun path(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]); canvas.drawPath(p, paint) }
        fun cloud() { val p = Path(); p.moveTo(5f, 18f); p.cubicTo(-1f, 18f, 0f, 10f, 6f, 10f); p.cubicTo(6f, 2f, 18f, 2f, 19f, 11f); p.cubicTo(25f, 11f, 25f, 18f, 19f, 18f); p.close(); canvas.drawPath(p, paint) }
        when (name) {
            "sun" -> { canvas.drawCircle(12f, 12f, 4f, paint); for (i in 0..7) { val a = i * Math.PI / 4; line((12+7*kotlin.math.cos(a)).toFloat(), (12+7*kotlin.math.sin(a)).toFloat(), (12+10*kotlin.math.cos(a)).toFloat(), (12+10*kotlin.math.sin(a)).toFloat()) } }
            "cloud", "partlycloudy", "rain", "storm", "snow" -> { if (name == "partlycloudy") { canvas.drawCircle(18f, 5f, 3f, paint); line(18f, 0f, 18f, 1f); line(22f, 4f, 24f, 4f) }; cloud(); if (name == "rain") { line(6f, 20f, 5f, 23f); line(12f, 20f, 11f, 23f); line(18f, 20f, 17f, 23f) }; if (name == "storm") path(13f, 17f, 10f, 21f, 14f, 21f, 12f, 24f); if (name == "snow") { line(12f, 19f, 12f, 24f); line(9f, 20f, 15f, 23f); line(9f, 23f, 15f, 20f) } }
            "humidity" -> { val drop=Path().apply { moveTo(12f,2f); cubicTo(9f,7f,4f,12f,4f,16f); cubicTo(4f,25f,20f,25f,20f,16f); cubicTo(20f,12f,15f,7f,12f,2f); close() }; canvas.drawPath(drop,paint) }
            "pressure" -> { canvas.drawCircle(12f,12f,9f,paint); line(12f,12f,17f,6f); line(4f,12f,6f,12f); line(12f,3f,12f,5f); line(18f,12f,20f,12f) }
            "wind" -> { line(2f, 7f, 18f, 7f); line(4f, 12f, 22f, 12f); line(2f, 17f, 17f, 17f) }

            "check" -> { canvas.drawCircle(12f, 12f, 9f, paint); path(7f, 12f, 10f, 15f, 17f, 8f) }
            "folder" -> { path(2f, 6f, 9f, 6f, 11f, 9f, 22f, 9f, 22f, 21f, 2f, 21f, 2f, 6f) }
            "pages" -> { path(6f, 2f, 15f, 2f, 20f, 7f, 20f, 22f, 6f, 22f, 6f, 2f); path(15f, 2f, 15f, 7f, 20f, 7f); line(9f, 12f, 17f, 12f); line(9f, 16f, 17f, 16f) }
            "pie" -> { canvas.drawCircle(12f, 12f, 9f, paint); path(12f, 3f, 12f, 12f, 18f, 18f) }
            "people" -> { canvas.drawCircle(12f, 7f, 3f, paint); canvas.drawArc(6f, 12f, 18f, 24f, 180f, 180f, false, paint); canvas.drawCircle(4f, 9f, 2f, paint); canvas.drawCircle(20f, 9f, 2f, paint); path(1f, 20f, 1f, 16f, 4f, 14f); path(23f, 20f, 23f, 16f, 20f, 14f) }
            "speaker" -> { path(4f, 9f, 8f, 9f, 13f, 5f, 13f, 19f, 8f, 15f, 4f, 15f, 4f, 9f); canvas.drawArc(12f, 5f, 23f, 19f, -55f, 110f, false, paint) }
            "device" -> { canvas.drawRoundRect(6f, 2f, 18f, 22f, 2f, 2f, paint); canvas.drawCircle(12f, 15f, 3f, paint); canvas.drawCircle(12f, 6f, .6f, paint) }
            "clock" -> { canvas.drawCircle(12f, 12f, 9f, paint); path(12f, 6f, 12f, 12f, 17f, 15f) }
            "back" -> { path(12f, 4f, 4f, 12f, 12f, 20f); line(4f, 12f, 21f, 12f) }
            "plus" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "edit" -> { path(4f, 16f, 16f, 4f, 20f, 8f, 8f, 20f, 4f, 20f, 4f, 16f); line(14f, 6f, 18f, 10f) }
            "more" -> { paint.style = Paint.Style.FILL; for (y in listOf(5f, 12f, 19f)) canvas.drawCircle(12f, y, 1.5f, paint) }
            "down" -> path(5f, 9f, 12f, 16f, 19f, 9f)
            "refresh" -> {
                paint.style=Paint.Style.FILL
                canvas.drawPath(Path().apply {
                    moveTo(17.65f,6.35f);cubicTo(16.2f,4.9f,14.21f,4f,12f,4f)
                    cubicTo(7.58f,4f,4f,7.58f,4f,12f);cubicTo(4f,16.42f,7.58f,20f,12f,20f)
                    cubicTo(15.73f,20f,18.84f,17.45f,19.73f,14f);lineTo(17.65f,14f)
                    cubicTo(16.83f,16.33f,14.61f,18f,12f,18f);cubicTo(8.69f,18f,6f,15.31f,6f,12f)
                    cubicTo(6f,8.69f,8.69f,6f,12f,6f);cubicTo(13.66f,6f,15.14f,6.69f,16.22f,7.78f)
                    lineTo(13f,11f);lineTo(20f,11f);lineTo(20f,4f);close()
                },paint)
            }
            "shelf-covers" -> {canvas.drawRoundRect(3f,3f,10f,21f,1f,1f,paint);canvas.drawRoundRect(14f,3f,21f,21f,1f,1f,paint);line(5f,16f,8f,16f);line(16f,16f,19f,16f)}
            "calendar" -> {canvas.drawRoundRect(3f,5f,21f,22f,2f,2f,paint);line(3f,10f,21f,10f);line(8f,2f,8f,7f);line(16f,2f,16f,7f);line(7f,14f,10f,14f);line(14f,14f,17f,14f);line(7f,18f,10f,18f)}
            "barcode" -> { listOf(3f,6f,8f,12f,15f,17f,21f).forEachIndexed {i,x->paint.strokeWidth=if(i%3==0)2f else 1f;line(x,5f,x,19f)} }
            "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(15f, 15f, 21f, 21f) }
            "camera" -> { canvas.drawRoundRect(2f, 6f, 22f, 20f, 2f, 2f, paint); canvas.drawCircle(12f, 13f, 4f, paint); path(7f, 6f, 9f, 3f, 15f, 3f, 17f, 6f) }
            "film" -> { canvas.drawRoundRect(3f, 5f, 21f, 21f, 2f, 2f, paint); line(3f, 10f, 21f, 10f); line(7f, 5f, 11f, 10f); line(14f, 5f, 18f, 10f) }
            "moon" -> { val p = Path(); p.moveTo(17f, 3f); p.cubicTo(4f, 1f, 0f, 20f, 13f, 22f); p.cubicTo(17f, 23f, 22f, 20f, 22f, 16f); p.cubicTo(11f, 20f, 8f, 8f, 17f, 3f); canvas.drawPath(p, paint) }
            "chevron" -> path(9f, 5f, 16f, 12f, 9f, 19f)
            "arrow-up-right" -> { path(6f, 18f, 18f, 6f); path(7f, 6f, 18f, 6f, 18f, 17f) }
            "send" -> { path(3f, 3f, 22f, 12f, 3f, 21f, 6f, 12f, 3f, 3f); line(6f, 12f, 16f, 12f) }
            "add" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "reading-history" -> { line(6f, 13f, 6f, 21f); line(12f, 4f, 12f, 21f); line(18f, 9f, 18f, 21f) }
            "stats" -> { path(4f, 20f, 4f, 12f, 8f, 12f, 8f, 20f); path(10f, 20f, 10f, 4f, 14f, 4f, 14f, 20f); path(16f, 20f, 16f, 8f, 20f, 8f, 20f, 20f); line(2f, 20f, 22f, 20f) }
            "backup" -> { path(4f, 15f, 4f, 21f, 20f, 21f, 20f, 15f); line(12f, 3f, 12f, 16f); path(7f, 11f, 12f, 16f, 17f, 11f) }
            "filter" -> { line(3f, 6f, 21f, 6f); line(6f, 12f, 18f, 12f); line(9f, 18f, 15f, 18f) }
            "home" -> { path(3f, 11f, 12f, 3f, 21f, 11f); path(6f, 10f, 6f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 18f, 21f, 18f, 10f) }
            "plug" -> { canvas.drawRoundRect(RectF(4f, 4f, 20f, 20f), 4f, 4f, paint); line(9f, 9f, 9f, 12f); line(15f, 9f, 15f, 12f); canvas.drawCircle(12f, 16f, .7f, paint) }
            "bolt" -> path(14f, 2f, 5f, 14f, 11f, 14f, 9f, 22f, 19f, 10f, 13f, 10f, 14f, 2f)
            "power" -> { canvas.drawArc(RectF(5f, 5f, 19f, 19f), -55f, 290f, false, paint); line(12f, 2f, 12f, 11f) }
            "light" -> { canvas.drawCircle(12f, 9f, 5f, paint); path(9f, 13f, 9f, 17f, 15f, 17f, 15f, 13f); line(10f, 21f, 14f, 21f) }
            "music" -> { path(10f, 17f, 10f, 5f, 20f, 3f, 20f, 15f); canvas.drawOval(4f, 16f, 10f, 21f, paint); canvas.drawOval(14f, 14f, 20f, 19f, paint) }
            "stack" -> { path(3f, 7f, 12f, 3f, 21f, 7f, 12f, 11f, 3f, 7f); path(3f, 12f, 12f, 16f, 21f, 12f); path(3f, 17f, 12f, 21f, 21f, 17f) }
            "book-spines" -> {
                canvas.drawRoundRect(3f,4f,7f,21f,1f,1f,paint)
                canvas.drawRoundRect(9f,4f,13f,21f,1f,1f,paint)
                path(15f,5f,18f,4f,22f,20f,18f,21f,15f,5f)
            }
            "classification-tag" -> {
                path(3f,3f,12f,3f,21f,12f,12f,21f,3f,12f,3f,3f)
                canvas.drawCircle(7f,7f,1.2f,paint)
            }
            "shopping-bag" -> {canvas.drawRect(4f,8f,20f,22f,paint);canvas.drawArc(7f,2f,17f,14f,180f,180f,false,paint)}
            "lock" -> {canvas.drawRect(5f,10f,19f,22f,paint);canvas.drawArc(8f,2f,16f,18f,180f,180f,false,paint);line(12f,15f,12f,18f)}
            "books" -> { path(12f, 6f, 4f, 4f, 4f, 19f, 12f, 21f, 20f, 19f, 20f, 4f, 12f, 6f, 12f, 21f) }
            "book_nav" -> {
                val pages = Path().apply {
                    moveTo(12f, 6f)
                    cubicTo(9f, 3.5f, 5f, 3f, 2f, 4f)
                    lineTo(2f, 18.5f)
                    cubicTo(5f, 17.5f, 9f, 18f, 12f, 20.5f)
                    cubicTo(15f, 18f, 19f, 17.5f, 22f, 18.5f)
                    lineTo(22f, 4f)
                    cubicTo(19f, 3f, 15f, 3.5f, 12f, 6f)
                    close()
                }
                canvas.drawPath(pages, paint)
                line(12f, 6f, 12f, 20.5f)
                line(5.5f, 8.5f, 8.5f, 9f); line(15.5f, 9f, 18.5f, 8.5f)
                line(5.5f, 12.5f, 8.5f, 13f); line(15.5f, 13f, 18.5f, 12.5f)
            }
            "ai" -> path(12f, 2f, 15f, 9f, 22f, 12f, 15f, 15f, 12f, 22f, 9f, 15f, 2f, 12f, 9f, 9f, 12f, 2f)
            "apps" -> { for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) canvas.drawRoundRect(x, y, x + 6, y + 6, 1f, 1f, paint) }
            "server" -> { for (y in listOf(3f, 10f, 17f)) { canvas.drawRoundRect(3f, y, 21f, y + 5f, 1f, 1f, paint); line(6f, y + 2.5f, 7f, y + 2.5f); line(17f, y + 2.5f, 18f, y + 2.5f) } }
            "phone" -> { canvas.drawRoundRect(6f, 2f, 18f, 22f, 2f, 2f, paint); line(10f, 5f, 14f, 5f); line(11f, 19f, 13f, 19f) }
            "cpu" -> { canvas.drawRect(6f, 6f, 18f, 18f, paint); canvas.drawRect(9f, 9f, 15f, 15f, paint); for (p in listOf(8f, 12f, 16f)) { line(p, 2f, p, 6f); line(p, 18f, p, 22f); line(2f, p, 6f, p); line(18f, p, 22f, p) } }
            "ram" -> { canvas.drawRect(2f, 5f, 22f, 18f, paint); for (x in listOf(5f, 10f, 15f)) canvas.drawRect(x, 8f, x + 3f, 14f, paint); for (x in listOf(4f, 8f, 12f, 16f, 20f)) line(x, 18f, x, 21f) }
            "wifi" -> { canvas.drawArc(2f, 4f, 22f, 24f, 225f, 90f, false, paint); canvas.drawArc(6f, 8f, 18f, 20f, 225f, 90f, false, paint); canvas.drawCircle(12f, 17f, 1f, paint) }
            "network" -> { path(4f, 4f, 20f, 4f, 20f, 17f, 16f, 17f, 16f, 21f, 8f, 21f, 8f, 17f, 4f, 17f, 4f, 4f); for (x in listOf(7f, 10f, 13f, 16f)) line(x, 6f, x, 10f) }
            "disk" -> { canvas.drawRoundRect(5f, 2f, 19f, 22f, 2f, 2f, paint); canvas.drawCircle(12f, 10f, 4f, paint); canvas.drawCircle(12f, 10f, 1f, paint); line(7f, 18f, 17f, 18f); line(15f, 12f, 18f, 15f) }
            "shield" -> { path(12f, 2f, 21f, 6f, 19f, 17f, 12f, 22f, 5f, 17f, 3f, 6f, 12f, 2f); path(8f, 12f, 11f, 15f, 16f, 9f) }
            "settings" -> { canvas.drawCircle(12f, 12f, 7f, paint); canvas.drawCircle(12f, 12f, 2.5f, paint); for (i in 0..7) { val a = i * Math.PI / 4; line((12 + 7 * kotlin.math.cos(a)).toFloat(), (12 + 7 * kotlin.math.sin(a)).toFloat(), (12 + 10 * kotlin.math.cos(a)).toFloat(), (12 + 10 * kotlin.math.sin(a)).toFloat()) } }
            "mic" -> { canvas.drawRoundRect(9f, 3f, 15f, 14f, 3f, 3f, paint); canvas.drawArc(6f, 6f, 18f, 18f, 0f, 180f, false, paint); line(12f, 18f, 12f, 22f); line(9f, 22f, 15f, 22f) }
            "play" -> { paint.style = Paint.Style.FILL; path(8f, 5f, 19f, 12f, 8f, 19f, 8f, 5f) }
            "pause" -> { paint.strokeWidth = 3.2f; line(9f, 5f, 9f, 19f); line(15f, 5f, 15f, 19f) }
            "previous", "next" -> { val flip = name == "previous"; canvas.save(); if (flip) { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }; path(5f, 5f, 16f, 12f, 5f, 19f, 5f, 5f); line(19f, 5f, 19f, 19f); canvas.restore() }
            "voice" -> { for (i in 0..4) { val x = 4f + i * 4f; val height = listOf(4f, 9f, 16f, 9f, 4f)[i]; line(x, 12f - height / 2, x, 12f + height / 2) } }
            else -> canvas.drawCircle(12f, 12f, 8f, paint)
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class ArtworkView @JvmOverloads constructor(context: Context, private val book: Boolean = false, private val variant: Int = 0) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (!book) {
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(35, 35, 35); canvas.drawCircle(w/2, h/2, w*.49f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.rgb(100, 100, 100)
            canvas.drawCircle(w/2,h/2,w*.43f,paint); canvas.drawCircle(w/2,h/2,w*.065f,paint)
            paint.style = Paint.Style.FILL; paint.textSize = w*.04f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("MÚSICA",w/2,h*.88f,paint); return
        }
        paint.style = Paint.Style.FILL; paint.color = if (book) Color.rgb(40 + variant * 5, 40 + variant * 5, 40 + variant * 5) else Color.rgb(40, 40, 40)
        canvas.drawRoundRect(0f, 0f, w, h, 8f, 8f, paint)
        paint.color = Color.rgb(230, 230, 230)
        if (book) {
            paint.alpha = 45; canvas.drawRect(w * .64f, 0f, w * .9f, h, paint); paint.alpha = 255
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; canvas.drawCircle(w * .48f, h * .62f, w * .29f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = w * .12f; paint.typeface = Typeface.create("serif", Typeface.NORMAL)
            canvas.drawText("ENTRE", w * .12f, h * .22f, paint); canvas.drawText("PÁGINAS", w * .12f, h * .33f, paint)
            paint.alpha = 80; canvas.drawRect(w * .08f, 0f, w * .10f, h, paint); paint.alpha = 255
        } else {
            paint.alpha = 35; canvas.drawCircle(w * .5f, h * .5f, w * .42f, paint); paint.alpha = 255
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; canvas.drawCircle(w * .5f, h * .5f, w * .28f, paint)
            canvas.drawCircle(w * .5f, h * .5f, w * .08f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = w * .085f; canvas.drawText("MÚSICA", w * .12f, h * .88f, paint)
        }
    }
}

/** SeekBar retains native touch/accessibility while drawing the approved round track. */
private class SliderTrack(context: Context, private val warm: Boolean,private val color:Int) : Drawable() {
    private val thickness = 4f * context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val track = RectF(bounds.left.toFloat(), bounds.exactCenterY() - thickness / 2, bounds.right.toFloat(), bounds.exactCenterY() + thickness / 2)
        paint.color = 0xff343436.toInt()
        paint.shader = if (warm) LinearGradient(track.left, 0f, track.right, 0f, 0xffffbd61.toInt(), 0xffb5dcff.toInt(), Shader.TileMode.CLAMP) else null
        canvas.drawRoundRect(track, thickness / 2, thickness / 2, paint); paint.shader = null
        if (!warm && level > 0) {
            paint.color = color; track.right = track.left + track.width() * level / 10000f
            canvas.drawRoundRect(track, thickness / 2, thickness / 2, paint)
        }
    }
    override fun onLevelChange(level: Int): Boolean { invalidateSelf(); return true }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable contract") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
