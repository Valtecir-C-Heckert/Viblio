package br.com.centralcasa.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/** A 20% blurred layer over 80% original artwork. Does not modify or recycle the source. */
object CoverBlur {
    const val MAX_EDGE = 1920
    const val BLUR_ALPHA = 51 // 20% of 255; the original remains visible underneath.
    fun render(source: Bitmap, darkOverlay: Int = 0xB3000000.toInt()): Bitmap {
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val blurWidth = minOf(80, width)
        val blurHeight = (blurWidth.toFloat() * height / width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, blurWidth, blurHeight, true)
        val pixels = IntArray(blurWidth * blurHeight)
        small.getPixels(pixels, 0, blurWidth, 0, 0, blurWidth, blurHeight)
        repeat(2) {
            val previous = pixels.copyOf()
            for (y in 0 until blurHeight) for (x in 0 until blurWidth) {
                var red = 0; var green = 0; var blue = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val pixel = previous[(y + dy).coerceIn(0, blurHeight - 1) * blurWidth + (x + dx).coerceIn(0, blurWidth - 1)]
                    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
                }
                pixels[y * blurWidth + x] = Color.rgb(red / 9, green / 9, blue / 9)
            }
        }
        val blurred = Bitmap.createBitmap(pixels, blurWidth, blurHeight, Bitmap.Config.ARGB_8888)
        // Canvas always receives a mutable target, including very small covers.
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result); val target = Rect(0, 0, width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, target, paint)
        paint.alpha = BLUR_ALPHA; canvas.drawBitmap(blurred, null, target, paint)
        canvas.drawColor(darkOverlay)
        if (small !== source) small.recycle()
        blurred.recycle()
        return result
    }

    /**
     * Deep atmospheric 100% blur with 1.25x saturation boost (matching `blur(26px) saturate(1.25)`),
     * with no sharp artwork underneath so foreground typography stays crisp and legible.
     */
    fun renderAtmospheric(source: Bitmap): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val blurWidth = minOf(44, w).coerceAtLeast(1)
        val blurHeight = (blurWidth.toFloat() * h / w).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, blurWidth, blurHeight, true)
        val pixels = IntArray(blurWidth * blurHeight)
        small.getPixels(pixels, 0, blurWidth, 0, 0, blurWidth, blurHeight)
        repeat(4) {
            val previous = pixels.copyOf()
            for (y in 0 until blurHeight) for (x in 0 until blurWidth) {
                var red = 0; var green = 0; var blue = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val pixel = previous[(y + dy).coerceIn(0, blurHeight - 1) * blurWidth + (x + dx).coerceIn(0, blurWidth - 1)]
                    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
                }
                pixels[y * blurWidth + x] = Color.rgb(red / 9, green / 9, blue / 9)
            }
        }
        val blurred = Bitmap.createBitmap(pixels, blurWidth, blurHeight, Bitmap.Config.ARGB_8888)
        val outWidth = minOf(360, maxOf(blurWidth * 4, 180))
        val outHeight = (outWidth.toFloat() * h / w).toInt().coerceAtLeast(1)
        val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setSaturation(1.25f) }
            )
        }
        canvas.drawBitmap(blurred, null, Rect(0, 0, outWidth, outHeight), paint)
        if (small !== source) small.recycle()
        blurred.recycle()
        return result
    }
}
