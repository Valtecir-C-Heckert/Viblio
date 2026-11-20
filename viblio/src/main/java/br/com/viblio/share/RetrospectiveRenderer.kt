package br.com.viblio.share

import android.content.Context
import android.graphics.*
import br.com.centralcasa.books.BookImages
import br.com.centralcasa.books.RetrospectiveData
import java.text.NumberFormat
import java.util.Locale

enum class RetrospectiveFormat(val label: String, val width: Int, val height: Int) {
    STORIES("Stories 9:16", 1080, 1920),
    FEED("Feed 4:5", 1080, 1350),
}

/**
 * Renders an editorial high-resolution PNG card for the user's yearly or monthly Reading Retrospective.
 */
object RetrospectiveRenderer {
    fun render(
        context: Context,
        data: RetrospectiveData,
        format: RetrospectiveFormat,
        accentColor: Int = 0xFFC9A77C.toInt(),
    ): Bitmap {
        val w = format.width
        val h = format.height
        val stories = format == RetrospectiveFormat.STORIES
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val images = BookImages(context)
        val numbers = NumberFormat.getIntegerInstance(Locale("pt", "BR"))

        val accent = normalizeAccent(accentColor)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF191612.toInt(), 0xFF101010.toInt(), 0xFF15120F.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Subtle outer editorial frame
        val frameInset = 44f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
        c.drawRect(frameInset, frameInset, w - frameInset, h - frameInset, borderPaint)

        val padX = 88f
        val contentW = w - padX * 2f
        var y = if (stories) 128f else 108f

        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8C877F.toInt()
            textSize = 26f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = 26f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        c.drawText("VIBLIO · RETROSPECTIVA", padX, y, kickerPaint)
        val periodW = mutedPaint.measureText(data.periodLabel.uppercase(Locale("pt", "BR")))
        c.drawText(data.periodLabel.uppercase(Locale("pt", "BR")), w - padX - periodW, y, mutedPaint)

        y += if (stories) 94f else 78f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF0ECE4.toInt()
            textSize = if (stories) 76f else 64f
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        c.drawText(ellipsize(data.title, titlePaint, contentW), padX, y, titlePaint)

        // Covers Mosaic (4 columns x 2 rows in Stories, or 4 x 1 in Feed)
        y += if (stories) 56f else 42f
        val cols = 4
        val maxRows = if (stories && data.showcaseBooks.size > 4) 2 else 1
        val maxSlots = cols * maxRows
        val gap = 22f
        val tileW = (contentW - gap * (cols - 1)) / cols
        val tileH = tileW * 1.48f

        if (data.showcaseBooks.isNotEmpty()) {
            val totalShown = minOf(data.showcaseBooks.size, maxSlots)
            val hasOverflow = data.showcaseBooks.size > maxSlots
            val actualRows = if (totalShown > cols) 2 else 1
            for (i in 0 until totalShown) {
                val r = i / cols
                val colIdx = i % cols
                val left = padX + colIdx * (tileW + gap)
                val top = y + r * (tileH + gap)
                val rect = RectF(left, top, left + tileW, top + tileH)
                if (hasOverflow && i == maxSlots - 1) {
                    val moreCount = data.showcaseBooks.size - (maxSlots - 1)
                    val boxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1915.toInt() }
                    c.drawRect(rect, boxP)
                    c.drawRect(rect, borderPaint)
                    val moreP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = accent
                        textSize = 54f
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("serif", Typeface.NORMAL)
                    }
                    c.drawText("+$moreCount", rect.centerX(), rect.centerY() + 18f, moreP)
                } else {
                    val book = data.showcaseBooks[i]
                    drawCoverTile(c, images, book.cover, book.title, rect, accent)
                }
            }
            y += actualRows * tileH + (actualRows - 1) * gap + if (stories) 64f else 48f
        } else {
            val emptyBox = RectF(padX, y, w - padX, y + 180f)
            c.drawRect(emptyBox, Paint().apply { color = 0xFF161616.toInt() })
            c.drawRect(emptyBox, borderPaint)
            val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8C877F.toInt()
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            c.drawText("Nenhuma leitura registrada neste período", emptyBox.centerX(), emptyBox.centerY() + 10f, ep)
            y += 230f
        }

        // Hairline divider
        val linePaint = Paint().apply { color = 0xFF282828.toInt(); strokeWidth = 2f }
        c.drawLine(padX, y, w - padX, y, linePaint)
        y += if (stories) 76f else 62f

        // 4 Big Editorial Numbers (2x2 grid)
        val colW = contentW / 2f
        val numAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = if (stories) 82f else 68f
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        val numWhite = Paint(numAccent).apply { color = 0xFFF0ECE4.toInt() }
        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8C877F.toInt()
            textSize = 24f
            letterSpacing = 0.08f
        }

        // Row 1: Books completed + Pages read
        c.drawText(numbers.format(data.completedCount), padX, y, numAccent)
        c.drawText(if (data.completedCount == 1) "LIVRO CONCLUÍDO" else "LIVROS CONCLUÍDOS", padX, y + 36f, capPaint)

        c.drawText(numbers.format(data.totalPagesRead), padX + colW, y, numWhite)
        c.drawText("PÁGINAS LIDAS", padX + colW, y + 36f, capPaint)

        y += if (stories) 144f else 118f

        // Row 2: Reading time + Active days & best streak
        val timeStr = formatTime(data.totalDurationMs)
        c.drawText(timeStr, padX, y, numWhite)
        c.drawText("TEMPO CRONOMETRADO", padX, y + 36f, capPaint)

        val daysStr = "${numbers.format(data.activeDays)} dias"
        c.drawText(daysStr, padX + colW, y, numWhite)
        val streakCap = if (data.bestStreakInPeriod > 1) "SEQUÊNCIA MÁX. ${data.bestStreakInPeriod}D" else "DIAS COM LEITURA"
        c.drawText(streakCap, padX + colW, y + 36f, capPaint)

        y += if (stories) 94f else 72f

        // Highlights Card (Best Rated Book + Favorite Genre)
        if (data.topRatedBook != null || data.topGenres.isNotEmpty()) {
            val boxH = if (data.topRatedBook != null && data.topGenres.isNotEmpty()) {
                if (stories) 210f else 178f
            } else {
                122f
            }
            val box = RectF(padX, y, w - padX, y + boxH)
            c.drawRect(box, Paint().apply { color = 0xFF161616.toInt() })
            c.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF282828.toInt() })
            c.drawRect(box.left, box.top, box.left + 8f, box.bottom, Paint().apply { color = accent })

            var hy = y + 52f
            val innerX = padX + 34f
            val innerW = contentW - 68f
            data.topRatedBook?.let { top ->
                val ratingStr = top.rating?.let { String.format(Locale("pt", "BR"), "★ %.1f", it) }.orEmpty()
                c.drawText("MELHOR AVALIADO", innerX, hy, capPaint)
                if (ratingStr.isNotBlank()) {
                    val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 28f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
                    c.drawText(ratingStr, box.right - 34f - rp.measureText(ratingStr), hy, rp)
                }
                hy += 48f
                val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0ECE4.toInt(); textSize = 36f; typeface = Typeface.create("serif", Typeface.NORMAL) }
                val label = listOf(top.title, top.author).filter { it.isNotBlank() }.joinToString(" · ")
                c.drawText(ellipsize(label, bp, innerW), innerX, hy, bp)
                hy += 56f
            }
            if (data.topGenres.isNotEmpty()) {
                val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C877F.toInt(); textSize = 26f }
                val prefix = "Gênero favorito: "
                c.drawText(prefix, innerX, hy, gp)
                val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0ECE4.toInt(); textSize = 26f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
                val genreText = data.topGenres.joinToString(" · ")
                c.drawText(ellipsize(genreText, valP, innerW - gp.measureText(prefix)), innerX + gp.measureText(prefix), hy, valP)
            }
        }

        // Footer
        val footerY = h - 94f
        c.drawLine(padX, footerY - 46f, w - padX, footerY - 46f, linePaint)
        c.drawText("VIBLIO", padX, footerY, mutedPaint)
        val rightFoot = data.periodLabel.uppercase(Locale("pt", "BR"))
        c.drawText(rightFoot, w - padX - mutedPaint.measureText(rightFoot), footerY, mutedPaint)

        return bmp
    }

    private fun drawCoverTile(
        c: Canvas,
        images: BookImages,
        coverName: String,
        title: String,
        rect: RectF,
        accent: Int,
    ) {
        val bmp = if (coverName.isNotBlank()) runCatching { images.thumbnail(coverName) }.getOrNull() else null
        if (bmp != null) {
            // Center-crop cover cleanly into rect
            val srcRatio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
            val dstRatio = rect.width() / rect.height().coerceAtLeast(1f)
            val srcRect = if (srcRatio > dstRatio) {
                val cropW = (bmp.height * dstRatio).toInt().coerceIn(1, bmp.width)
                val left = (bmp.width - cropW) / 2
                Rect(left, 0, left + cropW, bmp.height)
            } else {
                val cropH = (bmp.width / dstRatio).toInt().coerceIn(1, bmp.height)
                val top = (bmp.height - cropH) / 2
                Rect(0, top, bmp.width, top + cropH)
            }
            c.drawBitmap(bmp, srcRect, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawRect(rect, Paint().apply { color = 0xFF1D1B18.toInt() })
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = 26f
                typeface = Typeface.create("serif", Typeface.NORMAL)
            }
            c.drawText(ellipsize(title, tp, rect.width() - 24f), rect.left + 12f, rect.bottom - 24f, tp)
        }
        c.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x33FFFFFF })
    }

    private fun formatTime(ms: Long): String {
        val sec = ms.coerceAtLeast(0L) / 1000L
        val h = sec / 3600L
        val m = (sec / 60L) % 60L
        return when {
            h > 0L -> if (m == 0L) "${h}h" else "${h}h ${m.toString().padStart(2, '0')}m"
            m > 0L -> "${m} min"
            else -> "0 min"
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun normalizeAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.60f)
        hsv[2] = hsv[2].coerceAtLeast(0.76f)
        return Color.HSVToColor(hsv)
    }
}
