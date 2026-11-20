package br.com.centralcasa.books

import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class PlanGoalMode(val label: String) {
    NONE("Só lista"),
    PAGES_PER_DAY("Páginas por dia"),
    BOOKS_PER_MONTH("Livros por mês"),
}

enum class PlanEntryKind {
    SHELF_BOOK,
    WISHLIST_BOOK, // Legacy decode compatibility only (no longer created)
    JOKER,         // Legacy decode compatibility only (no longer created)
}

enum class PlanItemStatus(val label: String) {
    PLANNED("Planejado"),
    READING("Lendo"),
    READ_IN_MONTH("Lido no mês"),
    COMPLETED("Concluído"),
}

enum class MonthHealth {
    EMPTY,
    COMPLETED, // Green: goal met / all completed
    ON_PACE,   // Green: current/future month within target
    INTENSE,   // Amber/Gold: current/future month planned above target (never red!)
    MISSED,    // Red: past month where goal was NOT met or planned books stayed unfinished
}

data class PlanEntry(
    val id: String = UUID.randomUUID().toString(),
    val month: Int,
    val position: Int = 0,
    val kind: PlanEntryKind = PlanEntryKind.SHELF_BOOK,
    val bookId: String = "",
    val wishlistCatalogId: String = "",
    val title: String = "",
    val author: String = "",
    val saga: String = "",
    val coverUrl: String = "",
    val reservedPages: Int = 200,
    val tag: String = "",
    val completed: Boolean = false,
    val reread: Boolean = false,
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
    val entry: PlanEntry?,
    val bookId: String,
    val title: String,
    val author: String,
    val saga: String,
    val localCover: String,
    val remainingPages: Int,
    val totalPages: Int,
    val pagesReadInMonth: Int,
    val status: PlanItemStatus,
    val sequenceNumber: Int?,
    val linkedBook: LibraryBook?,
    val isAutoActivity: Boolean = false,
    val isReread: Boolean = false,
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
    val actualPagesRead: Int,
    val actualBooksCompleted: Int,
    val plannedBookCount: Int,
    val completedBookCount: Int,
    val pacePagesPerDay: Double,
    val barProgress: Double,
    val health: MonthHealth,
    val pendingCount: Int,
    val hasManualPending: Boolean,
)

data class SagaPlanSummary(
    val name: String,
    val completedCount: Int,
    val totalCount: Int,
)

data class EffectivePlanTargets(
    val mode: PlanGoalMode,
    val dailyPagesTarget: Int,
    val monthlyBooksTarget: Int,
    val annualBookGoal: Int?,
    val annualPageGoal: Int?,
    val derivedFromReadingGoals: Boolean,
)

data class YearPlanSummary(
    val completedCount: Int,
    val totalBooksInYear: Int,
    val totalPagesReadInYear: Int,
    val annualBookGoal: Int,
    val averageDailyPages: Double,
    val estimatedMinutesPerDay: Int?,
    val effectiveTargets: EffectivePlanTargets,
    val months: List<MonthPlanSummary>,
    val sagas: List<SagaPlanSummary>,
)

/**
 * Computes which books were completed within a given [ReadingPeriodRange] without modifying [PeriodReading.from],
 * keeping existing period page invariants intact while accurately accounting for completed books via sessions,
 * manual reading dates, or completion timestamps.
 */
object PeriodCompletions {
    fun completionDay(
        book: LibraryBook,
        bookSessions: List<ReadingSession>,
        bookDates: List<ReadingDate>,
    ): String? {
        if (book.archived) return null
        if (book.pagesKnown) {
            val finishSessionDay = bookSessions
                .filter { it.endPage >= book.totalPages }
                .map { it.readingDay ?: ReadingDays.at(it.finishedAt) }
                .minOrNull()
            if (finishSessionDay != null) return finishSessionDay
        }
        if (!book.complete) return null
        val lastManualDay = bookDates.maxOfOrNull { it.day }
        val lastSessionDay = bookSessions.maxOfOrNull { it.readingDay ?: ReadingDays.at(it.finishedAt) }
        return listOfNotNull(lastManualDay, lastSessionDay).maxOrNull()
    }

    fun completedBookIds(
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate>,
        range: ReadingPeriodRange,
    ): Set<String> {
        val sessionsByBook = sessions.groupBy { it.bookId }
        val datesByBook = dates.groupBy { it.bookId }
        val result = LinkedHashSet<String>()
        books.forEach { book ->
            if (book.archived) return@forEach
            val bSessions = sessionsByBook[book.id].orEmpty()
            // Any timed session hitting the final page inside this range counts (including rereads).
            val finishedBySessionInRange = book.pagesKnown && bSessions.any { s ->
                s.endPage >= book.totalPages && range.contains(s.readingDay ?: ReadingDays.at(s.finishedAt))
            }
            if (finishedBySessionInRange) {
                result.add(book.id)
                return@forEach
            }
            val day = completionDay(book, bSessions, datesByBook[book.id].orEmpty())
            if (day != null && range.contains(day)) {
                result.add(book.id)
            }
        }
        return result
    }
}

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

    fun monthRange(year: Int, month: Int): ReadingPeriodRange {
        val m = month.coerceIn(1, 12)
        val anchor = String.format(Locale.US, "%04d-%02d-01", year, m)
        return ReadingPeriods.range(anchor, ReadingPeriodUnit.MONTH)
    }

    fun deriveTargets(plan: AnnualReadingPlan, goals: List<ReadingGoal>, yearDays: Int): EffectivePlanTargets {
        val pagesGoal = goals.firstOrNull { it.metric == GoalMetric.PAGES }
        val booksGoal = goals.firstOrNull { it.metric == GoalMetric.BOOKS }

        val derivedDailyPages: Int? = pagesGoal?.let { g ->
            when (g.period) {
                GoalPeriod.DAY -> g.target
                GoalPeriod.WEEK -> ceil(g.target / 7.0).toInt()
                GoalPeriod.MONTH -> ceil(g.target / 30.0).toInt()
                GoalPeriod.YEAR -> ceil(g.target / yearDays.toDouble()).toInt()
                GoalPeriod.CONTINUOUS -> ceil(g.target / 30.0).toInt()
            }.coerceIn(1, 2000)
        }
        val derivedAnnualPages: Int? = pagesGoal?.let { g ->
            when (g.period) {
                GoalPeriod.DAY -> g.target * yearDays
                GoalPeriod.WEEK -> g.target * 52
                GoalPeriod.MONTH -> g.target * 12
                GoalPeriod.YEAR -> g.target
                GoalPeriod.CONTINUOUS -> g.target
            }
        }
        val derivedMonthlyBooks: Int? = booksGoal?.let { g ->
            when (g.period) {
                GoalPeriod.DAY -> g.target * 30
                GoalPeriod.WEEK -> g.target * 4
                GoalPeriod.MONTH -> g.target
                GoalPeriod.YEAR -> ceil(g.target / 12.0).toInt()
                GoalPeriod.CONTINUOUS -> ceil(g.target / 12.0).toInt()
            }.coerceIn(1, 100)
        }
        val derivedAnnualBooks: Int? = booksGoal?.let { g ->
            when (g.period) {
                GoalPeriod.DAY -> g.target * yearDays
                GoalPeriod.WEEK -> g.target * 52
                GoalPeriod.MONTH -> g.target * 12
                GoalPeriod.YEAR -> g.target
                GoalPeriod.CONTINUOUS -> g.target
            }
        }

        val hasRealGoal = derivedDailyPages != null || derivedMonthlyBooks != null
        val mode = when {
            plan.goalMode == PlanGoalMode.NONE && !hasRealGoal -> PlanGoalMode.NONE
            plan.goalMode == PlanGoalMode.PAGES_PER_DAY && derivedDailyPages != null -> PlanGoalMode.PAGES_PER_DAY
            plan.goalMode == PlanGoalMode.BOOKS_PER_MONTH && derivedMonthlyBooks != null -> PlanGoalMode.BOOKS_PER_MONTH
            derivedDailyPages != null -> PlanGoalMode.PAGES_PER_DAY
            derivedMonthlyBooks != null -> PlanGoalMode.BOOKS_PER_MONTH
            else -> plan.goalMode
        }
        return EffectivePlanTargets(
            mode = mode,
            dailyPagesTarget = derivedDailyPages ?: plan.dailyPagesTarget,
            monthlyBooksTarget = derivedMonthlyBooks ?: plan.monthlyBooksTarget,
            annualBookGoal = derivedAnnualBooks,
            annualPageGoal = derivedAnnualPages,
            derivedFromReadingGoals = hasRealGoal,
        )
    }

    fun resolveItem(
        entry: PlanEntry,
        booksById: Map<String, LibraryBook>,
        pagesReadInMonth: Int = 0,
        completedInMonth: Boolean = false,
        readInMonth: Boolean = false,
    ): ResolvedPlanItem {
        val book = entry.bookId.takeIf { it.isNotBlank() }?.let { booksById[it] }
        if (book != null) {
            val total = if (book.pagesKnown) book.totalPages.coerceAtLeast(0) else entry.reservedPages.coerceAtLeast(0)
            val status = when {
                entry.completed -> PlanItemStatus.COMPLETED
                entry.reread -> when {
                    completedInMonth -> PlanItemStatus.COMPLETED
                    readInMonth -> PlanItemStatus.READING
                    else -> PlanItemStatus.PLANNED
                }
                completedInMonth || book.complete -> PlanItemStatus.COMPLETED
                book.currentPage > 0 -> PlanItemStatus.READING
                readInMonth -> PlanItemStatus.READ_IN_MONTH
                else -> PlanItemStatus.PLANNED
            }
            val remaining = when {
                status == PlanItemStatus.COMPLETED -> 0
                entry.reread -> (total - pagesReadInMonth).coerceAtLeast(0)
                book.pagesKnown -> (book.totalPages - book.currentPage).coerceAtLeast(0)
                else -> entry.reservedPages.coerceAtLeast(0)
            }
            val effectiveReadPages = when {
                pagesReadInMonth > 0 -> pagesReadInMonth
                status == PlanItemStatus.COMPLETED && book.pagesKnown -> book.totalPages
                else -> 0
            }
            return ResolvedPlanItem(
                entry = entry,
                bookId = book.id,
                title = book.title,
                author = book.author,
                saga = book.saga.ifBlank { entry.saga },
                localCover = book.cover,
                remainingPages = remaining,
                totalPages = total,
                pagesReadInMonth = effectiveReadPages,
                status = status,
                sequenceNumber = book.sequenceNumber,
                linkedBook = book,
                isAutoActivity = false,
                isReread = entry.reread,
            )
        }
        val status = if (entry.completed) PlanItemStatus.COMPLETED else PlanItemStatus.PLANNED
        val total = entry.reservedPages.coerceAtLeast(0)
        return ResolvedPlanItem(
            entry = entry,
            bookId = entry.bookId,
            title = entry.title.ifBlank { "Livro planejado" },
            author = entry.author,
            saga = entry.saga,
            localCover = "",
            remainingPages = if (status == PlanItemStatus.COMPLETED) 0 else total,
            totalPages = total,
            pagesReadInMonth = if (status == PlanItemStatus.COMPLETED) total else pagesReadInMonth,
            status = status,
            sequenceNumber = null,
            linkedBook = null,
            isAutoActivity = false,
            isReread = entry.reread,
        )
    }

    fun summarize(
        plan: AnnualReadingPlan,
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate> = emptyList(),
        goals: List<ReadingGoal> = emptyList(),
        currentBookId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): YearPlanSummary {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        return summarizeAt(
            plan = plan,
            books = books,
            sessions = sessions,
            dates = dates,
            goals = goals,
            currentBookId = currentBookId,
            todayYear = cal.get(Calendar.YEAR),
            todayMonth = cal.get(Calendar.MONTH) + 1,
            todayDay = cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    fun summarizeAt(
        plan: AnnualReadingPlan,
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate> = emptyList(),
        goals: List<ReadingGoal> = emptyList(),
        currentBookId: String? = null,
        todayYear: Int,
        todayMonth: Int,
        todayDay: Int,
    ): YearPlanSummary {
        val activeBooks = books.filter { !it.archived }
        val booksById = activeBooks.associateBy { it.id }
        // Ignore legacy JOKER and WISHLIST_BOOK entries safely without failing decode.
        val shelfEntriesByMonth = plan.entries
            .filter { it.kind == PlanEntryKind.SHELF_BOOK }
            .groupBy { it.month }

        val yearDays = (1..12).sumOf { daysInMonth(plan.year, it) }
        val targets = deriveTargets(plan, goals, yearDays)

        // User's real pages/hour speed across timed sessions -> converts daily pages into ~min/day.
        val timedSessions = sessions.filter { s ->
            val b = booksById[s.bookId]
            b != null && b.pagesKnown && s.pages > 0 && s.durationMs >= 1000L
        }
        val totalTimedPages = timedSessions.sumOf { it.pages.toLong() }
        val totalTimedMs = timedSessions.sumOf { it.durationMs }
        val pagesPerHour = if (totalTimedPages > 0L && totalTimedMs > 0L) {
            totalTimedPages * 3_600_000.0 / totalTimedMs.toDouble()
        } else 0.0

        val currentBook = currentBookId?.let { booksById[it] }?.takeUnless { it.complete }

        val monthSummaries = (1..12).map { m ->
            val days = daysInMonth(plan.year, m)
            val isPast = plan.year < todayYear || (plan.year == todayYear && m < todayMonth)
            val isCurrent = plan.year == todayYear && m == todayMonth
            val remainingDays = when {
                isPast -> 0
                isCurrent -> (days - todayDay + 1).coerceIn(1, days)
                else -> days
            }

            val range = monthRange(plan.year, m)
            val period = PeriodReading.from(activeBooks, sessions, dates, range)
            val completedAutoIds = PeriodCompletions.completedBookIds(activeBooks, sessions, dates, range)

            val sessionPagesByBook = period.sessions
                .groupBy { it.bookId }
                .mapValues { (bookId, list) ->
                    val b = booksById[bookId]
                    if (b != null && b.pagesKnown) list.sumOf { it.pages.coerceAtLeast(0) } else 0
                }

            val manualEntries = (shelfEntriesByMonth[m] ?: emptyList())
                .sortedWith(compareBy<PlanEntry> { it.position }.thenBy { it.id })
            val manualResolved = manualEntries.map { entry ->
                val bId = entry.bookId
                val sPages = if (bId.isNotBlank()) sessionPagesByBook[bId] ?: 0 else 0
                val compHere = bId.isNotBlank() && bId in completedAutoIds
                val readHere = bId.isNotBlank() && bId in period.bookIds
                resolveItem(
                    entry = entry,
                    booksById = booksById,
                    pagesReadInMonth = sPages,
                    completedInMonth = compHere,
                    readInMonth = readHere,
                )
            }
            val manualBookIds = manualResolved.mapNotNull { it.bookId.takeIf(String::isNotBlank) }.toSet()

            // Automatic activity in this month: completed here, had sessions/dates here, or is the active current book (only in current month).
            val autoBookIds = LinkedHashSet<String>()
            autoBookIds.addAll(completedAutoIds)
            autoBookIds.addAll(period.bookIds)
            if (isCurrent && currentBook != null) {
                autoBookIds.add(currentBook.id)
            }
            autoBookIds.removeAll(manualBookIds)

            val autoResolved = autoBookIds.mapNotNull { id ->
                val book = booksById[id] ?: return@mapNotNull null
                val sPages = sessionPagesByBook[id] ?: 0
                val isCompletedHere = id in completedAutoIds
                val isActiveCurrent = isCurrent && currentBook != null && id == currentBook.id
                val isReadingThisMonth = isCurrent && !book.complete && (isActiveCurrent || id in period.bookIds)
                val status = when {
                    isCompletedHere -> PlanItemStatus.COMPLETED
                    isReadingThisMonth -> PlanItemStatus.READING
                    id in period.bookIds -> PlanItemStatus.READ_IN_MONTH
                    else -> PlanItemStatus.COMPLETED
                }
                val total = if (book.pagesKnown) book.totalPages.coerceAtLeast(0) else 0
                val remaining = when {
                    status == PlanItemStatus.COMPLETED || book.complete -> 0
                    book.pagesKnown -> (book.totalPages - book.currentPage).coerceAtLeast(0)
                    else -> 0
                }
                val readPages = when {
                    sPages > 0 -> sPages
                    status == PlanItemStatus.COMPLETED && book.pagesKnown -> book.totalPages
                    else -> 0
                }
                ResolvedPlanItem(
                    entry = null,
                    bookId = book.id,
                    title = book.title,
                    author = book.author,
                    saga = book.saga,
                    localCover = book.cover,
                    remainingPages = remaining,
                    totalPages = total,
                    pagesReadInMonth = readPages,
                    status = status,
                    sequenceNumber = book.sequenceNumber,
                    linkedBook = book,
                    isAutoActivity = true,
                    isReread = false,
                )
            }

            // Order cleanly: Lendo -> Planejado -> Lido no mês -> Concluído
            fun statusRank(s: PlanItemStatus) = when (s) {
                PlanItemStatus.READING -> 0
                PlanItemStatus.PLANNED -> 1
                PlanItemStatus.READ_IN_MONTH -> 2
                PlanItemStatus.COMPLETED -> 3
            }
            val combined = (manualResolved + autoResolved).sortedWith(
                compareBy<ResolvedPlanItem> { statusRank(it.status) }
                    .thenBy { it.entry?.position ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase(Locale("pt", "BR")) },
            )

            // Actual pages read in month (sessions + completed-without-full-session books).
            val actualPagesRead = combined.sumOf { it.pagesReadInMonth }
            val completedBooks = combined.count { it.status == PlanItemStatus.COMPLETED }
            val pendingCount = combined.count { it.status == PlanItemStatus.PLANNED || it.status == PlanItemStatus.READING }
            val hasManualPending = manualResolved.any { it.status != PlanItemStatus.COMPLETED }
            val totalRemainingPages = combined.sumOf { it.remainingPages }
            val totalMonthPages = actualPagesRead + totalRemainingPages

            val pace = when {
                isCurrent -> totalRemainingPages.toDouble() / remainingDays.coerceAtLeast(1)
                else -> totalMonthPages.toDouble() / days.coerceAtLeast(1)
            }

            // Real read barProgress (0.0..1.0): combines both books completed AND pages read in this month!
            val booksGoalDenom = when (targets.mode) {
                PlanGoalMode.BOOKS_PER_MONTH -> maxOf(combined.size, targets.monthlyBooksTarget)
                else -> combined.size
            }
            val pagesGoalDenom = when (targets.mode) {
                PlanGoalMode.PAGES_PER_DAY -> maxOf(totalMonthPages, targets.dailyPagesTarget * days)
                else -> totalMonthPages
            }
            val bookFrac = if (booksGoalDenom > 0) (completedBooks.toDouble() / booksGoalDenom).coerceIn(0.0, 1.0) else null
            val pageFrac = if (pagesGoalDenom > 0) (actualPagesRead.toDouble() / pagesGoalDenom).coerceIn(0.0, 1.0) else null
            val rawBar = when {
                bookFrac != null && pageFrac != null -> {
                    if (completedBooks > 0 && pendingCount == 0 && bookFrac >= 1.0 && (targets.mode != PlanGoalMode.PAGES_PER_DAY || pageFrac >= 1.0)) {
                        1.0
                    } else {
                        ((bookFrac + pageFrac) / 2.0).coerceIn(0.0, 1.0)
                    }
                }
                pageFrac != null -> pageFrac
                bookFrac != null -> bookFrac
                else -> 0.0
            }

            val health = when {
                combined.isEmpty() -> {
                    if (isPast && targets.mode != PlanGoalMode.NONE) MonthHealth.MISSED else MonthHealth.EMPTY
                }
                isPast -> {
                    val metGoal = when (targets.mode) {
                        PlanGoalMode.BOOKS_PER_MONTH -> completedBooks >= targets.monthlyBooksTarget
                        PlanGoalMode.PAGES_PER_DAY -> actualPagesRead >= (targets.dailyPagesTarget * days * 0.85)
                        PlanGoalMode.NONE -> pendingCount == 0 && (completedBooks > 0 || actualPagesRead > 0)
                    }
                    if (metGoal && !hasManualPending) MonthHealth.COMPLETED else MonthHealth.MISSED
                }
                else -> {
                    val allDone = pendingCount == 0 && completedBooks > 0 && when (targets.mode) {
                        PlanGoalMode.BOOKS_PER_MONTH -> completedBooks >= targets.monthlyBooksTarget
                        PlanGoalMode.PAGES_PER_DAY -> actualPagesRead >= (targets.dailyPagesTarget * days * 0.85)
                        PlanGoalMode.NONE -> true
                    }
                    if (allDone) {
                        MonthHealth.COMPLETED
                    } else if (targets.mode == PlanGoalMode.PAGES_PER_DAY && pace > targets.dailyPagesTarget * 1.05) {
                        // Heavy planned month -> Amber/Gold (INTENSE), NEVER red!
                        MonthHealth.INTENSE
                    } else if (targets.mode == PlanGoalMode.BOOKS_PER_MONTH && combined.size > targets.monthlyBooksTarget) {
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
                items = combined,
                totalRemainingPages = totalRemainingPages,
                totalPlannedPages = totalMonthPages,
                actualPagesRead = actualPagesRead,
                actualBooksCompleted = completedBooks,
                plannedBookCount = combined.size,
                completedBookCount = completedBooks,
                pacePagesPerDay = pace,
                barProgress = rawBar,
                health = health,
                pendingCount = pendingCount,
                hasManualPending = hasManualPending,
            )
        }

        val allResolved = monthSummaries.flatMap { it.items }
        // Distinct completed books across the year (including explicit reread entries).
        val completedUniqueKeys = LinkedHashSet<String>()
        allResolved.forEach { item ->
            if (item.status == PlanItemStatus.COMPLETED) {
                val key = if (item.isReread && item.entry != null) "reread:${item.entry.id}" else item.bookId.ifBlank { item.entry?.id ?: item.title }
                completedUniqueKeys.add(key)
            }
        }
        val allUniqueBookKeys = allResolved.map { item ->
            if (item.isReread && item.entry != null) "reread:${item.entry.id}" else item.bookId.ifBlank { item.entry?.id ?: item.title }
        }.toSet()

        val completedTotal = completedUniqueKeys.size
        val totalBooksInYear = allUniqueBookKeys.size
        val totalPagesReadInYear = monthSummaries.sumOf { it.actualPagesRead }

        val annualBookGoal = targets.annualBookGoal ?: when {
            targets.mode == PlanGoalMode.BOOKS_PER_MONTH -> maxOf(totalBooksInYear, targets.monthlyBooksTarget * 12)
            totalBooksInYear > 0 -> totalBooksInYear
            else -> targets.monthlyBooksTarget * 12
        }

        val totalYearPages = monthSummaries.sumOf { it.totalPlannedPages }
        val avgDaily = if (totalYearPages > 0) totalYearPages.toDouble() / yearDays else targets.dailyPagesTarget.toDouble()
        val estMin = if (pagesPerHour > 1.0 && avgDaily > 0.0) {
            ((avgDaily / pagesPerHour) * 60.0).roundToInt().coerceIn(1, 600)
        } else null

        // Sagas progress across books referenced in the year (crossed with shelf volumes of those sagas).
        val plannedSagas = LinkedHashMap<String, String>()
        allResolved.forEach { item ->
            val s = item.saga.trim()
            val key = ShelfFilters.key(s)
            if (key.isNotBlank() && key !in plannedSagas) plannedSagas[key] = s
        }
        val sagas = plannedSagas.map { (key, display) ->
            val shelfInSaga = activeBooks.filter { ShelfFilters.key(it.saga) == key }
            val planInSaga = allResolved.filter { ShelfFilters.key(it.saga) == key }
            val total = maxOf(shelfInSaga.size, planInSaga.map { it.bookId.ifBlank { it.title } }.distinct().size)
            val done = maxOf(
                shelfInSaga.count { it.complete },
                planInSaga.filter { it.status == PlanItemStatus.COMPLETED }.map { it.bookId.ifBlank { it.title } }.distinct().size,
            ).coerceAtMost(total)
            SagaPlanSummary(display, done, total)
        }.sortedWith(compareByDescending<SagaPlanSummary> { it.totalCount }.thenBy { it.name.lowercase(Locale("pt", "BR")) })

        return YearPlanSummary(
            completedCount = completedTotal,
            totalBooksInYear = totalBooksInYear,
            totalPagesReadInYear = totalPagesReadInYear,
            annualBookGoal = annualBookGoal,
            averageDailyPages = avgDaily,
            estimatedMinutesPerDay = estMin,
            effectiveTargets = targets,
            months = monthSummaries,
            sagas = sagas,
        )
    }

    fun formatDecimal(value: Double): String =
        String.format(Locale("pt", "BR"), "%.1f", value)
}
