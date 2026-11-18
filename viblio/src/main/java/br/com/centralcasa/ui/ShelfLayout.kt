package br.com.centralcasa.ui

/** Preserve readable card widths instead of squeezing the requested columns onto a phone. */
object ShelfLayout {
    fun columns(availableWidthDp: Float, requested: Int, fontScale: Float): Int {
        if (requested >= 5) {
            val compact = ((availableWidthDp.coerceAtLeast(0f) + 4f) / 52f).toInt()
            if (compact >= 5) return minOf(requested.coerceAtMost(6), compact)
        }
        val minimumWidth = if (requested == 4) 72f else 96f * fontScale.coerceAtLeast(1f)
        val fitting = ((availableWidthDp.coerceAtLeast(0f) + 8f) / (minimumWidth + 8f)).toInt().coerceAtLeast(1)
        return minOf(requested.coerceIn(1, 6), fitting)
    }
}
