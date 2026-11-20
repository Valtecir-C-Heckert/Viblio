package br.com.viblio.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure timing and hinged-geometry curves for the cold-start bookshelf-to-V intro (v7).
 * Every control point converges to the exact official ic_books_nav.png silhouette (97.1% pixel IoU).
 */
internal object ViblioIntroMotion {
    const val DURATION_MS = 2000L
    const val START_SCALE = 0.68f
    const val PHI_COVER = 1.15f
    const val PHI_PAGE1 = 1.05f
    const val PHI_PAGE2 = 0.94f

    fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun invLerp(a: Float, b: Float, x: Float): Float = clamp01((x - a) / (b - a))

    fun easeOutCubic(t: Float): Float {
        val u = 1f - clamp01(t)
        return 1f - u * u * u
    }

    fun easeInOutCubic(t: Float): Float {
        val x = clamp01(t)
        return if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f).let { it * it * it } / 2f)
    }

    fun easeInOutSine(t: Float): Float = (-(cos(PI * clamp01(t)) - 1.0) * 0.5).toFloat()

    /** Weighted opening curve: gentle initial acceleration followed by a long smooth settle. */
    fun easeBookOpen(t: Float): Float {
        val x = clamp01(t)
        return lerp(easeInOutCubic(x), easeOutCubic(x), 0.30f)
    }

    fun sceneIn(t: Float): Float = easeOutCubic(invLerp(0.00f, 0.09f, t))

    fun shelfOut(t: Float): Float = easeInOutCubic(invLerp(0.20f, 0.43f, t))

    fun shelfAlpha(t: Float): Float = sceneIn(t) * (1f - shelfOut(t))

    fun liftProgress(t: Float): Float = easeInOutCubic(invLerp(0.14f, 0.34f, t))

    fun forwardProgress(t: Float): Float = easeInOutCubic(invLerp(0.18f, 0.46f, t))

    fun tiltProgress(t: Float): Float = easeInOutCubic(invLerp(0.24f, 0.48f, t))

    fun openCover(t: Float): Float = easeBookOpen(invLerp(0.36f, 0.72f, t))

    fun openPage1(t: Float): Float = easeBookOpen(invLerp(0.38f, 0.75f, t))

    fun openPage2(t: Float): Float = easeBookOpen(invLerp(0.40f, 0.79f, t))

    fun pagesVisible(t: Float): Float = easeOutCubic(invLerp(0.02f, 0.16f, tiltProgress(t)))

    fun brandAlpha(t: Float): Float = easeOutCubic(invLerp(0.68f, 0.84f, t))

    fun ruleProgress(t: Float): Float = easeOutCubic(invLerp(0.66f, 0.82f, t))

    fun exitProgress(t: Float): Float = easeInOutCubic(invLerp(0.90f, 1.00f, t))

    /** Overall overlay alpha (1 while active, 0 at end). */
    fun overlayAlpha(t: Float): Float = 1f - exitProgress(t)

    /** Rotational hinge X projection from closed book -> exact ic_books_nav coordinate. */
    fun hingeX(fx: Float, u: Float, closedX: Float, phiMax: Float, mirror: Boolean): Float {
        val phi = clamp01(u) * phiMax
        val s = (sin(phi.toDouble()) / sin(phiMax.toDouble())).toFloat()
        val x = lerp(closedX, fx, s)
        return if (mirror) 1f - x else x
    }

    /** Rotational hinge Y projection with smooth vertical swing arc (0 at u=0 and u=1). */
    fun hingeY(fy: Float, u: Float, closedY: Float, arcLift: Float): Float {
        val cu = clamp01(u)
        val arch = sin(cu * PI).toFloat() * arcLift
        return lerp(closedY, fy, easeInOutSine(cu)) - arch
    }
}

/**
 * Cold-start intro overlay: a minimalist bookshelf on pure #151515 background (no radial glow)
 * where the center book in the current reading book's color lifts out, glides forward and opens
 * continuously into the exact official Viblio V icon (colored covers/spine + pure white pages),
 * then fades cleanly in a single hardware layer to reveal the Current Book screen underneath.
 */
class ViblioIntroView(
    context: Context,
    initialCoverColor: Int,
    private val onDone: () -> Unit,
) : View(context) {
    private val bgColor = Color.rgb(21, 21, 21)
    private var coverColor: Int = initialCoverColor

    // Pre-allocated paints & scratch paths (zero allocations in onDraw).
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = initialCoverColor }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val shelfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(242, 240, 236)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.32f
    }
    private val path = Path()
    private val rect = RectF()

    private data class NeighborBook(val xMul: Float, val wMul: Float, val hMul: Float, val col: Int, val band: Int)

    private val neighbors = arrayOf(
        NeighborBook(-0.050f - 0.024f - 0.118f, 0.118f, 0.90f, Color.rgb(44, 44, 49), Color.rgb(63, 63, 71)),
        NeighborBook(-0.050f - 0.048f - 0.214f, 0.096f, 1.06f, Color.rgb(37, 37, 42), Color.rgb(54, 54, 61)),
        NeighborBook(-0.050f - 0.072f - 0.340f, 0.126f, 0.80f, Color.rgb(32, 32, 37), Color.rgb(46, 46, 53)),
        NeighborBook(0.050f + 0.024f, 0.106f, 1.04f, Color.rgb(46, 46, 51), Color.rgb(65, 65, 73)),
        NeighborBook(0.050f + 0.048f + 0.106f, 0.122f, 0.86f, Color.rgb(37, 37, 42), Color.rgb(53, 53, 60)),
        NeighborBook(0.050f + 0.072f + 0.228f, 0.096f, 0.94f, Color.rgb(32, 32, 37), Color.rgb(46, 46, 52)),
    )

    private var progress = 0f
    private var finished = false
    private var usingHardwareLayer = false
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Abertura do Viblio"
        setOnClickListener { finishNow() }
    }

    /** Updates the hero book cover/spine color if async reading-theme resolution completes during intro. */
    fun updateCoverColor(color: Int) {
        if (finished || coverColor == color) return
        coverColor = color
        coverPaint.color = color
        invalidate()
    }

    fun start() {
        if (finished || animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ViblioIntroMotion.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                val overlayAlpha = ViblioIntroMotion.overlayAlpha(progress)
                if (overlayAlpha < 0.999f && !usingHardwareLayer) {
                    setLayerType(LAYER_TYPE_HARDWARE, null)
                    usingHardwareLayer = true
                }
                alpha = overlayAlpha
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finishNow() }
                override fun onAnimationCancel(animation: Animator) { finishNow() }
            })
            start()
        }
    }

    fun cancel() {
        finished = true
        animator?.cancel()
        animator = null
        clearHardwareLayer()
    }

    private fun finishNow() {
        if (finished) return
        finished = true
        animator?.cancel()
        animator = null
        clearHardwareLayer()
        onDone()
    }

    private fun clearHardwareLayer() {
        if (usingHardwareLayer) {
            setLayerType(LAYER_TYPE_NONE, null)
            usingHardwareLayer = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val t = progress

        // Pure flat #151515 background — zero radial glow.
        canvas.drawColor(bgColor)

        val size = minOf(w, h) * 0.50f
        val cx = w * 0.5f
        val cy = h * 0.44f

        val sceneIn = ViblioIntroMotion.sceneIn(t)
        val shelfOut = ViblioIntroMotion.shelfOut(t)
        val shelfAlpha = ViblioIntroMotion.shelfAlpha(t)
        val liftP = ViblioIntroMotion.liftProgress(t)
        val forwardP = ViblioIntroMotion.forwardProgress(t)
        val tiltP = ViblioIntroMotion.tiltProgress(t)
        val openCover = ViblioIntroMotion.openCover(t)
        val openP1 = ViblioIntroMotion.openPage1(t)
        val openP2 = ViblioIntroMotion.openPage2(t)
        val exitP = ViblioIntroMotion.exitProgress(t)
        val exitScale = ViblioIntroMotion.lerp(1.0f, 1.03f, exitP)

        // 1. Minimalist bookshelf + 6 neighbor books receding behind hero book.
        if (shelfAlpha > 0.002f) {
            drawBookshelf(canvas, cx, cy, size, density, sceneIn, shelfOut, shelfAlpha)
        }

        // 2. Continuous hero book (colored cover & spine + pure white pages -> exact official V).
        if (sceneIn > 0.002f) {
            canvas.save()
            val bookScale = ViblioIntroMotion.lerp(ViblioIntroMotion.START_SCALE, 1.0f, forwardP) *
                exitScale * ViblioIntroMotion.lerp(0.97f, 1.0f, sceneIn)
            val liftOffset = sin(liftP * PI).toFloat() * (-14f * density) * (1f - forwardP * 0.55f)
            val bookDy = ViblioIntroMotion.lerp(10f * density + ViblioIntroMotion.lerp(5f * density, 0f, sceneIn), 0f, forwardP) + liftOffset

            canvas.translate(cx, cy + bookDy)
            canvas.scale(bookScale, bookScale)

            val sceneAlphaInt = (sceneIn * 255f).toInt().coerceIn(0, 255)
            coverPaint.color = coverColor
            coverPaint.alpha = sceneAlphaInt

            val spineTopY = ViblioIntroMotion.lerp(0.325f, 0.589f, tiltP)
            val spineTopDip = ViblioIntroMotion.lerp(spineTopY, 0.618f, ViblioIntroMotion.easeInOutCubic(ViblioIntroMotion.invLerp(0.15f, 1.0f, tiltP)))
            val spineBotTipY = ViblioIntroMotion.lerp(0.885f, 0.918f, tiltP)
            val closedTopY = ViblioIntroMotion.lerp(0.325f, 0.255f, tiltP)
            val closedKneeY = ViblioIntroMotion.lerp(0.878f, 0.600f, tiltP)
            val coverGap = ViblioIntroMotion.lerp(0f, 0.011f, ViblioIntroMotion.easeOutCubic(ViblioIntroMotion.invLerp(0f, 0.25f, openCover)))
            val coverHingeX = 0.550f + coverGap

            // 2A. Left & right covers in current book's cover color.
            if (openCover > 0.001f) {
                drawOpeningCover(canvas, size, openCover, coverHingeX, spineTopY, closedTopY, closedKneeY, mirror = false)
                drawOpeningCover(canvas, size, openCover, coverHingeX, spineTopY, closedTopY, closedKneeY, mirror = true)
            }

            // 2B. Pure white pages spread emerging smoothly above the colored spine.
            val pagesVis = ViblioIntroMotion.pagesVisible(t)
            if (pagesVis > 0.005f) {
                pagePaint.alpha = (sceneIn * pagesVis * 255f).toInt().coerceIn(0, 255)
                drawOpeningPages(canvas, size, tiltP, openP1, openP2, spineTopY, closedTopY)
            }

            // 2C. Center spine pillar in current book's cover color (always in front, one continuous shape).
            drawSpinePillar(canvas, size, tiltP, spineTopY, spineTopDip, spineBotTipY)

            // Subtle white spine rib detail while standing on the shelf (fades cleanly as book lifts).
            val ribAlpha = sceneIn * (1f - ViblioIntroMotion.easeInOutCubic(ViblioIntroMotion.invLerp(0.22f, 0.36f, t))) * 0.28f
            if (ribAlpha > 0.005f) {
                ribPaint.alpha = (ribAlpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawRect(tx(0.458f, size), ty(spineTopY + 0.080f, size), tx(0.542f, size), ty(spineTopY + 0.092f, size), ribPaint)
                canvas.drawRect(tx(0.458f, size), ty(0.800f, size), tx(0.542f, size), ty(0.809f, size), ribPaint)
            }

            canvas.restore()
        }

        // 3. Editorial colored hairline + VIBLIO wordmark.
        val brandP = ViblioIntroMotion.brandAlpha(t)
        if (brandP > 0.005f) {
            val brandAlphaInt = (brandP * 255f).toInt().coerceIn(0, 255)
            val ruleY = cy + size * 0.50f + 13f * density
            val ruleW = 24f * density * ViblioIntroMotion.ruleProgress(t)
            val ruleH = 2.0f * density
            coverPaint.color = coverColor
            coverPaint.alpha = brandAlphaInt
            rect.set(cx - ruleW * 0.5f, ruleY, cx + ruleW * 0.5f, ruleY + ruleH)
            canvas.drawRoundRect(rect, ruleH * 0.5f, ruleH * 0.5f, coverPaint)

            val textY = ruleY + 28f * density + ViblioIntroMotion.lerp(7f * density, 0f, brandP)
            wordPaint.textSize = 15.5f * density
            wordPaint.alpha = brandAlphaInt
            canvas.drawText("VIBLIO", cx, textY, wordPaint)
        }
    }

    private fun tx(nx: Float, size: Float): Float = (nx - 0.5f) * size

    private fun ty(ny: Float, size: Float): Float = (ny - 0.5f) * size

    private fun drawBookshelf(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        density: Float,
        sceneIn: Float,
        shelfOut: Float,
        shelfAlpha: Float,
    ) {
        canvas.save()
        val shelfScale = ViblioIntroMotion.lerp(0.97f, 1.0f, sceneIn) * ViblioIntroMotion.lerp(1.0f, 0.86f, shelfOut)
        val shelfDy = ViblioIntroMotion.lerp(5f * density, 0f, sceneIn) + ViblioIntroMotion.lerp(0f, 22f * density, shelfOut)
        canvas.translate(cx, cy + shelfDy)
        canvas.scale(shelfScale, shelfScale)

        val unit = size * ViblioIntroMotion.START_SCALE
        val shelfY = (0.885f - 0.5f) * unit + 10f * density
        val heroW = 0.100f * unit
        val heroH = 0.560f * unit
        val cornerR = 2.2f * density
        val alphaInt = (shelfAlpha * 255f).toInt().coerceIn(0, 255)

        for (b in neighbors) {
            val bx = b.xMul * unit
            val bw = b.wMul * unit
            val bh = heroH * b.hMul
            val top = shelfY - bh

            shelfPaint.color = b.col
            shelfPaint.alpha = alphaInt
            rect.set(bx, top, bx + bw, shelfY)
            canvas.drawRoundRect(rect, cornerR, cornerR, shelfPaint)

            shelfPaint.color = b.band
            shelfPaint.alpha = alphaInt
            val pad = 1.4f * density
            canvas.drawRect(bx + pad, top + bh * 0.16f, bx + bw - pad, top + bh * 0.16f + 1.8f * density, shelfPaint)
            canvas.drawRect(bx + pad, top + bh * 0.83f, bx + bw - pad, top + bh * 0.83f + 1.4f * density, shelfPaint)
        }

        val plankW = unit * 0.94f
        val plankH = 3.2f * density
        shelfPaint.color = Color.rgb(56, 56, 62)
        shelfPaint.alpha = alphaInt
        rect.set(-plankW * 0.5f, shelfY + density, plankW * 0.5f, shelfY + density + plankH)
        canvas.drawRoundRect(rect, plankH * 0.5f, plankH * 0.5f, shelfPaint)

        shelfPaint.color = coverColor
        shelfPaint.alpha = (shelfAlpha * 0.55f * 255f).toInt().coerceIn(0, 255)
        rect.set(-heroW * 0.45f, shelfY + density, heroW * 0.45f, shelfY + density + plankH)
        canvas.drawRoundRect(rect, plankH * 0.5f, plankH * 0.5f, shelfPaint)

        canvas.restore()
    }

    private fun drawOpeningCover(
        canvas: Canvas,
        size: Float,
        u: Float,
        cInX: Float,
        spineTopY: Float,
        closedTopY: Float,
        closedKneeY: Float,
        mirror: Boolean,
    ) {
        val phi = ViblioIntroMotion.PHI_COVER
        val cOutX = 0.550f
        fun mx(x: Float) = tx(if (mirror) 1f - x else x, size)
        fun hx(fx: Float, cxClosed: Float) = tx(ViblioIntroMotion.hingeX(fx, u, cxClosed, phi, mirror), size)
        fun hy(fy: Float, cyClosed: Float, lift: Float) = ty(ViblioIntroMotion.hingeY(fy, u, cyClosed, lift), size)

        val inTopY = ty(ViblioIntroMotion.lerp(spineTopY, 0.578f, u), size)
        val inBotY = ty(ViblioIntroMotion.lerp(0.882f, 0.874f, u), size)
        val inBotCtrlY = ty(ViblioIntroMotion.lerp(0.884f, 0.878f, u), size)
        val inBot2X = mx(cInX + 0.005f * u)
        val inBot2Y = ty(ViblioIntroMotion.lerp(0.883f, 0.875f, u), size)

        path.reset()
        path.moveTo(mx(cInX), inTopY)
        path.lineTo(mx(cInX), inBotY)
        path.quadTo(mx(cInX), inBotCtrlY, inBot2X, inBot2Y)
        path.lineTo(hx(0.773f, cOutX), hy(0.740f, closedKneeY + 0.015f, 0.026f))
        path.quadTo(
            hx(0.782f, cOutX), hy(0.733f, closedKneeY + 0.007f, 0.028f),
            hx(0.787f, cOutX), hy(0.720f, closedKneeY, 0.030f),
        )
        path.lineTo(hx(0.972f, cOutX), hy(0.264f, closedTopY + 0.014f, 0.052f))
        path.quadTo(
            hx(0.977f, cOutX), hy(0.251f, closedTopY + 0.005f, 0.054f),
            hx(0.967f, cOutX - 0.002f), hy(0.244f, closedTopY, 0.054f),
        )
        path.quadTo(
            hx(0.958f, cOutX - 0.004f), hy(0.239f, closedTopY, 0.052f),
            hx(0.947f, cOutX - 0.006f), hy(0.246f, closedTopY + 0.006f, 0.050f),
        )
        path.cubicTo(
            hx(0.818f, cInX), hy(0.328f, ViblioIntroMotion.lerp(closedTopY, spineTopY, 0.30f), 0.036f),
            hx(0.625f, cInX), hy(0.452f, ViblioIntroMotion.lerp(closedTopY, spineTopY, 0.68f), 0.014f),
            mx(cInX), inTopY,
        )
        path.close()
        canvas.drawPath(path, coverPaint)
    }

    private fun drawOpeningPages(
        canvas: Canvas,
        size: Float,
        tiltP: Float,
        openP1: Float,
        openP2: Float,
        spineTopY: Float,
        closedTopY: Float,
    ) {
        val cradleGap = ViblioIntroMotion.lerp(0f, 0.015f, ViblioIntroMotion.easeInOutCubic(ViblioIntroMotion.invLerp(0.18f, 0.78f, tiltP)))
        val crBotEdge = spineTopY - cradleGap
        val crBotMid = ViblioIntroMotion.lerp(crBotEdge, 0.606f, tiltP * ViblioIntroMotion.lerp(0.50f, 1.0f, openP1))
        val crTopBase = max(closedTopY, crBotEdge - 0.075f)
        val crValleyY = ViblioIntroMotion.lerp(crTopBase, 0.517f, openP2)
        val crShoulderX = ViblioIntroMotion.lerp(0.546f, 0.564f, openP1)
        val crShoulderY = ViblioIntroMotion.lerp(crTopBase, 0.492f, openP1)
        val crEdgeX = ViblioIntroMotion.lerp(0.546f, 0.560f, openP1)
        val crEdgeY = ViblioIntroMotion.lerp(crBotEdge, 0.564f, openP1)
        val crValCtrlY = ViblioIntroMotion.lerp(crTopBase, 0.512f, openP2)

        // Center white cradle.
        path.reset()
        path.moveTo(tx(1f - crEdgeX, size), ty(crEdgeY, size))
        path.quadTo(tx(0.500f, size), ty(crBotMid, size), tx(crEdgeX, size), ty(crEdgeY, size))
        path.lineTo(tx(crShoulderX, size), ty(crShoulderY, size))
        path.quadTo(tx(0.528f, size), ty(crValCtrlY, size), tx(0.500f, size), ty(crValleyY, size))
        path.quadTo(tx(0.472f, size), ty(crValCtrlY, size), tx(1f - crShoulderX, size), ty(crShoulderY, size))
        path.close()
        canvas.drawPath(path, pagePaint)

        // Lower fanned page layer (left & right).
        drawLowerPageRibbon(canvas, size, openP1, crBotEdge, closedTopY, mirror = false)
        drawLowerPageRibbon(canvas, size, openP1, crBotEdge, closedTopY, mirror = true)

        // Upper fanned page layer (left & right).
        drawUpperPageRibbon(canvas, size, openP2, crBotEdge, closedTopY, crValleyY, mirror = false)
        drawUpperPageRibbon(canvas, size, openP2, crBotEdge, closedTopY, crValleyY, mirror = true)
    }

    private fun drawLowerPageRibbon(
        canvas: Canvas,
        size: Float,
        u: Float,
        crBotEdge: Float,
        closedTopY: Float,
        mirror: Boolean,
    ) {
        val phi = ViblioIntroMotion.PHI_PAGE1
        fun hx(fx: Float, cxClosed: Float) = tx(ViblioIntroMotion.hingeX(fx, u, cxClosed, phi, mirror), size)
        fun hy(fy: Float, cyClosed: Float, lift: Float) = ty(ViblioIntroMotion.hingeY(fy, u, cyClosed, lift), size)

        path.reset()
        path.moveTo(hx(0.542f, 0.548f), hy(0.568f, crBotEdge, 0.004f))
        path.cubicTo(
            hx(0.612f, 0.548f), hy(0.442f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.35f), 0.020f),
            hx(0.766f, 0.548f), hy(0.330f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.72f), 0.040f),
            hx(0.914f, 0.548f), hy(0.246f, closedTopY + 0.032f, 0.052f),
        )
        path.quadTo(
            hx(0.919f, 0.548f), hy(0.242f, closedTopY + 0.028f, 0.052f),
            hx(0.919f, 0.548f), hy(0.235f, closedTopY + 0.022f, 0.052f),
        )
        path.lineTo(hx(0.919f, 0.548f), hy(0.197f, closedTopY + 0.006f, 0.054f))
        path.quadTo(
            hx(0.919f, 0.546f), hy(0.183f, closedTopY, 0.054f),
            hx(0.903f, 0.524f), hy(0.187f, closedTopY, 0.052f),
        )
        path.cubicTo(
            hx(0.754f, 0.524f), hy(0.264f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.72f), 0.038f),
            hx(0.614f, 0.524f), hy(0.386f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.38f), 0.018f),
            hx(0.554f, 0.522f), hy(0.490f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.14f), 0.005f),
        )
        path.lineTo(hx(0.532f, 0.518f), hy(0.532f, crBotEdge - 0.015f, 0f))
        path.close()
        canvas.drawPath(path, pagePaint)
    }

    private fun drawUpperPageRibbon(
        canvas: Canvas,
        size: Float,
        u: Float,
        crBotEdge: Float,
        closedTopY: Float,
        crValleyY: Float,
        mirror: Boolean,
    ) {
        val phi = ViblioIntroMotion.PHI_PAGE2
        fun hx(fx: Float, cxClosed: Float) = tx(ViblioIntroMotion.hingeX(fx, u, cxClosed, phi, mirror), size)
        fun hy(fy: Float, cyClosed: Float, lift: Float) = ty(ViblioIntroMotion.hingeY(fy, u, cyClosed, lift), size)

        path.reset()
        path.moveTo(hx(0.538f, 0.526f), hy(0.514f, crBotEdge - 0.015f, 0.004f))
        path.cubicTo(
            hx(0.606f, 0.526f), hy(0.362f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.42f), 0.020f),
            hx(0.738f, 0.526f), hy(0.246f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.78f), 0.042f),
            hx(0.854f, 0.526f), hy(0.193f, closedTopY + 0.026f, 0.056f),
        )
        path.quadTo(
            hx(0.860f, 0.526f), hy(0.189f, closedTopY + 0.022f, 0.056f),
            hx(0.860f, 0.526f), hy(0.182f, closedTopY + 0.018f, 0.056f),
        )
        path.lineTo(hx(0.860f, 0.526f), hy(0.160f, closedTopY + 0.004f, 0.058f))
        path.quadTo(
            hx(0.860f, 0.522f), hy(0.145f, closedTopY, 0.058f),
            hx(0.844f, 0.500f), hy(0.150f, closedTopY, 0.054f),
        )
        path.cubicTo(
            hx(0.708f, 0.500f), hy(0.196f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.74f), 0.038f),
            hx(0.586f, 0.500f), hy(0.324f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.38f), 0.016f),
            hx(0.522f, 0.500f), hy(0.496f, ViblioIntroMotion.lerp(crBotEdge, closedTopY, 0.10f), 0.004f),
        )
        path.quadTo(hx(0.512f, 0.500f), hy(0.518f, crValleyY, 0f), tx(0.500f, size), ty(crValleyY, size))
        path.lineTo(tx(0.500f, size), ty(ViblioIntroMotion.lerp(crBotEdge, 0.558f, u), size))
        path.close()
        canvas.drawPath(path, pagePaint)
    }

    private fun drawSpinePillar(
        canvas: Canvas,
        size: Float,
        tiltP: Float,
        spineTopY: Float,
        spineTopDip: Float,
        spineBotTipY: Float,
    ) {
        val shoulderBotY = ViblioIntroMotion.lerp(0.885f, 0.890f, tiltP)
        path.reset()
        path.moveTo(tx(0.450f, size), ty(spineTopY, size))
        path.quadTo(tx(0.500f, size), ty(spineTopDip, size), tx(0.550f, size), ty(spineTopY, size))
        path.lineTo(tx(0.550f, size), ty(0.884f, size))
        path.quadTo(tx(0.550f, size), ty(0.888f, size), tx(0.546f, size), ty(shoulderBotY, size))
        path.lineTo(tx(0.503f, size), ty(spineBotTipY, size))
        path.quadTo(tx(0.500f, size), ty(spineBotTipY + 0.002f * tiltP, size), tx(0.497f, size), ty(spineBotTipY, size))
        path.lineTo(tx(0.454f, size), ty(shoulderBotY, size))
        path.quadTo(tx(0.450f, size), ty(0.888f, size), tx(0.450f, size), ty(0.884f, size))
        path.close()
        canvas.drawPath(path, coverPaint)
    }

    companion object {
        fun animatorsEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return false
            return runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }.getOrDefault(true)
        }
    }
}
