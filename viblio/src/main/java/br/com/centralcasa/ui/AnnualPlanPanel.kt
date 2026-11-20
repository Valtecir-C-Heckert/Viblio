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
 * Editorial Annual Reading Plan panel (Esboço 1 — Agenda Editorial por Mês).
 * Modular: supports pages/day, books/month, or pure reading list (no goals), plus optional 3:1 Purchase Goal integration.
 */
class AnnualPlanPanel(
    private val activity: Activity,
    private val ui: DisplayUi,
    private val repo: LibraryRepository,
    private val catalogProvider: () -> BookCatalog?,
    private val onOpenBook: ((String) -> Unit)? = null,
    private val onAddCatalogBook: ((CatalogBook) -> Unit)? = null,
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
        setColor(alphaColor(ui.accent, 14))
        setStroke(ui.dp(1).coerceAtLeast(1), alphaColor(ui.accent, 130), ui.dp(5).toFloat(), ui.dp(4).toFloat())
    }

    private fun healthColor(health: MonthHealth): Int = when (health) {
        MonthHealth.COMPLETED, MonthHealth.ON_PACE -> colorGreen
        MonthHealth.INTENSE -> colorAmber
        MonthHealth.MISSED -> colorRed
        MonthHealth.EMPTY -> ui.lineColor
    }

    private fun mutatePlan(plan: AnnualReadingPlan) {
        if (closed) return
        try {
            store.save(plan)
            changed()
        } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: "Não foi possível salvar o plano.", Toast.LENGTH_LONG).show()
        }
    }

    private fun findPurchaseGoal(): Pair<ReadingGoalStore.Entry, ReadingGoal>? {
        val entry = goalStore.entries().firstOrNull { it.goal?.metric == GoalMetric.PURCHASE } ?: return null
        return entry to requireNotNull(entry.goal)
    }

    fun render(parent: LinearLayout) {
        if (closed) return
        val existingYears = store.years()
        if (store.load(selectedYear) == null && existingYears.isNotEmpty() && selectedYear !in existingYears) {
            // Keep selectedYear if user explicitly chose a year; otherwise default to current year or latest existing.
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            if (selectedYear == currentYear && existingYears.contains(currentYear)) selectedYear = currentYear
        }

        val plan = store.load(selectedYear)
        val books = repo.books(true)
        val sessions = repo.sessions()
        val now = System.currentTimeMillis()
        val purchasePair = findPurchaseGoal()
        val purchaseProgress = purchasePair?.second?.let { ReadingGoals.progress(it, books, sessions, now) }

        val wrap = ui.column().apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(16)) }
        parent.addView(wrap)

        // Top controls row: Year selector + Quick Goal Mode toggle / Configure
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

        if (plan != null) {
            val modeChipText = when (plan.goalMode) {
                PlanGoalMode.PAGES_PER_DAY -> "${plan.dailyPagesTarget} p/dia ⇄"
                PlanGoalMode.BOOKS_PER_MONTH -> "${plan.monthlyBooksTarget} liv/mês ⇄"
                PlanGoalMode.NONE -> "Sem meta"
            }
            val modeBtn = TextView(activity).apply {
                tag = "plan-mode-toggle"
                text = modeChipText
                textSize = 11f
                setTextColor(ui.text)
                background = ui.outlined(ui.activeSurface, ui.lineColor, 0)
                setPadding(ui.dp(9), ui.dp(7), ui.dp(9), ui.dp(7))
                setOnClickListener {
                    val next = when (plan.goalMode) {
                        PlanGoalMode.PAGES_PER_DAY -> PlanGoalMode.BOOKS_PER_MONTH
                        PlanGoalMode.BOOKS_PER_MONTH -> PlanGoalMode.PAGES_PER_DAY
                        PlanGoalMode.NONE -> PlanGoalMode.PAGES_PER_DAY
                    }
                    mutatePlan(plan.copy(goalMode = next))
                }
            }
            topRow.addView(modeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })

            ui.icon(topRow, "edit", "Configurar plano anual", 36) { openConfigDialog(plan) }.apply {
                tag = "plan-config"
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            }
        }

        if (plan == null) {
            renderEmptyPlan(wrap)
            renderLegacyGoals(parent)
            return
        }

        val summary = AnnualPlanCalculator.summarize(
            plan = plan,
            books = books,
            sessions = sessions,
            purchaseGoal = if (plan.showPurchaseGoal) purchasePair?.second else null,
            purchaseProgress = if (plan.showPurchaseGoal) purchaseProgress else null,
            nowMs = now,
        )

        // 1) Top KPI Strip (Modular: hidden when goalMode == NONE; 1 full-width card if !showPurchaseGoal; 2 cards if showPurchaseGoal)
        if (plan.goalMode != PlanGoalMode.NONE || plan.showPurchaseGoal) {
            val kpiRow = ui.row().apply { gravity = Gravity.TOP }
            wrap.addView(kpiRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })

            if (plan.goalMode != PlanGoalMode.NONE) {
                val yearCard = ui.column().apply {
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                    background = ui.outlined(ui.surface, ui.lineColor, 0)
                    setOnClickListener { openConfigDialog(plan) }
                }
                val hasRight = plan.showPurchaseGoal
                kpiRow.addView(yearCard, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (hasRight) marginEnd = ui.dp(8)
                })
                ui.label(yearCard, "ANO · ${plan.year}", 9, ui.muted).apply {
                    letterSpacing = 0.09f
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(4)
                }
                val pct = if (summary.annualBookGoal > 0) (summary.completedCount * 100 / summary.annualBookGoal).coerceIn(0, 100) else 0
                val countRow = ui.row().apply { gravity = Gravity.BOTTOM }
                yearCard.addView(countRow)
                ui.label(countRow, "${summary.completedCount}", 22, ui.accent).apply {
                    typeface = Typeface.create("serif", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(5) }
                }
                ui.label(countRow, "/ ${summary.annualBookGoal} livros · $pct%", 10, ui.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                // Thin 3dp progress bar
                val barWrap = FrameLayout(activity).apply { setBackgroundColor(alphaColor(ui.lineColor, 140)) }
                val barRow = ui.row()
                val fillPct = pct.coerceIn(0, 100)
                if (fillPct > 0) barRow.addView(View(activity).apply { setBackgroundColor(ui.accent) }, LinearLayout.LayoutParams(0, -1, fillPct.toFloat()))
                if (fillPct < 100) barRow.addView(View(activity), LinearLayout.LayoutParams(0, -1, (100 - fillPct).toFloat()))
                barWrap.addView(barRow, FrameLayout.LayoutParams(-1, -1))
                yearCard.addView(barWrap, LinearLayout.LayoutParams(-1, ui.dp(3).coerceAtLeast(3)).apply {
                    topMargin = ui.dp(6)
                    bottomMargin = ui.dp(6)
                })
                val avgStr = "${AnnualPlanCalculator.formatDecimal(summary.averageDailyPages)} p/dia" +
                    (summary.estimatedMinutesPerDay?.let { " · ~$it min" } ?: "")
                ui.label(yearCard, avgStr, 10, ui.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
                }
            }

            if (plan.showPurchaseGoal) {
                val buyCard = ui.column().apply {
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                    background = ui.outlined(ui.surface, ui.lineColor, 0)
                    setOnClickListener {
                        if (purchasePair == null) offerCreatePurchaseGoal()
                        else pickFromWishlist(plan, null, consumeToken = summary.purchaseTokensFree > 0)
                    }
                }
                kpiRow.addView(buyCard, LinearLayout.LayoutParams(0, -2, 1f))
                val buyTop = ui.row()
                buyCard.addView(buyTop, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(7) })
                ui.label(buyTop, "COMPRA ${summary.purchaseCycleTarget}:1", 9, ui.muted).apply {
                    letterSpacing = 0.09f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val tokenLabel = when {
                    purchasePair == null -> "Ativar"
                    summary.purchaseTokensFree == 1 -> "1 ficha"
                    else -> "${summary.purchaseTokensFree} fichas"
                }
                ui.label(buyTop, tokenLabel, 10, ui.accent, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2)
                }

                // Segmented [■■□] cycle bar
                val segRow = ui.row()
                buyCard.addView(segRow, LinearLayout.LayoutParams(-1, ui.dp(8)).apply { bottomMargin = ui.dp(6) })
                val segs = summary.purchaseCycleTarget.coerceIn(1, 6)
                repeat(segs) { i ->
                    val filled = purchasePair != null && i < summary.purchaseCycleDone
                    segRow.addView(
                        View(activity).apply {
                            background = if (filled) ui.shape(ui.accent, 0) else ui.outlined(Color.TRANSPARENT, ui.lineColor, 0)
                        },
                        LinearLayout.LayoutParams(0, -1, 1f).apply { if (i < segs - 1) marginEnd = ui.dp(3) },
                    )
                }
                ui.label(
                    buyCard,
                    if (purchasePair == null) "Toque para criar ciclo 3:1" else "${summary.purchaseCycleDone}/${summary.purchaseCycleTarget} p/ próxima ficha",
                    10,
                    ui.muted,
                ).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(2)
                }
                ui.label(buyCard, if (purchasePair == null) "Definir regra ›" else "Usar na Wishlist ›", 10, ui.accent).apply {
                    maxLines = 1
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
                }
            }
        }

        // 2) Horizontal Month Ribbon (JAN..DEZ) with color-coded mini load bars
        val ribbonScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val ribbonRow = ui.row()
        ribbonScroll.addView(ribbonRow)
        wrap.addView(ribbonScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })

        summary.months.forEach { ms ->
            val sel = ms.month == selectedMonth
            val barCol = healthColor(ms.health)
            val mBtn = ui.column().apply {
                tag = "plan-month:${ms.month}"
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(4), ui.dp(7), ui.dp(4), ui.dp(6))
                background = ui.outlined(if (sel) ui.activeSurface else ui.surface, if (sel) ui.accent else ui.lineColor, 0)
                isFocusable = true
                setOnClickListener {
                    if (selectedMonth != ms.month) {
                        selectedMonth = ms.month
                        changed()
                    }
                }
            }
            ribbonRow.addView(mBtn, LinearLayout.LayoutParams(ui.dp(44), -2).apply { marginEnd = ui.dp(6) })
            ui.label(mBtn, AnnualPlanCalculator.shortName(ms.month), 10, if (sel) ui.accent else ui.muted, bold = sel).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.06f
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(4)
            }
            val miniTrack = FrameLayout(activity).apply { setBackgroundColor(alphaColor(ui.lineColor, 120)) }
            val fillFrac = (ms.loadRatio.coerceIn(0.0, 1.0)).toFloat()
            val miniRow = ui.row()
            if (fillFrac > 0.02f) miniRow.addView(View(activity).apply { setBackgroundColor(barCol) }, LinearLayout.LayoutParams(0, -1, fillFrac))
            if (fillFrac < 0.98f) miniRow.addView(View(activity), LinearLayout.LayoutParams(0, -1, (1f - fillFrac).coerceAtLeast(0.02f)))
            miniTrack.addView(miniRow, FrameLayout.LayoutParams(-1, -1))
            mBtn.addView(miniTrack, LinearLayout.LayoutParams(ui.dp(30), ui.dp(3).coerceAtLeast(3)))
        }

        // Scroll selected month into view cleanly
        ribbonScroll.post {
            val targetX = ((selectedMonth - 3).coerceAtLeast(0)) * ui.dp(50)
            ribbonScroll.scrollTo(targetX, 0)
        }

        val currentMs = summary.months[(selectedMonth - 1).coerceIn(0, 11)]

        // 3) Rollover suggestion banner if previous month has pending unfinished books
        if (selectedMonth > 1) {
            val prevMs = summary.months[selectedMonth - 2]
            if (prevMs.isPast && prevMs.pendingCount > 0) {
                val rollover = ui.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(8), ui.dp(8))
                    background = ui.outlined(alphaColor(colorRed, 24), alphaColor(colorRed, 110), 0)
                }
                wrap.addView(rollover, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
                val prevName = AnnualPlanCalculator.fullName(prevMs.month)
                ui.label(rollover, "${prevMs.pendingCount} pendente(s) em $prevName", 11, ui.text).apply {
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

        // 4) Selected Month Header + Health Badge + "+ Livro" button
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
        if (plan.goalMode != PlanGoalMode.NONE && currentMs.health != MonthHealth.EMPTY) {
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

        val paceSubtitle = when (plan.goalMode) {
            PlanGoalMode.PAGES_PER_DAY -> {
                val pagesLabel = if (currentMs.isCurrent) "${currentMs.totalRemainingPages} págs restantes" else "${currentMs.totalPlannedPages} págs"
                val dayHint = if (currentMs.isCurrent) " (${currentMs.remainingDays}d)" else ""
                "$pagesLabel · ${AnnualPlanCalculator.formatDecimal(currentMs.pacePagesPerDay)} págs/dia$dayHint"
            }
            PlanGoalMode.BOOKS_PER_MONTH -> {
                val totalBooks = currentMs.plannedBookCount + currentMs.jokerCount
                "$totalBooks / ${plan.monthlyBooksTarget} livros · ${currentMs.totalPlannedPages} págs"
            }
            PlanGoalMode.NONE -> {
                "${currentMs.plannedBookCount} livros" +
                    (if (currentMs.jokerCount > 0) " · ${currentMs.jokerCount} coringa" else "") +
                    " · ${currentMs.totalPlannedPages} págs"
            }
        }
        ui.label(mTitleCol, paceSubtitle, 11, ui.muted).apply {
            maxLines = 1
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
            setOnClickListener { openAddMenu(plan) }
        }.also { mHead.addView(it, LinearLayout.LayoutParams(-2, -2)) }

        // 5) Month Items (Books + Dashed Joker Slots)
        if (currentMs.items.isEmpty()) {
            val emptyBox = ui.column().apply {
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                background = dashedBg()
            }
            wrap.addView(emptyBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
            ui.label(emptyBox, "Nenhum livro planejado para ${AnnualPlanCalculator.fullName(selectedMonth)}.", 12, ui.muted)
            val emptyBtns = ui.row()
            emptyBox.addView(emptyBtns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            ui.action(emptyBtns, "+ Da Estante") { pickBookFromShelf(plan, null) }.apply {
                minHeight = ui.dp(36); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(36), 1f).apply { marginEnd = ui.dp(6) }
            }
            ui.action(emptyBtns, "+ Slot Coringa") { addJokerSlot(plan) }.apply {
                minHeight = ui.dp(36); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(36), 1f)
            }
        } else {
            var jokerIdx = 0
            currentMs.items.forEachIndexed { idx, item ->
                if (item.entry.kind == PlanEntryKind.JOKER) {
                    jokerIdx++
                    renderJokerCard(wrap, plan, item, jokerIdx, summary.purchaseTokensFree)
                } else {
                    renderBookCard(wrap, plan, item, idx + 1)
                }
            }
        }

        // 6) Collapsible Sagas Summary (only when planned books have sagas)
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
            ui.label(sagaToggle, "SAGAS DO PLANO (${summary.sagas.size})", 10, ui.muted).apply {
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

        // 7) Preserved Custom Goals section below (ReadingGoalsPanel)
        renderLegacyGoals(parent)
    }

    private fun renderEmptyPlan(parent: LinearLayout) {
        val box = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            background = ui.outlined(ui.surface, ui.lineColor, 0)
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        ui.label(box, "Plano de leitura $selectedYear", 20, ui.text).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        ui.label(box, "Organize seus livros por mês com meta opcional de páginas/dia, livros/mês ou apenas sua lista do ano.", 12, ui.muted).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(10)
        }
        ui.action(box, "Criar plano de $selectedYear", true) {
            openConfigDialog(AnnualReadingPlan(year = selectedYear), creating = true)
        }
    }

    private fun renderBookCard(parent: LinearLayout, plan: AnnualReadingPlan, item: ResolvedPlanItem, orderInMonth: Int) {
        val stripeCol = when (item.status) {
            PlanItemStatus.COMPLETED -> colorGreen
            PlanItemStatus.READING -> ui.accent
            PlanItemStatus.PLANNED -> if (item.entry.tag.contains("saga", ignoreCase = true)) colorBlue else ui.accent
        }
        val card = ui.row().apply {
            tag = "plan-entry:${item.entry.id}"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
            background = stripeBg(stripeCol)
            isFocusable = true
            setOnClickListener { openEntryMenu(plan, item) }
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
        val kicker = listOf(ordStr, item.entry.tag.ifBlank { if (item.entry.kind == PlanEntryKind.WISHLIST_BOOK) "Wishlist" else "" })
            .filter { it.isNotBlank() }.joinToString(" · ").uppercase(Locale("pt", "BR"))
        ui.label(r1, kicker, 9, ui.accent).apply {
            letterSpacing = 0.06f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }
        }
        val statusCol = when (item.status) {
            PlanItemStatus.COMPLETED -> colorGreen
            PlanItemStatus.READING -> ui.accent
            PlanItemStatus.PLANNED -> ui.muted
        }
        ui.label(r1, item.status.label, 10, statusCol).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

        ui.label(col, item.title, 15, ui.text).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(2)
        }

        val pagesStr = when {
            item.status == PlanItemStatus.COMPLETED -> "Concluído"
            item.remainingPages > 0 -> "${item.remainingPages} págs restantes"
            else -> "Páginas não informadas"
        }
        val meta = listOf(item.saga.ifBlank { item.author }, pagesStr).filter { it.isNotBlank() }.joinToString(" · ")
        ui.label(col, meta, 10, ui.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
    }

    private fun renderJokerCard(
        parent: LinearLayout,
        plan: AnnualReadingPlan,
        item: ResolvedPlanItem,
        index: Int,
        freeTokens: Int,
    ) {
        val card = ui.column().apply {
            tag = "plan-joker:${item.entry.id}"
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
            background = dashedBg()
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })

        val top = ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openJokerMenu(plan, item) }
        }
        card.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(7) })
        ui.label(top, "✦ Slot livre #$index (Coringa)", 11, ui.accent, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        ui.label(top, "~${item.remainingPages} págs ⋮", 10, ui.muted).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

        val btns = ui.row()
        card.addView(btns)
        TextView(activity).apply {
            text = "+ Puxar da Estante"
            gravity = Gravity.CENTER
            textSize = 10.5f
            setTextColor(ui.text)
            background = ui.outlined(ui.surface, ui.lineColor, 0)
            setPadding(ui.dp(6), ui.dp(7), ui.dp(6), ui.dp(7))
            setOnClickListener { pickBookFromShelf(plan, item.entry.id) }
        }.also { btns.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }) }

        val wishLabel = if (plan.showPurchaseGoal && freeTokens > 0) {
            "Usar ficha (Wishlist)"
        } else {
            "+ Da Wishlist"
        }
        TextView(activity).apply {
            text = wishLabel
            gravity = Gravity.CENTER
            textSize = 10.5f
            setTextColor(ui.accent)
            background = ui.outlined(ui.surface, ui.accent, 0)
            setPadding(ui.dp(6), ui.dp(7), ui.dp(6), ui.dp(7))
            setOnClickListener { pickFromWishlist(plan, item.entry.id, consumeToken = plan.showPurchaseGoal && freeTokens > 0) }
        }.also { btns.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun renderLegacyGoals(parent: LinearLayout) {
        val divider = View(activity).apply { setBackgroundColor(ui.lineColor) }
        parent.addView(divider, LinearLayout.LayoutParams(-1, ui.dp(1).coerceAtLeast(1)).apply { topMargin = ui.dp(4) })
        val gp = goalsPanel ?: ReadingGoalsPanel(activity, ui, repo) { if (!closed) changed() }.also { goalsPanel = it }
        gp.render(parent)
    }

    private fun pickYear() {
        val current = Calendar.getInstance().get(Calendar.YEAR)
        val options = (store.years() + listOf(current, current + 1, current - 1)).distinct().sorted()
        val labels = options.map { y ->
            if (store.load(y) != null) "$y · ativo" else "$y · criar"
        }.toTypedArray()
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Escolher ano do plano")
                .setItems(labels) { _, idx ->
                    selectedYear = options[idx]
                    changed()
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun openConfigDialog(plan: AnnualReadingPlan, creating: Boolean = false) {
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8)) }
        var chosenMode = plan.goalMode

        ui.label(body, "Tipo de meta", 12, ui.muted)
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
        body.addView(targetInput, LinearLayout.LayoutParams(-1, ui.dp(44)).apply { bottomMargin = ui.dp(12) })

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
                    targetLabel.text = "Meta de páginas por dia"
                    targetInput.setText(plan.dailyPagesTarget.toString())
                }
                PlanGoalMode.BOOKS_PER_MONTH -> {
                    targetLabel.visibility = View.VISIBLE
                    targetInput.visibility = View.VISIBLE
                    targetLabel.text = "Meta de livros por mês"
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

        val purchaseToggle = CheckBox(activity).apply {
            text = "Integrar Meta de Compra (3:1 / Fichas)"
            setTextColor(ui.text)
            isChecked = plan.showPurchaseGoal
        }
        body.addView(purchaseToggle)

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (creating) "Criar plano ${plan.year}" else "Configurar plano ${plan.year}")
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
        if (!creating) {
            builder.setNeutralButton("Excluir plano") { _, _ ->
                tracked(
                    AlertDialog.Builder(activity)
                        .setTitle("Excluir plano ${plan.year}?")
                        .setMessage("Os livros na sua Estante permanecem intactos.")
                        .setPositiveButton("Excluir") { _, _ -> store.delete(plan.year); changed() }
                        .setNegativeButton("Cancelar", null)
                        .create(),
                )
            }
        }
        val dlg = tracked(builder.create())
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rawNum = targetInput.text.toString().trim().toIntOrNull()
            val updated = when (chosenMode) {
                PlanGoalMode.PAGES_PER_DAY -> {
                    if (rawNum == null || rawNum !in 1..2000) {
                        targetInput.error = "Informe entre 1 e 2.000 páginas/dia."
                        return@setOnClickListener
                    }
                    plan.copy(goalMode = chosenMode, dailyPagesTarget = rawNum, showPurchaseGoal = purchaseToggle.isChecked)
                }
                PlanGoalMode.BOOKS_PER_MONTH -> {
                    if (rawNum == null || rawNum !in 1..100) {
                        targetInput.error = "Informe entre 1 e 100 livros/mês."
                        return@setOnClickListener
                    }
                    plan.copy(goalMode = chosenMode, monthlyBooksTarget = rawNum, showPurchaseGoal = purchaseToggle.isChecked)
                }
                PlanGoalMode.NONE -> plan.copy(goalMode = chosenMode, showPurchaseGoal = purchaseToggle.isChecked)
            }
            dlg.dismiss()
            mutatePlan(updated)
        }
    }

    private fun openAddMenu(plan: AnnualReadingPlan) {
        val options = arrayOf(
            "Puxar livro da Estante",
            "Adicionar Slot livre (Coringa)",
            "Puxar da Wishlist (Quero comprar)",
        )
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Adicionar em ${AnnualPlanCalculator.fullName(selectedMonth)}")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> pickBookFromShelf(plan, null)
                        1 -> addJokerSlot(plan)
                        2 -> pickFromWishlist(plan, null, consumeToken = false)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun pickBookFromShelf(plan: AnnualReadingPlan, replaceEntryId: String?) {
        val alreadyInPlan = plan.entries.mapNotNull { it.bookId.takeIf(String::isNotBlank) }.toSet()
        val candidates = repo.books()
            .filter { !it.archived && !it.complete && it.id !in alreadyInPlan }
            .sortedWith(compareBy<LibraryBook> { if (it.currentPage > 0) 0 else 1 }.thenBy { it.title.lowercase(Locale("pt", "BR")) })
        if (candidates.isEmpty()) {
            Toast.makeText(activity, "Nenhum livro não concluído disponível na Estante.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = candidates.map { b ->
            val rem = if (b.pagesKnown) "${(b.totalPages - b.currentPage).coerceAtLeast(0)}p" else "sem págs"
            val extra = listOf(b.saga.ifBlank { b.author }, rem).filter { it.isNotBlank() }.joinToString(" · ")
            "${b.title}\n$extra"
        }.toTypedArray()

        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Escolher da Estante")
                .setItems(labels) { _, idx ->
                    val chosen = candidates[idx]
                    val nextPos = (plan.entries.filter { it.month == selectedMonth }.maxOfOrNull { it.position } ?: -1) + 1
                    val newEntry = PlanEntry(
                        month = selectedMonth,
                        position = nextPos,
                        kind = PlanEntryKind.SHELF_BOOK,
                        bookId = chosen.id,
                        title = chosen.title,
                        author = chosen.author,
                        saga = chosen.saga,
                        reservedPages = if (chosen.pagesKnown) chosen.totalPages else 200,
                        tag = if (chosen.saga.isNotBlank()) "Saga" else "",
                    )
                    val updatedList = if (replaceEntryId != null) {
                        plan.entries.map { if (it.id == replaceEntryId) newEntry.copy(position = it.position, month = it.month) else it }
                    } else {
                        plan.entries + newEntry
                    }
                    mutatePlan(plan.copy(entries = updatedList))
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun addJokerSlot(plan: AnnualReadingPlan) {
        val input = EditText(activity).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("200")
            setTextColor(ui.text)
            setHintTextColor(ui.muted)
            hint = "Reserva estimada em páginas"
        }
        val box = ui.column().apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0) }
        ui.label(box, "Reserva estimada de páginas:", 12, ui.muted)
        box.addView(input, LinearLayout.LayoutParams(-1, ui.dp(46)))

        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Novo Slot Coringa")
                .setView(box)
                .setPositiveButton("Adicionar") { _, _ ->
                    val pages = input.text.toString().trim().toIntOrNull()?.coerceIn(1, 20000) ?: 200
                    val nextPos = (plan.entries.filter { it.month == selectedMonth }.maxOfOrNull { it.position } ?: -1) + 1
                    val entry = PlanEntry(
                        month = selectedMonth,
                        position = nextPos,
                        kind = PlanEntryKind.JOKER,
                        title = "Slot livre (Coringa)",
                        reservedPages = pages,
                    )
                    mutatePlan(plan.copy(entries = plan.entries + entry))
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun pickFromWishlist(plan: AnnualReadingPlan, replaceEntryId: String?, consumeToken: Boolean) {
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
                        .setTitle(if (consumeToken) "Usar ficha · Quero comprar" else "Escolher de Quero comprar")
                        .setItems(labels) { _, idx ->
                            val chosen = list[idx]
                            if (consumeToken) {
                                confirmWishlistPurchase(plan, replaceEntryId, chosen)
                            } else {
                                attachWishlistBook(plan, replaceEntryId, chosen)
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .create(),
                )
            }, {
                Toast.makeText(activity, "Não foi possível carregar Quero comprar.", Toast.LENGTH_LONG).show()
            })
        }
    }

    private fun confirmWishlistPurchase(plan: AnnualReadingPlan, replaceEntryId: String?, chosen: CatalogBook) {
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(chosen.title)
                .setMessage("Deseja registrar o uso de 1 ficha de compra (3:1), remover da Wishlist e cadastrar na Estante?")
                .setPositiveButton("Comprar e planejar") { _, _ ->
                    findPurchaseGoal()?.let { (entry, goal) ->
                        val prog = ReadingGoals.progress(goal, repo.books(true), repo.sessions(), System.currentTimeMillis())
                        if (prog.value >= goal.target) {
                            runCatching { goalStore.save(ReadingGoals.purchase(goal, prog, System.currentTimeMillis()), entry.raw) }
                        }
                    }
                    catalogProvider()?.removeWishlist(chosen.id) {}
                    attachWishlistBook(plan, replaceEntryId, chosen)
                    onAddCatalogBook?.invoke(chosen)
                }
                .setNeutralButton("Só planejar") { _, _ ->
                    attachWishlistBook(plan, replaceEntryId, chosen)
                }
                .setNegativeButton("Cancelar", null)
                .create(),
        )
    }

    private fun attachWishlistBook(plan: AnnualReadingPlan, replaceEntryId: String?, chosen: CatalogBook) {
        val nextPos = (plan.entries.filter { it.month == selectedMonth }.maxOfOrNull { it.position } ?: -1) + 1
        val newEntry = PlanEntry(
            month = selectedMonth,
            position = nextPos,
            kind = PlanEntryKind.WISHLIST_BOOK,
            wishlistCatalogId = chosen.id,
            title = chosen.title,
            author = chosen.author,
            saga = chosen.saga,
            coverUrl = chosen.coverUrl,
            reservedPages = chosen.pages ?: 250,
            tag = "Wishlist",
        )
        val updated = if (replaceEntryId != null) {
            plan.entries.map { if (it.id == replaceEntryId) newEntry.copy(position = it.position, month = it.month) else it }
        } else {
            plan.entries + newEntry
        }
        mutatePlan(plan.copy(entries = updated))
    }

    private fun openEntryMenu(plan: AnnualReadingPlan, item: ResolvedPlanItem) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (item.linkedBook != null && onOpenBook != null) {
            actions.add("Abrir livro na Estante" to { onOpenBook.invoke(item.linkedBook.id) })
        }
        actions.add("Mover para outro mês" to { pickTargetMonth(plan, item.entry) })
        actions.add("Editar etiqueta (Saga / Pausa)" to { editTag(plan, item.entry) })
        actions.add("Remover do plano" to {
            mutatePlan(plan.copy(entries = plan.entries.filterNot { it.id == item.entry.id }))
        })
        tracked(
            AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setItems(actions.map { it.first }.toTypedArray()) { _, idx -> actions[idx].second.invoke() }
                .setNegativeButton("Fechar", null)
                .create(),
        )
    }

    private fun openJokerMenu(plan: AnnualReadingPlan, item: ResolvedPlanItem) {
        val actions = arrayOf("Alterar reserva de páginas", "Mover para outro mês", "Remover Slot Coringa")
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Slot Coringa (~${item.remainingPages} págs)")
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> {
                            val input = EditText(activity).apply {
                                isSingleLine = true
                                inputType = InputType.TYPE_CLASS_NUMBER
                                setText(item.entry.reservedPages.toString())
                                setTextColor(ui.text)
                            }
                            val box = ui.column().apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0) }
                            box.addView(input, LinearLayout.LayoutParams(-1, ui.dp(46)))
                            tracked(
                                AlertDialog.Builder(activity)
                                    .setTitle("Reserva estimada de páginas")
                                    .setView(box)
                                    .setPositiveButton("Salvar") { _, _ ->
                                        val pages = input.text.toString().trim().toIntOrNull()?.coerceIn(1, 20000) ?: item.entry.reservedPages
                                        mutatePlan(plan.copy(entries = plan.entries.map { if (it.id == item.entry.id) it.copy(reservedPages = pages) else it }))
                                    }
                                    .setNegativeButton("Cancelar", null)
                                    .create(),
                            )
                        }
                        1 -> pickTargetMonth(plan, item.entry)
                        2 -> mutatePlan(plan.copy(entries = plan.entries.filterNot { it.id == item.entry.id }))
                    }
                }
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
            if (e.month == fromMonth && e.kind != PlanEntryKind.JOKER) {
                val resolved = AnnualPlanCalculator.resolveItem(e, booksById)
                if (resolved.status != PlanItemStatus.COMPLETED) {
                    e.copy(month = toMonth, position = nextPos++)
                } else e
            } else e
        }
        mutatePlan(plan.copy(entries = updated))
    }

    private fun offerCreatePurchaseGoal() {
        tracked(
            AlertDialog.Builder(activity)
                .setTitle("Ativar Meta de Compra 3:1?")
                .setMessage("A cada 3 livros concluídos você libera 1 ficha para comprar um livro da sua Wishlist.")
                .setPositiveButton("Criar 3:1") { _, _ ->
                    val now = System.currentTimeMillis()
                    val books = repo.books(true)
                    val goal = ReadingGoal(
                        name = "Próxima compra",
                        metric = GoalMetric.PURCHASE,
                        target = 3,
                        reward = 1,
                        period = GoalPeriod.CONTINUOUS,
                        kind = "book",
                        createdAt = now,
                        fromAt = now,
                        baseline = books.associate { it.id to it.currentPage },
                        excluded = books.filter { it.complete }.map { it.id }.toSet(),
                    )
                    runCatching { goalStore.save(goal, null); changed() }
                        .onFailure { Toast.makeText(activity, it.message ?: "Não foi possível criar a meta.", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Agora não", null)
                .create(),
        )
    }
}
