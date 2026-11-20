package br.com.viblio.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import br.com.centralcasa.R
import br.com.centralcasa.books.BookImages
import br.com.centralcasa.books.LibraryBook
import br.com.centralcasa.books.LibraryRepository
import br.com.viblio.ViblioActivity

enum class WidgetSize { SIZE_1X2, SIZE_2X2, SIZE_3X2, SIZE_4X2 }

/**
 * Shared editorial Home Screen widget provider supporting 1x2, 2x2, 3x2 and 4x2 sizes
 * with dynamic resize adaptation, real current-book cover (memory-bounded for RemoteViews IPC),
 * and cover-derived accent color.
 */
abstract class BaseViblioReadingWidget(private val defaultSize: WidgetSize) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val opts = appWidgetManager.getAppWidgetOptions(id)
            updateOne(context, appWidgetManager, id, resolveSize(opts, defaultSize))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateOne(context, appWidgetManager, appWidgetId, resolveSize(newOptions, defaultSize))
    }

    private fun resolveSize(options: Bundle?, fallback: WidgetSize): WidgetSize {
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        if (minW <= 0) return fallback
        return when {
            minW < 105 -> WidgetSize.SIZE_1X2
            minW < 175 -> WidgetSize.SIZE_2X2
            minW < 245 -> WidgetSize.SIZE_3X2
            else -> WidgetSize.SIZE_4X2
        }
    }

    companion object {
        private const val DEFAULT_ACCENT = 0xFFC9A77C.toInt()
        private const val TRACK_COLOR = 0xFF282828.toInt()
        private const val MAX_COVER_W = 240
        private const val MAX_COVER_H = 360

        fun refreshAll(context: Context) {
            val appCtx = context.applicationContext
            val manager = runCatching { AppWidgetManager.getInstance(appCtx) }.getOrNull() ?: return
            val providers = listOf(
                ViblioWidget1x2::class.java to WidgetSize.SIZE_1X2,
                ViblioWidget2x2::class.java to WidgetSize.SIZE_2X2,
                ViblioWidget3x2::class.java to WidgetSize.SIZE_3X2,
                ViblioWidget4x2::class.java to WidgetSize.SIZE_4X2,
            )
            providers.forEach { (cls, defSize) ->
                val ids = runCatching { manager.getAppWidgetIds(ComponentName(appCtx, cls)) }.getOrNull() ?: return@forEach
                ids.forEach { id ->
                    val opts = manager.getAppWidgetOptions(id)
                    val minW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                    val size = if (minW <= 0) defSize else when {
                        minW < 105 -> WidgetSize.SIZE_1X2
                        minW < 175 -> WidgetSize.SIZE_2X2
                        minW < 245 -> WidgetSize.SIZE_3X2
                        else -> WidgetSize.SIZE_4X2
                    }
                    runCatching { updateOne(appCtx, manager, id, size) }
                }
            }
        }

        private fun boundedCover(images: BookImages, coverName: String): Bitmap? {
            if (coverName.isBlank()) return null
            val raw = runCatching { images.thumbnail(coverName) }.getOrNull() ?: return null
            if (raw.width <= MAX_COVER_W && raw.height <= MAX_COVER_H) return raw
            val scale = minOf(MAX_COVER_W.toFloat() / raw.width.coerceAtLeast(1), MAX_COVER_H.toFloat() / raw.height.coerceAtLeast(1))
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(raw, w, h, true)
        }

        private fun progressBitmap(percent: Int, accent: Int): Bitmap {
            val w = 240
            val h = 10
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint()
            p.color = TRACK_COLOR
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            val fill = (w * percent.coerceIn(0, 100) / 100f)
            if (fill > 0f) {
                p.color = accent
                c.drawRect(0f, 0f, fill, h.toFloat(), p)
            }
            return bmp
        }

        private fun solidBitmap(color: Int): Bitmap {
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(color)
            return bmp
        }

        private fun placeholderCover(title: String, accent: Int): Bitmap {
            val bmp = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF1A1A1A.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = 54f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.SERIF
            }
            val letter = title.trim().take(1).uppercase().ifBlank { "V" }
            c.drawText(letter, 60f, 108f, p)
            return bmp
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int, size: WidgetSize) {
            val layoutId = when (size) {
                WidgetSize.SIZE_1X2 -> R.layout.widget_reading_1x2
                WidgetSize.SIZE_2X2 -> R.layout.widget_reading_2x2
                WidgetSize.SIZE_3X2 -> R.layout.widget_reading_3x2
                WidgetSize.SIZE_4X2 -> R.layout.widget_reading_4x2
            }
            val views = RemoteViews(context.packageName, layoutId)

            val repo = LibraryRepository(context)
            val book: LibraryBook? = try {
                repo.current()
            } finally {
                runCatching { repo.close() }
            }
            val images = BookImages(context)
            val accent = book?.cover?.takeIf { it.isNotBlank() }?.let {
                runCatching { images.coverColor(it) }.getOrNull()
            } ?: DEFAULT_ACCENT
            // Keep accent readable on dark editorial background (#101010)
            val readableAccent = brightenForDark(accent)

            val pct = book?.percent?.coerceIn(0, 100) ?: 0
            val coverBmp = book?.let { boundedCover(images, it.cover) }
                ?: placeholderCover(book?.title.orEmpty(), readableAccent)

            views.setImageViewBitmap(R.id.widget_cover, coverBmp)
            views.setImageViewBitmap(R.id.widget_progress_img, progressBitmap(pct, readableAccent))

            if (size != WidgetSize.SIZE_1X2) {
                views.setImageViewBitmap(R.id.widget_accent_bar, solidBitmap(readableAccent))
                views.setTextColor(R.id.widget_kicker, readableAccent)
                if (book != null) {
                    views.setTextViewText(R.id.widget_kicker, if (size == WidgetSize.SIZE_2X2) "LENDO" else "LENDO AGORA")
                    views.setTextViewText(R.id.widget_title, book.title)
                    views.setTextViewText(R.id.widget_author, book.author.ifBlank { book.saga })
                    val pagesText = when {
                        !book.pagesKnown -> if (book.complete) "Concluído" else "Em andamento"
                        size == WidgetSize.SIZE_2X2 -> "${book.currentPage} / ${book.totalPages} ›"
                        size == WidgetSize.SIZE_3X2 -> "${book.currentPage}/${book.totalPages} · $pct%"
                        else -> "${book.currentPage} / ${book.totalPages} págs · $pct%"
                    }
                    views.setTextViewText(R.id.widget_pages, pagesText)
                } else {
                    views.setTextViewText(R.id.widget_kicker, "VIBLIO")
                    views.setTextViewText(R.id.widget_title, "Nenhuma leitura atual")
                    views.setTextViewText(R.id.widget_author, "Toque para abrir a Estante")
                    views.setTextViewText(R.id.widget_pages, "")
                }
            }

            if (size == WidgetSize.SIZE_1X2 || size == WidgetSize.SIZE_2X2) {
                views.setTextColor(R.id.widget_percent, readableAccent)
                views.setTextViewText(R.id.widget_percent, if (book != null) "$pct%" else "—")
            }
            if (size == WidgetSize.SIZE_4X2) {
                views.setTextColor(R.id.widget_cta, readableAccent)
                views.setTextViewText(R.id.widget_cta, if (book != null) "Continuar ›" else "Abrir ›")
            }

            val openIntent = if (book != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse("viblio://current"), context, ViblioActivity::class.java)
            } else {
                Intent(context, ViblioActivity::class.java)
            }.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getActivity(context, widgetId, openIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_root, pending)

            manager.updateAppWidget(widgetId, views)
        }

        private fun brightenForDark(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = hsv[1].coerceAtMost(0.65f)
            hsv[2] = hsv[2].coerceAtLeast(0.76f)
            return Color.HSVToColor(hsv)
        }
    }
}

class ViblioWidget1x2 : BaseViblioReadingWidget(WidgetSize.SIZE_1X2)
class ViblioWidget2x2 : BaseViblioReadingWidget(WidgetSize.SIZE_2X2)
class ViblioWidget3x2 : BaseViblioReadingWidget(WidgetSize.SIZE_3X2)
class ViblioWidget4x2 : BaseViblioReadingWidget(WidgetSize.SIZE_4X2)
