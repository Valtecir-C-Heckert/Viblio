package br.com.viblio.share

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import br.com.centralcasa.books.*
import br.com.viblio.ui.ViblioBookMarkDrawable
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.Locale

enum class AnnualPlanExportFormat(val label: String, val ext: String, val mime: String) {
    PNG("Imagem (PNG)", "png", "image/png"),
    PDF("PDF (A4)", "pdf", "application/pdf"),
}

data class AnnualPlanExportItem(
    val title: String,
    val author: String,
    val cover: String,
    val pages: Int,
    val statusLabel: String,
    val completed: Boolean,
)

data class AnnualPlanExportMonth(
    val month: Int,
    val name: String,
    val items: List<AnnualPlanExportItem>,
)

data class AnnualPlanExportData(
    val year: Int,
    val months: List<AnnualPlanExportMonth>,
    val totalBooks: Int,
    val totalPages: Long,
    val activeMonthCount: Int,
    val includeEmptyMonths: Boolean,
    val onlyPlanned: Boolean,
) {
    companion object {
        fun from(
            summary: YearPlanSummary,
            includeEmptyMonths: Boolean = true,
            onlyPlanned: Boolean = false,
        ): AnnualPlanExportData {
            val monthBlocks = (1..12).mapNotNull { m ->
                val ms = summary.months.getOrNull(m - 1)
                val rawItems = ms?.items.orEmpty()
                val filtered = if (onlyPlanned) {
                    rawItems.filter {
                        it.status == PlanItemStatus.PLANNED || it.status == PlanItemStatus.READING
                    }
                } else {
                    rawItems
                }
                val mapped = filtered.map { item ->
                    val statusText = when {
                        item.isReread && item.status == PlanItemStatus.PLANNED -> "Releitura"
                        item.status == PlanItemStatus.COMPLETED || item.status == PlanItemStatus.READ_IN_MONTH -> "Concluído"
                        item.status == PlanItemStatus.READING -> "Lendo"
                        else -> "Planejado"
                    }
                    AnnualPlanExportItem(
                        title = item.title,
                        author = item.author,
                        cover = item.localCover,
                        pages = item.totalPages.coerceAtLeast(0),
                        statusLabel = statusText,
                        completed = item.status == PlanItemStatus.COMPLETED || item.status == PlanItemStatus.READ_IN_MONTH,
                    )
                }
                if (!includeEmptyMonths && mapped.isEmpty()) {
                    null
                } else {
                    AnnualPlanExportMonth(
                        month = m,
                        name = AnnualPlanCalculator.fullName(m).uppercase(Locale("pt", "BR")),
                        items = mapped,
                    )
                }
            }
            val totalBooks = monthBlocks.sumOf { it.items.size }
            val totalPages = monthBlocks.sumOf { mb -> mb.items.sumOf { it.pages.toLong() } }
            val activeMonths = monthBlocks.count { it.items.isNotEmpty() }
            return AnnualPlanExportData(
                year = summary.year,
                months = monthBlocks,
                totalBooks = totalBooks,
                totalPages = totalPages,
                activeMonthCount = activeMonths,
                includeEmptyMonths = includeEmptyMonths,
                onlyPlanned = onlyPlanned,
            )
        }
    }
}

/**
 * Renders the full annual reading list (January -> December for 2026, 2027, 2028...)
 * as either a balanced 2-column high-resolution PNG image or a multi-page vector A4 PDF.
 */
object AnnualPlanExportRenderer {
    private const val PNG_WIDTH = 1400
    private const val PNG_MAX_HEIGHT = 4800
    private const val PDF_W = 595
    private const val PDF_H = 842

    fun renderPng(
        context: Context,
        data: AnnualPlanExportData,
        accentColor: Int = 0xFFC9A77C.toInt(),
    ): Bitmap {
        val accent = normalizeAccent(accentColor)
        val numbers = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
        val images = BookImages(context)

        val padX = 76f
        val colGap = 44f
        val colW = (PNG_WIDTH - padX * 2f - colGap) / 2f
        val headerH = 260f
        val footerH = 104f
        val monthHeadH = 38f
        val itemH = 92f
        val itemGap = 10f
        val emptyBoxH = 54f
        val monthGap = 28f

        val split = (data.months.size + 1) / 2
        val leftMonths = data.months.take(split)
        val rightMonths = data.months.drop(split)

        fun columnHeight(list: List<AnnualPlanExportMonth>): Float {
            if (list.isEmpty()) return 120f
            var y = 0f
            list.forEachIndexed { i, m ->
                if (i > 0) y += monthGap
                y += monthHeadH
                if (m.items.isEmpty()) {
                    y += emptyBoxH
                } else {
                    y += m.items.size * itemH + (m.items.size - 1) * itemGap
                }
            }
            return y
        }

        val bodyH = maxOf(columnHeight(leftMonths), columnHeight(rightMonths), 260f)
        val desiredH = (headerH + bodyH + footerH + 40f).toInt()
        val height = desiredH.coerceIn(920, PNG_MAX_HEIGHT)

        val bmp = Bitmap.createBitmap(PNG_WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(0xFF191612.toInt(), 0xFF11100F.toInt(), 0xFF151310.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, PNG_WIDTH.toFloat(), height.toFloat(), bgPaint)

        // Outer editorial frame
        val frameInset = 34f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
        c.drawRect(frameInset, frameInset, PNG_WIDTH - frameInset, height - frameInset, borderPaint)

        val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = 22f
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF0ECE4.toInt()
            textSize = 62f
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8C877F.toInt()
            textSize = 23f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E2B27.toInt()
            strokeWidth = 2f
        }

        // Header with Viblio book icon (book cover color + white pages)
        val topY = 98f
        val iconSz = 30
        val mark = ViblioBookMarkDrawable(accent, Color.WHITE)
        mark.setBounds(padX.toInt(), (topY - iconSz + 5).toInt(), (padX + iconSz).toInt(), (topY + 5).toInt())
        mark.draw(c)
        c.drawText("VIBLIO · PLANO ANUAL DE LEITURA", padX + iconSz + 12f, topY, kickerPaint)

        val scopeTag = if (data.includeEmptyMonths) "JAN → DEZ" else "${data.activeMonthCount} MESES"
        c.drawText(scopeTag, PNG_WIDTH - padX - subPaint.measureText(scopeTag), topY, subPaint)

        c.drawText("Leituras de ${data.year}", padX, topY + 72f, titlePaint)
        val booksLbl = if (data.totalBooks == 1) "1 livro selecionado" else "${numbers.format(data.totalBooks)} livros selecionados"
        val pagesLbl = if (data.totalPages > 0L) " · ${numbers.format(data.totalPages)} páginas" else ""
        val filterLbl = if (data.onlyPlanned) " · Somente a ler" else ""
        c.drawText("$booksLbl$pagesLbl$filterLbl", padX, topY + 112f, subPaint)
        c.drawLine(padX, headerH - 22f, PNG_WIDTH - padX, headerH - 22f, linePaint)

        val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = 20f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF181715.toInt() }
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = 0xFF2D2A26.toInt()
        }
        val dashedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = 0xFF2F2C28.toInt()
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        val bookTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF0ECE4.toInt()
            textSize = 24f
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        val bookMetaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8C877F.toInt()
            textSize = 18.5f
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        val maxContentBottom = height - footerH - 12f
        var overflowCount = 0

        fun drawColumn(xStart: Float, list: List<AnnualPlanExportMonth>) {
            var y = headerH
            list.forEachIndexed { idx, monthBlock ->
                if (idx > 0) y += monthGap
                if (y + monthHeadH > maxContentBottom) {
                    overflowCount += monthBlock.items.size
                    return@forEachIndexed
                }
                c.drawText(monthBlock.name, xStart, y + 22f, monthPaint)
                y += monthHeadH

                if (monthBlock.items.isEmpty()) {
                    if (y + emptyBoxH <= maxContentBottom) {
                        val rect = RectF(xStart, y, xStart + colW, y + emptyBoxH)
                        c.drawRect(rect, dashedStroke)
                        c.drawText("Mês livre", xStart + 16f, y + 34f, bookMetaPaint)
                        y += emptyBoxH
                    }
                } else {
                    monthBlock.items.forEachIndexed { itemIdx, item ->
                        if (itemIdx > 0) y += itemGap
                        if (y + itemH > maxContentBottom) {
                            overflowCount++
                            return@forEachIndexed
                        }
                        val rect = RectF(xStart, y, xStart + colW, y + itemH)
                        c.drawRect(rect, cardFill)
                        c.drawRect(rect, cardStroke)

                        val coverRect = RectF(xStart + 12f, y + 11f, xStart + 12f + 46f, y + itemH - 11f)
                        drawCover(c, images, item.cover, item.title, coverRect, accent, darkMode = true)

                        val badgeW = badgePaint.measureText(item.statusLabel) + 20f
                        val badgeRect = RectF(rect.right - 14f - badgeW, y + (itemH - 30f) * 0.5f, rect.right - 14f, y + (itemH + 30f) * 0.5f)
                        val bStroke = Paint(cardStroke).apply {
                            color = if (item.completed) accent else 0xFF3A3631.toInt()
                        }
                        c.drawRect(badgeRect, bStroke)
                        badgePaint.color = if (item.completed) accent else 0xFF9C968E.toInt()
                        c.drawText(item.statusLabel, badgeRect.left + 10f, badgeRect.top + 21f, badgePaint)

                        val textX = coverRect.right + 16f
                        val textMaxW = (badgeRect.left - 12f - textX).coerceAtLeast(80f)
                        c.drawText(ellipsize(item.title, bookTitlePaint, textMaxW), textX, y + 39f, bookTitlePaint)
                        val meta = buildList {
                            if (item.author.isNotBlank()) add(item.author)
                            if (item.pages > 0) add("${numbers.format(item.pages)} p")
                        }.joinToString(" · ")
                        if (meta.isNotBlank()) {
                            c.drawText(ellipsize(meta, bookMetaPaint, textMaxW), textX, y + 69f, bookMetaPaint)
                        }
                        y += itemH
                    }
                }
            }
        }

        if (data.months.isEmpty()) {
            c.drawText("Nenhum livro selecionado para ${data.year}.", padX, headerH + 60f, bookTitlePaint)
        } else {
            drawColumn(padX, leftMonths)
            drawColumn(padX + colW + colGap, rightMonths)
        }

        // Footer
        val footY = height - 56f
        c.drawLine(padX, footY - 38f, PNG_WIDTH - padX, footY - 38f, linePaint)
        val fIcon = 24
        val fMark = ViblioBookMarkDrawable(accent, Color.WHITE)
        fMark.setBounds(padX.toInt(), (footY - fIcon + 4).toInt(), (padX + fIcon).toInt(), (footY + 4).toInt())
        fMark.draw(c)
        c.drawText("VIBLIO · ${data.year}", padX + fIcon + 10f, footY, subPaint)
        val rightFoot = if (overflowCount > 0) {
            "+ $overflowCount livros (use PDF A4 para a lista completa)"
        } else {
            "JANEIRO A DEZEMBRO"
        }
        c.drawText(rightFoot, PNG_WIDTH - padX - subPaint.measureText(rightFoot), footY, subPaint)

        return bmp
    }

    /**
     * Generates a crisp multi-page A4 vector PDF (595x842 pt) listing all selected books
     * from January through December with automatic pagination. Returns total page count.
     */
    fun renderPdf(
        context: Context,
        data: AnnualPlanExportData,
        accentColor: Int,
        outputFile: File,
    ): Int {
        val pages = paginateForPdf(data)
        val totalPages = pages.size.coerceAtLeast(1)
        val doc = PdfDocument()
        val images = BookImages(context)
        try {
            pages.forEachIndexed { idx, pageEntries ->
                val pageInfo = PdfDocument.PageInfo.Builder(PDF_W, PDF_H, idx + 1).create()
                val page = doc.startPage(pageInfo)
                drawPdfPage(page.canvas, data, pageEntries, idx + 1, totalPages, accentColor, images)
                doc.finishPage(page)
            }
            outputFile.parentFile?.mkdirs()
            val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
            try {
                FileOutputStream(tmp).use { out -> doc.writeTo(out) }
                if (!tmp.renameTo(outputFile)) {
                    tmp.copyTo(outputFile, overwrite = true)
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            return totalPages
        } finally {
            doc.close()
        }
    }

    /** Renders page 1 of the exact A4 PDF layout into a preview Bitmap (returns bitmap + page count). */
    fun renderPdfPreview(
        context: Context,
        data: AnnualPlanExportData,
        accentColor: Int,
    ): Pair<Bitmap, Int> {
        val pages = paginateForPdf(data)
        val totalPages = pages.size.coerceAtLeast(1)
        val scale = 1.6f
        val bmp = Bitmap.createBitmap((PDF_W * scale).toInt(), (PDF_H * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        drawPdfPage(c, data, pages.first(), 1, totalPages, accentColor, BookImages(context))
        return bmp to totalPages
    }

    private sealed class PdfRow(val height: Float) {
        class MonthHeader(val name: String) : PdfRow(24f)
        class EmptyMonth : PdfRow(28f)
        class BookItem(val item: AnnualPlanExportItem) : PdfRow(48f)
        object Spacer : PdfRow(10f)
    }

    internal fun pdfPageCount(data: AnnualPlanExportData): Int = paginateForPdf(data).size

    private fun paginateForPdf(data: AnnualPlanExportData): List<List<PdfRow>> {
        val allRows = mutableListOf<PdfRow>()
        data.months.forEachIndexed { idx, m ->
            if (idx > 0) allRows.add(PdfRow.Spacer)
            allRows.add(PdfRow.MonthHeader(m.name))
            if (m.items.isEmpty()) {
                allRows.add(PdfRow.EmptyMonth())
            } else {
                m.items.forEach { allRows.add(PdfRow.BookItem(it)) }
            }
        }
        if (allRows.isEmpty()) return listOf(emptyList())

        val pages = mutableListOf<MutableList<PdfRow>>()
        var current = mutableListOf<PdfRow>()
        var usedY = 0f
        var maxY = 668f // Page 1 has full title header (top ~122pt, footer ~52pt)

        var i = 0
        while (i < allRows.size) {
            val row = allRows[i]
            if (row is PdfRow.Spacer && current.isEmpty()) {
                i++
                continue
            }
            // Keep month header together with at least its first entry
            val needed = if (row is PdfRow.MonthHeader && i + 1 < allRows.size) {
                row.height + allRows[i + 1].height
            } else {
                row.height
            }
            if (current.isNotEmpty() && usedY + needed > maxY) {
                pages.add(current)
                current = mutableListOf()
                usedY = 0f
                maxY = 716f // Continuation pages have slim header
                if (row is PdfRow.Spacer) {
                    i++
                    continue
                }
            }
            current.add(row)
            usedY += row.height
            i++
        }
        if (current.isNotEmpty() || pages.isEmpty()) pages.add(current)
        return pages
    }

    private fun drawPdfPage(
        c: Canvas,
        data: AnnualPlanExportData,
        rows: List<PdfRow>,
        pageNum: Int,
        totalPages: Int,
        accentColor: Int,
        images: BookImages,
    ) {
        val numbers = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
        val accent = darkenForPaper(accentColor)
        val padX = 42f
        val contentW = PDF_W - padX * 2f

        // Editorial warm paper background
        c.drawColor(0xFFFAF8F4.toInt())
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = 0xFFDDD7CE.toInt()
        }
        val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = 0xFFD5CFC6.toInt()
            pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
        }
        val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = 8.5f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1B1917.toInt()
            textSize = 24f
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6B665F.toInt()
            textSize = 9.5f
        }
        val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = 9f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val bookTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1B1917.toInt()
            textSize = 11.5f
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        val bookMetaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6B665F.toInt()
            textSize = 8.8f
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 7.8f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

        var y: Float
        if (pageNum == 1) {
            val topY = 48f
            val mark = ViblioBookMarkDrawable(accent, 0xFFFAF8F4.toInt())
            mark.setBounds(padX.toInt(), (topY - 11).toInt(), (padX + 14).toInt(), (topY + 3).toInt())
            mark.draw(c)
            c.drawText("VIBLIO · PLANO ANUAL DE LEITURA", padX + 19f, topY, kickerPaint)

            val rightTag = if (data.includeEmptyMonths) "JANEIRO → DEZEMBRO" else "${data.activeMonthCount} MESES"
            c.drawText(rightTag, PDF_W - padX - mutedPaint.measureText(rightTag), topY, mutedPaint)

            c.drawText("Leituras de ${data.year}", padX, topY + 30f, titlePaint)
            val booksLbl = if (data.totalBooks == 1) "1 livro selecionado" else "${numbers.format(data.totalBooks)} livros selecionados"
            val pagesLbl = if (data.totalPages > 0L) " · ${numbers.format(data.totalPages)} páginas" else ""
            val filterLbl = if (data.onlyPlanned) " · Somente a ler" else ""
            c.drawText("$booksLbl$pagesLbl$filterLbl", padX, topY + 46f, mutedPaint)
            c.drawLine(padX, topY + 58f, PDF_W - padX, topY + 58f, borderPaint)
            y = topY + 72f
        } else {
            val topY = 44f
            c.drawText("VIBLIO · LEITURAS DE ${data.year}", padX, topY, kickerPaint)
            val cont = "Continuação"
            c.drawText(cont, PDF_W - padX - mutedPaint.measureText(cont), topY, mutedPaint)
            c.drawLine(padX, topY + 10f, PDF_W - padX, topY + 10f, borderPaint)
            y = topY + 24f
        }

        if (rows.isEmpty()) {
            c.drawText("Nenhum livro selecionado para ${data.year}.", padX, y + 20f, bookTitlePaint)
        } else {
            rows.forEach { row ->
                when (row) {
                    is PdfRow.Spacer -> y += row.height
                    is PdfRow.MonthHeader -> {
                        c.drawText(row.name, padX, y + 15f, monthPaint)
                        y += row.height
                    }
                    is PdfRow.EmptyMonth -> {
                        val rect = RectF(padX, y, padX + contentW, y + 22f)
                        c.drawRect(rect, dashedPaint)
                        c.drawText("Mês livre", padX + 10f, y + 14.5f, bookMetaPaint)
                        y += row.height
                    }
                    is PdfRow.BookItem -> {
                        val item = row.item
                        val cardH = 42f
                        val rect = RectF(padX, y, padX + contentW, y + cardH)
                        c.drawRect(rect, cardFill)
                        c.drawRect(rect, borderPaint)

                        val coverRect = RectF(padX + 6f, y + 5f, padX + 6f + 22f, y + cardH - 5f)
                        drawCover(c, images, item.cover, item.title, coverRect, accent, darkMode = false)

                        val bw = badgePaint.measureText(item.statusLabel) + 12f
                        val bRect = RectF(rect.right - 8f - bw, y + 13f, rect.right - 8f, y + 29f)
                        val bStroke = Paint(borderPaint).apply {
                            color = if (item.completed) accent else 0xFFCCC5BA.toInt()
                        }
                        c.drawRect(bRect, bStroke)
                        badgePaint.color = if (item.completed) accent else 0xFF6B665F.toInt()
                        c.drawText(item.statusLabel, bRect.left + 6f, bRect.top + 11f, badgePaint)

                        val tx = coverRect.right + 10f
                        val maxW = (bRect.left - 8f - tx).coerceAtLeast(60f)
                        c.drawText(ellipsize(item.title, bookTitlePaint, maxW), tx, y + 18.5f, bookTitlePaint)
                        val meta = buildList {
                            if (item.author.isNotBlank()) add(item.author)
                            if (item.pages > 0) add("${numbers.format(item.pages)} págs.")
                        }.joinToString(" · ")
                        if (meta.isNotBlank()) {
                            c.drawText(ellipsize(meta, bookMetaPaint, maxW), tx, y + 32.5f, bookMetaPaint)
                        }
                        y += row.height
                    }
                }
            }
        }

        // Footer
        val footY = PDF_H - 30f
        c.drawLine(padX, footY - 16f, PDF_W - padX, footY - 16f, borderPaint)
        c.drawText("Gerado pelo Viblio · Plano ${data.year}", padX, footY, mutedPaint)
        val pgText = "Página $pageNum de $totalPages"
        c.drawText(pgText, PDF_W - padX - mutedPaint.measureText(pgText), footY, mutedPaint)
    }

    private fun drawCover(
        c: Canvas,
        images: BookImages,
        coverName: String,
        title: String,
        rect: RectF,
        accent: Int,
        darkMode: Boolean,
    ) {
        val bmp = if (coverName.isNotBlank()) runCatching { images.thumbnail(coverName) }.getOrNull() else null
        if (bmp != null) {
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
            val bg = if (darkMode) 0xFF23211E.toInt() else 0xFFEBE6DD.toInt()
            c.drawRect(rect, Paint().apply { color = bg })
            val initial = title.trim().take(1).uppercase(Locale("pt", "BR")).ifBlank { "V" }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = (rect.width() * 0.48f).coerceAtLeast(9f)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
            val fm = p.fontMetrics
            val cy = rect.centerY() - (fm.ascent + fm.descent) * 0.5f
            c.drawText(initial, rect.centerX(), cy, p)
        }
    }

    private fun normalizeAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.25f, 0.65f)
        hsv[2] = hsv[2].coerceAtLeast(0.78f)
        return Color.HSVToColor(hsv)
    }

    private fun darkenForPaper(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.72f)
        hsv[2] = hsv[2].coerceAtMost(0.52f)
        return Color.HSVToColor(hsv)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end = Character.offsetByCodePoints(text, end, -1)
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }
}
