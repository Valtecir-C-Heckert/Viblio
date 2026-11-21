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
import br.com.viblio.ui.ViblioBookMarkDrawable

enum class WidgetSize { SIZE_1X1, SIZE_2X1, SIZE_3X1, SIZE_4X1, SIZE_5X1 }

/**
 * Shared 1-row editorial Home Screen widget provider supporting 1x1, 2x1, 3x1, 4x1 and 5x1 sizes
 * with width-only resize adaptation (height locked to one row), real current-book cover (memory-bounded for RemoteViews IPC),
 * cover-derived accent color, and the Viblio book icon (book cover color + white pages) beside VIBLIO.
 */
abstract class BaseViblioReadingWidget(private val defaultSize: WidgetSize) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        val ids = appWidgetIds.copyOf()
        widgetExecutor.execute {
            try {
                val snap = loadSnapshot(appCtx)
                try {
                    ids.forEach { id ->
                        val opts = appWidgetManager.getAppWidgetOptions(id)
                        runCatching { updateOne(appCtx, appWidgetManager, id, resolveSize(opts, defaultSize), snap) }
                    }
                } finally {
                    snap.releaseTempCover()
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        val appCtx = context.applicationContext
        val size = resolveSize(newOptions, defaultSize)
        widgetExecutor.execute {
            try {
                val snap = loadSnapshot(appCtx)
                try {
                    runCatching { updateOne(appCtx, appWidgetManager, appWidgetId, size, snap) }
                } finally {
                    snap.releaseTempCover()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun resolveSize(options: Bundle?, fallback: WidgetSize): WidgetSize {
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        return resolveWidgetSize(minW, fallback)
    }

    private class WidgetSnapshot(
        val book: LibraryBook?,
        val readableAccent: Int,
        val coverBmp: Bitmap,
        val ownsCover: Boolean,
    ) {
        fun releaseTempCover() {
            if (ownsCover && !coverBmp.isRecycled) coverBmp.recycle()
        }
    }

    companion object {
        private const val DEFAULT_ACCENT = 0xFFC9A77C.toInt()
        private const val TRACK_COLOR = 0xFF282828.toInt()
        private const val MAX_COVER_W = 240
        private const val MAX_COVER_H = 360
        private val widgetExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        internal fun resolveWidgetSize(minWidthDp: Int, fallback: WidgetSize): WidgetSize {
            if (minWidthDp <= 0) return fallback
            return when {
                minWidthDp < 100 -> WidgetSize.SIZE_1X1
                minWidthDp < 165 -> WidgetSize.SIZE_2X1
                minWidthDp < 230 -> WidgetSize.SIZE_3X1
                minWidthDp < 295 -> WidgetSize.SIZE_4X1
                else -> WidgetSize.SIZE_5X1
            }
        }

        internal fun brandMarkBitmap(coverColor: Int, sizePx: Int = 48): Bitmap {
            val s = sizePx.coerceIn(16, 128)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val drawable = ViblioBookMarkDrawable(coverColor, Color.WHITE)
            drawable.setBounds(0, 0, s, s)
            drawable.draw(canvas)
            return bmp
        }

        private fun loadSnapshot(context: Context): WidgetSnapshot {
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
            val readableAccent = brightenForDark(accent)
            val (coverBmp, ownsCover) = book?.let { boundedCover(images, it.cover) }
                ?: (placeholderCover(book?.title.orEmpty(), readableAccent) to true)
            return WidgetSnapshot(book, readableAccent, coverBmp, ownsCover)
        }

        fun refreshAll(context: Context) {
            val appCtx = context.applicationContext
            runCatching { ViblioIconWidget.updateAll(appCtx) }
            val manager = runCatching { AppWidgetManager.getInstance(appCtx) }.getOrNull() ?: return
            val providers = listOf(
                ViblioWidget1x1::class.java to WidgetSize.SIZE_1X1,
                ViblioWidget2x1::class.java to WidgetSize.SIZE_2X1,
                ViblioWidget3x1::class.java to WidgetSize.SIZE_3X1,
                ViblioWidget4x1::class.java to WidgetSize.SIZE_4X1,
                ViblioWidget5x1::class.java to WidgetSize.SIZE_5X1,
            )
            val targets = providers.flatMap { (cls, defSize) ->
                val ids = runCatching { manager.getAppWidgetIds(ComponentName(appCtx, cls)) }.getOrNull() ?: IntArray(0)
                ids.map { it to defSize }
            }
            if (targets.isEmpty()) return
            val snap = loadSnapshot(appCtx)
            try {
                targets.forEach { (id, defSize) ->
                    val opts = manager.getAppWidgetOptions(id)
                    val minW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                    val size = resolveWidgetSize(minW, defSize)
                    runCatching { updateOne(appCtx, manager, id, size, snap) }
                }
            } finally {
                snap.releaseTempCover()
            }
        }

        private fun boundedCover(images: BookImages, coverName: String): Pair<Bitmap, Boolean>? {
            if (coverName.isBlank()) return null
            val raw = runCatching { images.thumbnail(coverName) }.getOrNull() ?: return null
            if (raw.width <= MAX_COVER_W && raw.height <= MAX_COVER_H) return raw to false
            val scale = minOf(MAX_COVER_W.toFloat() / raw.width.coerceAtLeast(1), MAX_COVER_H.toFloat() / raw.height.coerceAtLeast(1))
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(raw, w, h, true) to true
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

        private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int, size: WidgetSize, snap: WidgetSnapshot) {
            val layoutId = when (size) {
                WidgetSize.SIZE_1X1 -> R.layout.widget_reading_1x1
                WidgetSize.SIZE_2X1 -> R.layout.widget_reading_2x1
                WidgetSize.SIZE_3X1 -> R.layout.widget_reading_3x1
                WidgetSize.SIZE_4X1 -> R.layout.widget_reading_4x1
                WidgetSize.SIZE_5X1 -> R.layout.widget_reading_5x1
            }
            val views = RemoteViews(context.packageName, layoutId)
            val book = snap.book
            val readableAccent = snap.readableAccent
            val pct = when {
                book == null -> 0
                book.pagesKnown -> book.percent.coerceIn(0, 100)
                book.complete -> 100
                else -> 0
            }
            val progBmp = progressBitmap(pct, readableAccent)
            var markBmp: Bitmap? = null
            try {
                views.setImageViewBitmap(R.id.widget_cover, snap.coverBmp)
                views.setImageViewBitmap(R.id.widget_progress_img, progBmp)

                if (size != WidgetSize.SIZE_1X1) {
                    if (book != null) {
                        views.setTextViewText(R.id.widget_title, book.title)
                        val pagesText = when {
                            !book.pagesKnown -> if (book.complete) "Concluído" else "Em andamento"
                            size == WidgetSize.SIZE_2X1 -> "${book.currentPage}/${book.totalPages}"
                            size == WidgetSize.SIZE_3X1 -> "${book.currentPage}/${book.totalPages} págs · $pct%"
                            size == WidgetSize.SIZE_4X1 -> "${book.currentPage} / ${book.totalPages} págs · $pct%"
                            else -> "${book.currentPage} / ${book.totalPages} págs · $pct% concluído"
                        }
                        views.setTextViewText(R.id.widget_pages, pagesText)
                    } else {
                        views.setTextViewText(R.id.widget_title, "Nenhuma leitura atual")
                        views.setTextViewText(R.id.widget_pages, "Abrir Estante")
                    }
                }

                if (size == WidgetSize.SIZE_1X1 || size == WidgetSize.SIZE_2X1) {
                    views.setTextColor(R.id.widget_percent, readableAccent)
                    val pctText = when {
                        book == null -> "—"
                        book.pagesKnown -> "$pct%"
                        book.complete -> "100%"
                        else -> "Lendo"
                    }
                    views.setTextViewText(R.id.widget_percent, pctText)
                }

                if (size == WidgetSize.SIZE_3X1 || size == WidgetSize.SIZE_4X1 || size == WidgetSize.SIZE_5X1) {
                    markBmp = brandMarkBitmap(readableAccent, 48)
                    views.setImageViewBitmap(R.id.widget_brand_icon, markBmp)
                    views.setTextColor(R.id.widget_kicker, readableAccent)
                    val kicker = when {
                        book == null -> "VIBLIO"
                        size == WidgetSize.SIZE_3X1 -> "LENDO"
                        else -> "LENDO AGORA"
                    }
                    views.setTextViewText(R.id.widget_kicker, kicker)
                }

                if (size == WidgetSize.SIZE_4X1 || size == WidgetSize.SIZE_5X1) {
                    // 5x1 intentionally omits saga for a cleaner layout (title + author only).
                    val authorText = if (book != null) book.author else "Toque para abrir a Estante"
                    views.setTextViewText(R.id.widget_author, authorText)
                    views.setTextColor(R.id.widget_cta, readableAccent)
                    val ctaText = when {
                        book == null -> "Abrir ›"
                        size == WidgetSize.SIZE_5X1 -> "Continuar leitura ›"
                        else -> "Continuar ›"
                    }
                    views.setTextViewText(R.id.widget_cta, ctaText)
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
            } finally {
                if (!progBmp.isRecycled) progBmp.recycle()
                if (markBmp != null && !markBmp.isRecycled) markBmp.recycle()
            }
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

class ViblioWidget1x1 : BaseViblioReadingWidget(WidgetSize.SIZE_1X1)
class ViblioWidget2x1 : BaseViblioReadingWidget(WidgetSize.SIZE_2X1)
class ViblioWidget3x1 : BaseViblioReadingWidget(WidgetSize.SIZE_3X1)
class ViblioWidget4x1 : BaseViblioReadingWidget(WidgetSize.SIZE_4X1)
class ViblioWidget5x1 : BaseViblioReadingWidget(WidgetSize.SIZE_5X1)
