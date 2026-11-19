package br.com.viblio.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import br.com.centralcasa.R
import kotlin.math.cos
import kotlin.math.sin

/** Pure timing and geometry curves for the cold-start book-opening intro. */
internal object ViblioIntroMotion {
    const val DURATION_MS = 1150L

    fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    fun easeOutCubic(t: Float): Float {
        val u = 1f - clamp01(t)
        return 1f - u * u * u
    }

    fun easeInOutCubic(t: Float): Float {
        val x = clamp01(t)
        return if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f).let { it * it * it } / 2f)
    }

    /** Opening progress (0 = closed flat lying book, 1 = full open V icon). */
    fun openProgress(p: Float): Float = easeInOutCubic((p - 0.16f) / 0.54f)

    /** Top cover angle in degrees (0 = lying flat right on top of pages, 150 = open left V wing). */
    fun leftCoverAngle(open: Float): Float = 150f * clamp01(open)

    /** Bottom cover angle in degrees (0 = lying flat right, 30 = open right V wing). */
    fun rightCoverAngle(open: Float): Float = 30f * clamp01(open)

    /** Cross-fade alpha into the exact official ic_books_nav glyph as opening completes. */
    fun glyphBlend(p: Float): Float = easeOutCubic((p - 0.62f) / 0.16f)

    /** Wordmark visibility alpha. */
    fun wordmarkAlpha(p: Float): Float = easeOutCubic((p - 0.48f) / 0.24f)

    /** Overall overlay exit fade (1 while visible, 0 at end). */
    fun overlayAlpha(p: Float): Float = 1f - easeInOutCubic((p - 0.84f) / 0.16f)
}

/**
 * Cold-start intro overlay: a closed horizontal book resting flat that smoothly opens its covers
 * and fans its pages into the iconic V silhouette of Viblio, then reveals the already-rendered app.
 */
class ViblioIntroView(
    context: Context,
    private val accentColor: Int,
    private val onDone: () -> Unit,
) : View(context) {
    private val bgColor = Color.rgb(21, 21, 21)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = bgColor; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillCutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = bgColor }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.26f
    }
    private val iconDrawable: Drawable? = runCatching {
        context.resources.getDrawable(R.drawable.ic_books_nav, context.theme)?.mutate()?.apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
    }.getOrNull()

    private var progress = 0f
    private var finished = false
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Abertura do Viblio"
        setOnClickListener { finishNow() }
    }

    fun start() {
        if (finished || animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ViblioIntroMotion.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
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
    }

    private fun finishNow() {
        if (finished) return
        finished = true
        animator?.cancel()
        animator = null
        onDone()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val overlayAlpha = ViblioIntroMotion.overlayAlpha(progress)
        if (overlayAlpha <= 0.001f) return

        // Background veil fades out smoothly to reveal the already-rendered shelf underneath.
        canvas.drawColor(Color.argb((overlayAlpha * 255).toInt().coerceIn(0, 255), Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))

        val introIn = ViblioIntroMotion.easeOutCubic(progress / 0.14f)
        val open = ViblioIntroMotion.openProgress(progress)
        val glyphBlend = ViblioIntroMotion.glyphBlend(progress)
        val exitScale = 1f + (1f - overlayAlpha) * 0.05f

        val iconSize = minOf(w, h) * 0.34f
        val cx = w * 0.5f
        val cy = h * 0.47f + (1f - introIn) * (8f * density)

        canvas.save()
        canvas.scale(exitScale, exitScale, cx, cy)

        // Procedural opening book (fades out cleanly as the exact official V-glyph blends in).
        val proceduralAlpha = ((1f - glyphBlend) * introIn * overlayAlpha * 255).toInt().coerceIn(0, 255)
        if (proceduralAlpha > 2) {
            drawOpeningBook(canvas, cx, cy, iconSize, open, proceduralAlpha)
        }

        // Exact official V-book glyph for pixel-perfect match with the launcher icon.
        val finalGlyphAlpha = (glyphBlend * overlayAlpha * 255).toInt().coerceIn(0, 255)
        if (finalGlyphAlpha > 2 && iconDrawable != null) {
            val half = (iconSize * 0.5f).toInt()
            iconDrawable.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            iconDrawable.alpha = finalGlyphAlpha
            iconDrawable.draw(canvas)
        }

        // Editorial serif wordmark fading gently below the V.
        val wordAlpha = (ViblioIntroMotion.wordmarkAlpha(progress) * overlayAlpha * 235).toInt().coerceIn(0, 255)
        if (wordAlpha > 2) {
            wordPaint.textSize = 15f * density
            wordPaint.color = Color.argb(wordAlpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            val wordY = cy + iconSize * 0.64f + (1f - ViblioIntroMotion.wordmarkAlpha(progress)) * (6f * density)
            canvas.drawText("VIBLIO", cx, wordY, wordPaint)
        }
        canvas.restore()
    }

    private fun drawOpeningBook(canvas: Canvas, cx: Float, cy: Float, size: Float, open: Float, alpha: Int) {
        inkPaint.alpha = alpha
        fillCutPaint.alpha = alpha
        cutPaint.alpha = alpha
        cutPaint.strokeWidth = (size * 0.024f).coerceAtLeast(2f)

        // Pivot shifts smoothly from left edge (closed lying book) to center bottom apex (open V).
        val halfLen = size * 0.44f
        val bookHeight = size * 0.42f
        val spineX = cx - halfLen * 0.55f * (1f - open)
        val spineY = cy + size * 0.14f + size * 0.20f * open

        // Thickness of the closed lying book collapses smoothly as pages fan open.
        val closedBlockH = size * 0.14f * (1f - open).coerceAtLeast(0f)

        // 1. Right (bottom) cover wing: starts horizontal flat (0 deg) -> rises to 30 deg right V wing.
        val rightDeg = ViblioIntroMotion.rightCoverAngle(open)
        drawWing(canvas, spineX, spineY, halfLen, bookHeight * (0.28f + 0.72f * open), -rightDeg, isLeft = false, open = open, closedThickness = closedBlockH)

        // 2. Fanned inner pages emerging between the covers as the book opens.
        if (open > 0.08f) {
            val fan = ViblioIntroMotion.clamp01((open - 0.08f) / 0.85f)
            val layers = listOf(0.84f to 7f, 0.92f to 15f)
            layers.forEach { (lenScale, extraDeg) ->
                val spreadRight = rightDeg + extraDeg * fan
                val spreadLeft = ViblioIntroMotion.leftCoverAngle(open) - extraDeg * fan
                drawPageLayer(canvas, spineX, spineY - size * 0.04f * open, halfLen * lenScale, bookHeight * (0.35f + 0.55f * open), -spreadRight, false)
                drawPageLayer(canvas, spineX, spineY - size * 0.04f * open, halfLen * lenScale, bookHeight * (0.35f + 0.55f * open), -spreadLeft, true)
            }
        }

        // 3. Left (top) cover wing: starts lying flat on top of the closed book (0 deg) -> swings open to 150 deg (left V wing).
        val leftDeg = ViblioIntroMotion.leftCoverAngle(open)
        val topOffsetY = -closedBlockH
        drawWing(canvas, spineX, spineY + topOffsetY, halfLen, bookHeight * (0.28f + 0.72f * open), -leftDeg, isLeft = true, open = open, closedThickness = 0f)

        // 4. Center spine column & crisp dark V cut matching ic_books_nav.
        val spineW = size * (0.075f + 0.03f * open)
        val spineTopY = spineY - bookHeight * (0.28f + 0.62f * open)
        if (open > 0.2f) {
            canvas.drawRect(spineX - spineW * 0.5f, spineTopY + size * 0.14f, spineX + spineW * 0.5f, spineY, inkPaint)
            canvas.drawLine(spineX - spineW * 0.5f, spineTopY + size * 0.14f, spineX - spineW * 0.5f, spineY, cutPaint)
            canvas.drawLine(spineX + spineW * 0.5f, spineTopY + size * 0.14f, spineX + spineW * 0.5f, spineY, cutPaint)
        } else {
            // Closed lying book: crisp dark page-edge hairlines inside the horizontal block.
            val edgeLeft = spineX + size * 0.06f
            val edgeRight = spineX + halfLen * 0.95f
            val mid1 = spineY - closedBlockH * 0.35f
            val mid2 = spineY - closedBlockH * 0.68f
            canvas.drawRect(spineX, spineY - closedBlockH, spineX + halfLen, spineY, inkPaint)
            canvas.drawLine(edgeLeft, mid1, edgeRight, mid1, cutPaint)
            canvas.drawLine(edgeLeft, mid2, edgeRight, mid2, cutPaint)
        }
    }

    private fun drawWing(
        canvas: Canvas,
        pivotX: Float,
        pivotY: Float,
        length: Float,
        thickness: Float,
        angleDeg: Float,
        isLeft: Boolean,
        open: Float,
        closedThickness: Float,
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        // Outward normal pointing upward from the cover plane into the page fan.
        val nx = if (isLeft) -dy else dy
        val ny = if (isLeft) dx else -dx
        val slab = if (open < 0.05f) (thickness * 0.34f).coerceAtLeast(closedThickness * 0.35f) else thickness * (0.36f + 0.64f * open)

        val x1 = pivotX + dx * length
        val y1 = pivotY + dy * length
        val tipTaper = 0.78f + 0.22f * (1f - open)

        val path = Path().apply {
            moveTo(pivotX, pivotY)
            lineTo(x1, y1)
            lineTo(x1 + nx * slab * tipTaper, y1 + ny * slab * tipTaper)
            lineTo(pivotX + nx * slab * 0.58f, pivotY + ny * slab * 0.58f)
            close()
        }
        canvas.drawPath(path, inkPaint)
        canvas.drawPath(path, cutPaint)
    }

    private fun drawPageLayer(
        canvas: Canvas,
        pivotX: Float,
        pivotY: Float,
        length: Float,
        height: Float,
        angleDeg: Float,
        isLeft: Boolean,
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val nx = if (isLeft) -dy else dy
        val ny = if (isLeft) dx else -dx
        val lift = height * 0.22f

        val x1 = pivotX + dx * length
        val y1 = pivotY + dy * length
        val ctrlX = pivotX + dx * (length * 0.45f) + nx * (lift * 0.7f)
        val ctrlY = pivotY + dy * (length * 0.45f) + ny * (lift * 0.7f)

        val path = Path().apply {
            moveTo(pivotX, pivotY)
            quadTo(ctrlX, ctrlY, x1, y1)
            lineTo(x1 - nx * (lift * 0.35f), y1 - ny * (lift * 0.35f))
            quadTo(ctrlX - nx * (lift * 0.35f), ctrlY - ny * (lift * 0.35f), pivotX, pivotY + lift * 0.25f)
            close()
        }
        canvas.drawPath(path, inkPaint)
        canvas.drawPath(path, cutPaint)
    }

    companion object {
        fun animatorsEnabled(context: Context): Boolean {
            return runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }.getOrDefault(true)
        }
    }
}
