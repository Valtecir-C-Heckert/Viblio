package br.com.centralcasa.ui

import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import br.com.centralcasa.books.ShelfWoodStyle
import br.com.centralcasa.books.SpineMaterial

/**
 * Local photographed bookbinding textures (real leather & woven cloth),
 * continuous wooden bookcase furniture pieces (oak & collector LED), and lazy-cached WebP spine stamps.
 */
class ShelfMaterials private constructor(
    private val appContext: Context,
    val leather: Bitmap?,
    val cloth: Bitmap?,
    val oakPlank: Bitmap?,
    val oakSide: Bitmap?,
    val collectorPlank: Bitmap?,
    val collectorSide: Bitmap?,
    val collectorLedCeiling: Bitmap?,
    val legacyWood: Bitmap?
) {
    private val stamps = object : LinkedHashMap<String, Bitmap?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > 32
    }

    fun materialTexture(kind: SpineMaterial): Bitmap? = when (kind) {
        SpineMaterial.LEATHER -> leather ?: cloth
        SpineMaterial.CLOTH -> cloth ?: leather
    }

    fun plankFor(style: ShelfWoodStyle): Bitmap? = when (style) {
        ShelfWoodStyle.EDITORIAL -> oakPlank ?: legacyWood
        ShelfWoodStyle.COLLECTOR -> collectorPlank ?: legacyWood
    }

    fun sideFor(style: ShelfWoodStyle): Bitmap? = when (style) {
        ShelfWoodStyle.EDITORIAL -> oakSide ?: oakPlank
        ShelfWoodStyle.COLLECTOR -> collectorSide ?: collectorPlank
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
            val realLeather = decodeAsset(appCtx, "spines/real_leather_relief.webp")
            val realCloth = decodeAsset(appCtx, "spines/real_cloth_relief.webp")
            val oakPlank = decodeAsset(appCtx, "spines/oak_plank.webp")
            val oakSide = decodeAsset(appCtx, "spines/oak_side.webp")
            val collectorPlank = decodeAsset(appCtx, "spines/collector_plank.webp")
            val collectorSide = decodeAsset(appCtx, "spines/collector_side.webp")
            val collectorLedCeiling = decodeAsset(appCtx, "spines/collector_led_ceiling.webp")
            var legacyWood: Bitmap? = null
            if (oakPlank == null || collectorPlank == null) {
                val source = decodeAsset(appCtx, "shelf-materials.png")
                if (source != null) {
                    try {
                        if (source.width == 1024 && source.height == 1536) {
                            legacyWood = Bitmap.createBitmap(source, 26, 1330, 973, 48)
                        }
                    } finally {
                        source.recycle()
                    }
                }
            }
            return ShelfMaterials(
                appCtx, realLeather, realCloth, oakPlank, oakSide,
                collectorPlank, collectorSide, collectorLedCeiling, legacyWood
            ).also { cached = it }
        }
    }
}

/** Shared horizontal wooden shelf plank connecting left and right bookcase walls with zero gaps. */
class ShelfWoodView(
    context: Context,
    private val materials: ShelfMaterials?,
    private val woodStyle: ShelfWoodStyle = ShelfWoodStyle.COLLECTOR
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        val wood = materials?.plankFor(woodStyle)
        if (wood != null) {
            canvas.drawBitmap(wood, null, rect, paint)
        } else {
            paint.color = if (woodStyle == ShelfWoodStyle.EDITORIAL) 0xFFA67B4E.toInt() else 0xFF3A2416.toInt()
            canvas.drawRect(rect, paint)
        }
        // Subtle top bevel edge highlight so books look grounded on the shelf
        linePaint.color = if (woodStyle == ShelfWoodStyle.EDITORIAL) 0x55FFF0D8 else 0x38FFE0B8
        linePaint.strokeWidth = density
        canvas.drawLine(0f, density * 0.5f, w, density * 0.5f, linePaint)
        linePaint.color = 0x55000000
        canvas.drawLine(0f, h - density * 0.5f, w, h - density * 0.5f, linePaint)
    }
}

/** Recessed cabinet interior background for one shelf row (real LED ceiling on Collector; deep oak recess on Editorial). */
private class ShelfNicheBackView(
    context: Context,
    private val materials: ShelfMaterials?,
    private val woodStyle: ShelfWoodStyle,
    private val ledHeightPx: Int
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Deep wood cabinet back wall
        paint.shader = null
        paint.color = if (woodStyle == ShelfWoodStyle.COLLECTOR) 0xFF100B08.toInt() else 0xFF161310.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        if (woodStyle == ShelfWoodStyle.COLLECTOR) {
            val led = materials?.collectorLedCeiling
            if (led != null) {
                rect.set(0f, 0f, w, minOf(h, ledHeightPx.toFloat()))
                canvas.drawBitmap(led, null, rect, paint)
            }
        } else {
            // Soft natural top interior depth shadow under the oak shelf above
            val shH = minOf(h, 22f * density)
            paint.shader = LinearGradient(0f, 0f, 0f, shH, intArrayOf(0x78000000, 0x00000000), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, shH, paint)
            paint.shader = null
        }

        // Subtle inner shadows cast by left and right wooden bookcase walls
        val sideSh = minOf(12f * density, w * 0.08f)
        paint.shader = LinearGradient(0f, 0f, sideSh, 0f, intArrayOf(0x70000000, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, sideSh, h, paint)
        paint.shader = LinearGradient(w - sideSh, 0f, w, 0f, intArrayOf(0x00000000, 0x70000000), null, Shader.TileMode.CLAMP)
        canvas.drawRect(w - sideSh, 0f, w, h, paint)
        paint.shader = null
    }
}

/**
 * Single continuous wooden bookcase enclosing ALL rows seamlessly:
 * - Continuous left & right wooden side walls top-to-bottom (zero seams between levels)
 * - Top wooden cornice plank
 * - Shared horizontal shelf plank directly dividing each row (zero floating gaps!)
 * - Real recessed warm LEDs under every shelf ceiling on Colecionador Iluminada
 */
class ShelfCabinetLayout(
    context: Context,
    private val materials: ShelfMaterials?,
    private val woodStyle: ShelfWoodStyle,
    val sideWidthPx: Int,
    val plankHeightPx: Int,
    val ceilingHeightPx: Int
) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val flipMatrix = Matrix()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(sideWidthPx, plankHeightPx, sideWidthPx, 0)
    }

    /** Adds one shelf niche (real interior back + flush wall-to-wall book row) plus the wooden plank below it. */
    fun addNiche(bookRowHeightPx: Int): LinearLayout {
        val nicheHeight = ceilingHeightPx + bookRowHeightPx
        val niche = FrameLayout(context)
        val ledDrawH = ceilingHeightPx + (44f * resources.displayMetrics.density).toInt()
        niche.addView(
            ShelfNicheBackView(context, materials, woodStyle, ledDrawH).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, nicheHeight)
        )
        val bookRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            tag = "spine-row"
        }
        niche.addView(
            bookRow,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, bookRowHeightPx).apply {
                topMargin = ceilingHeightPx
            }
        )
        addView(niche, LayoutParams(LayoutParams.MATCH_PARENT, nicheHeight))
        addView(
            ShelfWoodView(context, materials, woodStyle).apply {
                tag = "shelf-wood"
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(LayoutParams.MATCH_PARENT, plankHeightPx)
        )
        return bookRow
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sw = sideWidthPx.toFloat()
        val ph = plankHeightPx.toFloat()

        // Draw continuous left & right wooden side walls OVER full height (zero joints between rows!)
        val sideBmp = materials?.sideFor(woodStyle)
        if (sideBmp != null) {
            rect.set(0f, 0f, sw, h)
            canvas.drawBitmap(sideBmp, null, rect, paint)
            canvas.save()
            flipMatrix.reset()
            flipMatrix.setScale(-sw / sideBmp.width.toFloat(), h / sideBmp.height.toFloat())
            flipMatrix.postTranslate(w, 0f)
            canvas.drawBitmap(sideBmp, flipMatrix, paint)
            canvas.restore()
        } else {
            paint.color = if (woodStyle == ShelfWoodStyle.EDITORIAL) 0xFFA67B4E.toInt() else 0xFF3A2416.toInt()
            canvas.drawRect(0f, 0f, sw, h, paint)
            canvas.drawRect(w - sw, 0f, w, h, paint)
        }

        // Draw continuous top wooden cornice spanning full bookcase width
        val topBmp = materials?.plankFor(woodStyle)
        if (topBmp != null) {
            rect.set(0f, 0f, w, ph)
            canvas.drawBitmap(topBmp, null, rect, paint)
        } else {
            paint.color = if (woodStyle == ShelfWoodStyle.EDITORIAL) 0xFFA67B4E.toInt() else 0xFF3A2416.toInt()
            canvas.drawRect(0f, 0f, w, ph, paint)
        }
    }
}
