package br.com.centralcasa.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import br.com.centralcasa.books.ShelfWoodStyle
import br.com.centralcasa.books.SpineMaterial

/** Local shelf woods, tactile material grains, and lazy-cached WebP spine hot-stamps. */
class ShelfMaterials private constructor(
    private val appContext: Context,
    val leather: Bitmap?,
    val linen: Bitmap?,
    val velvet: Bitmap?,
    val concrete: Bitmap?,
    val wood: Bitmap?,
    val woodOak: Bitmap?,
    val woodEspresso: Bitmap?
) {
    private val stamps = object : LinkedHashMap<String, Bitmap?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > 32
    }

    fun materialTexture(kind: SpineMaterial): Bitmap? = when (kind) {
        SpineMaterial.LEATHER -> leather ?: velvet
        SpineMaterial.LINEN -> linen ?: leather
        SpineMaterial.VELVET -> velvet ?: leather
        SpineMaterial.CONCRETE -> concrete ?: leather
    }

    fun woodFor(style: ShelfWoodStyle): Bitmap? = when (style) {
        ShelfWoodStyle.EDITORIAL -> woodOak ?: wood
        ShelfWoodStyle.COLLECTOR -> woodEspresso ?: wood
    }

    @Synchronized fun stamp(assetPath: String): Bitmap? {
        if (stamps.containsKey(assetPath)) return stamps[assetPath]
        val bmp = runCatching {
            appContext.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        stamps[assetPath] = bmp
        return bmp
    }

    companion object {
        @Volatile private var cached: ShelfMaterials? = null

        private fun decodeAsset(context: Context, path: String): Bitmap? =
            runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()

        @Synchronized fun load(context: Context): ShelfMaterials? {
            cached?.let { return it }
            val appCtx = context.applicationContext
            var leather: Bitmap? = null
            var legacyWood: Bitmap? = null
            val source = decodeAsset(appCtx, "shelf-materials.png")
            if (source != null) {
                try {
                    if (source.width == 1024 && source.height == 1536) {
                        val crop = Bitmap.createBitmap(source, 75, 680, 120, 160)
                        val pixels = IntArray(crop.width * crop.height)
                        crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
                        fun light(c: Int) = .2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c)
                        val mean = pixels.sumOf(::light) / pixels.size
                        for (i in pixels.indices) {
                            val delta = ((light(pixels[i]) - mean) / mean).coerceIn(-.08, .08)
                            pixels[i] = (kotlin.math.abs(delta) * 255).toInt().shl(24) or if (delta > 0) 0x00ffffff else 0
                        }
                        leather = Bitmap.createBitmap(pixels, crop.width, crop.height, Bitmap.Config.ARGB_8888)
                        crop.recycle()
                        legacyWood = Bitmap.createBitmap(source, 26, 1330, 973, 48)
                    }
                } finally {
                    source.recycle()
                }
            }
            val linen = decodeAsset(appCtx, "spines/mat_linen.webp")
            val velvet = decodeAsset(appCtx, "spines/mat_velvet.webp")
            val concrete = decodeAsset(appCtx, "spines/mat_concrete.webp")
            val woodOak = decodeAsset(appCtx, "spines/wood_oak.webp")
            val woodEspresso = decodeAsset(appCtx, "spines/wood_espresso.webp")
            return ShelfMaterials(appCtx, leather, linen, velvet, concrete, legacyWood, woodOak, woodEspresso).also { cached = it }
        }
    }
}

/** Wooden shelf plank with top specular bevel, contact shadow, and style-matched wood grain. */
class ShelfWoodView(
    context: Context,
    private val materials: ShelfMaterials?,
    private val woodStyle: ShelfWoodStyle = ShelfWoodStyle.COLLECTOR
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = 2.5f * density
        rect.set(0f, 0f, w, h)
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        val wood = materials?.woodFor(woodStyle)
        if (wood != null) {
            canvas.drawBitmap(wood, null, rect, paint)
        } else {
            paint.color = if (woodStyle == ShelfWoodStyle.EDITORIAL) 0xFFA5784B.toInt() else 0xFF3E2617.toInt()
            canvas.drawRect(rect, paint)
        }
        // Subtle 3D bevel: crisp top edge light + darker front lip bottom
        overlay.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                if (woodStyle == ShelfWoodStyle.EDITORIAL) 0x38FFF1DC else 0x28FFE4BF,
                0x00000000,
                0x48000000
            ),
            floatArrayOf(0f, 0.28f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, overlay)
        overlay.shader = null
        canvas.restore()
    }
}

/** Recessed cabinet backdrop: warm 3-spot gallery lighting for Collector, crisp matte slate for Editorial. */
class ShelfRowBackdropDrawable(
    private val woodStyle: ShelfWoodStyle,
    private val density: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = 4f * density
        val rf = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

        if (woodStyle == ShelfWoodStyle.COLLECTOR) {
            paint.shader = LinearGradient(
                0f, rf.top, 0f, rf.bottom,
                intArrayOf(0xFF140F0C.toInt(), 0xFF1D1611.toInt(), 0xFF0E0A08.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rf, r, r, paint)
            paint.shader = null

            // Three soft warm museum-style LED recessed spotlights along the shelf ceiling
            val spotRadius = maxOf(w * 0.24f, h * 0.55f)
            for (frac in floatArrayOf(0.20f, 0.50f, 0.80f)) {
                val sx = rf.left + w * frac
                val sy = rf.top + 2f * density
                spotPaint.shader = RadialGradient(
                    sx, sy, spotRadius,
                    intArrayOf(0x36FFE3B0, 0x16FFD088, 0x00000000),
                    floatArrayOf(0f, 0.42f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rf, spotPaint)
                // Tiny recessed LED fixture dot on the top rim
                spotPaint.shader = null
                spotPaint.color = 0xAAFFEAC2.toInt()
                canvas.drawOval(RectF(sx - 6f * density, rf.top + 1f * density, sx + 6f * density, rf.top + 4f * density), spotPaint)
            }
        } else {
            paint.shader = LinearGradient(
                0f, rf.top, 0f, rf.bottom,
                intArrayOf(0xFF17191C.toInt(), 0xFF1D2024.toInt(), 0xFF121417.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rf, r, r, paint)
            paint.shader = null
        }

        // Recessed top/bottom inner shelf shadow
        paint.shader = LinearGradient(
            0f, rf.top, 0f, rf.top + 16f * density,
            intArrayOf(0x55000000, 0x00000000),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rf.left, rf.top, rf.right, rf.top + 16f * density, paint)
        paint.shader = LinearGradient(
            0f, rf.bottom - 14f * density, 0f, rf.bottom,
            intArrayOf(0x00000000, 0x66000000),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rf.left, rf.bottom - 14f * density, rf.right, rf.bottom, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; spotPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
