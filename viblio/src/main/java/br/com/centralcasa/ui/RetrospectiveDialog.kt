package br.com.centralcasa.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import br.com.centralcasa.books.*
import br.com.viblio.share.RetrospectiveFormat
import br.com.viblio.share.RetrospectiveRenderer
import br.com.viblio.share.ViblioShareProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Interactive preview dialog for the shareable Reading Retrospective (Stories 9:16 or Feed 4:5).
 */
class RetrospectiveDialog(
    private val activity: Activity,
    private val ui: DisplayUi,
    private val repo: LibraryRepository,
) {
    private var dialog: AlertDialog? = null
    private var currentBitmap: Bitmap? = null

    fun close() {
        dialog?.dismiss()
        dialog = null
        currentBitmap?.recycle()
        currentBitmap = null
    }

    fun showYear(initialYear: Int) {
        var year = initialYear
        showInternal(
            allowYearNav = true,
            yearProvider = { year },
            onShiftYear = { delta -> year = (year + delta).coerceIn(2020, 2100) },
            dataBuilder = {
                val range = ReadingPeriods.range(String.format(Locale.US, "%04d-01-01", year), ReadingPeriodUnit.YEAR)
                RetrospectiveBuilder.build(
                    books = repo.books(),
                    sessions = repo.sessions(),
                    dates = repo.dates(),
                    range = range,
                    title = "Meu ano em livros",
                    periodLabel = "$year",
                )
            },
            fileSlug = { "viblio_retrospectiva_$year" },
        )
    }

    fun showRange(range: ReadingPeriodRange, title: String, slug: String) {
        showInternal(
            allowYearNav = false,
            yearProvider = { 0 },
            onShiftYear = {},
            dataBuilder = {
                RetrospectiveBuilder.build(
                    books = repo.books(),
                    sessions = repo.sessions(),
                    dates = repo.dates(),
                    range = range,
                    title = title,
                    periodLabel = range.label,
                )
            },
            fileSlug = { slug },
        )
    }

    private fun showInternal(
        allowYearNav: Boolean,
        yearProvider: () -> Int,
        onShiftYear: (Int) -> Unit,
        dataBuilder: () -> RetrospectiveData,
        fileSlug: () -> String,
    ) {
        close()
        var format = RetrospectiveFormat.STORIES

        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(10)) }
        val topControls = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(topControls, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })

        val yearLabel = if (allowYearNav) {
            val nav = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            topControls.addView(nav, LinearLayout.LayoutParams(-2, -2))
            val prev = TextView(activity).apply {
                text = "‹"
                textSize = 18f
                setTextColor(ui.accent)
                setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
            }
            val lbl = TextView(activity).apply {
                text = "${yearProvider()}"
                textSize = 13f
                setTextColor(ui.text)
                setPadding(ui.dp(4), 0, ui.dp(4), 0)
            }
            val next = TextView(activity).apply {
                text = "›"
                textSize = 18f
                setTextColor(ui.accent)
                setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
            }
            nav.addView(prev); nav.addView(lbl); nav.addView(next)
            Triple(prev, lbl, next)
        } else null

        topControls.addView(android.view.View(activity), LinearLayout.LayoutParams(0, 1, 1f))

        val fmtRow = ui.row()
        topControls.addView(fmtRow, LinearLayout.LayoutParams(-2, -2))
        val formatBtns = mutableMapOf<RetrospectiveFormat, TextView>()
        RetrospectiveFormat.entries.forEachIndexed { idx, fmt ->
            val btn = TextView(activity).apply {
                text = fmt.label
                textSize = 10.5f
                setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
            }
            formatBtns[fmt] = btn
            fmtRow.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { if (idx == 0) marginEnd = ui.dp(6) })
        }

        val preview = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
        }
        body.addView(preview, LinearLayout.LayoutParams(-1, ui.dp(420)).apply { bottomMargin = ui.dp(10) })

        val actions = ui.row()
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))

        fun repaint() {
            formatBtns.forEach { (fmt, btn) ->
                val on = fmt == format
                btn.setTextColor(if (on) ui.background else ui.text)
                btn.background = if (on) ui.shape(ui.accent, 0) else ui.outlined(ui.activeSurface, ui.lineColor, 0)
            }
            yearLabel?.second?.text = "${yearProvider()}"
            val old = currentBitmap
            val bmp = runCatching {
                RetrospectiveRenderer.render(activity, dataBuilder(), format, ui.accent)
            }.getOrNull()
            if (bmp != null) {
                currentBitmap = bmp
                preview.setImageBitmap(bmp)
                old?.recycle()
            }
        }

        formatBtns.forEach { (fmt, btn) ->
            btn.setOnClickListener {
                if (format != fmt) {
                    format = fmt
                    repaint()
                }
            }
        }
        yearLabel?.first?.setOnClickListener { onShiftYear(-1); repaint() }
        yearLabel?.third?.setOnClickListener { onShiftYear(1); repaint() }

        ui.action(actions, "Salvar imagem") {
            val bmp = currentBitmap ?: return@action
            val cleanSlug = "${fileSlug()}_${format.name.lowercase(Locale.US)}".replace(Regex("[^a-z0-9_-]"), "_")
            saveToGallery(bmp, "$cleanSlug.png")
        }.apply {
            minHeight = ui.dp(40)
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(40), 1f).apply { marginEnd = ui.dp(8) }
        }

        ui.action(actions, "Compartilhar", primary = true) {
            val bmp = currentBitmap ?: return@action
            val cleanSlug = "${fileSlug()}_${format.name.lowercase(Locale.US)}".replace(Regex("[^a-z0-9_-]"), "_")
            sharePng(bmp, "$cleanSlug.png")
        }.apply {
            minHeight = ui.dp(40)
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(40), 1f)
        }

        repaint()
        dialog = AlertDialog.Builder(activity)
            .setTitle("Retrospectiva")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("Fechar") { _, _ -> close() }
            .create().also { d ->
                d.setOnDismissListener {
                    currentBitmap?.recycle()
                    currentBitmap = null
                    dialog = null
                }
                d.show()
                d.window?.setBackgroundDrawable(ui.shape(ui.surface, 0))
            }
    }

    private fun sharePng(bitmap: Bitmap, fileName: String) {
        try {
            val safeName = fileName.lowercase(Locale.US).take(60).let { if (it.endsWith(".png")) it else "$it.png" }
            val outFile = File(ViblioShareProvider.directory(activity), safeName)
            FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ViblioShareProvider.pruneOld(activity)
            val uri = ViblioShareProvider.uriFor(safeName)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Compartilhar retrospectiva"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Não foi possível compartilhar a imagem.", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGallery(bitmap: Bitmap, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Viblio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore retornou vazio")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: throw IllegalStateException("Sem acesso de escrita")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (err: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw err
                }
                Toast.makeText(activity, "Salva em Fotos/Viblio.", Toast.LENGTH_SHORT).show()
            } else {
                // On Android < 10 avoid requesting legacy WRITE_EXTERNAL_STORAGE; share/save via system chooser cleanly.
                sharePng(bitmap, fileName)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Não foi possível salvar na galeria.", Toast.LENGTH_LONG).show()
        }
    }
}
