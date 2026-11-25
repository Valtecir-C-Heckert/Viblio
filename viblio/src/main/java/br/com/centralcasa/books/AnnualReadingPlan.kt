package br.com.centralcasa.books

import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class BooksGoalType(val label: String) {
    NONE("Desligada"),
    PER_MONTH("Por mês"),
    PER_YEAR("No ano"),
}

enum class PagesGoalType(val label: String) {
    NONE("Desligada"),
    PER_DAY("Por dia"),
    PER_MONTH("Por mês"),
}

enum class PlanGoalMode(val label: String) {
    NONE("Só lista"),
    PAGES_PER_DAY("Páginas por dia"),
    BOOKS_PER_MONTH("Livros por mês"),
}

enum class PlanEntryKind {
    SHELF_BOOK,
    WISHLIST_BOOK, // Legacy decode compatibility only
    JOKER,         // Legacy decode compatibility only
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
    val booksGoalType: BooksGoalType = BooksGoalType.NONE,
    val booksGoalValue: Int = 2,
    val pagesGoalType: PagesGoalType = PagesGoalType.NONE,
    val pagesGoalValue: Int = 30,
    val goalConfigured: Boolean = false,
    val excludedMonthBooks: Set<String> = emptySet(), // "MM:bookId"
    val excludedYearBooks: Set<String> = emptySet(),  // "bookId"
    val entries: List<PlanEntry> = emptyList(),
    // Legacy fields preserved for smooth decode compatibility
    val goalMode: PlanGoalMode = PlanGoalMode.NONE,
    val dailyPagesTarget: Int = 30,
    val monthlyBooksTarget: Int = 2,
    val showPurchaseGoal: Boolean = true,
) {
    fun validated(): AnnualReadingPlan {
        require(year in 2020..2100) { "Ano da meta inválido." }
        require(booksGoalValue in 1..5000) { "Meta de livros deve ficar entre 1 e 5.000." }
        require(pagesGoalValue in 1..1_000_000) { "Meta de páginas deve ficar entre 1 e 1.000.000." }
        require(dailyPagesTarget in 1..2000) { "Meta diária deve ficar entre 1 e 2.000 páginas." }
        require(monthlyBooksTarget in 1..100) { "Meta mensal deve ficar entre 1 e 100 livros." }
        require(excludedMonthBooks.size <= MAX_EXCLUDED && excludedMonthBooks.all { it.length in 3..210 }) { "Limite de exclusões mensais atingido." }
        require(excludedYearBooks.size <= MAX_EXCLUDED && excludedYearBooks.all { it.length in 1..200 }) { "Limite de exclusões anuais atingido." }
        require(entries.size <= MAX_ENTRIES) { "Limite de $MAX_ENTRIES itens por ano atingido." }
        val seen = HashSet<String>(entries.size)
        entries.forEach {
            it.validated()
            require(seen.add(it.id)) { "Item duplicado no ano." }
        }
        return this
    }

    companion object {
        const val MAX_ENTRIES = 240
        const val MAX_EXCLUDED = 2000
        fun monthExclusionKey(month: Int, bookId: String): String =
            String.format(Locale.US, "%02d:%s", month.coerceIn(1, 12), bookId)
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
    val advancedFromMonth: Int? = null,
)

data class YearBookCredit(
    val bookId: String,
    val title: String,
    val author: String,
    val cover: String,
    val month: Int,
    val entryId: String?,
    val isReread: Boolean,
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
    val monthlyBooksTarget: Int,
    val monthlyPagesTarget: Int,
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

data class YearPlanSummary(
    val year: Int,
    val completedCount: Int,
    val completedBooksInYear: List<YearBookCredit>,
    val totalBooksInYear: Int,
    val totalPagesReadInYear: Int,
    val annualBookGoal: Int,
    val annualPageGoal: Int,
    val averageDailyPagesRead: Double,
    val estimatedMinutesPerDay: Int?,
    val months: List<MonthPlanSummary>,
    val sagas: List<SagaPlanSummary>,
)

/**
 * Computes which books were completed within a given [ReadingPeriodRange] without modifying [PeriodReading.from].
 * Strictly uses recorded sessions and manual [ReadingDate]s — never guesses from [LibraryBook.updatedAt].
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

    fun resolveItem(
        entry: PlanEntry,
        booksById: Map<String, LibraryBook>,
        pagesReadInMonth: Int = 0,
        completedInMonth: Boolean = false,
        readInMonth: Boolean = false,
        advancedFromMonth: Int? = null,
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
                (entry.completed || completedInMonth) && book.pagesKnown -> book.totalPages
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
                advancedFromMonth = advancedFromMonth,
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
            advancedFromMonth = advancedFromMonth,
        )
    }

    fun summarize(
        plan: AnnualReadingPlan,
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate> = emptyList(),
        currentBookId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): YearPlanSummary {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        return summarizeAt(
            plan = plan,
            books = books,
            sessions = sessions,
            dates = dates,
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
        currentBookId: String? = null,
        todayYear: Int,
        todayMonth: Int,
        todayDay: Int,
    ): YearPlanSummary {
        val activeBooks = books.filter { !it.archived }
        val booksById = activeBooks.associateBy { it.id }
        val activeBooksByTitle = activeBooks.groupBy { ShelfFilters.key(it.title) }
        fun resolvedBookId(entry: PlanEntry): String {
            if (entry.bookId.isNotBlank() && entry.bookId in booksById) return entry.bookId
            val tKey = ShelfFilters.key(entry.title)
            if (tKey.isBlank()) return entry.bookId
            val candidates = activeBooksByTitle[tKey].orEmpty()
            if (candidates.size == 1) return candidates[0].id
            val aKey = ShelfFilters.key(entry.author)
            if (aKey.isNotBlank()) {
                val authorMatches = candidates.filter { ShelfFilters.key(it.author) == aKey }
                if (authorMatches.size == 1) return authorMatches[0].id
            }
            return entry.bookId
        }
        val shelfEntries = plan.entries.filter { it.kind == PlanEntryKind.SHELF_BOOK }
        val effectiveEntryBookIds = shelfEntries.associate { it.id to resolvedBookId(it) }
        val shelfEntriesByMonth = shelfEntries.groupBy { it.month }

        val yearDays = (1..12).sumOf { daysInMonth(plan.year, it) }
        val monthlyBooksGoal = when (plan.booksGoalType) {
            BooksGoalType.PER_MONTH -> plan.booksGoalValue
            BooksGoalType.PER_YEAR -> ceil(plan.booksGoalValue / 12.0).toInt().coerceAtLeast(1)
            BooksGoalType.NONE -> 0
        }
        val annualBookGoal = when (plan.booksGoalType) {
            BooksGoalType.PER_MONTH -> plan.booksGoalValue * 12
            BooksGoalType.PER_YEAR -> plan.booksGoalValue
            BooksGoalType.NONE -> 0
        }
        val annualPageGoal = when (plan.pagesGoalType) {
            PagesGoalType.PER_DAY -> plan.pagesGoalValue * yearDays
            PagesGoalType.PER_MONTH -> plan.pagesGoalValue * 12
            PagesGoalType.NONE -> 0
        }

        // User's real pages/hour speed across timed sessions -> converts daily target/avg into ~min/day.
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

        // Pre-pass: gather raw monthly activity so early reading of a book planned for a later month
        // in the same year credits both completion and pages to that planned month without double-counting.
        val rawPeriods = (1..12).associateWith { m ->
            PeriodReading.from(activeBooks, sessions, dates, monthRange(plan.year, m))
        }
        val rawCompletedByMonth = (1..12).associateWith { m ->
            PeriodCompletions.completedBookIds(activeBooks, sessions, dates, monthRange(plan.year, m))
        }
        val rawSessionPagesByMonth = (1..12).associateWith { m ->
            rawPeriods.getValue(m).sessions
                .groupBy { it.bookId }
                .mapValues { (bookId, list) ->
                    val b = booksById[bookId]
                    if (b != null && b.pagesKnown) list.sumOf { it.pages.coerceAtLeast(0) } else 0
                }
        }
        val manualEntriesByBook = shelfEntries
            .mapNotNull { e ->
                val bId = effectiveEntryBookIds[e.id].orEmpty()
                if (bId.isBlank()) null else bId to e
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.month } }

        fun futurePlannedMonthFor(bookId: String, month: Int): Int? {
            if (bookId.isBlank() || bookId in plan.excludedYearBooks) return null
            val planned = manualEntriesByBook[bookId] ?: return null
            if (planned.any { it.month == month }) return null
            return planned.firstOrNull { it.month > month }?.month
        }

        val redirectedOutOfMonth = (1..12).associateWith { HashSet<String>() }
        val advancedPagesByTarget = (1..12).associateWith { HashMap<String, Int>() }
        val advancedCompletedByTarget = (1..12).associateWith { HashSet<String>() }
        val advancedReadByTarget = (1..12).associateWith { HashSet<String>() }
        val advancedFromMonthByTarget = (1..12).associateWith { HashMap<String, Int>() }

        for (m in 1..12) {
            val isCurrentM = plan.year == todayYear && m == todayMonth
            val periodM = rawPeriods.getValue(m)
            val completedM = rawCompletedByMonth.getValue(m)
            val pagesM = rawSessionPagesByMonth.getValue(m)
            val activeIds = LinkedHashSet<String>()
            activeIds.addAll(completedM)
            activeIds.addAll(periodM.bookIds)
            if (isCurrentM && currentBook != null) activeIds.add(currentBook.id)
            for (bookId in activeIds) {
                val target = futurePlannedMonthFor(bookId, m) ?: continue
                redirectedOutOfMonth.getValue(m).add(bookId)
                val p = pagesM[bookId] ?: 0
                if (p > 0) {
                    val map = advancedPagesByTarget.getValue(target)
                    map[bookId] = (map[bookId] ?: 0) + p
                }
                if (bookId in completedM) {
                    advancedCompletedByTarget.getValue(target).add(bookId)
                }
                if (bookId in periodM.bookIds || bookId in completedM || (isCurrentM && currentBook?.id == bookId)) {
                    advancedReadByTarget.getValue(target).add(bookId)
                    advancedFromMonthByTarget.getValue(target)[bookId] = m
                }
            }
        }

        val monthSummaries = (1..12).map { m ->
            val days = daysInMonth(plan.year, m)
            val isPast = plan.year < todayYear || (plan.year == todayYear && m < todayMonth)
            val isCurrent = plan.year == todayYear && m == todayMonth
            val remainingDays = when {
                isPast -> 0
                isCurrent -> (days - todayDay + 1).coerceIn(1, days)
                else -> days
            }
            val monthlyPagesGoal = when (plan.pagesGoalType) {
                PagesGoalType.PER_DAY -> plan.pagesGoalValue * days
                PagesGoalType.PER_MONTH -> plan.pagesGoalValue
                PagesGoalType.NONE -> 0
            }
            val dailyPagesGoal = when (plan.pagesGoalType) {
                PagesGoalType.PER_DAY -> plan.pagesGoalValue.toDouble()
                PagesGoalType.PER_MONTH -> plan.pagesGoalValue.toDouble() / days.coerceAtLeast(1)
                PagesGoalType.NONE -> 0.0
            }

            fun isExcluded(bookId: String): Boolean =
                bookId.isNotBlank() && (
                    bookId in plan.excludedYearBooks ||
                        AnnualReadingPlan.monthExclusionKey(m, bookId) in plan.excludedMonthBooks
                    )

            val period = rawPeriods.getValue(m)
            val completedAutoIds = rawCompletedByMonth.getValue(m)
            val sessionPagesByBook = rawSessionPagesByMonth.getValue(m)
            val advPages = advancedPagesByTarget.getValue(m)
            val advCompleted = advancedCompletedByTarget.getValue(m)
            val advRead = advancedReadByTarget.getValue(m)
            val advFrom = advancedFromMonthByTarget.getValue(m)

            val manualEntries = (shelfEntriesByMonth[m] ?: emptyList())
                .sortedWith(compareBy<PlanEntry> { it.position }.thenBy { it.id })
            val manualResolved = manualEntries.map { entry ->
                val bId = effectiveEntryBookIds[entry.id] ?: entry.bookId
                val resolvedEntry = if (bId.isNotBlank() && bId != entry.bookId) entry.copy(bookId = bId) else entry
                val sPages = if (bId.isNotBlank()) (sessionPagesByBook[bId] ?: 0) + (advPages[bId] ?: 0) else 0
                val compHere = bId.isNotBlank() && (bId in completedAutoIds || bId in advCompleted)
                val readHere = bId.isNotBlank() && (bId in period.bookIds || bId in advRead)
                resolveItem(
                    entry = resolvedEntry,
                    booksById = booksById,
                    pagesReadInMonth = sPages,
                    completedInMonth = compHere,
                    readInMonth = readHere,
                    advancedFromMonth = if (bId.isNotBlank()) advFrom[bId] else null,
                )
            }
            val manualBookIds = manualResolved.mapNotNull { it.bookId.takeIf(String::isNotBlank) }.toSet()

            // Automatic activity: completed here, had sessions/dates here, or active current book (current month only).
            val autoBookIds = LinkedHashSet<String>()
            autoBookIds.addAll(completedAutoIds)
            autoBookIds.addAll(period.bookIds)
            if (isCurrent && currentBook != null) {
                autoBookIds.add(currentBook.id)
            }
            autoBookIds.removeAll(manualBookIds)
            autoBookIds.removeAll(redirectedOutOfMonth.getValue(m))
            autoBookIds.removeAll { isExcluded(it) }

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

            // Real read barProgress (0.0..1.0): immediately reflects changes to monthly books or pages targets!
            val hasBooksGoal = monthlyBooksGoal > 0
            val hasPagesGoal = monthlyPagesGoal > 0
            val booksDenom = if (hasBooksGoal) maxOf(monthlyBooksGoal, combined.size) else combined.size
            val pagesDenom = if (hasPagesGoal) monthlyPagesGoal else totalMonthPages
            val bookFrac = if (booksDenom > 0) (completedBooks.toDouble() / booksDenom).coerceIn(0.0, 1.0) else null
            val pageFrac = if (pagesDenom > 0) (actualPagesRead.toDouble() / pagesDenom).coerceIn(0.0, 1.0) else null

            val barProgress = when {
                hasBooksGoal && hasPagesGoal -> {
                    val bF = bookFrac ?: 0.0
                    val pF = pageFrac ?: 0.0
                    if (bF >= 1.0 && pF >= 1.0 && pendingCount == 0) 1.0 else ((bF + pF) / 2.0).coerceIn(0.0, 1.0)
                }
                hasPagesGoal -> (pageFrac ?: 0.0)
                hasBooksGoal -> {
                    val bF = bookFrac ?: 0.0
                    val pF = pageFrac
                    when {
                        bF >= 1.0 && pendingCount == 0 -> 1.0
                        pF != null -> ((bF * 0.7) + (pF * 0.3)).coerceIn(0.0, 1.0)
                        else -> bF
                    }
                }
                else -> when {
                    bookFrac != null && pageFrac != null -> {
                        if (completedBooks > 0 && pendingCount == 0) 1.0 else ((bookFrac + pageFrac) / 2.0).coerceIn(0.0, 1.0)
                    }
                    pageFrac != null -> pageFrac
                    bookFrac != null -> bookFrac
                    else -> 0.0
                }
            }

            val hasAnyGoal = hasBooksGoal || hasPagesGoal
            val health = when {
                combined.isEmpty() -> {
                    if (isPast && hasAnyGoal) MonthHealth.MISSED else MonthHealth.EMPTY
                }
                isPast -> {
                    val booksOk = !hasBooksGoal || completedBooks >= monthlyBooksGoal
                    val pagesOk = !hasPagesGoal || actualPagesRead >= (monthlyPagesGoal * 0.90)
                    val noGoalOk = hasAnyGoal || (pendingCount == 0 && (completedBooks > 0 || actualPagesRead > 0))
                    if (booksOk && pagesOk && noGoalOk && !hasManualPending) MonthHealth.COMPLETED else MonthHealth.MISSED
                }
                else -> {
                    val booksHit = !hasBooksGoal || completedBooks >= monthlyBooksGoal
                    val pagesHit = !hasPagesGoal || actualPagesRead >= monthlyPagesGoal
                    val allDone = pendingCount == 0 && (completedBooks > 0 || actualPagesRead > 0) && booksHit && pagesHit
                    val intensePages = hasPagesGoal && (
                        totalMonthPages > monthlyPagesGoal * 1.05 ||
                            (isCurrent && dailyPagesGoal > 0.0 && pace > dailyPagesGoal * 1.05)
                        )
                    val intenseBooks = hasBooksGoal && combined.size > monthlyBooksGoal
                    when {
                        allDone -> MonthHealth.COMPLETED
                        intensePages || intenseBooks -> MonthHealth.INTENSE // Amber/Gold, NEVER Red!
                        else -> MonthHealth.ON_PACE
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
                monthlyBooksTarget = monthlyBooksGoal,
                monthlyPagesTarget = monthlyPagesGoal,
                plannedBookCount = combined.size,
                completedBookCount = completedBooks,
                pacePagesPerDay = pace,
                barProgress = barProgress,
                health = health,
                pendingCount = pendingCount,
                hasManualPending = hasManualPending,
            )
        }

        // Single source of truth for year totals (100% identical to the months!)
        val completedCreditsMap = LinkedHashMap<String, YearBookCredit>()
        monthSummaries.forEach { ms ->
            ms.items.forEach { item ->
                if (item.status == PlanItemStatus.COMPLETED) {
                    val key = if (item.isReread && item.entry != null) "reread:${item.entry.id}" else item.bookId.ifBlank { item.entry?.id ?: item.title }
                    if (key !in completedCreditsMap) {
                        completedCreditsMap[key] = YearBookCredit(
                            bookId = item.bookId,
                            title = item.title,
                            author = item.author,
                            cover = item.localCover,
                            month = ms.month,
                            entryId = item.entry?.id,
                            isReread = item.isReread,
                        )
                    }
                }
            }
        }
        val completedBooksInYear = completedCreditsMap.values.toList()
        val allResolved = monthSummaries.flatMap { it.items }
        val allUniqueBookKeys = allResolved.map { item ->
            if (item.isReread && item.entry != null) "reread:${item.entry.id}" else item.bookId.ifBlank { item.entry?.id ?: item.title }
        }.toSet()

        val totalPagesReadInYear = monthSummaries.sumOf { it.actualPagesRead }
        val elapsedDaysInYear = when {
            plan.year < todayYear -> yearDays
            plan.year > todayYear -> 0
            else -> ((1 until todayMonth).sumOf { daysInMonth(plan.year, it) } + todayDay).coerceIn(1, yearDays)
        }
        val avgDailyRead = if (elapsedDaysInYear > 0) totalPagesReadInYear.toDouble() / elapsedDaysInYear else 0.0

        val refDailyForMinutes = when (plan.pagesGoalType) {
            PagesGoalType.PER_DAY -> plan.pagesGoalValue.toDouble()
            PagesGoalType.PER_MONTH -> plan.pagesGoalValue.toDouble() / 30.0
            PagesGoalType.NONE -> avgDailyRead
        }
        val estMin = if (pagesPerHour > 1.0 && refDailyForMinutes > 0.5) {
            ((refDailyForMinutes / pagesPerHour) * 60.0).roundToInt().coerceIn(1, 600)
        } else null

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
            year = plan.year,
            completedCount = completedBooksInYear.size,
            completedBooksInYear = completedBooksInYear,
            totalBooksInYear = allUniqueBookKeys.size,
            totalPagesReadInYear = totalPagesReadInYear,
            annualBookGoal = annualBookGoal,
            annualPageGoal = annualPageGoal,
            averageDailyPagesRead = avgDailyRead,
            estimatedMinutesPerDay = estMin,
            months = monthSummaries,
            sagas = sagas,
        )
    }

    fun formatDecimal(value: Double): String =
        String.format(Locale("pt", "BR"), "%.1f", value)
}
