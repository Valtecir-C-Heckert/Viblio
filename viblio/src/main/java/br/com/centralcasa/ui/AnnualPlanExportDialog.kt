package br.com.centralcasa.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import br.com.centralcasa.books.YearPlanSummary
import br.com.viblio.share.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Asynchronous export preview dialog for the Annual Reading Plan (January -> December)
 * supporting both high-resolution PNG image and multi-page A4 PDF.
 */
class AnnualPlanExportDialog(
    private val activity: Activity,
    private val ui: DisplayUi,
) {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var previewView: ImageView? = null
    private var currentBitmap: Bitmap? = null
    private var renderEpoch = 0
    @Volatile private var closed = false
    val isShowing: Boolean get() = dialog?.isShowing == true

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

    fun show(summary: YearPlanSummary) {
        if (!uiAlive()) return
        close()

        var format = AnnualPlanExportFormat.PNG
        var includeEmptyMonths = true
        var onlyPlanned = false
        var busyIo = false

        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(10)) }

        // Row 1: Format switcher (PNG | PDF A4)
        val fmtRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(fmtRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        val formatBtns = mutableMapOf<AnnualPlanExportFormat, TextView>()
        AnnualPlanExportFormat.entries.forEachIndexed { idx, fmt ->
            val btn = TextView(activity).apply {
                text = fmt.label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7))
            }
            formatBtns[fmt] = btn
            fmtRow.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (idx == 0) marginEnd = ui.dp(6)
            })
        }

        // Row 2: Compact options (Jan-Dez empty months toggle + Only planned toggle)
        val optRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(optRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        val emptyBtn = TextView(activity).apply {
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
        }
        val plannedBtn = TextView(activity).apply {
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
        }
        optRow.addView(emptyBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) })
        optRow.addView(plannedBtn, LinearLayout.LayoutParams(0, -2, 1f))

        val previewFrame = FrameLayout(activity).apply {
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
        }
        val preview = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        previewView = preview
        val loadingLabel = TextView(activity).apply {
            text = "Gerando prévia…"
            textSize = 11.5f
            setTextColor(ui.muted)
            gravity = Gravity.CENTER
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(loadingLabel, FrameLayout.LayoutParams(-1, -1))
        body.addView(previewFrame, LinearLayout.LayoutParams(-1, ui.dp(340)).apply { bottomMargin = ui.dp(8) })

        val statusInfo = TextView(activity).apply {
            textSize = 10.5f
            setTextColor(ui.muted)
            gravity = Gravity.CENTER
        }
        body.addView(statusInfo, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })

        val btnRow = ui.row()
        body.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        val saveBtn = TextView(activity).apply {
            text = "Salvar"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ui.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        }
        val shareBtn = TextView(activity).apply {
            text = "Compartilhar"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ui.background)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ui.shape(ui.accent, 0)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        }
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        btnRow.addView(shareBtn, LinearLayout.LayoutParams(0, -2, 1f))

        fun currentData() = AnnualPlanExportData.from(
            summary = summary,
            includeEmptyMonths = includeEmptyMonths,
            onlyPlanned = onlyPlanned,
        )

        fun updateToggles() {
            formatBtns.forEach { (fmt, btn) ->
                val active = fmt == format
                btn.setTextColor(if (active) ui.accent else ui.muted)
                btn.background = ui.outlined(
                    if (active) ui.activeSurface else ui.surface,
                    if (active) ui.accent else ui.lineColor,
                    0,
                )
            }
            emptyBtn.text = if (includeEmptyMonths) "✓ Jan–Dez completo" else "Só meses com livros"
            emptyBtn.setTextColor(if (includeEmptyMonths) ui.accent else ui.muted)
            emptyBtn.background = ui.outlined(
                if (includeEmptyMonths) ui.activeSurface else ui.surface,
                if (includeEmptyMonths) ui.accent else ui.lineColor,
                0,
            )
            plannedBtn.text = if (onlyPlanned) "✓ Só a ler" else "Todos do ano"
            plannedBtn.setTextColor(if (onlyPlanned) ui.accent else ui.muted)
            plannedBtn.background = ui.outlined(
                if (onlyPlanned) ui.activeSurface else ui.surface,
                if (onlyPlanned) ui.accent else ui.lineColor,
                0,
            )
        }

        fun setActionEnabled(enabled: Boolean) {
            saveBtn.isEnabled = enabled
            shareBtn.isEnabled = enabled
            val alpha = if (enabled) 1f else 0.5f
            saveBtn.alpha = alpha
            shareBtn.alpha = alpha
        }

        fun rerender() {
            val epoch = ++renderEpoch
            updateToggles()
            setActionEnabled(false)
            loadingLabel.visibility = View.VISIBLE
            statusInfo.text = "Preparando lista de ${summary.year}…"
            val fmtSnapshot = format
            val dataSnapshot = currentData()
            val accent = ui.accent
            val appCtx = activity.applicationContext
            runCatching {
                worker.execute {
                    val result = runCatching {
                        if (fmtSnapshot == AnnualPlanExportFormat.PNG) {
                            AnnualPlanExportRenderer.renderPng(appCtx, dataSnapshot, accent) to 1
                        } else {
                            AnnualPlanExportRenderer.renderPdfPreview(appCtx, dataSnapshot, accent)
                        }
                    }
                    mainHandler.post {
                        if (!uiAlive() || epoch != renderEpoch) {
                            result.getOrNull()?.first?.let { if (!it.isRecycled) it.recycle() }
                            return@post
                        }
                        loadingLabel.visibility = View.GONE
                        result.fold(
                            onSuccess = { (bmp, pageCount) ->
                                currentBitmap = bmp
                                preview.setImageBitmap(bmp)
                                val bookTxt = if (dataSnapshot.totalBooks == 1) "1 livro" else "${dataSnapshot.totalBooks} livros"
                                statusInfo.text = if (fmtSnapshot == AnnualPlanExportFormat.PDF) {
                                    "$bookTxt · PDF A4 ($pageCount ${if (pageCount == 1) "página" else "páginas"})"
                                } else {
                                    "$bookTxt · Imagem PNG (${bmp.width}×${bmp.height})"
                                }
                                setActionEnabled(!busyIo)
                            },
                            onFailure = {
                                statusInfo.text = "Não foi possível gerar a prévia."
                                Toast.makeText(activity, "Não foi possível gerar a lista.", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }

        formatBtns.forEach { (fmt, btn) ->
            btn.setOnClickListener {
                if (format != fmt && !busyIo) {
                    format = fmt
                    rerender()
                }
            }
        }
        emptyBtn.setOnClickListener {
            if (!busyIo) {
                includeEmptyMonths = !includeEmptyMonths
                rerender()
            }
        }
        plannedBtn.setOnClickListener {
            if (!busyIo) {
                onlyPlanned = !onlyPlanned
                rerender()
            }
        }

        saveBtn.setOnClickListener {
            if (busyIo) return@setOnClickListener
            busyIo = true
            setActionEnabled(false)
            val fmtSnapshot = format
            val dataSnapshot = currentData()
            val bmpSnapshot = currentBitmap
            val accent = ui.accent
            val appCtx = activity.applicationContext
            runCatching {
                worker.execute {
                    val outcome = runCatching {
                        if (fmtSnapshot == AnnualPlanExportFormat.PNG) {
                            val bmp = bmpSnapshot ?: AnnualPlanExportRenderer.renderPng(appCtx, dataSnapshot, accent)
                            savePngToGallery(bmp, safeExportName("viblio_plano_${dataSnapshot.year}", "png"))
                        } else {
                            savePdfToDocuments(appCtx, dataSnapshot, accent, safeExportName("viblio_plano_${dataSnapshot.year}", "pdf"))
                        }
                    }
                    mainHandler.post {
                        busyIo = false
                        if (!uiAlive()) return@post
                        setActionEnabled(currentBitmap != null)
                        outcome.fold(
                            onSuccess = { msg -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() },
                            onFailure = { Toast.makeText(activity, "Não foi possível salvar o arquivo.", Toast.LENGTH_SHORT).show() },
                        )
                    }
                }
            }
        }

        shareBtn.setOnClickListener {
            if (busyIo) return@setOnClickListener
            busyIo = true
            setActionEnabled(false)
            val fmtSnapshot = format
            val dataSnapshot = currentData()
            val bmpSnapshot = currentBitmap
            val accent = ui.accent
            val appCtx = activity.applicationContext
            runCatching {
                worker.execute {
                    val outcome = runCatching {
                        val fileName = safeExportName("viblio_plano_${dataSnapshot.year}", fmtSnapshot.ext)
                        val file = File(ViblioShareProvider.directory(appCtx), fileName)
                        if (fmtSnapshot == AnnualPlanExportFormat.PNG) {
                            val bmp = bmpSnapshot ?: AnnualPlanExportRenderer.renderPng(appCtx, dataSnapshot, accent)
                            FileOutputStream(file).use { out ->
                                require(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Falha ao gravar PNG." }
                            }
                        } else {
                            AnnualPlanExportRenderer.renderPdf(appCtx, dataSnapshot, accent, file)
                        }
                        ViblioShareProvider.pruneOld(appCtx)
                        ViblioShareProvider.uriFor(fileName) to fmtSnapshot
                    }
                    mainHandler.post {
                        busyIo = false
                        if (!uiAlive()) return@post
                        setActionEnabled(currentBitmap != null)
                        outcome.fold(
                            onSuccess = { (uri, fmt) ->
                                runCatching {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = fmt.mime
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_TITLE, "Lista de leitura ${dataSnapshot.year}")
                                        clipData = ClipData.newRawUri("Lista de leitura ${dataSnapshot.year}", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    activity.startActivity(Intent.createChooser(send, "Compartilhar lista ${dataSnapshot.year}"))
                                }.onFailure {
                                    Toast.makeText(activity, "Não foi possível compartilhar.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onFailure = {
                                Toast.makeText(activity, "Não foi possível preparar o compartilhamento.", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }

        val dlg = AlertDialog.Builder(activity)
            .setTitle("Lista anual · ${summary.year}")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("Fechar", null)
            .create()
        dlg.setOnDismissListener {
            renderEpoch++
            previewView?.setImageDrawable(null)
            previewView = null
            currentBitmap = null
            dialog = null
        }
        dialog = dlg
        dlg.show()
        dlg.window?.setBackgroundDrawable(ui.shape(ui.surface, 0))
        rerender()
    }

    private fun savePngToGallery(bmp: Bitmap, fileName: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Viblio")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Falha ao criar imagem na Galeria.")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    require(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Falha ao gravar PNG." }
                } ?: throw IllegalStateException("Fluxo indisponível.")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return "Salvo em Imagens/Viblio."
        }
        val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: activity.filesDir, "Viblio").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> require(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) }
        return "Salvo em ${file.name}."
    }

    private fun savePdfToDocuments(
        appCtx: android.content.Context,
        data: AnnualPlanExportData,
        accent: Int,
        fileName: String,
    ): String {
        val tempFile = File(ViblioShareProvider.directory(appCtx), "tmp_$fileName")
        try {
            AnnualPlanExportRenderer.renderPdf(appCtx, data, accent, tempFile)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Viblio")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = activity.contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("Falha ao criar PDF em Documentos.")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Fluxo indisponível.")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                return "PDF salvo em Documentos/Viblio."
            }
            val dir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: activity.filesDir, "Viblio").apply { mkdirs() }
            val dest = File(dir, fileName)
            tempFile.copyTo(dest, overwrite = true)
            return "PDF salvo em ${dest.name}."
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    companion object {
        internal fun safeExportName(slug: String, ext: String): String {
            val cleanExt = if (ext.lowercase(Locale.US) == "pdf") "pdf" else "png"
            val base = slug.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9_-]"), "_")
                .trim('_')
                .ifBlank { "viblio_plano" }
                .take(56)
            return "$base.$cleanExt"
        }
    }
}
