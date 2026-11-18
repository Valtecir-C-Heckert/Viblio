package br.com.centralcasa.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln

/** Discrete zoom, skipping densities which cannot change the visible grid on this screen. */
object ShelfPinch {
    fun columns(current: Int, scale: Double, widthDp: Float, fontScale: Float, step:Double=1.2,maxSteps:Int=5): Int {
        if (!scale.isFinite() || scale <= 0 || !step.isFinite() || step<=1) return current
        val magnitude = ln(scale) / ln(step)
        val steps = floor(abs(magnitude) + 1e-8).toInt().coerceAtMost(maxSteps.coerceIn(1,5))
        val direction = if (scale > 1) -1 else 1
        var result = current.coerceIn(1, 6)
        repeat(steps) {
            var request = result + direction
            while (request in 1..6) {
                val fitting = ShelfLayout.columns(widthDp, request, fontScale)
                if ((fitting - result) * direction > 0) { result = fitting; break }
                request += direction
            }
        }
        return result
    }
}
