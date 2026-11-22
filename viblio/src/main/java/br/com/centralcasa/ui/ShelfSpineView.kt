package br.com.centralcasa.ui

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.ImageView
import br.com.centralcasa.books.*

/**
 * Physical hardcover book spine renderer (100% independent of bookcase furniture):
 * - Real photographed leather & woven bookcloth textures with per-book UV offset
 * - Authentic hardcover French groove hinge crease + matte diffuse shading + book-to-book contact edges
 * - 4 independent modes: ORIGINAL, ANTIQUE_BROWN (single antique brown), EDITORIAL (top cover color + cream body), BY_GENRE (saga-consistent A/B/C stamps preserving cover color)
 * - Strict dual-axis fit() + canvas.clipRect() so text never overflows or touches symbols
 */
class ShelfSpineView(
    context: Context,
    private val title: String,
    private val subtitle: String = "",
    private val author: String = "",
    private val materials: ShelfMaterials? = null,
    private val bookId: String = "",
    private val saga: String = "",
    private val collectionId: String = "",
    private val genre: String = "",
    private val kind: String = "book",
    private val spineDesign: SpineDesignMode = SpineDesignMode.BY_GENRE,
    private val resolvedVariant: Int? = null
) : ImageView(context) {
    var spineColor: Int = 0xff404944.toInt()
        private set

    private val seedId: String = bookId.ifBlank { title }
    private val genreTheme: SpineGenreTheme =
        if (spineDesign == SpineDesignMode.BY_GENRE) SpineThemes.resolveGenre(genre, kind) else SpineGenreTheme.DEFAULT
    private val stampAsset: String? = when (spineDesign) {
        SpineDesignMode.BY_GENRE -> SpineThemes.assetName(genreTheme, seedId, saga, collectionId, overrideVariant = resolvedVariant) ?: "spines/classicos_a.webp"
        SpineDesignMode.ANTIQUE_BROWN -> "spines/classicos_a.webp"
        else -> null
    }
    private val useStamp: Boolean = stampAsset != null
    private val useSans: Boolean = when (spineDesign) {
        SpineDesignMode.EDITORIAL -> true
        SpineDesignMode.BY_GENRE -> genreTheme.sansSerif
        else -> false
    }

    private var faceColor: Int = if (spineDesign == SpineDesignMode.ANTIQUE_BROWN) SpineThemes.antiqueBrownColor(seedId) else ShelfSpines.materialFace(spineColor)
    private var inkColor: Int = computeInk()
    private var foilColor: Int = computeFoil()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stampShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0x78000000, PorterDuff.Mode.SRC_IN)
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(if (useSans) Typeface.SANS_SERIF else Typeface.SERIF, Typeface.BOLD)
    }
    private val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(if (useSans) Typeface.SANS_SERIF else Typeface.SERIF, Typeface.NORMAL)
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(if (useSans) Typeface.SANS_SERIF else Typeface.SERIF, Typeface.NORMAL)
    }

    private val density = resources.displayMetrics.density
    private var padX = 0f
    private var topEmblemBottom = 0f
    private var botEmblemTop = 0f
    private var emblemW = 0f
    private var emblemH = 0f
    private var authorTop = 0f
    private var authorSpace = 0f
    private var dividerY = 0f
    private var bodyTop = 0f
    private var bodyBottom = 0f
    private var blockHeight = 0f
    private var titleLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null
    private var authorLayout: StaticLayout? = null

    private val surfacePath = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val shadowRect = RectF()

    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val matKind = when (spineDesign) {
            SpineDesignMode.EDITORIAL -> SpineMaterial.CLOTH
            SpineDesignMode.BY_GENRE -> genreTheme.material
            else -> SpineMaterial.LEATHER
        }
        shader = materials?.materialTexture(matKind)?.let { bmp ->
            val (ox, oy) = SpineThemes.grainOffset(seedId, bmp.width, bmp.height)
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(density / 2.6f, density / 2.6f)
                    postTranslate(ox, oy)
                })
            }
        }
    }

    private fun computeInk(): Int = when (spineDesign) {
        SpineDesignMode.ANTIQUE_BROWN -> 0xFFE8C880.toInt()
        SpineDesignMode.EDITORIAL -> 0xFF1C1916.toInt()
        else -> ShelfSpines.materialInk(faceColor)
    }

    private fun computeFoil(): Int = when (spineDesign) {
        SpineDesignMode.ANTIQUE_BROWN -> 0xFFDAB260.toInt()
        SpineDesignMode.BY_GENRE -> SpineThemes.foilColor(faceColor, genreTheme)
        else -> SpineThemes.foilColor(faceColor, SpineGenreTheme.DEFAULT)
    }

    override fun setImageBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (spineDesign == SpineDesignMode.ANTIQUE_BROWN) return // Single antique brown color; never overridden by cover color
        extractColor(bitmap)
    }

    private fun extractColor(bitmap: Bitmap) {
        val w = minOf(32, bitmap.width); val h = minOf(32, bitmap.height)
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = bitmap.getPixel(x * bitmap.width / w, y * bitmap.height / h)
        spineColor = CoverColors.dominant(pixels) ?: 0xff404944.toInt()
        faceColor = ShelfSpines.materialFace(spineColor)
        inkColor = computeInk()
        foilColor = computeFoil()
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun fit(value: String, brush: TextPaint, length: Int, cross: Float, preferred: Float, wholeWords: Boolean = true): StaticLayout {
        fun layout(size: Float): StaticLayout {
            brush.textSize = size
            return StaticLayout(value, brush, length, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }
        fun fits(block: StaticLayout, enforceWords: Boolean): Boolean {
            if (block.height > cross) return false
            for (line in 0 until block.lineCount) {
                if (block.getLineWidth(line) > length + 0.5f) return false
                if (enforceWords) {
                    val end = block.getLineEnd(line)
                    if (end in 1 until value.length && !value[end - 1].isWhitespace() && !value[end].isWhitespace()) return false
                }
            }
            return true
        }
        val minSize = 4.5f * density
        // First try keeping whole words intact
        if (wholeWords) {
            val init = layout(preferred)
            if (fits(init, true)) return init
            var lo = minSize; var hi = preferred
            var found: Float? = null
            repeat(16) {
                val mid = (lo + hi) / 2f
                if (fits(layout(mid), true)) { found = mid; lo = mid } else hi = mid
            }
            if (found != null) return layout(found!!)
        }
        // Fallback: strictly enforce height <= cross and lineWidth <= length even for extra-long unbroken words
        var lo = 1f; var hi = preferred
        repeat(16) {
            val mid = (lo + hi) / 2f
            if (fits(layout(mid), false)) lo = mid else hi = mid
        }
        return layout(lo)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val wf = w.toFloat(); val hf = h.toFloat()
        val scale = hf / (210f * density)
        padX = minOf(4f * density, maxOf(2.4f * density, wf * 0.13f))
        val availW = (wf - 2f * padX).coerceAtLeast(4f)

        if (useStamp) {
            // Compute exact flush top & bottom emblem geometry FIRST, then derive safe title bounds from it!
            val stampAspect = 180f / (640f * 0.21f) // ~1.339
            val maxEmbH = hf * (if (spineDesign == SpineDesignMode.ANTIQUE_BROWN) 0.19f else 0.20f)
            emblemH = minOf(maxEmbH, maxOf(16f * density * scale, (availW / stampAspect) * 1.18f))
            emblemW = minOf(availW, emblemH * stampAspect * 1.12f)
            topEmblemBottom = 5f * density * scale + emblemH
            botEmblemTop = hf - 5f * density * scale - emblemH

            authorLayout = null; authorTop = 0f; authorSpace = 0f; dividerY = 0f
            val hubPad = if (spineDesign == SpineDesignMode.ANTIQUE_BROWN) 24f * density * scale else 7f * density * scale
            bodyTop = topEmblemBottom + hubPad
            bodyBottom = (botEmblemTop - hubPad).coerceAtLeast(bodyTop + 20f * density)
        } else if (spineDesign == SpineDesignMode.EDITORIAL) {
            authorLayout = null; authorTop = 0f; authorSpace = 0f
            dividerY = hf * 0.225f
            bodyTop = dividerY + 9f * density * scale
            bodyBottom = (hf - 25f * density * scale).coerceAtLeast(bodyTop + 20f * density)
        } else {
            // ORIGINAL (clean 0.7.4 proportions)
            val authorValue = ShelfSpines.authorLines(author)
            authorTop = 10f * density * scale
            authorSpace = if (authorValue.isBlank()) 0f else 32f * density * scale
            val authorWidth = availW.toInt().coerceAtLeast(1)
            val preferredAuthor = 11.5f * density * scale * resources.configuration.fontScale
            authorLayout = if (authorValue.isBlank()) null else fit(authorValue, authorPaint, authorWidth, authorSpace, preferredAuthor, wholeWords = true)
            dividerY = if (authorLayout == null) 12f * density * scale else authorTop + authorSpace + 4f * density * scale
            bodyTop = dividerY + 8f * density * scale
            bodyBottom = (hf - 12f * density * scale).coerceAtLeast(bodyTop + 20f * density)
        }

        val length = (bodyBottom - bodyTop).toInt().coerceAtLeast(1)
        val cross = availW.coerceAtLeast(1f)
        val gap = if (subtitle.isBlank()) 0f else 3f * density * scale
        subtitleLayout = if (subtitle.isBlank()) null else fit(subtitle, subtitlePaint, length, cross * 0.34f, 8.5f * density * scale * resources.configuration.fontScale, wholeWords = false)
        val preferredTitle = 16.5f * density * scale * resources.configuration.fontScale
        val titleCross = (cross - (subtitleLayout?.height ?: 0) - gap).coerceAtLeast(1f)
        titleLayout = fit(title, text, length, titleCross, preferredTitle, wholeWords = true)
        blockHeight = (titleLayout?.height ?: 0) + (subtitleLayout?.height ?: 0) + gap

        val r = minOf(2.5f * density, wf * 0.1f)
        surfacePath.reset()
        surfacePath.addRoundRect(0f, 0f, wf, hf, r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ink = inkColor
        val foil = foilColor

        canvas.save()
        canvas.clipPath(surfacePath)

        // LAYER 1: Authentic base surface
        paint.shader = null
        paint.style = Paint.Style.FILL
        if (spineDesign == SpineDesignMode.EDITORIAL) {
            // Warm matte cream cloth body + top 22.5% band in book's real cover color
            paint.color = EDITORIAL_PAPER
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.color = faceColor
            canvas.drawRect(0f, 0f, w, dividerY, paint)
            paint.color = 0x30000000
            paint.strokeWidth = density * 0.9f
            canvas.drawLine(0f, dividerY, w, dividerY, paint)
            drawEditorialPublisherMark(canvas, w, h)
        } else {
            paint.color = faceColor
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        // LAYER 2: Flush top & bottom hot-stamp art (Por Gênero & Couro Antigo) + sculpted leather hubs
        val stamp = stampAsset?.let { materials?.stamp(it) }
        if (useStamp && stamp != null && !stamp.isRecycled) {
            drawFlushStamps(canvas, stamp, w, h, foil)
            if (spineDesign == SpineDesignMode.ANTIQUE_BROWN) {
                drawSculptedLeatherHubs(canvas, w, h, foil)
            }
        } else if (spineDesign == SpineDesignMode.ORIGINAL) {
            paint.color = (ink and 0x00FFFFFF) or 0x99000000.toInt()
            paint.strokeWidth = density * 0.7f
            canvas.drawLine(padX, dividerY, w - padX, dividerY, paint)
        }

        // LAYER 3: Real photographed leather/cloth texture + hardcover French groove + matte lighting
        if (texturePaint.shader != null) {
            canvas.drawRect(0f, 0f, w, h, texturePaint)
        }
        drawPhysicalBookFinish(canvas, w, h)

        // LAYER 4: Strictly clipped editorial typography (ZERO overflow, ZERO symbol overlap!)
        val isDarkTextSurface = (spineDesign != SpineDesignMode.EDITORIAL) && (ink != 0xFF000000.toInt() && ink != 0xFF1C1916.toInt())
        val textShadow = if (isDarkTextSurface) 0x80000000.toInt() else 0x00000000
        text.color = ink
        if (textShadow != 0) text.setShadowLayer(1.1f * density, 0f, 0.7f * density, textShadow) else text.clearShadowLayer()
        subtitlePaint.color = (ink and 0x00FFFFFF) or 0xCC000000.toInt()
        authorPaint.color = (ink and 0x00FFFFFF) or 0xE0000000.toInt()

        authorLayout?.let { block ->
            canvas.save()
            canvas.clipRect(padX, authorTop, w - padX, authorTop + authorSpace)
            canvas.translate((w - block.width) / 2f, authorTop + (authorSpace - block.height) / 2f)
            block.draw(canvas)
            canvas.restore()
        }

        titleLayout?.let { block ->
            canvas.save()
            canvas.clipRect(padX, bodyTop, w - padX, bodyBottom)
            canvas.translate(w / 2f, (bodyTop + bodyBottom) / 2f)
            canvas.rotate(90f)
            canvas.translate(-block.width / 2f, -blockHeight / 2f)
            block.draw(canvas)
            subtitleLayout?.let {
                canvas.translate(0f, block.height + 3f * density * h / (210f * density))
                it.draw(canvas)
            }
            canvas.restore()
        }

        canvas.restore()
    }

    /** Anchors reframed top (0..21%) and bottom (79..100%) artwork flush to the top & bottom of the spine with zero dead gaps. */
    private fun drawFlushStamps(canvas: Canvas, stamp: Bitmap, w: Float, h: Float, foil: Int) {
        val sw = stamp.width
        val sh = stamp.height
        val topCut = (sh * 0.21f).toInt().coerceAtLeast(1)
        val botCut = (sh * 0.79f).toInt().coerceAtMost(sh - 1)
        val ex0 = (w - emblemW) / 2f
        val topY0 = 5f * density * (h / (210f * density))
        val botY0 = botEmblemTop

        stampPaint.colorFilter = PorterDuffColorFilter(foil, PorterDuff.Mode.SRC_IN)
        stampPaint.alpha = if (spineDesign == SpineDesignMode.ANTIQUE_BROWN) 215 else 225

        fun drawSlice(sy0: Int, sy1: Int, dy0: Float) {
            srcRect.set(0, sy0, sw, sy1)
            dstRect.set(ex0, dy0, ex0 + emblemW, dy0 + emblemH)
            shadowRect.set(dstRect.left + 0.7f * density, dstRect.top + 0.9f * density, dstRect.right + 0.7f * density, dstRect.bottom + 0.9f * density)
            canvas.drawBitmap(stamp, srcRect, shadowRect, stampShadowPaint)
            canvas.drawBitmap(stamp, srcRect, dstRect, stampPaint)
        }

        drawSlice(0, topCut, topY0)
        drawSlice(botCut, sh, botY0)
    }

    /** 4 authentic sculpted 3D leather raised hubs + antique gold rules for Couro Antigo. */
    private fun drawSculptedLeatherHubs(canvas: Canvas, w: Float, h: Float, foil: Int) {
        val scale = h / (210f * density)
        val hubYs = floatArrayOf(
            topEmblemBottom + 2f * density * scale,
            topEmblemBottom + 12f * density * scale,
            botEmblemTop - 16f * density * scale,
            botEmblemTop - 6f * density * scale
        )
        paint.style = Paint.Style.FILL
        for (hy in hubYs) {
            paint.color = 0x3AFFE0AF; canvas.drawRect(0f, hy, w, hy + 1.4f * density, paint)
            paint.color = 0x2A000000; canvas.drawRect(0f, hy + 1.4f * density, w, hy + 3.2f * density, paint)
            paint.color = 0x82000000.toInt(); canvas.drawRect(0f, hy + 3.2f * density, w, hy + 5f * density, paint)
        }
        paint.color = (foil and 0x00FFFFFF) or 0xA5000000.toInt()
        paint.strokeWidth = 0.8f * density
        canvas.drawLine(padX, topEmblemBottom + 20f * density * scale, w - padX, topEmblemBottom + 20f * density * scale, paint)
        canvas.drawLine(padX, botEmblemTop - 20f * density * scale, w - padX, botEmblemTop - 20f * density * scale, paint)
    }

    /** Minimalist publisher icon at the bottom of Editorial spines (matching Prancha 2). */
    private fun drawEditorialPublisherMark(canvas: Canvas, w: Float, h: Float) {
        val icW = minOf(8f * density, maxOf(5f * density, w * 0.22f))
        val icH = icW * 1.1f
        val x0 = (w - icW) / 2f
        val y0 = h - 16f * density
        paint.style = Paint.Style.FILL
        paint.color = 0xC426221E.toInt()
        canvas.drawRect(x0, y0, x0 + icW, y0 + icH, paint)
        paint.color = EDITORIAL_PAPER
        paint.strokeWidth = 0.9f * density
        canvas.drawLine(w / 2f, y0 + density, w / 2f, y0 + icH - density, paint)
        paint.color = 0x38282420
        paint.strokeWidth = 0.6f * density
        canvas.drawLine(padX, h - 21f * density, w - padX, h - 21f * density, paint)
    }

    /** Authentic hardcover French groove hinge crease + matte diffuse shading + contact shadows. */
    private fun drawPhysicalBookFinish(canvas: Canvas, w: Float, h: Float) {
        val isEditorial = spineDesign == SpineDesignMode.EDITORIAL
        val groove = minOf(3.8f * density, maxOf(2f * density, w * 0.11f))
        paint.style = Paint.Style.FILL

        // Soft matte side lighting (no glossy plastic!) + crisp contact borders between adjacent books
        paint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                0xA8080503.toInt(),
                0x280C0805,
                if (isEditorial) 0x12FFF8EC else 0x1AFFF8EC,
                0x00000000,
                if (isEditorial) 0x280E0906 else 0x380E0906,
                0xA8080503.toInt()
            ),
            floatArrayOf(0f, 0.05f, 0.16f, 0.45f, 0.94f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // Vertical top bevel light & natural bottom shelf grounding shadow
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x28FFF4E0, 0x00FFF4E0, 0x00000000, 0x5E0A0603),
            floatArrayOf(0f, 0.06f, 0.89f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Pressed hardcover French groove vertical hinge creases (left & right)
        paint.color = if (isEditorial) 0x520A0603 else 0x6A0A0603
        paint.strokeWidth = 0.95f * density
        canvas.drawLine(groove, 0f, groove, h, paint)
        canvas.drawLine(w - groove, 0f, w - groove, h, paint)
        paint.color = if (isEditorial) 0x22FFF6E8 else 0x2CFFF6E8
        paint.strokeWidth = 0.7f * density
        canvas.drawLine(groove + density, 0f, groove + density, h, paint)
        canvas.drawLine(w - groove - density, 0f, w - groove - density, h, paint)

        // Crisp 1px top paper/board edge highlight
        paint.color = 0x2CFFF4E0
        canvas.drawRect(0f, 0f, w, density, paint)
    }

    private companion object {
        const val EDITORIAL_PAPER = 0xFFE0D9CB.toInt()
    }
}
