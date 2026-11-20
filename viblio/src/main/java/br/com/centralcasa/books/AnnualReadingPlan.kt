package br.com.centralcasa.books

import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class PlanGoalMode(val label: String) {
    NONE("Só lista"),
    PAGES_PER_DAY("Páginas por dia"),
    BOOKS_PER_MONTH("Livros por mês"),
}

enum class PlanEntryKind {
    SHELF_BOOK,
    WISHLIST_BOOK,
    JOKER,
}

enum class PlanItemStatus(val label: String) {
    PLANNED("Planejado"),
    READING("Lendo"),
    COMPLETED("Concluído"),
}

enum class MonthHealth {
    EMPTY,
    COMPLETED, // Green: past month met goal / all completed
    ON_PACE,   // Green: current/future month within target
    INTENSE,   // Amber/Gold: current/future month planned above target (never red!)
    MISSED,    // Red: past month where goal was NOT met or planned books stayed unfinished
}

data class PlanEntry(
    val id: String = UUID.randomUUID().toString(),
    val month: Int,
    val position: Int = 0,
    val kind: PlanEntryKind,
    val bookId: String = "",
    val wishlistCatalogId: String = "",
    val title: String = "",
    val author: String = "",
    val saga: String = "",
    val coverUrl: String = "",
    val reservedPages: Int = 200,
    val tag: String = "",
    val completed: Boolean = false,
) {
    fun validated(): PlanEntry {
        require(UUID.fromString(id).toString() == id) { "Identificador do item inválido." }
        require(month in 1..12) { "Mês inválido." }
        require(position in 0..10000) { "Ordem inválida." }
        require(bookId.length <= 200 && wishlistCatalogId.length <= 32) { "Vínculo inválido." }
        require(title.length <= 300 && author.length <= 200 && saga.length <= 200) { "Metadados do plano muito longos." }
        require(coverUrl.length <= 2048 && tag.length <= 40) { "Etiqueta ou capa inválida." }
        require(reservedPages in 0..20000) { "Reserva de páginas inválida." }
        return this
    }
}

data class AnnualReadingPlan(
    val year: Int,
    val goalMode: PlanGoalMode = PlanGoalMode.PAGES_PER_DAY,
    val dailyPagesTarget: Int = 30,
    val monthlyBooksTarget: Int = 2,
    val showPurchaseGoal: Boolean = true,
    val entries: List<PlanEntry> = emptyList(),
) {
    fun validated(): AnnualReadingPlan {
        require(year in 2020..2100) { "Ano do plano inválido." }
        require(dailyPagesTarget in 1..2000) { "Meta diária deve ficar entre 1 e 2.000 páginas." }
        require(monthlyBooksTarget in 1..100) { "Meta mensal deve ficar entre 1 e 100 livros." }
        require(entries.size <= MAX_ENTRIES) { "Limite de $MAX_ENTRIES itens por plano anual atingido." }
        val seen = HashSet<String>(entries.size)
        entries.forEach {
            it.validated()
            require(seen.add(it.id)) { "Item duplicado no plano." }
        }
        return this
    }

    companion object {
        const val MAX_ENTRIES = 240
    }
}

data class ResolvedPlanItem(
    val entry: PlanEntry,
    val title: String,
    val author: String,
    val saga: String,
    val localCover: String,
    val remoteCoverUrl: String,
    val remainingPages: Int,
    val totalPages: Int,
    val status: PlanItemStatus,
    val sequenceNumber: Int?,
    val linkedBook: LibraryBook?,
)

data class MonthPlanSummary(
    val month: Int,
    val daysInMonth: Int,
    val remainingDays: Int,
    val isPast: Boolean,
    val isCurrent: Boolean,
    val items: List<ResolvedPlanItem>,
    val totalRemainingPages: Int,
    val totalPlannedPages: Int,
    val plannedBookCount: Int,
    val completedBookCount: Int,
    val jokerCount: Int,
    val pacePagesPerDay: Double,
    val loadRatio: Double,
    val health: MonthHealth,
    val pendingCount: Int,
)

data class SagaPlanSummary(
    val name: String,
    val completedCount: Int,
    val totalCount: Int,
)

data class YearPlanSummary(
    val completedCount: Int,
    val totalPlannedCount: Int,
    val annualBookGoal: Int,
    val averageDailyPages: Double,
    val estimatedMinutesPerDay: Int?,
    val months: List<MonthPlanSummary>,
    val sagas: List<SagaPlanSummary>,
    val purchaseTokensFree: Int,
    val purchaseCycleDone: Int,
    val purchaseCycleTarget: Int,
    val purchaseGoalId: String?,
)

object AnnualPlanCalculator {
    private val monthShorts = listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OUT", "NOV", "DEZ")
    private val monthNames = listOf(
        "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
        "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro",
    )

    fun shortName(month: Int): String = monthShorts[(month - 1).coerceIn(0, 11)]
    fun fullName(month: Int): String = monthNames[(month - 1).coerceIn(0, 11)]

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        else -> 30
    }

    fun resolveItem(entry: PlanEntry, booksById: Map<String, LibraryBook>): ResolvedPlanItem {
        if (entry.kind == PlanEntryKind.JOKER) {
            return ResolvedPlanItem(
                entry = entry,
                title = entry.title.ifBlank { "Slot livre (Coringa)" },
                author = entry.author,
                saga = entry.saga,
                localCover = "",
                remoteCoverUrl = "",
                remainingPages = entry.reservedPages.coerceAtLeast(0),
                totalPages = entry.reservedPages.coerceAtLeast(0),
                status = if (entry.completed) PlanItemStatus.COMPLETED else PlanItemStatus.PLANNED,
                sequenceNumber = null,
                linkedBook = null,
            )
        }
        val book = entry.bookId.takeIf { it.isNotBlank() }?.let { booksById[it] }
        if (book != null) {
            val total = if (book.pagesKnown) book.totalPages.coerceAtLeast(0) else entry.reservedPages.coerceAtLeast(0)
            val remaining = if (book.complete) 0 else if (book.pagesKnown) (book.totalPages - book.currentPage).coerceAtLeast(0) else entry.reservedPages.coerceAtLeast(0)
            val status = when {
                book.complete -> PlanItemStatus.COMPLETED
                book.currentPage > 0 -> PlanItemStatus.READING
                else -> PlanItemStatus.PLANNED
            }
            return ResolvedPlanItem(
                entry = entry,
                title = book.title,
                author = book.author,
                saga = book.saga.ifBlank { entry.saga },
                localCover = book.cover,
                remoteCoverUrl = entry.coverUrl,
                remainingPages = remaining,
                totalPages = total,
                status = status,
                sequenceNumber = book.sequenceNumber,
                linkedBook = book,
            )
        }
        return ResolvedPlanItem(
            entry = entry,
            title = entry.title.ifBlank { "Livro planejado" },
            author = entry.author,
            saga = entry.saga,
            localCover = "",
            remoteCoverUrl = entry.coverUrl,
            remainingPages = if (entry.completed) 0 else entry.reservedPages.coerceAtLeast(0),
            totalPages = entry.reservedPages.coerceAtLeast(0),
            status = if (entry.completed) PlanItemStatus.COMPLETED else PlanItemStatus.PLANNED,
            sequenceNumber = null,
            linkedBook = null,
        )
    }

    fun summarize(
        plan: AnnualReadingPlan,
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        purchaseGoal: ReadingGoal? = null,
        purchaseProgress: GoalProgress? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): YearPlanSummary {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        return summarizeAt(
            plan = plan,
            books = books,
            sessions = sessions,
            purchaseGoal = purchaseGoal,
            purchaseProgress = purchaseProgress,
            todayYear = cal.get(Calendar.YEAR),
            todayMonth = cal.get(Calendar.MONTH) + 1,
            todayDay = cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    fun summarizeAt(
        plan: AnnualReadingPlan,
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        purchaseGoal: ReadingGoal? = null,
        purchaseProgress: GoalProgress? = null,
        todayYear: Int,
        todayMonth: Int,
        todayDay: Int,
    ): YearPlanSummary {
        val activeBooks = books.filter { !it.archived }
        val booksById = activeBooks.associateBy { it.id }
        val byMonth = plan.entries.groupBy { it.month }

        // User's real pages/hour speed across timed sessions -> converts daily pages into ~min/day!
        val timedSessions = sessions.filter { s ->
            val b = booksById[s.bookId]
            b != null && b.pagesKnown && s.pages > 0 && s.durationMs >= 1000L
        }
        val totalTimedPages = timedSessions.sumOf { it.pages.toLong() }
        val totalTimedMs = timedSessions.sumOf { it.durationMs }
        val pagesPerHour = if (totalTimedPages > 0L && totalTimedMs > 0L) {
            totalTimedPages * 3_600_000.0 / totalTimedMs.toDouble()
        } else 0.0

        val monthSummaries = (1..12).map { m ->
            val days = daysInMonth(plan.year, m)
            val isPast = plan.year < todayYear || (plan.year == todayYear && m < todayMonth)
            val isCurrent = plan.year == todayYear && m == todayMonth
            val remainingDays = when {
                isPast -> 0
                isCurrent -> (days - todayDay + 1).coerceIn(1, days)
                else -> days
            }
            val resolved = (byMonth[m] ?: emptyList())
                .sortedWith(compareBy<PlanEntry> { it.position }.thenBy { it.id })
                .map { resolveItem(it, booksById) }

            val jokers = resolved.count { it.entry.kind == PlanEntryKind.JOKER }
            val nonJokers = resolved.filter { it.entry.kind != PlanEntryKind.JOKER }
            val completedBooks = nonJokers.count { it.status == PlanItemStatus.COMPLETED }
            val pendingCount = nonJokers.count { it.status != PlanItemStatus.COMPLETED }
            val totalPlannedPages = resolved.sumOf { it.totalPages }
            val totalRemainingPages = resolved.sumOf { it.remainingPages }

            val pace = when {
                isCurrent -> totalRemainingPages.toDouble() / remainingDays.coerceAtLeast(1)
                else -> totalPlannedPages.toDouble() / days.coerceAtLeast(1)
            }

            val loadRatio = when (plan.goalMode) {
                PlanGoalMode.PAGES_PER_DAY -> {
                    val denom = (plan.dailyPagesTarget * if (isCurrent) remainingDays else days).coerceAtLeast(1)
                    (if (isCurrent) totalRemainingPages else totalPlannedPages).toDouble() / denom
                }
                PlanGoalMode.BOOKS_PER_MONTH -> {
                    resolved.size.toDouble() / plan.monthlyBooksTarget.coerceAtLeast(1)
                }
                PlanGoalMode.NONE -> if (resolved.isEmpty()) 0.0 else 0.75
            }

            val health = when {
                resolved.isEmpty() -> {
                    if (isPast && plan.goalMode != PlanGoalMode.NONE) MonthHealth.MISSED else MonthHealth.EMPTY
                }
                isPast -> {
                    val goalMet = when (plan.goalMode) {
                        PlanGoalMode.BOOKS_PER_MONTH -> completedBooks >= plan.monthlyBooksTarget
                        PlanGoalMode.PAGES_PER_DAY -> pendingCount == 0 && (totalPlannedPages >= (plan.dailyPagesTarget * days * 0.85))
                        PlanGoalMode.NONE -> pendingCount == 0
                    }
                    if (goalMet || (pendingCount == 0 && completedBooks > 0)) MonthHealth.COMPLETED else MonthHealth.MISSED
                }
                else -> {
                    if (pendingCount == 0 && nonJokers.isNotEmpty() && jokers == 0) {
                        MonthHealth.COMPLETED
                    } else if (plan.goalMode == PlanGoalMode.PAGES_PER_DAY && pace > plan.dailyPagesTarget * 1.05) {
                        // Heavy planned month -> Amber/Gold (INTENSE), NEVER red!
                        MonthHealth.INTENSE
                    } else if (plan.goalMode == PlanGoalMode.BOOKS_PER_MONTH && resolved.size > plan.monthlyBooksTarget) {
                        MonthHealth.INTENSE
                    } else {
                        MonthHealth.ON_PACE
                    }
                }
            }

            MonthPlanSummary(
                month = m,
                daysInMonth = days,
                remainingDays = remainingDays,
                isPast = isPast,
                isCurrent = isCurrent,
                items = resolved,
                totalRemainingPages = totalRemainingPages,
                totalPlannedPages = totalPlannedPages,
                plannedBookCount = nonJokers.size,
                completedBookCount = completedBooks,
                jokerCount = jokers,
                pacePagesPerDay = pace,
                loadRatio = loadRatio,
                health = health,
                pendingCount = pendingCount,
            )
        }

        val allResolved = monthSummaries.flatMap { it.items }
        val completedTotal = allResolved.count { it.entry.kind != PlanEntryKind.JOKER && it.status == PlanItemStatus.COMPLETED }
        val plannedTotal = allResolved.size
        val annualBookGoal = when {
            plan.goalMode == PlanGoalMode.BOOKS_PER_MONTH -> maxOf(plannedTotal, plan.monthlyBooksTarget * 12)
            plannedTotal > 0 -> plannedTotal
            else -> plan.monthlyBooksTarget * 12
        }

        val totalYearPages = monthSummaries.sumOf { it.totalPlannedPages }
        val yearDays = (1..12).sumOf { daysInMonth(plan.year, it) }
        val avgDaily = if (totalYearPages > 0) totalYearPages.toDouble() / yearDays else plan.dailyPagesTarget.toDouble()
        val estMin = if (pagesPerHour > 1.0 && avgDaily > 0.0) {
            ((avgDaily / pagesPerHour) * 60.0).roundToInt().coerceIn(1, 600)
        } else null

        // Sagas progress across books referenced in the plan (crossed with shelf volumes of those sagas).
        val plannedSagas = LinkedHashMap<String, String>()
        allResolved.forEach { item ->
            val s = item.saga.trim()
            val key = ShelfFilters.key(s)
            if (key.isNotBlank() && key !in plannedSagas) plannedSagas[key] = s
        }
        val sagas = plannedSagas.map { (key, display) ->
            val shelfInSaga = activeBooks.filter { ShelfFilters.key(it.saga) == key }
            val planInSaga = allResolved.filter { ShelfFilters.key(it.saga) == key }
            val total = maxOf(shelfInSaga.size, planInSaga.size)
            val done = maxOf(
                shelfInSaga.count { it.complete },
                planInSaga.count { it.status == PlanItemStatus.COMPLETED },
            ).coerceAtMost(total)
            SagaPlanSummary(display, done, total)
        }.sortedWith(compareByDescending<SagaPlanSummary> { it.totalCount }.thenBy { it.name.lowercase(Locale("pt", "BR")) })

        val pTarget = purchaseGoal?.target?.coerceAtLeast(1) ?: 3
        val pVal = purchaseProgress?.value?.toInt()?.coerceAtLeast(0) ?: 0
        val freeTokens = if (purchaseGoal != null) (pVal / pTarget) * purchaseGoal.reward else 0
        val cycleDone = if (purchaseGoal != null) pVal % pTarget else 0

        return YearPlanSummary(
            completedCount = completedTotal,
            totalPlannedCount = plannedTotal,
            annualBookGoal = annualBookGoal,
            averageDailyPages = avgDaily,
            estimatedMinutesPerDay = estMin,
            months = monthSummaries,
            sagas = sagas,
            purchaseTokensFree = freeTokens,
            purchaseCycleDone = cycleDone,
            purchaseCycleTarget = pTarget,
            purchaseGoalId = purchaseGoal?.id,
        )
    }

    fun formatDecimal(value: Double): String =
        String.format(Locale("pt", "BR"), "%.1f", value)
}
