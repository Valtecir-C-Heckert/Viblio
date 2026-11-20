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
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var previewView: ImageView? = null
    private var currentBitmap: Bitmap? = null
    private var renderEpoch = 0
    @Volatile private var closed = false

    fun close() {
        renderEpoch++
        previewView?.setImageDrawable(null)
        previewView = null
        dialog?.dismiss()
        dialog = null
        currentBitmap = null
    }

    fun destroy() {
        closed = true
        close()
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdown()
    }

    private fun uiAlive(): Boolean = !closed && !activity.isFinishing && !activity.isDestroyed

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
        if (!uiAlive()) return
        close()
        var format = RetrospectiveFormat.STORIES
        var busyIo = false

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

        val previewFrame = FrameLayout(activity).apply {
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
        }
        val preview = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        previewView = preview
        val loadingLabel = TextView(activity).apply {
            text = "Gerando imagem…"
            textSize = 12f
            setTextColor(ui.muted)
            gravity = Gravity.CENTER
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(loadingLabel, FrameLayout.LayoutParams(-1, -1))
        body.addView(previewFrame, LinearLayout.LayoutParams(-1, ui.dp(420)).apply { bottomMargin = ui.dp(10) })

        val actions = ui.row()
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))

        lateinit var saveBtn: TextView
        lateinit var shareBtn: TextView

        fun updateActionState() {
            val ready = currentBitmap != null && !busyIo
            saveBtn.isEnabled = ready
            shareBtn.isEnabled = ready
            saveBtn.alpha = if (ready) 1f else 0.5f
            shareBtn.alpha = if (ready) 1f else 0.5f
        }

        fun repaint() {
            formatBtns.forEach { (fmt, btn) ->
                val on = fmt == format
                btn.setTextColor(if (on) ui.background else ui.text)
                btn.background = if (on) ui.shape(ui.accent, 0) else ui.outlined(ui.activeSurface, ui.lineColor, 0)
            }
            yearLabel?.second?.text = "${yearProvider()}"
            val epoch = ++renderEpoch
            val targetFormat = format
            val accent = ui.accent
            val appCtx = activity.applicationContext
            loadingLabel.visibility = android.view.View.VISIBLE
            preview.setImageDrawable(null)
            currentBitmap = null
            updateActionState()
            worker.execute {
                val bmp = runCatching {
                    val data = dataBuilder()
                    RetrospectiveRenderer.render(appCtx, data, targetFormat, accent)
                }.getOrNull()
                mainHandler.post {
                    if (!uiAlive() || epoch != renderEpoch || previewView !== preview) {
                        bmp?.recycle()
                        return@post
                    }
                    loadingLabel.visibility = if (bmp != null) android.view.View.GONE else android.view.View.VISIBLE
                    if (bmp != null) {
                        currentBitmap = bmp
                        preview.setImageBitmap(bmp)
                    } else {
                        loadingLabel.text = "Não foi possível gerar a imagem."
                    }
                    updateActionState()
                }
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

        saveBtn = ui.action(actions, "Salvar imagem") {
            val bmp = currentBitmap ?: return@action
            if (busyIo) return@action
            busyIo = true
            updateActionState()
            val cleanName = safePngName("${fileSlug()}_${format.name.lowercase(Locale.US)}")
            saveToGalleryAsync(bmp, cleanName) {
                busyIo = false
                if (uiAlive()) updateActionState()
            }
        }.apply {
            minHeight = ui.dp(40)
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(40), 1f).apply { marginEnd = ui.dp(8) }
        }

        shareBtn = ui.action(actions, "Compartilhar", primary = true) {
            val bmp = currentBitmap ?: return@action
            if (busyIo) return@action
            busyIo = true
            updateActionState()
            val cleanName = safePngName("${fileSlug()}_${format.name.lowercase(Locale.US)}")
            sharePngAsync(bmp, cleanName) {
                busyIo = false
                if (uiAlive()) updateActionState()
            }
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
                    renderEpoch++
                    preview.setImageDrawable(null)
                    if (previewView === preview) previewView = null
                    currentBitmap = null
                    if (dialog === d) dialog = null
                }
                d.show()
                d.window?.setBackgroundDrawable(ui.shape(ui.surface, 0))
            }
    }

    private fun sharePngAsync(bitmap: Bitmap, fileName: String, done: () -> Unit) {
        val appCtx = activity.applicationContext
        val safeName = safePngName(fileName)
        worker.execute {
            val result = runCatching {
                val dir = ViblioShareProvider.directory(appCtx)
                val outFile = File(dir, safeName)
                val tmpFile = File.createTempFile("wrapped-", ".tmp", dir)
                try {
                    FileOutputStream(tmpFile).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    if (!tmpFile.renameTo(outFile)) {
                        tmpFile.copyTo(outFile, overwrite = true)
                    }
                } finally {
                    if (tmpFile.exists()) tmpFile.delete()
                }
                ViblioShareProvider.pruneOld(appCtx)
                ViblioShareProvider.uriFor(safeName)
            }
            mainHandler.post {
                done()
                if (!uiAlive()) return@post
                result.fold({ uri ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("Retrospectiva Viblio", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { activity.startActivity(Intent.createChooser(send, "Compartilhar retrospectiva")) }
                        .onFailure { Toast.makeText(activity, "Não foi possível compartilhar a imagem.", Toast.LENGTH_LONG).show() }
                }, {
                    Toast.makeText(activity, "Não foi possível compartilhar a imagem.", Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    private fun saveToGalleryAsync(bitmap: Bitmap, fileName: String, done: () -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            sharePngAsync(bitmap, fileName, done)
            return
        }
        val appCtx = activity.applicationContext
        val safeName = safePngName(fileName)
        worker.execute {
            val result = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Viblio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = appCtx.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore retornou vazio")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    } ?: throw IllegalStateException("Sem acesso de escrita")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (err: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw err
                }
            }
            mainHandler.post {
                done()
                if (!uiAlive()) return@post
                result.fold({
                    Toast.makeText(activity, "Salva em Fotos/Viblio.", Toast.LENGTH_SHORT).show()
                }, {
                    Toast.makeText(activity, "Não foi possível salvar na galeria.", Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    companion object {
        internal fun safePngName(raw: String): String {
            val stem = raw.lowercase(Locale.US)
                .removeSuffix(".png")
                .replace(Regex("[^a-z0-9_-]"), "_")
                .trim('_')
                .ifBlank { "viblio_retrospectiva" }
                .take(56)
            return "$stem.png"
        }
    }
}
