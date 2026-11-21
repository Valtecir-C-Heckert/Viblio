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

/**
 * Pure geometry for the 4-corner cropper (JVM-testable, no Android graphics).
 * Quads are always ordered top-left, top-right, bottom-right, bottom-left.
 */
object PerspectiveGeometry {
    /** Longest output edge; keeps embedded sync images far below the 2048 px / 12 MB server limits. */
    const val MAX_OUTPUT_EDGE = 1600
    const val MIN_OUTPUT_EDGE = 16

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

    /** Output keeps the measured proportions of the photographed object, bounded to [MAX_OUTPUT_EDGE]. */
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

    /**
     * Lightweight margin detection on a small luminance image (≤ ~320 px): Sobel edges projected per half,
     * so each corner gets its own line estimate (captures mild perspective). Returns null when unsure.
     */
    fun detectQuad(luma: IntArray, width: Int, height: Int, mode: PhotoCropMode): List<QuadPoint>? {
        if (width < 24 || height < 24 || luma.size != width * height) return null
        val gx = FloatArray(width * height); val gy = FloatArray(width * height)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            fun p(dx: Int, dy: Int) = luma[(y + dy) * width + x + dx].toFloat()
            gx[y * width + x] = abs((p(1, -1) + 2 * p(1, 0) + p(1, 1)) - (p(-1, -1) + 2 * p(-1, 0) + p(-1, 1)))
            gy[y * width + x] = abs((p(-1, 1) + 2 * p(0, 1) + p(1, 1)) - (p(-1, -1) + 2 * p(0, -1) + p(1, -1)))
        }
        val spine = mode == PhotoCropMode.SPINE
        val xMin = if (spine) (width * .08f).toInt() else 1
        val xMax = if (spine) (width * .92f).toInt() else width - 2
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
        val lt = columnEdge(topRows, xMin, midX - 2) ?: return null
        val lb = columnEdge(bottomRows, xMin, midX - 2) ?: return null
        val rt = columnEdge(topRows, midX + 2, xMax) ?: return null
        val rb = columnEdge(bottomRows, midX + 2, xMax) ?: return null
        val tl = rowEdge(leftCols, 1, midY - 2) ?: return null
        val tr = rowEdge(rightCols, 1, midY - 2) ?: return null
        val bl = rowEdge(leftCols, midY + 2, height - 2) ?: return null
        val br = rowEdge(rightCols, midY + 2, height - 2) ?: return null
        val quad = listOf(
            QuadPoint(lt.toFloat(), tl.toFloat()), QuadPoint(rt.toFloat(), tr.toFloat()),
            QuadPoint(rb.toFloat(), br.toFloat()), QuadPoint(lb.toFloat(), bl.toFloat()),
        )
        val minShare = if (spine) .03f else .12f
        if (!isConvex(quad) || area(quad) < width * height * minShare) return null
        return orderQuad(quad)
    }

    /** Index of a clear maximum (≥ 1.8× the mean of the searched range), smoothed over 3 samples. */
    private fun peak(profile: FloatArray, from: Int, to: Int): Int? {
        if (to - from < 4) return null
        var best = -1; var bestValue = 0f; var total = 0f
        for (i in from..to) {
            val v = profile[i] + .5f * (profile.getOrElse(i - 1) { 0f } + profile.getOrElse(i + 1) { 0f })
            total += v
            if (v > bestValue) { bestValue = v; best = i }
        }
        val mean = total / (to - from + 1)
        return if (best >= 0 && mean > 0f && bestValue >= mean * 1.8f) best else null
    }
}
