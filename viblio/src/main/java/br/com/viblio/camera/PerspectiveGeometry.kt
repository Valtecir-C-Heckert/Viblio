package br.com.viblio.camera

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** What the photo becomes: a book cover or the narrow physical spine. */
enum class PhotoCropMode(val key: String) {
    COVER("cover"), SPINE("spine");
    /** Guide/default frame width ÷ height. */
    val guideRatio: Float get() = if (this == COVER) 2f / 3f else 1f / 6f
    companion object { fun of(value: String?) = if (value == "spine") SPINE else COVER }
}

data class QuadPoint(val x: Float, val y: Float)

data class GrayFrame(val luma: IntArray, val width: Int, val height: Int)

/**
 * Pure geometry and edge detection for the live document scanner and 4-corner cropper
 * (JVM-testable, zero Android framework dependency).
 * Quads are always ordered top-left, top-right, bottom-right, bottom-left.
 */
object PerspectiveGeometry {
    /** Default ceiling kept for direct callers; sync-optimized ceilings are provided per mode below. */
    const val MAX_OUTPUT_EDGE = 1600
    const val MAX_COVER_OUTPUT_EDGE = 1600
    const val MAX_SPINE_OUTPUT_EDGE = 1400
    const val MIN_OUTPUT_EDGE = 16

    fun maxEdgeFor(mode: PhotoCropMode): Int =
        if (mode == PhotoCropMode.SPINE) MAX_SPINE_OUTPUT_EDGE else MAX_COVER_OUTPUT_EDGE

    fun jpegQualityFor(mode: PhotoCropMode): Int = 92

    fun orderQuad(points: List<QuadPoint>): List<QuadPoint> {
        require(points.size == 4) { "Quatro cantos são necessários." }
        require(points.all { it.x.isFinite() && it.y.isFinite() }) { "Canto inválido." }
        val cx = points.sumOf { it.x.toDouble() } / 4
        val cy = points.sumOf { it.y.toDouble() } / 4
        val byAngle = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = byAngle.indices.minByOrNull { byAngle[it].x + byAngle[it].y } ?: 0
        return List(4) { byAngle[(start + it) % 4] }
    }

    fun area(quad: List<QuadPoint>): Float {
        require(quad.size == 4)
        var sum = 0.0
        for (i in 0 until 4) {
            val a = quad[i]; val b = quad[(i + 1) % 4]
            sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(sum / 2).toFloat()
    }

    /** Strictly convex (no crossed or collapsed edges) and not vanishingly small. */
    fun isConvex(quad: List<QuadPoint>, minArea: Float = 1f): Boolean {
        if (quad.size != 4 || quad.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = quad[i]; val b = quad[(i + 1) % 4]; val c = quad[(i + 2) % 4]
            val cross = (b.x - a.x).toDouble() * (c.y - b.y) - (b.y - a.y).toDouble() * (c.x - b.x)
            if (abs(cross) < 1e-6) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return area(quad) >= minArea
    }

    private fun distance(a: QuadPoint, b: QuadPoint) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /** Output keeps the measured proportions of the photographed object, bounded to [maxEdge]. */
    fun outputSize(quad: List<QuadPoint>, maxEdge: Int = MAX_OUTPUT_EDGE): Pair<Int, Int> {
        require(quad.size == 4)
        val (tl, tr, br, bl) = quad
        val width = max(distance(tl, tr), distance(bl, br))
        val height = max(distance(tl, bl), distance(tr, br))
        require(width >= 1 && height >= 1) { "Área de corte muito pequena." }
        val scale = minOf(1.0, maxEdge / max(width, height))
        val w = (width * scale).roundToInt().coerceIn(MIN_OUTPUT_EDGE, maxEdge)
        val h = (height * scale).roundToInt().coerceIn(MIN_OUTPUT_EDGE, maxEdge)
        return w to h
    }

    fun fullQuad(width: Int, height: Int): List<QuadPoint> {
        val w = width.toFloat(); val h = height.toFloat()
        return listOf(QuadPoint(0f, 0f), QuadPoint(w, 0f), QuadPoint(w, h), QuadPoint(0f, h))
    }

    /** Centred frame that matches the on-screen camera guide. */
    fun defaultQuad(width: Int, height: Int, mode: PhotoCropMode): List<QuadPoint> {
        require(width > 0 && height > 0)
        val maxW = width * if (mode == PhotoCropMode.COVER) .82f else .34f
        val maxH = height * .86f
        var frameH = maxH; var frameW = frameH * mode.guideRatio
        if (frameW > maxW) { frameW = maxW; frameH = frameW / mode.guideRatio }
        val left = (width - frameW) / 2; val top = (height - frameH) / 2
        return listOf(QuadPoint(left, top), QuadPoint(left + frameW, top), QuadPoint(left + frameW, top + frameH), QuadPoint(left, top + frameH))
    }

    /** Maps a corner by 90° clockwise rotation of an image that was [width]×[height] before rotating. */
    fun rotateQuad90(quad: List<QuadPoint>, height: Int): List<QuadPoint> =
        orderQuad(quad.map { QuadPoint(height - it.y, it.x) })

    fun clamp(point: QuadPoint, width: Int, height: Int) =
        QuadPoint(point.x.coerceIn(0f, width.toFloat()), point.y.coerceIn(0f, height.toFloat()))

    fun normalizeQuad(quad: List<QuadPoint>, width: Int, height: Int): List<QuadPoint> {
        val w = width.coerceAtLeast(1).toFloat(); val h = height.coerceAtLeast(1).toFloat()
        return quad.map { QuadPoint((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) }
    }

    fun denormalizeQuad(normQuad: List<QuadPoint>, width: Int, height: Int): List<QuadPoint> =
        orderQuad(normQuad.map { QuadPoint((it.x * width).coerceIn(0f, width.toFloat()), (it.y * height).coerceIn(0f, height.toFloat())) })

    /** Exponential moving average smoothing for live preview quads (normalized [0..1] coordinates). */
    fun smoothQuad(previous: List<QuadPoint>?, next: List<QuadPoint>, alpha: Float = 0.36f): List<QuadPoint> {
        if (previous == null || previous.size != 4 || next.size != 4) return next
        val a = alpha.coerceIn(0.05f, 1f)
        return List(4) { i ->
            QuadPoint(
                previous[i].x + (next[i].x - previous[i].x) * a,
                previous[i].y + (next[i].y - previous[i].y) * a,
            )
        }
    }

    /** Mean Euclidean movement across the 4 corners (in normalized [0..1] units) for auto-capture stability. */
    fun quadDelta(a: List<QuadPoint>, b: List<QuadPoint>): Float {
        if (a.size != 4 || b.size != 4) return Float.MAX_VALUE
        var total = 0.0
        for (i in 0 until 4) total += distance(a[i], b[i])
        return (total / 4.0).toFloat()
    }

    /**
     * Downscales and rotates an NV21 Y-plane directly into an upright portrait/landscape grayscale frame
     * (≤ [maxEdge] on longest side) with zero Bitmap allocations — runs in ~2-5 ms on-device.
     */
    fun downsampleNv21Oriented(
        nv21: ByteArray,
        srcW: Int,
        srcH: Int,
        rotationDegrees: Int,
        mirrorX: Boolean = false,
        maxEdge: Int = 160,
    ): GrayFrame? {
        if (srcW <= 0 || srcH <= 0 || nv21.size < srcW * srcH) return null
        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val uprightW = if (swapped) srcH else srcW
        val uprightH = if (swapped) srcW else srcH
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(uprightW, uprightH))
        val outW = (uprightW * scale).roundToInt().coerceAtLeast(24)
        val outH = (uprightH * scale).roundToInt().coerceAtLeast(24)
        val luma = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val uy = ((oy + 0.5f) / outH * uprightH).toInt().coerceIn(0, uprightH - 1)
            for (ox in 0 until outW) {
                val rawUx = ((ox + 0.5f) / outW * uprightW).toInt().coerceIn(0, uprightW - 1)
                val ux = if (mirrorX) uprightW - 1 - rawUx else rawUx
                val sx: Int
                val sy: Int
                when (rot) {
                    90 -> { sx = uy; sy = srcH - 1 - ux }
                    180 -> { sx = srcW - 1 - ux; sy = srcH - 1 - uy }
                    270 -> { sx = srcW - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                luma[oy * outW + ox] = nv21[sy.coerceIn(0, srcH - 1) * srcW + sx.coerceIn(0, srcW - 1)].toInt() and 0xFF
            }
        }
        return GrayFrame(luma, outW, outH)
    }

    /** Separable 5x5 Gaussian-like blur ([1, 4, 6, 4, 1] / 16) to suppress lettering while keeping book edges. */
    private fun blur5x5(luma: IntArray, width: Int, height: Int): FloatArray {
        val tmp = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                fun s(dx: Int) = luma[row + (x + dx).coerceIn(0, width - 1)]
                tmp[row + x] = (s(-2) + 4 * s(-1) + 6 * s(0) + 4 * s(1) + s(2)) / 16f
            }
        }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                fun s(dy: Int) = tmp[(y + dy).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = (s(-2) + 4 * s(-1) + 6 * s(0) + 4 * s(1) + s(2)) / 16f
            }
        }
        return out
    }

    /**
     * Edge detection on a small luminance image (120–320 px).
     * - SPINE: searches within the plausible central band using edge continuity and parallelism,
     *   then locates top/bottom strictly inside the detected vertical spine strip.
     * - COVER: smoothed Sobel projections per half for mild perspective.
     * Returns null when unsure so the caller keeps the guide frame cleanly.
     */
    fun detectQuad(luma: IntArray, width: Int, height: Int, mode: PhotoCropMode): List<QuadPoint>? {
        if (width < 24 || height < 24 || luma.size != width * height) return null
        val smooth = blur5x5(luma, width, height)
        val gx = FloatArray(width * height); val gy = FloatArray(width * height)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            fun p(dx: Int, dy: Int) = smooth[(y + dy) * width + x + dx]
            gx[y * width + x] = abs((p(1, -1) + 2 * p(1, 0) + p(1, 1)) - (p(-1, -1) + 2 * p(-1, 0) + p(-1, 1)))
            gy[y * width + x] = abs((p(-1, 1) + 2 * p(0, 1) + p(1, 1)) - (p(-1, -1) + 2 * p(0, -1) + p(1, -1)))
        }
        return if (mode == PhotoCropMode.SPINE) detectSpine(gx, gy, width, height) else detectCover(gx, gy, width, height)
    }

    private fun detectSpine(gx: FloatArray, gy: FloatArray, width: Int, height: Int): List<QuadPoint>? {
        val midX = width / 2
        val midY = height / 2
        val yFrom = (height * .12f).toInt().coerceAtLeast(1)
        val yTo = (height * .88f).toInt().coerceAtMost(height - 2)
        val bandRows = (yTo - yFrom + 1).coerceAtLeast(1)
        val leftFrom = (width * .18f).toInt().coerceAtLeast(1)
        val leftTo = (midX - max(3, (width * .03f).toInt())).coerceAtLeast(leftFrom + 2)
        val rightFrom = (midX + max(3, (width * .03f).toInt())).coerceAtMost(width - 4)
        val rightTo = (width * .82f).toInt().coerceAtMost(width - 2)

        fun continuousVerticalEdge(rows: IntRange, from: Int, to: Int): Int? {
            if (to - from < 3) return null
            val profile = FloatArray(width)
            val count = (rows.last - rows.first + 1).coerceAtLeast(1)
            for (x in from..to) {
                var sum = 0f
                var strong = 0
                for (y in rows) {
                    val g = gx[y * width + x]
                    sum += g
                    if (g >= 22f) strong++
                }
                val continuity = strong.toFloat() / count
                profile[x] = sum * (0.35f + continuity)
            }
            return peak(profile, from, to, minRatio = 1.65f)
        }

        val topHalf = yFrom until midY
        val bottomHalf = midY..yTo
        val lt = continuousVerticalEdge(topHalf, leftFrom, leftTo) ?: return null
        val lb = continuousVerticalEdge(bottomHalf, leftFrom, leftTo) ?: return null
        val rt = continuousVerticalEdge(topHalf, rightFrom, rightTo) ?: return null
        val rb = continuousVerticalEdge(bottomHalf, rightFrom, rightTo) ?: return null

        // Reject wild non-parallel skew (typical false detection on neighboring books or background objects).
        val maxSkew = (width * 0.14f).coerceAtLeast(6f)
        if (abs(lt - lb) > maxSkew || abs(rt - rb) > maxSkew) return null

        val innerLeft = maxOf(lt, lb).coerceIn(1, width - 3)
        val innerRight = minOf(rt, rb).coerceIn(innerLeft + 2, width - 2)
        val spineW = ((rt - lt) + (rb - lb)) / 2f
        if (spineW < width * .05f || spineW > width * .52f) return null

        fun horizontalEdgeInStrip(fromY: Int, toY: Int): Int? {
            if (toY - fromY < 4) return null
            val profile = FloatArray(height)
            val cols = (innerRight - innerLeft + 1).coerceAtLeast(1)
            for (y in fromY..toY) {
                var sum = 0f
                var strong = 0
                for (x in innerLeft..innerRight) {
                    val g = gy[y * width + x]
                    sum += g
                    if (g >= 18f) strong++
                }
                profile[y] = sum * (0.4f + strong.toFloat() / cols)
            }
            return peak(profile, fromY, toY, minRatio = 1.55f)
        }

        val topY = horizontalEdgeInStrip(1, (height * .35f).toInt().coerceAtLeast(6)) ?: (height * .07f).toInt()
        val botY = horizontalEdgeInStrip((height * .65f).toInt().coerceAtMost(height - 7), height - 2) ?: (height * .93f).toInt()
        val spineH = (botY - topY).toFloat()
        if (spineH < bandRows * .45f) return null
        val ratio = spineW / spineH
        if (ratio < .05f || ratio > .50f) return null

        val quad = listOf(
            QuadPoint(lt.toFloat(), topY.toFloat()),
            QuadPoint(rt.toFloat(), topY.toFloat()),
            QuadPoint(rb.toFloat(), botY.toFloat()),
            QuadPoint(lb.toFloat(), botY.toFloat()),
        )
        if (!isConvex(quad) || area(quad) < width * height * .025f) return null
        return orderQuad(quad)
    }

    private fun detectCover(gx: FloatArray, gy: FloatArray, width: Int, height: Int): List<QuadPoint>? {
        fun columnEdge(rows: IntRange, from: Int, to: Int): Int? {
            val profile = FloatArray(width)
            for (x in from..to) { var s = 0f; for (y in rows) s += gx[y * width + x]; profile[x] = s }
            return peak(profile, from, to)
        }
        fun rowEdge(cols: IntRange, from: Int, to: Int): Int? {
            val profile = FloatArray(height)
            for (y in from..to) { var s = 0f; for (x in cols) s += gy[y * width + x]; profile[y] = s }
            return peak(profile, from, to)
        }
        val midX = width / 2; val midY = height / 2
        val topRows = 1 until midY; val bottomRows = midY until height - 1
        val leftCols = 1 until midX; val rightCols = midX until width - 1
        val lt = columnEdge(topRows, 1, midX - 2) ?: return null
        val lb = columnEdge(bottomRows, 1, midX - 2) ?: return null
        val rt = columnEdge(topRows, midX + 2, width - 2) ?: return null
        val rb = columnEdge(bottomRows, midX + 2, width - 2) ?: return null
        val tl = rowEdge(leftCols, 1, midY - 2) ?: return null
        val tr = rowEdge(rightCols, 1, midY - 2) ?: return null
        val bl = rowEdge(leftCols, midY + 2, height - 2) ?: return null
        val br = rowEdge(rightCols, midY + 2, height - 2) ?: return null
        val quad = listOf(
            QuadPoint(lt.toFloat(), tl.toFloat()), QuadPoint(rt.toFloat(), tr.toFloat()),
            QuadPoint(rb.toFloat(), br.toFloat()), QuadPoint(lb.toFloat(), bl.toFloat()),
        )
        if (!isConvex(quad) || area(quad) < width * height * .12f) return null
        return orderQuad(quad)
    }

    /** Index of a clear maximum (≥ [minRatio]× the mean of the searched range), smoothed over 3 samples. */
    private fun peak(profile: FloatArray, from: Int, to: Int, minRatio: Float = 1.8f): Int? {
        if (to - from < 4) return null
        var best = -1; var bestValue = 0f; var total = 0f
        for (i in from..to) {
            val v = profile[i] + .5f * (profile.getOrElse(i - 1) { 0f } + profile.getOrElse(i + 1) { 0f })
            total += v
            if (v > bestValue) { bestValue = v; best = i }
        }
        val mean = total / (to - from + 1)
        return if (best >= 0 && mean > 0f && bestValue >= mean * minRatio) best else null
    }
}

/**
 * Eliminates capacitive touchscreen lift-off drift (the 40–70 ms centroid shift right as a finger
 * leaves the glass) and dampens tiny finger tremors while aiming inside the loupe.
 */
class TouchStabilizer(
    private val liftOffWindowMs: Long = 65L,
    private val holdLookbackMs: Long = 160L,
) {
    private data class Sample(val timeMs: Long, val quad: List<QuadPoint>)
    private val history = ArrayDeque<Sample>(24)

    fun reset(timeMs: Long, quad: List<QuadPoint>) {
        history.clear()
        history.addLast(Sample(timeMs, quad))
    }

    fun record(timeMs: Long, quad: List<QuadPoint>) {
        history.addLast(Sample(timeMs, quad))
        while (history.size > 24) history.removeFirst()
    }

    /**
     * Resolves the final quad on finger lift-off: if the user paused or moved slowly to aim
     * before the final [liftOffWindowMs], roll back any lift-off drift that occurred in those last ms.
     * Continuous fast drags keep the final release position untouched.
     */
    fun resolveRelease(releaseTimeMs: Long, currentQuad: List<QuadPoint>, imageDiag: Float): List<QuadPoint> {
        if (history.size < 2 || imageDiag <= 1f) return currentQuad
        val cutoff = releaseTimeMs - liftOffWindowMs
        val anchorIdx = history.indexOfLast { it.timeMs <= cutoff }
        if (anchorIdx < 0) return currentQuad
        val anchor = history[anchorIdx]
        val drift = PerspectiveGeometry.quadDelta(anchor.quad, currentQuad)
        if (drift <= 0.0002f * imageDiag) return currentQuad

        // Case 1: Finger was completely still (no MOVE events for >= 65 ms), then spiked right on lift-off.
        val nextAfterAnchor = history.getOrNull(anchorIdx + 1)
        if (nextAfterAnchor != null && nextAfterAnchor.timeMs - anchor.timeMs >= liftOffWindowMs) {
            return anchor.quad
        }

        // Case 2: User was aiming slowly / holding nearly steady before the lift-off window.
        val holdStart = anchor.timeMs - holdLookbackMs
        val preSample = history.firstOrNull { it.timeMs >= holdStart } ?: anchor
        val preDt = (anchor.timeMs - preSample.timeMs).coerceAtLeast(1L)
        val preMove = PerspectiveGeometry.quadDelta(preSample.quad, anchor.quad)
        val preSpeedPer100Ms = (preMove / imageDiag) * (100f / preDt)
        val isSlowAiming = preSpeedPer100Ms <= 0.018f
        // Only roll back plausible lift-off drifts (<= 8% of image diagonal), not intentional big jumps.
        return if (isSlowAiming && drift <= 0.08f * imageDiag) anchor.quad else currentQuad
    }

    companion object {
        /** Dampens tiny slow movements (< fineThresholdPx) to 0.55x for steady alignment inside the loupe. */
        fun dampenDelta(dxPx: Float, dyPx: Float, fineThresholdPx: Float): Pair<Float, Float> {
            if (fineThresholdPx <= 0f) return dxPx to dyPx
            val dist = hypot(dxPx.toDouble(), dyPx.toDouble()).toFloat()
            if (dist <= 0f) return 0f to 0f
            val factor = when {
                dist <= fineThresholdPx -> 0.55f
                dist >= fineThresholdPx * 2f -> 1f
                else -> 0.55f + 0.45f * ((dist - fineThresholdPx) / fineThresholdPx)
            }
            return (dxPx * factor) to (dyPx * factor)
        }
    }
}
