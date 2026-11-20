package br.com.centralcasa.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.books.catalog.BookCatalog
import br.com.centralcasa.books.catalog.CatalogBook
import java.util.Calendar
import java.util.Locale

/**
 * Unified Annual Reading Plan + Real Reading Goals panel.
 * - Integrates existing ReadingGoals (Books, Pages, and 3:1 Purchase Goal with real covers) once at the top (zero duplication).
 * - Wishlist is only accessible when a Purchase Goal has an unlocked reward.
 * - No Joker slots: `+ Livro` navigates to the real Shelf in selection mode (supporting already-read books & rereads).
 * - Each month automatically displays everything completed, read in that month, or currently being read, unified with planned items.
 * - Each month's bottom bar fills 0..100% by real reading progress (books completed + pages read in that month).
 */
class AnnualPlanPanel(
    private val activity: Activity,
    private val ui: DisplayUi,
    private val repo: LibraryRepository,
    private val catalogProvider: () -> BookCatalog?,
    private val onOpenBook: ((String) -> Unit)? = null,
    private val onAddCatalogBook: ((CatalogBook) -> Unit)? = null,
    private val onPickBookFromShelf: ((Int, Int) -> Unit)? = null,
    private val changed: () -> Unit,
) {
    private val store = AnnualPlanStore(activity)
    private val goalStore = ReadingGoalStore(activity)
    private val images = BookImages(activity)
    private val dialogs = mutableSetOf<AlertDialog>()
    private var goalsPanel: ReadingGoalsPanel? = null
    private var selectedYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var sagasExpanded = true
    private var closed = false

    val isEditing: Boolean
        get() = dialogs.any { it.isShowing } || (goalsPanel?.isEditing == true)

    private val colorGreen = Color.rgb(127, 176, 134)
    private val colorAmber = Color.rgb(226, 176, 112)
    private val colorRed = Color.rgb(220, 131, 115)
    private val colorBlue = Color.rgb(110, 155, 194)

    fun selectPeriod(year: Int, month: Int) {
        selectedYear = year.coerceIn(2020, 2100)
        selectedMonth = month.coerceIn(1, 12)
    }

    fun close() {
        closed = true
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
        goalsPanel?.close()
        goalsPanel = null
    }

    private fun tracked(dialog: AlertDialog): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ui.shape(ui.surface, 0))
        return dialog
    }

    private fun alphaColor(color: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun stripeBg(stripeColor: Int): Drawable {
        val bg = ui.surface
        val border = ui.lineColor
        val strokePx = ui.dp(1).coerceAtLeast(1).toFloat()
        val stripePx = ui.dp(3).coerceAtLeast(2).toFloat()
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; style = Paint.Style.FILL }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; style = Paint.Style.STROKE; strokeWidth = strokePx }
            private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stripeColor; style = Paint.Style.FILL }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val half = strokePx / 2f
                canvas.drawRect(b, fillPaint)
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + stripePx, b.bottom.toFloat(), stripePaint)
                canvas.drawRect(b.left + half, b.top + half, b.right - half, b.bottom - half, borderPaint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.OPAQUE
        }
    }

    private fun dashedBg(): Drawable = GradientDrawable().apply {
        setColor(alphaColor(ui.accent, 10))
        setStroke(ui.dp(1).coerceAtLeast(1), alphaColor(ui.accent, 110), ui.dp(5).toFloat(), ui.dp(4).toFloat())
    }

    private fun dotDrawable(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun healthColor(health: MonthHealth): Int = when (health) {
        MonthHealth.COMPLETED, MonthHealth.ON_PACE -> colorGreen
        MonthHealth.INTENSE -> colorAmber
        MonthHealth.MISSED -> colorRed
        MonthHealth.EMPTY -> ui.lineColor
    }

    private fun loadOrCreatePlan(year: Int): AnnualReadingPlan =
        store.load(year) ?: AnnualReadingPlan(year = year, goalMode = PlanGoalMode.NONE)

    private fun mutatePlan(plan: AnnualReadingPlan) {
        if (closed) return
        try {
            store.save(plan)
            changed()
        } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: "Não foi possível salvar o plano.", Toast.LENGTH_LONG).show()
        }
    }

    /** Called by ViblioActivity/BooksPanel after the user taps a book on the real Shelf in selection mode. */
    fun onShelfBookPicked(year: Int, month: Int, book: LibraryBook) {
        if (closed) return
        selectPeriod(year, month)
        val plan = loadOrCreatePlan(year)
        val existingInMonth = plan.entries.any { it.month == month && it.kind == PlanEntryKind.SHELF_BOOK && it.bookId == book.id }
        if (existingInMonth) {
            Toast.makeText(activity, "\"${book.title}\" já está em ${AnnualPlanCalculator.fullName(month)}.", Toast.LENGTH_SHORT).show()
            changed()
            return
        }
        val nextPos = (plan.entries.filter { it.month == month }.maxOfOrNull { it.position } ?: -1) + 1
        fun addEntry(completed: Boolean, reread: Boolean, tag: String) {
            val entry = PlanEntry(
                month = month,
                position = nextPos,
                kind = PlanEntryKind.SHELF_BOOK,
                bookId = book.id,
                title = book.title,
                author = book.author,
                saga = book.saga,
                reservedPages = if (book.pagesKnown) book.totalPages else 200,
                tag = tag,
                completed = completed,
                reread = reread,
            )
            mutatePlan(plan.copy(entries = plan.entries + entry))
        }

        if (!book.complete) {
            addEntry(completed = false, reread = false, tag = if (book.saga.isNotBlank()) "Saga" else "")
            return
        }

        // Book is already completed on the Shelf: check if it was already completed in this month.
        val monthRange = AnnualPlanCalculator.monthRange(year, month)
        val autoCompletedHere = book.id in PeriodCompletions.completedBookIds(
            listOf(book),
            repo.sessions(),
            repo.dates(),
            monthRange,
        )
        if (autoCompletedHere) {
            tracked(
                AlertDialog.Builder(activity)
                    .setTitle(book.title)
                    .setMessage("Este livro já aparece como concluído em ${AnnualPlanCalculator.fullName(month)}. Deseja planejar uma releitura?")
                    .setPositiveButton("Planejar releitura") { _, _ ->
                        addEntry(completed = false, reread = true, tag = "Releitura")
                    }
                    .setNegativeButton("Cancelar", null)
                    .create(),
            )
            return
        }

        tracked(
            AlertDialog.Builder(activity)
                .setTitle(book.title)
                .setMessage("Este livro já está concluído na sua Estante. Como deseja incluir em ${AnnualPlanCalculator.fullName(month)}?")
                .setPositiveButton("Já li neste mês") { _, _ ->
                    addEntry(completed = true, reread = false, tag = if (book.saga.isNotBlank()) "Saga" else "")
                }
                .setNeutralButton("Planejar releitura") { _, _ ->
                    addEntry(completed = false, reread = true, tag = "Releitura")
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    fun render(parent: LinearLayout) {
        if (closed) return
        val plan = loadOrCreatePlan(selectedYear)
        val books = repo.books(true)
        val sessions = repo.sessions()
        val dates = repo.dates()
        val activeGoals = goalStore.entries().mapNotNull { it.goal }
        val now = System.currentTimeMillis()

        val summary = AnnualPlanCalculator.summarize(
            plan = plan,
            books = books,
            sessions = sessions,
            dates = dates,
            goals = activeGoals,
            nowMs = now,
        )

        val wrap = ui.column().apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(16)) }
        parent.addView(wrap)

        // 1) Existing real ReadingGoals integrated once at the top (includes Purchase Goal with real covers + Wishlist ONLY when unlocked!)
        val gp = goalsPanel ?: ReadingGoalsPanel(activity, ui, repo) { if (!closed) changed() }.apply {
            onRedeemPurchaseWithWishlist = { entry, goal, progress ->
                redeemPurchaseFromWishlist(entry, goal, progress)
            }
        }.also { goalsPanel = it }
        gp.renderEmbedded(wrap)

        // 2) Annual Plan Header Row: Year Picker + Effective Rhythm Chip / Config
        val topRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })

        val yearBtn = TextView(activity).apply {
            tag = "plan-year-picker"
            text = "PLANO $selectedYear ▾"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(ui.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ui.outlined(ui.surface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7))
            setOnClickListener { pickYear() }
        }
        topRow.addView(yearBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })

        topRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))

        val targets = summary.effectiveTargets
        val rhythmChipText = when (targets.mode) {
            PlanGoalMode.PAGES_PER_DAY -> "${targets.dailyPagesTarget} p/dia"
            PlanGoalMode.BOOKS_PER_MONTH -> "${targets.monthlyBooksTarget} liv/mês"
            PlanGoalMode.NONE -> "Sem ritmo"
        }
        val modeBtn = TextView(activity).apply {
            tag = "plan-mode-toggle"
            text = rhythmChipText
            textSize = 11f
            setTextColor(ui.text)
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setPadding(ui.dp(9), ui.dp(7), ui.dp(9), ui.dp(7))
            setOnClickListener { openRhythmDialog(plan, targets) }
        }
        topRow.addView(modeBtn, LinearLayout.LayoutParams(-2, -2))

        // 3) Compact Year Real Progress Line
        val yearLine = ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            background = ui.outlined(ui.surface, ui.lineColor, 0)
        }
        wrap.addView(yearLine, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        val yearSummaryLeft = ui.column()
        yearLine.addView(yearSummaryLeft, LinearLayout.LayoutParams(0, -2, 1f))
        val compLabel = if (summary.completedCount == 1) "1 concluído" else "${summary.completedCount} concluídos"
        val pagesLabel = "${summary.totalPagesReadInYear} págs. lidas em ${plan.year}"
        ui.label(yearSummaryLeft, "$compLabel · $pagesLabel", 11, ui.text).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
        summary.estimatedMinutesPerDay?.let { mins ->
            ui.label(yearLine, "~$mins min/dia", 10, ui.accent).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) }
            }
        }

        // 4) Horizontal Month Ribbon (JAN..DEZ) — Bottom bar filled by REAL reading progress (books + pages)!
        val ribbonScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val ribbonRow = ui.row()
        ribbonScroll.addView(ribbonRow)
        wrap.addView(ribbonScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })

        summary.months.forEach { ms ->
            val sel = ms.month == selectedMonth
            val hc = healthColor(ms.health)
            val mBtn = ui.column().apply {
                tag = "plan-month:${ms.month}"
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
                background = ui.outlined(if (sel) ui.activeSurface else ui.surface, if (sel) ui.accent else ui.lineColor, 0)
                isFocusable = true
                setOnClickListener {
                    if (selectedMonth != ms.month) {
                        selectedMonth = ms.month
                        changed()
                    }
                }
            }
            ribbonRow.addView(mBtn, LinearLayout.LayoutParams(ui.dp(48), -2).apply { marginEnd = ui.dp(6) })

            val labelRow = ui.row().apply { gravity = Gravity.CENTER }
            mBtn.addView(labelRow, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(5) })
            ui.label(labelRow, AnnualPlanCalculator.shortName(ms.month), 10, if (sel) ui.accent else ui.muted, bold = sel).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.05f
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            }
            if (ms.health != MonthHealth.EMPTY) {
                labelRow.addView(
                    View(activity).apply { background = dotDrawable(hc) },
                    LinearLayout.LayoutParams(ui.dp(4), ui.dp(4)).apply { marginStart = ui.dp(3) },
                )
            }

            // Bottom progress bar: strictly reflects real read progress (books completed + pages read in this month).
            val miniTrack = FrameLayout(activity).apply { setBackgroundColor(alphaColor(ui.lineColor, 130)) }
            val fillFrac = ms.barProgress.coerceIn(0.0, 1.0).toFloat()
            val miniRow = ui.row()
            if (fillFrac > 0.01f) {
                miniRow.addView(View(activity).apply { setBackgroundColor(hc) }, LinearLayout.LayoutParams(0, -1, fillFrac))
            }
            if (fillFrac < 0.99f) {
                miniRow.addView(View(activity), LinearLayout.LayoutParams(0, -1, (1f - fillFrac).coerceAtLeast(0.01f)))
            }
            miniTrack.addView(miniRow, FrameLayout.LayoutParams(-1, -1))
            mBtn.addView(miniTrack, LinearLayout.LayoutParams(ui.dp(34), ui.dp(4).coerceAtLeast(3)))
        }

        ribbonScroll.post {
            val targetX = ((selectedMonth - 3).coerceAtLeast(0)) * ui.dp(54)
            ribbonScroll.scrollTo(targetX, 0)
        }

        val currentMs = summary.months[(selectedMonth - 1).coerceIn(0, 11)]

        // 5) Rollover banner if previous past month has manually planned pending books
        if (selectedMonth > 1) {
            val prevMs = summary.months[selectedMonth - 2]
            if (prevMs.isPast && prevMs.hasManualPending) {
                val rollover = ui.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(8), ui.dp(8))
                    background = ui.outlined(alphaColor(colorRed, 24), alphaColor(colorRed, 110), 0)
                }
                wrap.addView(rollover, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
                val prevName = AnnualPlanCalculator.fullName(prevMs.month)
                ui.label(rollover, "Planejados pendentes em $prevName", 11, ui.text).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
                }
                TextView(activity).apply {
                    text = "Mover p/ ${AnnualPlanCalculator.shortName(selectedMonth)}"
                    textSize = 10.5f
                    setTextColor(ui.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = ui.outlined(ui.surface, ui.accent, 0)
                    setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
                    setOnClickListener { movePendingFromPrevious(plan, prevMs.month, selectedMonth) }
                }.also { rollover.addView(it, LinearLayout.LayoutParams(-2, -2)) }
            }
        }

        // 6) Selected Month Header + Health Badge + "+ Livro" (Real Shelf Selection Mode!)
        val mHead = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(mHead, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        val mTitleCol = ui.column()
        mHead.addView(mTitleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })

        val mLine1 = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        mTitleCol.addView(mLine1)
        ui.label(mLine1, "${AnnualPlanCalculator.fullName(selectedMonth)} ${plan.year}", 18, ui.text).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) }
        }
        if (currentMs.health != MonthHealth.EMPTY) {
            val hc = healthColor(currentMs.health)
            val badgeText = when (currentMs.health) {
                MonthHealth.COMPLETED -> "Concluído"
                MonthHealth.ON_PACE -> "Dentro da meta"
                MonthHealth.INTENSE -> "Ritmo intenso"
                MonthHealth.MISSED -> "Meta não batida"
                MonthHealth.EMPTY -> ""
            }
            TextView(activity).apply {
                text = badgeText
                textSize = 9.5f
                setTextColor(hc)
                background = ui.outlined(alphaColor(hc, 28), alphaColor(hc, 110), 0)
                setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2))
            }.also { mLine1.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        }

        val subtitleParts = mutableListOf<String>()
        if (currentMs.actualBooksCompleted > 0) {
            subtitleParts.add(if (currentMs.actualBooksCompleted == 1) "1 concluído" else "${currentMs.actualBooksCompleted} concluídos")
        }
        if (currentMs.actualPagesRead > 0) {
            subtitleParts.add("${currentMs.actualPagesRead} págs. lidas")
        }
        if (currentMs.totalRemainingPages > 0) {
            val dayHint = if (currentMs.isCurrent) " (${AnnualPlanCalculator.formatDecimal(currentMs.pacePagesPerDay)} p/dia)" else ""
            subtitleParts.add("restam ${currentMs.totalRemainingPages} págs.$dayHint")
        }
        if (subtitleParts.isEmpty()) {
            subtitleParts.add(if (currentMs.items.isEmpty()) "Sem leituras neste mês" else "${currentMs.items.size} livro(s)")
        }
        ui.label(mTitleCol, subtitleParts.joinToString(" · "), 11, ui.muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }

        TextView(activity).apply {
            tag = "plan-add-item"
            text = "+ Livro"
            textSize = 11f
            setTextColor(ui.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7))
            setOnClickListener { triggerShelfPicker(plan.year, selectedMonth) }
        }.also { mHead.addView(it, LinearLayout.LayoutParams(-2, -2)) }

        // 7) Month Books List (Automatic real activity + manual planned books, unified without duplicates!)
        if (currentMs.items.isEmpty()) {
            val emptyBox = ui.column().apply {
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                background = dashedBg()
            }
            wrap.addView(emptyBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
            ui.label(emptyBox, "Nenhuma leitura registrada ou planejada em ${AnnualPlanCalculator.fullName(selectedMonth)}.", 12, ui.muted)
            ui.action(emptyBox, "+ Escolher da Estante") { triggerShelfPicker(plan.year, selectedMonth) }.apply {
                minHeight = ui.dp(36); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(-1, ui.dp(36)).apply { topMargin = ui.dp(6) }
            }
        } else {
            currentMs.items.forEachIndexed { idx, item ->
                renderBookCard(wrap, plan, item, idx + 1)
            }
        }

        // 8) Collapsible Sagas Summary (only when books in the year belong to sagas)
        if (summary.sagas.isNotEmpty()) {
            val sagaDivider = View(activity).apply { setBackgroundColor(ui.lineColor) }
            wrap.addView(sagaDivider, LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply {
                topMargin = ui.dp(6)
                bottomMargin = ui.dp(8)
            })
            val sagaToggle = ui.row().apply {
                isFocusable = true
                setOnClickListener { sagasExpanded = !sagasExpanded; changed() }
            }
            wrap.addView(sagaToggle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
            ui.label(sagaToggle, "SAGAS EM ${plan.year} (${summary.sagas.size})", 10, ui.muted).apply {
                letterSpacing = 0.09f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            ui.label(sagaToggle, if (sagasExpanded) "▾" else "▸", 12, ui.accent).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            }
            if (sagasExpanded) {
                summary.sagas.chunked(2).forEach { pair ->
                    val sRow = ui.row()
                    wrap.addView(sRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
                    pair.forEachIndexed { i, sp ->
                        val cell = ui.row().apply {
                            setPadding(ui.dp(9), ui.dp(7), ui.dp(9), ui.dp(7))
                            background = ui.outlined(ui.surface, ui.lineColor, 0)
                        }
                        sRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (i == 0 && pair.size == 2) marginEnd = ui.dp(6)
                        })
                        ui.label(cell, sp.name, 11, ui.text).apply {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }
                        }
                        val doneAll = sp.completedCount >= sp.totalCount && sp.totalCount > 0
                        ui.label(
                            cell,
                            if (doneAll) "${sp.completedCount}/${sp.totalCount} ✓" else "${sp.completedCount}/${sp.totalCount}",
                            11,
                            if (doneAll) colorGreen else ui.accent,
                            bold = true,
                        ).apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }
                    }
                    if (pair.size == 1) sRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ui.dp(6) })
                }
            }
        }
    }

    private fun triggerShelfPicker(year: Int, month: Int) {
        val nav = onPickBookFromShelf
        if (nav != null) {
            nav(year, month)
            return
        }
        // Fallback dialog only if host did not wire real Shelf navigation.
        val candidates = repo.books().filter { !it.archived }
            .sortedWith(compareBy<LibraryBook> { if (!it.complete && it.currentPage > 0) 0 else if (!it.complete) 1 else 2 }.thenBy { it.title.lowercase(Locale("pt", "BR")) })
        if (candidates.isEmpty()) {
            Toast.makeText(activity, "Nenhum livro na Estante.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = candidates.map { b ->
            val st = if (b.complete) "Concluído" else if (b.currentPage > 0) "Lendo" else "Não iniciado"
            "${b.title}\n${listOf(b.saga.ifBlank { b.author }, st).filter { it.isNotBlank() }.joinToString(" · ")}"
        }.toTypedArray()
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Escolher da Estante")
                .setItems(labels) { _, idx -> onShelfBookPicked(year, month, candidates[idx]) }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun renderBookCard(parent: LinearLayout, plan: AnnualReadingPlan, item: ResolvedPlanItem, orderInMonth: Int) {
        val stripeCol = when (item.status) {
            PlanItemStatus.COMPLETED -> colorGreen
            PlanItemStatus.READING -> ui.accent
            PlanItemStatus.READ_IN_MONTH -> colorBlue
            PlanItemStatus.PLANNED -> if (item.entry?.tag?.contains("saga", ignoreCase = true) == true) colorBlue else ui.lineColor
        }
        val card = ui.row().apply {
            tag = if (item.entry != null) "plan-entry:${item.entry.id}" else "plan-auto:${item.bookId}"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
            background = stripeBg(stripeCol)
            isFocusable = true
            setOnClickListener {
                if (item.entry != null) openEntryMenu(plan, item)
                else if (item.linkedBook != null) onOpenBook?.invoke(item.linkedBook.id)
            }
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })

        val cov = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setImageDrawable(ui.symbol("book_nav", ui.muted, 24))
            if (item.localCover.isNotBlank()) images.thumbnail(item.localCover)?.let(::setImageBitmap)
        }
        card.addView(cov, LinearLayout.LayoutParams(ui.dp(40), ui.dp(58)).apply { marginEnd = ui.dp(10) })

        val col = ui.column()
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        val r1 = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(r1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) })
        val ordStr = item.sequenceNumber?.let { "${it.toString().padStart(2, '0')}º" } ?: "${orderInMonth.toString().padStart(2, '0')}º"
        val tagLabel = when {
            item.isReread -> "Releitura"
            !item.entry?.tag.isNullOrBlank() -> item.entry!!.tag
            item.isAutoActivity -> "Leitura do mês"
            else -> ""
        }
        val kicker = listOf(ordStr, tagLabel).filter { it.isNotBlank() }.joinToString(" · ").uppercase(Locale("pt", "BR"))
        ui.label(r1, kicker, 9, if (item.isAutoActivity) ui.muted else ui.accent).apply {
            letterSpacing = 0.06f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }
        }
        val statusCol = when (item.status) {
            PlanItemStatus.COMPLETED -> colorGreen
            PlanItemStatus.READING -> ui.accent
            PlanItemStatus.READ_IN_MONTH -> colorBlue
            PlanItemStatus.PLANNED -> ui.muted
        }
        ui.label(r1, item.status.label, 10, statusCol, bold = item.status == PlanItemStatus.COMPLETED || item.status == PlanItemStatus.READING).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

        ui.label(col, item.title, 15, ui.text).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(2)
        }

        val detailParts = mutableListOf<String>()
        val identity = item.saga.ifBlank { item.author }
        if (identity.isNotBlank()) detailParts.add(identity)
        when (item.status) {
            PlanItemStatus.COMPLETED -> {
                if (item.pagesReadInMonth > 0) detailParts.add("${item.pagesReadInMonth} págs. no mês")
                else detailParts.add("Concluído")
            }
            PlanItemStatus.READING -> {
                if (item.pagesReadInMonth > 0) detailParts.add("${item.pagesReadInMonth} págs. no mês")
                if (item.remainingPages > 0) detailParts.add("restam ${item.remainingPages} págs.")
            }
            PlanItemStatus.READ_IN_MONTH -> {
                if (item.pagesReadInMonth > 0) detailParts.add("${item.pagesReadInMonth} págs. lidas no mês")
                else detailParts.add("Leitura registrada")
            }
            PlanItemStatus.PLANNED -> {
                if (item.remainingPages > 0) detailParts.add("${item.remainingPages} págs. restantes")
                else detailParts.add("Páginas não informadas")
            }
        }
        ui.label(col, detailParts.joinToString(" · "), 10, ui.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
    }

    private fun redeemPurchaseFromWishlist(
        entry: ReadingGoalStore.Entry,
        goal: ReadingGoal,
        progress: GoalProgress,
    ) {
        val cat = catalogProvider()
        if (cat == null || !cat.configured) {
            Toast.makeText(activity, "Conecte sua conta para acessar Quero comprar.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, "Carregando Quero comprar…", Toast.LENGTH_SHORT).show()
        cat.wishlist { res ->
            if (closed) return@wishlist
            res.fold({ list ->
                if (list.isEmpty()) {
                    Toast.makeText(activity, "Sua lista Quero comprar está vazia. Salve livros na aba Pesquisa.", Toast.LENGTH_LONG).show()
                    return@fold
                }
                val labels = list.map { b ->
                    val extra = listOf(b.author, b.pages?.let { "${it}p" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                    if (extra.isBlank()) b.title else "${b.title}\n$extra"
                }.toTypedArray()
                tracked(
                    AlertDialog.Builder(activity)
                        .setTitle("Usar recompensa · Quero comprar")
                        .setItems(labels) { _, idx ->
                            val chosen = list[idx]
                            tracked(
                                AlertDialog.Builder(activity)
                                    .setTitle(chosen.title)
                                    .setMessage("Registrar o resgate da meta \"${goal.name}\", remover da Wishlist e cadastrar na Estante?")
                                    .setPositiveButton("Resgatar e cadastrar") { _, _ ->
                                        runCatching {
                                            val fresh = ReadingGoals.progress(goal, repo.books(true), repo.sessions(), System.currentTimeMillis())
                                            goalStore.save(ReadingGoals.purchase(goal, fresh, System.currentTimeMillis()), entry.raw)
                                        }.onFailure {
                                            Toast.makeText(activity, it.message ?: "Não foi possível registrar a compra.", Toast.LENGTH_LONG).show()
                                            return@setPositiveButton
                                        }
                                        cat.removeWishlist(chosen.id) {}
                                        changed()
                                        onAddCatalogBook?.invoke(chosen)
                                    }
                                    .setNegativeButton("Cancelar", null)
                                    .create(),
                            )
                        }
                        .setNegativeButton("Cancelar", null)
                        .create(),
                )
            }, {
                Toast.makeText(activity, "Não foi possível carregar Quero comprar.", Toast.LENGTH_LONG).show()
            })
        }
    }

    private fun pickYear() {
        val current = Calendar.getInstance().get(Calendar.YEAR)
        val options = (store.years() + listOf(current - 1, current, current + 1)).distinct().sorted()
        val labels = options.map { y -> "$y" }.toTypedArray()
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Escolher ano")
                .setItems(labels) { _, idx ->
                    selectedYear = options[idx]
                    changed()
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun openRhythmDialog(plan: AnnualReadingPlan, targets: EffectivePlanTargets) {
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8)) }
        if (targets.derivedFromReadingGoals) {
            ui.label(body, "Ritmo calculado automaticamente pelas suas metas ativas acima.", 12, ui.muted).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(8)
            }
        }
        var chosenMode = plan.goalMode
        ui.label(body, "Referência de ritmo do plano", 12, ui.muted)
        val modeRow = ui.row()
        body.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })

        val targetLabel = ui.label(body, "", 12, ui.muted)
        val targetInput = EditText(activity).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(ui.text)
            setHintTextColor(ui.muted)
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        }
        body.addView(targetInput, LinearLayout.LayoutParams(-1, ui.dp(44)).apply { bottomMargin = ui.dp(8) })

        val modeButtons = mutableMapOf<PlanGoalMode, TextView>()
        fun repaintModes() {
            modeButtons.forEach { (m, btn) ->
                val on = m == chosenMode
                btn.setTextColor(if (on) ui.background else ui.text)
                btn.background = if (on) ui.shape(ui.accent, 0) else ui.outlined(ui.activeSurface, ui.lineColor, 0)
            }
            when (chosenMode) {
                PlanGoalMode.PAGES_PER_DAY -> {
                    targetLabel.visibility = View.VISIBLE
                    targetInput.visibility = View.VISIBLE
                    targetLabel.text = "Páginas por dia"
                    targetInput.setText(plan.dailyPagesTarget.toString())
                }
                PlanGoalMode.BOOKS_PER_MONTH -> {
                    targetLabel.visibility = View.VISIBLE
                    targetInput.visibility = View.VISIBLE
                    targetLabel.text = "Livros por mês"
                    targetInput.setText(plan.monthlyBooksTarget.toString())
                }
                PlanGoalMode.NONE -> {
                    targetLabel.visibility = View.GONE
                    targetInput.visibility = View.GONE
                }
            }
        }
        PlanGoalMode.entries.forEachIndexed { idx, mode ->
            val btn = TextView(activity).apply {
                text = mode.label
                gravity = Gravity.CENTER
                textSize = 11f
                setPadding(ui.dp(6), ui.dp(9), ui.dp(6), ui.dp(9))
                setOnClickListener { chosenMode = mode; repaintModes() }
            }
            modeButtons[mode] = btn
            modeRow.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { if (idx < 2) marginEnd = ui.dp(6) })
        }
        repaintModes()

        val dlg = tracked(
            AlertDialog.Builder(activity)
                .setTitle("Ritmo do plano ${plan.year}")
                .setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton("Salvar", null)
                .setNegativeButton("Cancelar", null)
                .create(),
        )
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawNum = targetInput.text.toString().trim().toIntOrNull()
            val updated = when (chosenMode) {
                PlanGoalMode.PAGES_PER_DAY -> {
                    if (rawNum == null || rawNum !in 1..2000) {
                        targetInput.error = "Informe entre 1 e 2.000 páginas/dia."
                        return@setOnClickListener
                    }
                    plan.copy(goalMode = chosenMode, dailyPagesTarget = rawNum)
                }
                PlanGoalMode.BOOKS_PER_MONTH -> {
                    if (rawNum == null || rawNum !in 1..100) {
                        targetInput.error = "Informe entre 1 e 100 livros/mês."
                        return@setOnClickListener
                    }
                    plan.copy(goalMode = chosenMode, monthlyBooksTarget = rawNum)
                }
                PlanGoalMode.NONE -> plan.copy(goalMode = chosenMode)
            }
            dlg.dismiss()
            mutatePlan(updated)
        }
    }

    private fun openEntryMenu(plan: AnnualReadingPlan, item: ResolvedPlanItem) {
        val entry = item.entry ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (item.linkedBook != null && onOpenBook != null) {
            actions.add("Abrir livro na Estante" to { onOpenBook.invoke(item.linkedBook.id) })
        }
        if (item.isReread) {
            actions.add(
                (if (entry.completed) "Desmarcar conclusão da releitura" else "Marcar releitura concluída") to {
                    mutatePlan(plan.copy(entries = plan.entries.map { if (it.id == entry.id) it.copy(completed = !it.completed) else it }))
                },
            )
        }
        actions.add("Mover para outro mês" to { pickTargetMonth(plan, entry) })
        actions.add("Editar etiqueta" to { editTag(plan, entry) })
        actions.add("Remover do plano" to {
            mutatePlan(plan.copy(entries = plan.entries.filterNot { it.id == entry.id }))
        })
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setItems(actions.map { it.first }.toTypedArray()) { _, idx -> actions[idx].second.invoke() }
                .setNegativeButton("Fechar", null)
                .create(),
        )
    }

    private fun pickTargetMonth(plan: AnnualReadingPlan, entry: PlanEntry) {
        val names = (1..12).map { AnnualPlanCalculator.fullName(it) }.toTypedArray()
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Mover para qual mês?")
                .setItems(names) { _, idx ->
                    val targetMonth = idx + 1
                    val nextPos = (plan.entries.filter { it.month == targetMonth }.maxOfOrNull { it.position } ?: -1) + 1
                    mutatePlan(
                        plan.copy(entries = plan.entries.map {
                            if (it.id == entry.id) it.copy(month = targetMonth, position = nextPos) else it
                        }),
                    )
                    selectedMonth = targetMonth
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun editTag(plan: AnnualReadingPlan, entry: PlanEntry) {
        val presets = arrayOf("Fecha Saga", "Pausa / Saga", "Saga", "Releitura", "Leitura leve", "Sem etiqueta")
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Etiqueta do item")
                .setItems(presets) { _, idx ->
                    val chosen = if (idx == presets.lastIndex) "" else presets[idx]
                    mutatePlan(plan.copy(entries = plan.entries.map { if (it.id == entry.id) it.copy(tag = chosen) else it }))
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun movePendingFromPrevious(plan: AnnualReadingPlan, fromMonth: Int, toMonth: Int) {
        val booksById = repo.books().associateBy { it.id }
        var nextPos = (plan.entries.filter { it.month == toMonth }.maxOfOrNull { it.position } ?: -1) + 1
        val updated = plan.entries.map { e ->
            if (e.month == fromMonth && e.kind == PlanEntryKind.SHELF_BOOK) {
                val resolved = AnnualPlanCalculator.resolveItem(e, booksById)
                if (resolved.status != PlanItemStatus.COMPLETED) {
                    e.copy(month = toMonth, position = nextPos++)
                } else e
            } else e
        }
        mutatePlan(plan.copy(entries = updated))
    }
}
