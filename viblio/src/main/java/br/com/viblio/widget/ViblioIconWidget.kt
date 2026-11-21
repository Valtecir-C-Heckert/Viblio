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
import android.graphics.RectF
import android.os.Build
import android.widget.RemoteViews
import br.com.centralcasa.R
import br.com.centralcasa.books.BookImages
import br.com.centralcasa.books.LibraryRepository
import br.com.viblio.ViblioActivity
import br.com.viblio.ui.ViblioBookMarkDrawable

/**
 * 1x1 widget drawn exactly like the Viblio launcher icon (#151515 rounded tile + book mark),
 * with the mark tinted by the cover color of the last book opened before leaving the app.
 */
class ViblioIconWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        val ids = appWidgetIds.copyOf()
        executor.execute {
            try { render(appCtx, appWidgetManager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private const val PREFS = "viblio_widget"
        private const val KEY_COLOR = "last_book_color"
        private const val TILE_COLOR = 0xFF151515.toInt()
        private const val DEFAULT_COLOR = 0xFFC9A77C.toInt()
        private const val ICON_PX = 144
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** Persists only an RGB color (no titles or personal data). */
        fun saveLastBookColor(context: Context, color: Int?) {
            val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (color == null) editor.remove(KEY_COLOR) else editor.putInt(KEY_COLOR, color or 0xFF000000.toInt())
            editor.apply()
        }

        /** Called from the shared widget refresh (already on a background thread). */
        fun updateAll(context: Context) {
            val appCtx = context.applicationContext
            val manager = runCatching { AppWidgetManager.getInstance(appCtx) }.getOrNull() ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(appCtx, ViblioIconWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) render(appCtx, manager, ids)
        }

        private fun resolveColor(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_COLOR)) return prefs.getInt(KEY_COLOR, DEFAULT_COLOR)
            val intro = context.getSharedPreferences("viblio_intro", Context.MODE_PRIVATE)
            if (intro.contains("cover_color")) return intro.getInt("cover_color", DEFAULT_COLOR)
            val cover = runCatching {
                val repo = LibraryRepository(context)
                try { repo.current()?.cover } finally { runCatching { repo.close() } }
            }.getOrNull()
            return cover?.takeIf { it.isNotBlank() }?.let { runCatching { BookImages(context).coverColor(it) }.getOrNull() } ?: DEFAULT_COLOR
        }

        /** Keeps the exact cover hue; only near-black covers are lifted so the mark stays visible on the dark tile. */
        internal fun visibleOnTile(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            if (hsv[2] >= .28f) return color or 0xFF000000.toInt()
            hsv[2] = .42f
            return Color.HSVToColor(hsv)
        }

        internal fun iconBitmap(color: Int, size: Int = ICON_PX): Bitmap {
            val s = size.coerceIn(48, 256)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val radius = s * 18f / 108f
            canvas.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = TILE_COLOR })
            val inset = (s * 18f / 108f).toInt()
            ViblioBookMarkDrawable(visibleOnTile(color), Color.WHITE).apply { setBounds(inset, inset, s - inset, s - inset) }.draw(canvas)
            return bmp
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val icon = iconBitmap(resolveColor(context))
            try {
                val open = Intent(context, ViblioActivity::class.java).apply {
                    action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
                ids.forEach { id ->
                    runCatching {
                        val views = RemoteViews(context.packageName, R.layout.widget_viblio_icon_1x1)
                        views.setImageViewBitmap(R.id.widget_icon_image, icon)
                        views.setOnClickPendingIntent(R.id.widget_icon_root, PendingIntent.getActivity(context, 7000 + id, open, flags))
                        manager.updateAppWidget(id, views)
                    }
                }
            } finally {
                if (!icon.isRecycled) icon.recycle()
            }
        }
    }
}
