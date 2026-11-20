package br.com.centralcasa.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.books.catalog.BookCatalog
import br.com.centralcasa.books.catalog.CatalogBook
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

/**
 * Unified "Metas" tab per year (Proposta B compact layout):
 * - Single source of truth per year (2026 is completely isolated from 2027).
 * - Books goal (Off / Per Month / Per Year) and Pages goal (Off / Per Day / Per Month) recalculate
 *   top cards, monthly subtitles, month ribbon bars, and health badges immediately.
 * - Optional continuous Purchase Goal (3:1) strip with real mini-covers; Wishlist appears ONLY when unlocked.
 * - Any book (manual or automatic) can be removed from a month, from the yearly goal, or from the Purchase cycle
 *   without touching the Shelf or reading history.
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
    private val numbers = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
    private var goalsPanel: ReadingGoalsPanel? = null
    private var selectedYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var sagasExpanded = false
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

    private fun dashedSlotBg(strokeColor: Int = ui.lineColor): Drawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(ui.dp(1).coerceAtLeast(1), strokeColor, ui.dp(3).toFloat(), ui.dp(3).toFloat())
    }

    private fun dotDrawable(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun progressBar(parent: LinearLayout, pct: Int, fillColor: Int, heightDp: Int = 3, topDp: Int = 6) {
        val track = FrameLayout(activity).apply { setBackgroundColor(alphaColor(ui.lineColor, 140)) }
        val row = ui.row()
        val clamped = pct.coerceIn(0, 100)
        if (clamped > 0) row.addView(View(activity).apply { setBackgroundColor(fillColor) }, LinearLayout.LayoutParams(0, -1, clamped.toFloat()))
        if (clamped < 100) row.addView(View(activity), LinearLayout.LayoutParams(0, -1, (100 - clamped).toFloat()))
        track.addView(row, FrameLayout.LayoutParams(-1, -1))
        parent.addView(track, LinearLayout.LayoutParams(-1, ui.dp(heightDp).coerceAtLeast(3)).apply { topMargin = ui.dp(topDp) })
    }

    private fun healthColor(health: MonthHealth): Int = when (health) {
        MonthHealth.COMPLETED, MonthHealth.ON_PACE -> colorGreen
        MonthHealth.INTENSE -> colorAmber
        MonthHealth.MISSED -> colorRed
        MonthHealth.EMPTY -> ui.lineColor
    }

    /**
     * Loads the unified goal+plan for [year].
     * For the current calendar year ONLY, if not yet configured in v2, performs a one-time smooth seed
     * from the latest legacy BOOKS/PAGES ReadingGoal so existing users never lose their current year's target,
     * while strictly keeping other years (e.g. 2027) completely isolated.
     */
    private fun loadPlanForYear(year: Int): AnnualReadingPlan {
        val existing = store.load(year)
        if (existing != null && existing.goalConfigured) return existing
        val base = existing ?: AnnualReadingPlan(year = year)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        if (year != currentYear) return base
        // Already has an explicit v1 goalMode -> preserve it as configured.
        if (base.booksGoalType != BooksGoalType.NONE || base.pagesGoalType != PagesGoalType.NONE) {
            return base.copy(goalConfigured = true)
        }
        val legacyGoals = goalStore.entries().mapNotNull { it.goal }.sortedByDescending { it.createdAt }
        val legacyBooks = legacyGoals.firstOrNull { it.metric == GoalMetric.BOOKS }
        val legacyPages = legacyGoals.firstOrNull { it.metric == GoalMetric.PAGES }
        if (legacyBooks == null && legacyPages == null) return base

        val bType = when (legacyBooks?.period) {
            GoalPeriod.MONTH -> BooksGoalType.PER_MONTH
            GoalPeriod.YEAR, GoalPeriod.CONTINUOUS -> BooksGoalType.PER_YEAR
            GoalPeriod.WEEK -> BooksGoalType.PER_MONTH
            GoalPeriod.DAY -> BooksGoalType.PER_MONTH
            null -> BooksGoalType.NONE
        }
        val bVal = when (legacyBooks?.period) {
            GoalPeriod.WEEK -> (legacyBooks.target * 4).coerceIn(1, 5000)
            GoalPeriod.DAY -> (legacyBooks.target * 30).coerceIn(1, 5000)
            else -> (legacyBooks?.target ?: 2).coerceIn(1, 5000)
        }
        val pType = when (legacyPages?.period) {
            GoalPeriod.DAY -> PagesGoalType.PER_DAY
            GoalPeriod.WEEK -> PagesGoalType.PER_DAY
            GoalPeriod.MONTH, GoalPeriod.YEAR, GoalPeriod.CONTINUOUS -> PagesGoalType.PER_MONTH
            null -> PagesGoalType.NONE
        }
        val pVal = when (legacyPages?.period) {
            GoalPeriod.WEEK -> ((legacyPages.target + 6) / 7).coerceIn(1, 1_000_000)
            GoalPeriod.YEAR -> ((legacyPages.target + 11) / 12).coerceIn(1, 1_000_000)
            else -> (legacyPages?.target ?: 30).coerceIn(1, 1_000_000)
        }
        val seeded = base.copy(
            booksGoalType = bType,
            booksGoalValue = bVal,
            pagesGoalType = pType,
            pagesGoalValue = pVal,
            goalConfigured = true,
        )
        runCatching { store.save(seeded) }
        return seeded
    }

    private fun mutatePlan(plan: AnnualReadingPlan) {
        if (closed) return
        try {
            store.save(plan.copy(goalConfigured = true))
            changed()
        } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: "Não foi possível salvar a meta.", Toast.LENGTH_LONG).show()
        }
    }

    private fun goalsHost(): ReadingGoalsPanel =
        goalsPanel ?: ReadingGoalsPanel(activity, ui, repo) { if (!closed) changed() }.apply {
            onRedeemPurchaseWithWishlist = { entry, goal, progress ->
                redeemPurchaseFromWishlist(entry, goal, progress)
            }
        }.also { goalsPanel = it }

    /** Called after the user taps a book on the real Shelf in selection mode. */
    fun onShelfBookPicked(year: Int, month: Int, book: LibraryBook) {
        if (closed) return
        selectPeriod(year, month)
        val rawPlan = loadPlanForYear(year)
        // Re-adding a previously excluded book clears its exclusion for this month/year automatically!
        val monthKey = AnnualReadingPlan.monthExclusionKey(month, book.id)
        val plan = rawPlan.copy(
            excludedMonthBooks = rawPlan.excludedMonthBooks - monthKey,
            excludedYearBooks = rawPlan.excludedYearBooks - book.id,
        )
        val existingInMonth = plan.entries.any { it.month == month && it.kind == PlanEntryKind.SHELF_BOOK && it.bookId == book.id }
        if (existingInMonth) {
            Toast.makeText(activity, "\"${book.title}\" já está em ${AnnualPlanCalculator.fullName(month)}.", Toast.LENGTH_SHORT).show()
            if (plan != rawPlan) mutatePlan(plan) else changed()
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

        val monthRange = AnnualPlanCalculator.monthRange(year, month)
        val autoCompletedHere = book.id in PeriodCompletions.completedBookIds(
            listOf(book),
            repo.sessions(),
            repo.dates(),
            monthRange,
        )
        if (autoCompletedHere) {
            // If it was previously excluded, simply restoring it brings it back automatically!
            if (plan != rawPlan) {
                mutatePlan(plan)
                return
            }
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
        val plan = loadPlanForYear(selectedYear)
        val books = repo.books(true)
        val sessions = repo.sessions()
        val dates = repo.dates()
        val now = System.currentTimeMillis()
        val gp = goalsHost()
        val purchaseGoalEntry = goalStore.entries().firstOrNull { it.goal?.metric == GoalMetric.PURCHASE }

        val summary = AnnualPlanCalculator.summarize(
            plan = plan,
            books = books,
            sessions = sessions,
            dates = dates,
            currentBookId = repo.currentId(),
            nowMs = now,
        )
        val currentMs = summary.months[(selectedMonth - 1).coerceIn(0, 11)]

        val wrap = ui.column().apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(16)) }
        parent.addView(wrap)

        // 1) Slim Top Bar: [ 2026 ▾ ]  ...  [ Configurar ]
        val topRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(topRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })

        TextView(activity).apply {
            tag = "plan-year-picker"
            text = "$selectedYear ▾"
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(ui.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ui.outlined(ui.surface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            setOnClickListener { pickYear() }
        }.also { topRow.addView(it, LinearLayout.LayoutParams(-2, -2)) }

        topRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))

        TextView(activity).apply {
            tag = "goal-configure"
            text = "Configurar"
            textSize = 11.5f
            setTextColor(ui.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            setOnClickListener { openGoalConfigDialog(plan, purchaseGoalEntry) }
        }.also { topRow.addView(it, LinearLayout.LayoutParams(-2, -2)) }

        // 2) Unified Yearly Goal Strip (STRICTLY for selectedYear — 100% identical numbers to months!)
        val hasBooksGoal = plan.booksGoalType != BooksGoalType.NONE
        val hasPagesGoal = plan.pagesGoalType != PagesGoalType.NONE

        if (hasBooksGoal || hasPagesGoal) {
            val kpiRow = ui.row().apply { gravity = Gravity.TOP }
            wrap.addView(kpiRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })

            if (hasBooksGoal) {
                val leftCard = ui.column().apply {
                    tag = "plan-kpi-books"
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(9))
                    background = ui.outlined(ui.surface, ui.lineColor, 0)
                    isFocusable = true
                    setOnClickListener { openYearBooksDialog(plan, summary, purchaseGoalEntry) }
                }
                kpiRow.addView(leftCard, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (hasPagesGoal) marginEnd = ui.dp(8)
                })
                val pct = if (summary.annualBookGoal > 0) ((summary.completedCount * 100) / summary.annualBookGoal).coerceIn(0, 100) else 0
                val rTop = ui.row()
                leftCard.addView(rTop)
                val titleStr = if (plan.booksGoalType == BooksGoalType.PER_MONTH) {
                    "LIVROS ${plan.year} · ${plan.booksGoalValue}/MÊS"
                } else {
                    "LIVROS ${plan.year}"
                }
                ui.label(rTop, titleStr, 9, ui.muted).apply {
                    letterSpacing = 0.05f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) }
                }
                ui.label(rTop, "$pct%", 9, ui.accent, bold = true).apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }

                val rVal = ui.row().apply { gravity = Gravity.BOTTOM }
                leftCard.addView(rVal, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
                ui.label(rVal, numbers.format(summary.completedCount), 20, ui.accent).apply {
                    typeface = Typeface.create("serif", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(4) }
                }
                ui.label(rVal, "/ ${numbers.format(summary.annualBookGoal)} livros", 10, ui.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                progressBar(leftCard, pct, if (pct >= 100) colorGreen else ui.accent, heightDp = 3, topDp = 6)
            }

            if (hasPagesGoal) {
                val rightCard = ui.column().apply {
                    tag = "plan-kpi-pages"
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(9))
                    background = ui.outlined(ui.surface, ui.lineColor, 0)
                    isFocusable = true
                    setOnClickListener { openGoalConfigDialog(plan, purchaseGoalEntry) }
                }
                kpiRow.addView(rightCard, LinearLayout.LayoutParams(0, -2, 1f))
                val minHint = summary.estimatedMinutesPerDay?.let { "~${it}m/d" }
                val monthTarget = currentMs.monthlyPagesTarget.coerceAtLeast(1)
                val monthPct = ((currentMs.actualPagesRead * 100) / monthTarget).coerceIn(0, 100)

                val rTop = ui.row()
                rightCard.addView(rTop)
                val pTitle = when (plan.pagesGoalType) {
                    PagesGoalType.PER_DAY -> "PÁGINAS · ${AnnualPlanCalculator.shortName(selectedMonth)}"
                    PagesGoalType.PER_MONTH -> "PÁGINAS · ${AnnualPlanCalculator.shortName(selectedMonth)}"
                    PagesGoalType.NONE -> "PÁGINAS"
                }
                ui.label(rTop, pTitle, 9, ui.muted).apply {
                    letterSpacing = 0.05f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) }
                }
                ui.label(rTop, minHint ?: "$monthPct%", 9, ui.muted).apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }

                val rVal = ui.row().apply { gravity = Gravity.BOTTOM }
                rightCard.addView(rVal, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
                if (plan.pagesGoalType == PagesGoalType.PER_DAY) {
                    val elapsedDays = if (currentMs.isCurrent) {
                        (currentMs.daysInMonth - currentMs.remainingDays + 1).coerceAtLeast(1)
                    } else currentMs.daysInMonth.coerceAtLeast(1)
                    val actualDaily = currentMs.actualPagesRead.toDouble() / elapsedDays
                    ui.label(rVal, AnnualPlanCalculator.formatDecimal(actualDaily), 20, ui.text).apply {
                        typeface = Typeface.create("serif", Typeface.NORMAL)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(4) }
                    }
                    ui.label(rVal, "/ ${numbers.format(plan.pagesGoalValue)} p/d", 10, ui.muted).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                } else {
                    ui.label(rVal, numbers.format(currentMs.actualPagesRead), 20, ui.text).apply {
                        typeface = Typeface.create("serif", Typeface.NORMAL)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(4) }
                    }
                    ui.label(rVal, "/ ${numbers.format(plan.pagesGoalValue)} p", 10, ui.muted).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                }
                progressBar(rightCard, monthPct, colorGreen, heightDp = 3, topDp = 6)
            }
        } else {
            // No yearly reading goal enabled -> clean single-line year summary + quick "Definir meta" link
            val noGoalBar = ui.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                background = ui.outlined(ui.surface, ui.lineColor, 0)
                setOnClickListener { openYearBooksDialog(plan, summary, purchaseGoalEntry) }
            }
            wrap.addView(noGoalBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            val doneStr = if (summary.completedCount == 1) "1 concluído" else "${summary.completedCount} concluídos"
            ui.label(noGoalBar, "$doneStr · ${numbers.format(summary.totalPagesReadInYear)} págs. em ${plan.year}", 11, ui.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
            }
            TextView(activity).apply {
                text = "Definir meta ›"
                textSize = 10.5f
                setTextColor(ui.accent)
                setOnClickListener { openGoalConfigDialog(plan, purchaseGoalEntry) }
            }.also { noGoalBar.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        }

        // 3) Compact Horizontal Purchase 3:1 Strip (continuous cycle across years, optional, real mini-covers!)
        if (purchaseGoalEntry?.goal != null) {
            val pGoal = purchaseGoalEntry.goal
            val pProg = ReadingGoals.progress(pGoal, books, sessions, now)
            val unlocked = pProg.value >= pGoal.target

            val strip = ui.row().apply {
                tag = "plan-purchase-strip"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                background = ui.outlined(
                    if (unlocked) ui.activeSurface else ui.surface,
                    if (unlocked) ui.accent else ui.lineColor,
                    0,
                )
                isFocusable = true
                setOnClickListener { gp.openGoalDetailDialog(purchaseGoalEntry) }
            }
            wrap.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })

            val slots = minOf(pGoal.target, 3)
            val coversRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            strip.addView(coversRow, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
            repeat(slots) { idx ->
                val credit = pProg.credits.getOrNull(idx)
                val frame = FrameLayout(activity).apply {
                    background = if (credit != null) ui.outlined(ui.activeSurface, ui.accent, 0) else dashedSlotBg()
                }
                coversRow.addView(frame, LinearLayout.LayoutParams(ui.dp(24), ui.dp(36)).apply {
                    if (idx < slots - 1) marginEnd = ui.dp(4)
                })
                if (credit != null) {
                    val bmp = images.thumbnail(credit.cover)
                    if (bmp != null) {
                        frame.addView(
                            ImageView(activity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setImageBitmap(bmp)
                            },
                            FrameLayout.LayoutParams(-1, -1).apply { setMargins(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1)) },
                        )
                    } else {
                        frame.addView(
                            TextView(activity).apply {
                                text = credit.title.take(1).uppercase()
                                textSize = 11f
                                setTextColor(ui.accent)
                                typeface = Typeface.create("serif", Typeface.NORMAL)
                                gravity = Gravity.CENTER
                            },
                            FrameLayout.LayoutParams(-1, -1),
                        )
                    }
                } else {
                    frame.addView(
                        TextView(activity).apply {
                            text = "+"
                            textSize = 10f
                            setTextColor(ui.muted)
                            gravity = Gravity.CENTER
                        },
                        FrameLayout.LayoutParams(-1, -1),
                    )
                }
            }

            val pCol = ui.column()
            strip.addView(pCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
            ui.label(pCol, "${pGoal.name.uppercase(Locale("pt", "BR"))} ${pGoal.target}:${pGoal.reward}", 9, ui.muted).apply {
                letterSpacing = 0.06f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(1)
            }
            val doneInCycle = minOf(pProg.value, pGoal.target.toLong())
            val statusStr = if (unlocked) {
                "$doneInCycle/${pGoal.target} · Liberada!"
            } else {
                val rem = pProg.remaining(pGoal)
                "$doneInCycle/${pGoal.target} lidos · ${if (rem == 1L) "falta 1" else "faltam $rem"}"
            }
            ui.label(pCol, statusStr, 11, if (unlocked) colorGreen else ui.text, bold = unlocked).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            }

            if (unlocked) {
                TextView(activity).apply {
                    tag = "plan-purchase-wishlist"
                    text = "Wishlist ›"
                    textSize = 11f
                    setTextColor(ui.background)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = ui.shape(ui.accent, 0)
                    setPadding(ui.dp(9), ui.dp(6), ui.dp(9), ui.dp(6))
                    setOnClickListener { redeemPurchaseFromWishlist(purchaseGoalEntry, pGoal, pProg) }
                }.also { strip.addView(it, LinearLayout.LayoutParams(-2, -2)) }
            } else {
                ui.label(strip, "Ver ciclo ›", 10, ui.accent).apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2)
                }
            }
        }

        // 4) Horizontal Month Ribbon (JAN..DEZ) — Bottom bar immediately reflects real progress vs monthly target!
        val ribbonScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val ribbonRow = ui.row()
        ribbonScroll.addView(ribbonRow)
        wrap.addView(ribbonScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2); bottomMargin = ui.dp(10) })

        summary.months.forEach { ms ->
            val sel = ms.month == selectedMonth
            val hc = healthColor(ms.health)
            val mBtn = ui.column().apply {
                tag = "plan-month:${ms.month}"
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(5), ui.dp(6), ui.dp(5), ui.dp(6))
                background = ui.outlined(if (sel) ui.activeSurface else ui.surface, if (sel) ui.accent else ui.lineColor, 0)
                isFocusable = true
                setOnClickListener {
                    if (selectedMonth != ms.month) {
                        selectedMonth = ms.month
                        changed()
                    }
                }
            }
            ribbonRow.addView(mBtn, LinearLayout.LayoutParams(ui.dp(50), -2).apply { marginEnd = ui.dp(6) })

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
            mBtn.addView(miniTrack, LinearLayout.LayoutParams(ui.dp(36), ui.dp(4).coerceAtLeast(3)))
        }

        ribbonScroll.post {
            val targetX = ((selectedMonth - 3).coerceAtLeast(0)) * ui.dp(56)
            ribbonScroll.scrollTo(targetX, 0)
        }

        // 5) Rollover banner if previous past month has manually planned pending books
        if (selectedMonth > 1) {
            val prevMs = summary.months[selectedMonth - 2]
            if (prevMs.isPast && prevMs.hasManualPending) {
                val rollover = ui.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(10), ui.dp(7), ui.dp(8), ui.dp(7))
                    background = ui.outlined(alphaColor(colorRed, 22), alphaColor(colorRed, 100), 0)
                }
                wrap.addView(rollover, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
                ui.label(rollover, "Planejados pendentes em ${AnnualPlanCalculator.fullName(prevMs.month)}", 11, ui.text).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
                }
                TextView(activity).apply {
                    text = "Mover p/ ${AnnualPlanCalculator.shortName(selectedMonth)}"
                    textSize = 10.5f
                    setTextColor(ui.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = ui.outlined(ui.surface, ui.accent, 0)
                    setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
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
        ui.label(mLine1, "${AnnualPlanCalculator.fullName(selectedMonth)} ${plan.year}", 17, ui.text).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(7) }
        }
        if (currentMs.health != MonthHealth.EMPTY) {
            val hc = healthColor(currentMs.health)
            val badgeText = when (currentMs.health) {
                MonthHealth.COMPLETED -> "Meta batida"
                MonthHealth.ON_PACE -> "Dentro da meta"
                MonthHealth.INTENSE -> "Ritmo intenso"
                MonthHealth.MISSED -> "Meta não batida"
                MonthHealth.EMPTY -> ""
            }
            TextView(activity).apply {
                text = badgeText
                textSize = 9.5f
                setTextColor(hc)
                background = ui.outlined(alphaColor(hc, 24), alphaColor(hc, 100), 0)
                setPadding(ui.dp(5), ui.dp(2), ui.dp(5), ui.dp(2))
            }.also { mLine1.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        }

        val subtitleParts = mutableListOf<String>()
        if (currentMs.monthlyBooksTarget > 0) {
            subtitleParts.add("${currentMs.actualBooksCompleted}/${currentMs.monthlyBooksTarget} livros")
        } else if (currentMs.actualBooksCompleted > 0) {
            subtitleParts.add(if (currentMs.actualBooksCompleted == 1) "1 concluído" else "${currentMs.actualBooksCompleted} concluídos")
        }
        if (currentMs.monthlyPagesTarget > 0) {
            subtitleParts.add("${numbers.format(currentMs.actualPagesRead)}/${numbers.format(currentMs.monthlyPagesTarget)} págs.")
        } else if (currentMs.actualPagesRead > 0) {
            subtitleParts.add("${numbers.format(currentMs.actualPagesRead)} págs. lidas")
        }
        if (currentMs.totalRemainingPages > 0) {
            val paceHint = if (!currentMs.isPast) " (${AnnualPlanCalculator.formatDecimal(currentMs.pacePagesPerDay)} p/d)" else ""
            subtitleParts.add("restam ${numbers.format(currentMs.totalRemainingPages)}$paceHint")
        }
        if (subtitleParts.isEmpty()) {
            subtitleParts.add("Sem leituras neste mês")
        }
        ui.label(mTitleCol, subtitleParts.joinToString(" · "), 10, ui.muted).apply {
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
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            setOnClickListener { triggerShelfPicker(plan.year, selectedMonth) }
        }.also { mHead.addView(it, LinearLayout.LayoutParams(-2, -2)) }

        // 7) Month Books List — crisp 2-line cards; ANY book (manual or auto) can be removed!
        if (currentMs.items.isEmpty()) {
            val emptyBox = ui.column().apply {
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                background = ui.outlined(ui.surface, ui.lineColor, 0)
            }
            wrap.addView(emptyBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            ui.label(emptyBox, "Nenhum livro em ${AnnualPlanCalculator.fullName(selectedMonth)}.", 12, ui.muted)
            ui.action(emptyBox, "+ Escolher da Estante") { triggerShelfPicker(plan.year, selectedMonth) }.apply {
                minHeight = ui.dp(36); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(-1, ui.dp(36)).apply { topMargin = ui.dp(6) }
            }
        } else {
            currentMs.items.forEach { item ->
                renderBookCard(wrap, plan, selectedMonth, item)
            }
        }

        // 8) Collapsible Sagas Summary
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
                letterSpacing = 0.08f
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

    private fun renderBookCard(parent: LinearLayout, plan: AnnualReadingPlan, month: Int, item: ResolvedPlanItem) {
        val stripeCol = when (item.status) {
            PlanItemStatus.COMPLETED -> colorGreen
            PlanItemStatus.READING -> ui.accent
            PlanItemStatus.READ_IN_MONTH -> colorBlue
            PlanItemStatus.PLANNED -> ui.lineColor
        }
        val card = ui.row().apply {
            tag = if (item.entry != null) "plan-entry:${item.entry.id}" else "plan-auto:${item.bookId}"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(9), ui.dp(8), ui.dp(9), ui.dp(8))
            background = stripeBg(stripeCol)
            isFocusable = true
            setOnClickListener { openBookItemMenu(plan, month, item) }
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })

        val cov = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setImageDrawable(ui.symbol("book_nav", ui.muted, 20))
            if (item.localCover.isNotBlank()) images.thumbnail(item.localCover)?.let(::setImageBitmap)
        }
        card.addView(cov, LinearLayout.LayoutParams(ui.dp(34), ui.dp(50)).apply { marginEnd = ui.dp(10) })

        val col = ui.column()
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        val line1 = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(line1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) })
        ui.label(line1, item.title, 14, ui.text).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) }
        }
        val statusCol = when (item.status) {
            PlanItemStatus.COMPLETED -> colorGreen
            PlanItemStatus.READING -> ui.accent
            PlanItemStatus.READ_IN_MONTH -> colorBlue
            PlanItemStatus.PLANNED -> ui.muted
        }
        ui.label(line1, item.status.label, 10, statusCol, bold = item.status == PlanItemStatus.COMPLETED || item.status == PlanItemStatus.READING).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

        val detailParts = mutableListOf<String>()
        if (item.isReread) detailParts.add("Releitura")
        else if (!item.entry?.tag.isNullOrBlank()) detailParts.add(item.entry!!.tag)
        val identity = item.saga.ifBlank { item.author }
        if (identity.isNotBlank()) detailParts.add(identity)
        when (item.status) {
            PlanItemStatus.COMPLETED -> {
                if (item.pagesReadInMonth > 0) detailParts.add("${numbers.format(item.pagesReadInMonth)} págs. no mês")
            }
            PlanItemStatus.READING -> {
                if (item.pagesReadInMonth > 0) detailParts.add("${numbers.format(item.pagesReadInMonth)} págs. no mês")
                if (item.remainingPages > 0) detailParts.add("restam ${numbers.format(item.remainingPages)}")
            }
            PlanItemStatus.READ_IN_MONTH -> {
                if (item.pagesReadInMonth > 0) detailParts.add("${numbers.format(item.pagesReadInMonth)} págs. lidas")
                else detailParts.add("Leitura registrada")
            }
            PlanItemStatus.PLANNED -> {
                if (item.remainingPages > 0) detailParts.add("${numbers.format(item.remainingPages)} págs. restantes")
            }
        }
        ui.label(col, detailParts.joinToString(" · "), 10, ui.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
    }

    /** Unified menu for ANY book in a month (manual OR automatic), always allowing clean removal from month/goal. */
    private fun openBookItemMenu(plan: AnnualReadingPlan, month: Int, item: ResolvedPlanItem) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (item.linkedBook != null && onOpenBook != null) {
            actions.add("Abrir livro na Estante" to { onOpenBook.invoke(item.linkedBook.id) })
        }
        val entry = item.entry
        if (entry != null) {
            if (item.isReread) {
                actions.add(
                    (if (entry.completed) "Desmarcar conclusão da releitura" else "Marcar releitura concluída") to {
                        mutatePlan(plan.copy(entries = plan.entries.map { if (it.id == entry.id) it.copy(completed = !it.completed) else it }))
                    },
                )
            }
            actions.add("Mover para outro mês" to { pickTargetMonth(plan, entry) })
            actions.add("Editar etiqueta" to { editTag(plan, entry) })
            actions.add("Retirar de ${AnnualPlanCalculator.fullName(month)}" to {
                val monthKey = AnnualReadingPlan.monthExclusionKey(month, entry.bookId)
                val updatedEntries = plan.entries.filterNot { it.id == entry.id }
                val updatedExcl = if (entry.bookId.isNotBlank()) plan.excludedMonthBooks + monthKey else plan.excludedMonthBooks
                mutatePlan(plan.copy(entries = updatedEntries, excludedMonthBooks = updatedExcl))
            })
        } else {
            actions.add("Retirar de ${AnnualPlanCalculator.fullName(month)}" to {
                val monthKey = AnnualReadingPlan.monthExclusionKey(month, item.bookId)
                mutatePlan(plan.copy(excludedMonthBooks = plan.excludedMonthBooks + monthKey))
            })
            actions.add("Retirar da meta de ${plan.year}" to {
                mutatePlan(plan.copy(excludedYearBooks = plan.excludedYearBooks + item.bookId))
            })
        }
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setItems(actions.map { it.first }.toTypedArray()) { _, idx -> actions[idx].second.invoke() }
                .setNegativeButton("Fechar", null)
                .create(),
        )
    }

    /** Shows all books counted in [plan.year] with 1-tap removal from the yearly goal + quick goal editing. */
    private fun openYearBooksDialog(
        plan: AnnualReadingPlan,
        summary: YearPlanSummary,
        purchaseGoalEntry: ReadingGoalStore.Entry?,
    ) {
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8)) }
        if (summary.completedBooksInYear.isEmpty()) {
            ui.label(body, "Nenhum livro concluído contabilizado em ${plan.year}.", 12, ui.muted)
        } else {
            summary.completedBooksInYear.forEach { credit ->
                val row = ui.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.dp(6), 0, ui.dp(6))
                }
                body.addView(row)
                val img = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
                    setImageDrawable(ui.symbol("book_nav", ui.muted, 18))
                    if (credit.cover.isNotBlank()) images.thumbnail(credit.cover)?.let(::setImageBitmap)
                }
                row.addView(img, LinearLayout.LayoutParams(ui.dp(30), ui.dp(44)).apply { marginEnd = ui.dp(10) })
                val col = ui.column()
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                ui.label(col, credit.title, 13, ui.text).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(1)
                }
                val sub = listOf(
                    AnnualPlanCalculator.fullName(credit.month),
                    if (credit.isReread) "Releitura" else credit.author,
                ).filter { it.isNotBlank() }.joinToString(" · ")
                ui.label(col, sub, 10, ui.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
                }
                TextView(activity).apply {
                    text = "Retirar"
                    textSize = 10.5f
                    setTextColor(colorRed)
                    background = ui.outlined(ui.surface, alphaColor(colorRed, 120), 0)
                    setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
                    setOnClickListener {
                        dialogs.toList().forEach { it.dismiss() }
                        dialogs.clear()
                        val updatedEntries = if (credit.entryId != null) {
                            plan.entries.filterNot { it.id == credit.entryId }
                        } else plan.entries
                        val updatedExcl = if (credit.bookId.isNotBlank() && !credit.isReread) {
                            plan.excludedYearBooks + credit.bookId
                        } else plan.excludedYearBooks
                        mutatePlan(plan.copy(entries = updatedEntries, excludedYearBooks = updatedExcl))
                    }
                }.also { row.addView(it, LinearLayout.LayoutParams(-2, -2)) }
            }
        }
        var dlg: AlertDialog? = null
        dlg = tracked(
            AlertDialog.Builder(activity)
                .setTitle("Livros em ${plan.year} (${summary.completedCount})")
                .setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Alterar meta") { _, _ ->
                    dlg?.dismiss()
                    openGoalConfigDialog(plan, purchaseGoalEntry)
                }
                .create(),
        )
    }

    /** Single unified goal configuration dialog for [plan.year] (Books/month or year, Pages/day or month, and Purchase 3:1). */
    private fun openGoalConfigDialog(plan: AnnualReadingPlan, purchaseGoalEntry: ReadingGoalStore.Entry?) {
        val body = ui.column().apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(8)) }
        var bType = plan.booksGoalType
        var pType = plan.pagesGoalType

        // --- SECTION 1: BOOKS GOAL ---
        ui.label(body, "META DE LIVROS (${plan.year})", 10, ui.muted).apply {
            letterSpacing = 0.08f
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(6)
        }
        val bRow = ui.row()
        body.addView(bRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        val bInput = EditText(activity).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(5))
            setTextColor(ui.text)
            setHintTextColor(ui.muted)
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            setText(plan.booksGoalValue.toString())
        }
        body.addView(bInput, LinearLayout.LayoutParams(-1, ui.dp(42)).apply { bottomMargin = ui.dp(4) })
        val bPreview = ui.label(body, "", 10, ui.accent).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12)
        }

        val bButtons = mutableMapOf<BooksGoalType, TextView>()
        fun refreshBooksUi() {
            bButtons.forEach { (t, btn) ->
                val on = t == bType
                btn.setTextColor(if (on) ui.background else ui.text)
                btn.background = if (on) ui.shape(ui.accent, 0) else ui.outlined(ui.activeSurface, ui.lineColor, 0)
            }
            val show = bType != BooksGoalType.NONE
            bInput.visibility = if (show) View.VISIBLE else View.GONE
            bPreview.visibility = if (show) View.VISIBLE else View.GONE
            val v = (bInput.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 10000)
            bPreview.text = when (bType) {
                BooksGoalType.PER_MONTH -> if (v > 0) "$v livro(s)/mês = ${numbers.format(v.toLong() * 12L)} no ano" else "Informe quantos livros por mês"
                BooksGoalType.PER_YEAR -> if (v > 0) "$v livro(s) em ${plan.year}" else "Informe quantos livros no ano"
                BooksGoalType.NONE -> ""
            }
        }
        BooksGoalType.entries.forEachIndexed { idx, t ->
            val btn = TextView(activity).apply {
                text = t.label
                gravity = Gravity.CENTER
                textSize = 11f
                setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                setOnClickListener {
                    val prev = bType
                    bType = t
                    val cur = (bInput.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 10000)
                    if (prev != t && t == BooksGoalType.PER_MONTH && cur > 12) {
                        bInput.setText(((cur + 11) / 12).coerceIn(1, 500).toString())
                    } else if (prev == BooksGoalType.PER_MONTH && t == BooksGoalType.PER_YEAR) {
                        val base = if (cur > 0) cur else 2
                        bInput.setText((base.toLong() * 12L).coerceIn(1L, 10000L).toString())
                    }
                    refreshBooksUi()
                }
            }
            bButtons[t] = btn
            bRow.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { if (idx < 2) marginEnd = ui.dp(6) })
        }
        bInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshBooksUi() }
        })
        refreshBooksUi()

        // --- SECTION 2: PAGES GOAL ---
        ui.label(body, "META DE PÁGINAS (${plan.year})", 10, ui.muted).apply {
            letterSpacing = 0.08f
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(6)
        }
        val pRow = ui.row()
        body.addView(pRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        val pInput = EditText(activity).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            setTextColor(ui.text)
            setHintTextColor(ui.muted)
            background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            setText(plan.pagesGoalValue.toString())
        }
        body.addView(pInput, LinearLayout.LayoutParams(-1, ui.dp(42)).apply { bottomMargin = ui.dp(4) })
        val pPreview = ui.label(body, "", 10, ui.accent).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12)
        }

        val pButtons = mutableMapOf<PagesGoalType, TextView>()
        fun refreshPagesUi() {
            pButtons.forEach { (t, btn) ->
                val on = t == pType
                btn.setTextColor(if (on) ui.background else ui.text)
                btn.background = if (on) ui.shape(ui.accent, 0) else ui.outlined(ui.activeSurface, ui.lineColor, 0)
            }
            val show = pType != PagesGoalType.NONE
            pInput.visibility = if (show) View.VISIBLE else View.GONE
            pPreview.visibility = if (show) View.VISIBLE else View.GONE
            val v = pInput.text.toString().trim().toIntOrNull() ?: 0
            pPreview.text = when (pType) {
                PagesGoalType.PER_DAY -> if (v > 0) "${numbers.format(v)} págs/dia ≈ ${numbers.format(v * 30)}/mês" else "Informe páginas por dia"
                PagesGoalType.PER_MONTH -> if (v > 0) "${numbers.format(v)} págs/mês (≈ ${AnnualPlanCalculator.formatDecimal(v / 30.0)}/dia)" else "Informe páginas por mês"
                PagesGoalType.NONE -> ""
            }
        }
        PagesGoalType.entries.forEachIndexed { idx, t ->
            val btn = TextView(activity).apply {
                text = t.label
                gravity = Gravity.CENTER
                textSize = 11f
                setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
                setOnClickListener {
                    val prev = pType
                    pType = t
                    if (prev == PagesGoalType.PER_DAY && t == PagesGoalType.PER_MONTH) {
                        val cur = pInput.text.toString().toIntOrNull() ?: 30
                        pInput.setText((cur * 30).toString())
                    } else if (prev == PagesGoalType.PER_MONTH && t == PagesGoalType.PER_DAY) {
                        val cur = pInput.text.toString().toIntOrNull() ?: 900
                        pInput.setText(((cur + 29) / 30).coerceAtLeast(1).toString())
                    }
                    refreshPagesUi()
                }
            }
            pButtons[t] = btn
            pRow.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { if (idx < 2) marginEnd = ui.dp(6) })
        }
        pInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshPagesUi() }
        })
        refreshPagesUi()

        // --- SECTION 3: CONTINUOUS PURCHASE GOAL (3:1) ---
        body.addView(
            View(activity).apply { setBackgroundColor(ui.lineColor) },
            LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply { bottomMargin = ui.dp(10) },
        )
        ui.label(body, "META DE COMPRA (CICLO CONTÍNUO)", 10, ui.muted).apply {
            letterSpacing = 0.08f
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(6)
        }

        var dlg: AlertDialog? = null
        val gp = goalsHost()
        ui.action(
            body,
            if (purchaseGoalEntry?.goal != null) {
                "Editar ${purchaseGoalEntry.goal.name} (${purchaseGoalEntry.goal.target}:${purchaseGoalEntry.goal.reward})"
            } else {
                "+ Ativar Meta de Compra (3:1)"
            },
        ) {
            dlg?.dismiss()
            if (purchaseGoalEntry != null) gp.openGoalDetailDialog(purchaseGoalEntry)
            else gp.openCreateDialog(GoalMetric.PURCHASE)
        }.apply {
            minHeight = ui.dp(38)
            textSize = 11f
        }

        dlg = tracked(
            AlertDialog.Builder(activity)
                .setTitle("Meta de ${plan.year}")
                .setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton("Salvar", null)
                .setNegativeButton("Cancelar", null)
                .create(),
        )
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val bVal = if (bType == BooksGoalType.NONE) plan.booksGoalValue else bInput.text.toString().trim().toIntOrNull()
            if (bType != BooksGoalType.NONE && (bVal == null || bVal !in 1..5000)) {
                bInput.error = "Informe entre 1 e 5.000 livros."
                return@setOnClickListener
            }
            val pVal = if (pType == PagesGoalType.NONE) plan.pagesGoalValue else pInput.text.toString().trim().toIntOrNull()
            if (pType != PagesGoalType.NONE && (pVal == null || pVal !in 1..1_000_000)) {
                pInput.error = "Informe entre 1 e 1.000.000 páginas."
                return@setOnClickListener
            }
            dlg.dismiss()
            mutatePlan(
                plan.copy(
                    booksGoalType = bType,
                    booksGoalValue = bVal ?: plan.booksGoalValue,
                    pagesGoalType = pType,
                    pagesGoalValue = pVal ?: plan.pagesGoalValue,
                    goalConfigured = true,
                ),
            )
        }
    }

    private fun triggerShelfPicker(year: Int, month: Int) {
        val nav = onPickBookFromShelf
        if (nav != null) {
            nav(year, month)
            return
        }
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
        val existingInDest = plan.entries
            .filter { it.month == toMonth && it.bookId.isNotBlank() }
            .mapTo(HashSet()) { it.bookId to it.reread }
        var nextPos = (plan.entries.filter { it.month == toMonth }.maxOfOrNull { it.position } ?: -1) + 1
        val updated = plan.entries.mapNotNull { e ->
            if (e.month == fromMonth && e.kind == PlanEntryKind.SHELF_BOOK) {
                val resolved = AnnualPlanCalculator.resolveItem(e, booksById)
                if (resolved.status != PlanItemStatus.COMPLETED) {
                    val key = e.bookId to e.reread
                    if (e.bookId.isNotBlank() && !existingInDest.add(key)) {
                        null // already planned in destination month; drop duplicate
                    } else {
                        e.copy(month = toMonth, position = nextPos++)
                    }
                } else e
            } else e
        }
        mutatePlan(plan.copy(entries = updated))
    }
}
