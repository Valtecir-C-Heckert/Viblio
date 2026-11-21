package br.com.centralcasa.ui

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.ImageView
import br.com.centralcasa.books.*

/**
 * Four-layer procedural + hot-stamped book spine view:
 * 1) Book's real dominant cover color + tactile material grain (leather/linen/velvet/concrete)
 * 2) Either deterministic A/B/C genre hot-stamp WebP (3-slice aspect-preserving) or shelf-matched standard binding
 * 3) 3D cylindrical spine curvature, edge bevels, top gallery light, and crimson bookmark ribbon for current reading
 * 4) High-contrast editorial title & author typography with subtle deboss shadow
 */
class ShelfSpineView(
    context: Context,
    private val title: String,
    private val subtitle: String = "",
    private val author: String = "",
    private val materials: ShelfMaterials? = null,
    private val bookId: String = "",
    private val genre: String = "",
    private val kind: String = "book",
    private val woodStyle: ShelfWoodStyle = ShelfWoodStyle.COLLECTOR,
    private val spineDesign: SpineDesignMode = SpineDesignMode.BY_GENRE,
    private val isCurrentReading: Boolean = false
) : ImageView(context) {
    var spineColor: Int = 0xff404944.toInt()
        private set
    private val genreTheme: SpineGenreTheme =
        if (spineDesign == SpineDesignMode.BY_GENRE) SpineThemes.resolveGenre(genre, kind) else SpineGenreTheme.DEFAULT
    private val stampAsset: String? =
        if (spineDesign == SpineDesignMode.BY_GENRE) SpineThemes.assetName(genreTheme, bookId.ifBlank { title }) else null
    private val useThematicStamp: Boolean = stampAsset != null

    private var faceColor = ShelfSpines.materialFace(spineColor)
    private var inkColor = ShelfSpines.materialInk(faceColor)
    private var foilColor = SpineThemes.foilColor(faceColor, genreTheme)

    private val useSans: Boolean =
        if (useThematicStamp) genreTheme.sansSerif else (woodStyle == ShelfWoodStyle.EDITORIAL)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stampShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0x70000000, PorterDuff.Mode.SRC_IN)
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(if (useSans) Typeface.SANS_SERIF else Typeface.SERIF, Typeface.BOLD)
    }
    private val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(
            if (useSans) Typeface.SANS_SERIF else Typeface.SERIF,
            if (woodStyle == ShelfWoodStyle.EDITORIAL && !useThematicStamp) Typeface.BOLD else Typeface.NORMAL
        )
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(if (useSans) Typeface.SANS_SERIF else Typeface.SERIF, Typeface.NORMAL)
    }
    private val density = resources.displayMetrics.density
    private var titleLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null
    private var blockHeight = 0f
    private var authorLayout: StaticLayout? = null
    private var authorTop = 0f
    private var authorSpace = 0f
    private var dividerY = 0f
    private var bodyTop = 0f
    private var bodyBottom = 0f
    private val surfacePath = Path()
    private val ribbonPath = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val shadowRect = RectF()

    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val matKind = when {
            useThematicStamp -> genreTheme.material
            woodStyle == ShelfWoodStyle.EDITORIAL -> SpineMaterial.LINEN
            else -> SpineMaterial.VELVET
        }
        shader = materials?.materialTexture(matKind)?.let {
            BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(density / 2.6f, density / 2.6f) })
            }
        }
    }

    override fun setImageBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        extractColor(bitmap)
    }

    private fun extractColor(bitmap: Bitmap) {
        val w = minOf(32, bitmap.width); val h = minOf(32, bitmap.height)
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = bitmap.getPixel(x * bitmap.width / w, y * bitmap.height / h)
        spineColor = CoverColors.dominant(pixels) ?: 0xff404944.toInt()
        faceColor = ShelfSpines.materialFace(spineColor)
        inkColor = ShelfSpines.materialInk(faceColor)
        foilColor = SpineThemes.foilColor(faceColor, genreTheme)
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun fit(value: String, brush: TextPaint, length: Int, cross: Float, preferred: Float, wholeWords: Boolean = false): StaticLayout {
        fun layout(size: Float): StaticLayout {
            brush.textSize = size
            return StaticLayout(value, brush, length, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }
        fun fits(block: StaticLayout): Boolean {
            if (block.height > cross) return false
            return !wholeWords || (0 until block.lineCount).all { line ->
                val end = block.getLineEnd(line)
                end == 0 || end >= value.length || value[end - 1].isWhitespace() || value[end].isWhitespace()
            }
        }
        val initial = layout(preferred); if (fits(initial)) return initial
        var low = .01f; var high = preferred
        repeat(16) { val middle = (low + high) / 2; if (fits(layout(middle))) low = middle else high = middle }
        return layout(low)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = h / (220f * density)
        val inset = (if (useThematicStamp) 6f else 5.5f) * density * scale

        if (useThematicStamp) {
            // Thematic stamps reserve the clean central 46% band (y = 27%..73%) for crisp vertical title & subtitle
            authorLayout = null
            authorTop = 0f; authorSpace = 0f; dividerY = 0f
            bodyTop = h * 0.275f
            bodyBottom = h * 0.725f
        } else {
            val authorValue = ShelfSpines.authorLines(author)
            authorTop = (if (woodStyle == ShelfWoodStyle.COLLECTOR) 13f else 10f) * density * scale
            authorSpace = if (authorValue.isBlank()) 0f else 34f * density * scale
            val authorWidth = (w - 2 * inset).toInt().coerceAtLeast(1)
            val preferredAuthor = 12f * density * scale * resources.configuration.fontScale
            authorPaint.textSize = preferredAuthor
            val unbroken = listOf(authorValue.substringBefore('\n')) + authorValue.substringAfter('\n', "").split(Regex("\\s+"))
            val longest = unbroken.maxOfOrNull { authorPaint.measureText(it) } ?: 0f
            val authorSize = preferredAuthor * minOf(1f, authorWidth * .98f / longest.coerceAtLeast(1f))
            authorLayout = if (authorValue.isBlank()) null else fit(authorValue, authorPaint, authorWidth, authorSpace, authorSize, wholeWords = true)
            dividerY = if (authorLayout == null) 18f * density * scale else authorTop + authorSpace + 6f * density * scale
            bodyTop = dividerY + (if (woodStyle == ShelfWoodStyle.COLLECTOR) 14f else 10f) * density * scale
            bodyBottom = h - 24f * density * scale
        }

        val length = (bodyBottom - bodyTop).toInt().coerceAtLeast(1)
        val cross = (w - 2 * inset).coerceAtLeast(1f)
        val gap = if (subtitle.isBlank()) 0f else 3f * density * scale
        subtitleLayout = if (subtitle.isBlank()) null else fit(subtitle, subtitlePaint, length, cross * .34f, 8.5f * density * scale * resources.configuration.fontScale)
        val preferredTitle = (if (useThematicStamp) 17.5f else 19f) * density * scale * resources.configuration.fontScale
        text.textSize = preferredTitle
        val singleLine = preferredTitle * minOf(1f, length * .96f / text.measureText(title).coerceAtLeast(1f))
        val titleSize = if (singleLine >= 7.5f * density * scale) singleLine else preferredTitle
        titleLayout = fit(title, text, length, (cross - (subtitleLayout?.height ?: 0) - gap).coerceAtLeast(1f), titleSize, wholeWords = true)
        blockHeight = (titleLayout?.height ?: 0) + (subtitleLayout?.height ?: 0) + gap

        val r = minOf(3f * density, w * .1f)
        surfacePath.reset()
        surfacePath.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val face = faceColor
        val ink = inkColor
        val foil = foilColor
        val isDarkCover = (ink == -1)

        canvas.save()
        canvas.clipPath(surfacePath)

        // LAYER 1: Real cover color + tactile material texture
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = face
        canvas.drawRect(0f, 0f, w, h, paint)
        if (texturePaint.shader != null) canvas.drawRect(0f, 0f, w, h, texturePaint)

        // LAYER 2: Genre hot-stamped artwork (3-slice aspect-preserving) OR shelf-matched standard binding
        val stamp = stampAsset?.let { materials?.stamp(it) }
        if (useThematicStamp && stamp != null && !stamp.isRecycled) {
            drawThreeSliceStamp(canvas, stamp, w, h, foil)
        } else {
            drawStandardBinding(canvas, w, h, ink, foil, isDarkCover)
        }

        // LAYER 3: 3D cylindrical spine shading, hinge/headband accents, lighting & bookmark ribbon
        drawLightingAndFinish(canvas, w, h, foil)
        if (woodStyle == ShelfWoodStyle.COLLECTOR && isCurrentReading) {
            drawBookmarkRibbon(canvas, w, h)
        }

        // LAYER 4: Editorial typography with subtle deboss shadow for effortless contrast
        val textShadow = if (isDarkCover) 0x66000000 else 0x33FFFFFF
        text.color = ink; text.setShadowLayer(1.2f * density, 0f, 0.7f * density, textShadow)
        subtitlePaint.color = (ink and 0x00FFFFFF) or 0xCC000000.toInt()
        authorPaint.color = (ink and 0x00FFFFFF) or 0xE0000000.toInt()
        authorPaint.setShadowLayer(0.8f * density, 0f, 0.5f * density, textShadow)

        authorLayout?.let { block ->
            canvas.save()
            canvas.translate((w - block.width) / 2f, authorTop + (authorSpace - block.height) / 2f)
            block.draw(canvas)
            canvas.restore()
        }

        titleLayout?.let { block ->
            canvas.save()
            canvas.translate(w / 2f, (bodyTop + bodyBottom) / 2f)
            canvas.rotate(90f)
            canvas.translate(-block.width / 2f, -blockHeight / 2f)
            block.draw(canvas)
            subtitleLayout?.let {
                canvas.translate(0f, block.height + 3f * density * h / (220f * density))
                it.draw(canvas)
            }
            canvas.restore()
        }

        canvas.restore()
    }

    /**
     * Draws a 180x640 engraved hot-stamp WebP in 3 logical bands (Top 28%, Middle 44%, Bottom 28%).
     * Top & bottom bands preserve natural emblem aspect ratio (never distorted on thin or thick books),
     * while the middle band tracks spine edges seamlessly.
     */
    private fun drawThreeSliceStamp(canvas: Canvas, stamp: Bitmap, w: Float, h: Float, foil: Int) {
        val sw = stamp.width
        val sh = stamp.height
        val topCut = (sh * 0.275f).toInt()
        val botCut = (sh * 0.725f).toInt()
        val dstTopH = h * 0.275f
        val dstBotY = h * 0.725f
        val dstBotH = h - dstBotY
        val pad = minOf(2f * density, w * 0.06f)
        val availW = (w - 2f * pad).coerceAtLeast(1f)

        stampPaint.colorFilter = PorterDuffColorFilter(foil, PorterDuff.Mode.SRC_IN)
        stampPaint.alpha = 225

        fun drawBand(sy0: Int, sy1: Int, dy0: Float, dy1: Float, preserveAspect: Boolean) {
            srcRect.set(0, sy0, sw, sy1)
            val bh = (dy1 - dy0).coerceAtLeast(1f)
            if (preserveAspect) {
                val aspect = sw.toFloat() / (sy1 - sy0).coerceAtLeast(1).toFloat()
                // Allow gentle stretch up to 1.35x so thin spines still fill gracefully while emblems stay natural
                val naturalW = bh * aspect
                val targetW = availW
                val targetH = minOf(bh, (targetW / aspect) * 1.32f)
                val cx = w / 2f
                val cy = dy0 + bh / 2f
                val drawW = minOf(targetW, naturalW * 1.25f)
                dstRect.set(cx - drawW / 2f, cy - targetH / 2f, cx + drawW / 2f, cy + targetH / 2f)
            } else {
                dstRect.set(pad, dy0, w - pad, dy1)
            }
            shadowRect.set(dstRect.left + 0.7f * density, dstRect.top + 0.9f * density, dstRect.right + 0.7f * density, dstRect.bottom + 0.9f * density)
            canvas.drawBitmap(stamp, srcRect, shadowRect, stampShadowPaint)
            canvas.drawBitmap(stamp, srcRect, dstRect, stampPaint)
        }

        drawBand(0, topCut, 2f * density, dstTopH, preserveAspect = true)
        drawBand(topCut, botCut, dstTopH, dstBotY, preserveAspect = false)
        drawBand(botCut, sh, dstBotY, h - 2f * density, preserveAspect = true)
    }

    private fun drawStandardBinding(canvas: Canvas, w: Float, h: Float, ink: Int, foil: Int, isDarkCover: Boolean) {
        if (woodStyle == ShelfWoodStyle.EDITORIAL) {
            // Editorial Moderna: clean upper color-block band, vertical hardcover hinge creases, minimalist publisher mark
            val bandBottom = if (authorLayout != null) dividerY else h * 0.16f
            paint.style = Paint.Style.FILL
            paint.color = if (isDarkCover) 0x22FFFFFF else 0x1E000000
            canvas.drawRect(0f, 0f, w, bandBottom, paint)

            paint.color = (ink and 0x00FFFFFF) or 0x44000000
            paint.strokeWidth = 0.8f * density
            canvas.drawLine(0f, bandBottom, w, bandBottom, paint)

            // Hardcover vertical hinge grooves near left and right edges
            val hinge = minOf(4.2f * density, w * 0.16f)
            paint.color = 0x44000000; paint.strokeWidth = 0.9f * density
            canvas.drawLine(hinge, 0f, hinge, h, paint)
            canvas.drawLine(w - hinge, 0f, w - hinge, h, paint)
            paint.color = 0x1FFFFFFF; paint.strokeWidth = 0.7f * density
            canvas.drawLine(hinge + 0.9f * density, 0f, hinge + 0.9f * density, h, paint)

            // Minimalist publisher colophon square at bottom
            val markSize = minOf(8f * density, w * 0.28f)
            val cx = w / 2f; val cy = h - 12f * density
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.9f * density
            paint.color = (ink and 0x00FFFFFF) or 0x77000000
            canvas.drawRoundRect(RectF(cx - markSize / 2f, cy - markSize / 2f, cx + markSize / 2f, cy + markSize / 2f), 1.5f * density, 1.5f * density, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, markSize * 0.17f, paint)
        } else {
            // Colecionador Iluminada: woven headband, raised leather hubs, debossed inner gilded frame & diamond colophon
            paint.style = Paint.Style.FILL
            val hbH = 3.2f * density
            paint.color = (foil and 0x00FFFFFF) or 0xB8000000.toInt()
            canvas.drawRect(1.5f * density, 0f, w - 1.5f * density, hbH, paint)
            paint.color = 0x55000000
            var tx = 2f * density
            while (tx < w - 2f * density) {
                canvas.drawRect(tx, 0f, tx + 2f * density, hbH, paint)
                tx += 4.5f * density
            }

            // Raised horizontal binding hubs (top & bottom)
            fun drawHub(y: Float) {
                paint.style = Paint.Style.FILL
                paint.color = 0x55000000; canvas.drawRect(0f, y, w, y + 3.2f * density, paint)
                paint.color = 0x2AFFFFFF; canvas.drawRect(0f, y, w, y + 1.1f * density, paint)
                paint.color = (foil and 0x00FFFFFF) or 0xBB000000.toInt()
                paint.strokeWidth = 0.7f * density
                canvas.drawLine(3.5f * density, y - 1.5f * density, w - 3.5f * density, y - 1.5f * density, paint)
            }
            val topHubY = (if (authorLayout != null) dividerY else 22f * density)
            drawHub(topHubY)
            drawHub(h - 22f * density)

            // Debossed double-lined frame around title body
            val pad = minOf(3.8f * density, w * 0.14f)
            val frameTop = topHubY + 7f * density
            val frameBot = h - 27f * density
            if (frameBot > frameTop + 20f * density && w > 18f * density) {
                paint.style = Paint.Style.FILL; paint.color = 0x1A000000
                canvas.drawRoundRect(RectF(pad, frameTop, w - pad, frameBot), 2f * density, 2f * density, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.8f * density
                paint.color = (foil and 0x00FFFFFF) or 0xAA000000.toInt()
                canvas.drawRoundRect(RectF(pad, frameTop, w - pad, frameBot), 2f * density, 2f * density, paint)
                if (w > 26f * density) {
                    paint.strokeWidth = 0.45f * density
                    paint.color = (foil and 0x00FFFFFF) or 0x66000000
                    canvas.drawRoundRect(RectF(pad + 2f * density, frameTop + 2f * density, w - pad - 2f * density, frameBot - 2f * density), 1f * density, 1f * density, paint)
                }
            }

            // Gilded diamond colophon at bottom
            val cx = w / 2f; val cy = h - 10.5f * density; val d = minOf(3.5f * density, w * 0.14f)
            paint.style = Paint.Style.FILL
            paint.color = (foil and 0x00FFFFFF) or 0xCC000000.toInt()
            val diamond = Path().apply { moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close() }
            canvas.drawPath(diamond, paint)
        }
    }

    private fun drawLightingAndFinish(canvas: Canvas, w: Float, h: Float, foil: Int) {
        paint.style = Paint.Style.FILL
        val edge = minOf(4.2f * density, w * 0.17f) / w
        // Horizontal 3D cylindrical curvature (deep contact shadow on edges + soft specular highlight)
        paint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0x88000000.toInt(), 0x22FFFFFF, 0x08FFFFFF, 0x00000000, 0x1C000000, 0x7A000000),
            floatArrayOf(0f, edge * 0.9f, edge * 1.9f, 0.55f, 1f - edge, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // Vertical depth shading (warmer top gallery glow on Collector)
        val topGlow = if (woodStyle == ShelfWoodStyle.COLLECTOR) 0x38FFE7BD else 0x1EFFFFFF
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(topGlow, 0x00FFFFFF, 0x00000000, 0x52000000),
            floatArrayOf(0f, 0.12f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Subtle crisp rim highlight on left spine crease
        paint.color = 0x1CFFFFFF
        canvas.drawRect(density, 2f * density, 1.8f * density, h - 2f * density, paint)

        // Tiny gilded top hairline for Collector thematic books so headband integrates seamlessly
        if (woodStyle == ShelfWoodStyle.COLLECTOR && useThematicStamp) {
            paint.color = (foil and 0x00FFFFFF) or 0x77000000
            canvas.drawRect(1.5f * density, 0f, w - 1.5f * density, 2.2f * density, paint)
        }
    }

    private fun drawBookmarkRibbon(canvas: Canvas, w: Float, h: Float) {
        val rw = minOf(7f * density, w * 0.28f).coerceAtLeast(4f * density)
        val rh = minOf(30f * density, h * 0.16f)
        val rx1 = w - minOf(4f * density, w * 0.14f)
        val rx0 = rx1 - rw
        val midX = (rx0 + rx1) / 2f
        val notchY = rh - rw * 0.65f

        // Soft cast shadow behind satin ribbon
        ribbonPath.reset()
        ribbonPath.moveTo(rx0 + 1.2f * density, 0f)
        ribbonPath.lineTo(rx1 + 1.2f * density, 0f)
        ribbonPath.lineTo(rx1 + 1.2f * density, rh + 1.2f * density)
        ribbonPath.lineTo(midX + 1.2f * density, notchY + 1.2f * density)
        ribbonPath.lineTo(rx0 + 1.2f * density, rh + 1.2f * density)
        ribbonPath.close()
        paint.style = Paint.Style.FILL
        paint.color = 0x66000000
        canvas.drawPath(ribbonPath, paint)

        // Crimson satin ribbon with subtle sheen
        ribbonPath.reset()
        ribbonPath.moveTo(rx0, 0f)
        ribbonPath.lineTo(rx1, 0f)
        ribbonPath.lineTo(rx1, rh)
        ribbonPath.lineTo(midX, notchY)
        ribbonPath.lineTo(rx0, rh)
        ribbonPath.close()
        paint.shader = LinearGradient(
            rx0, 0f, rx1, 0f,
            intArrayOf(0xFF9C1B22.toInt(), 0xFFD9363E.toInt(), 0xFF8C141A.toInt()),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(ribbonPath, paint)
        paint.shader = null
    }
}
