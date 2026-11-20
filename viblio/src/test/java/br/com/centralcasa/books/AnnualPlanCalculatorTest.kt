package br.com.centralcasa.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnualPlanCalculatorTest {
    private fun book(
        id: String,
        title: String,
        pages: Int,
        currentPage: Int = 0,
        saga: String = "",
        seq: Int? = null,
        updatedAt: Long = 1000L,
    ) = LibraryBook(
        id = id,
        title = title,
        author = "Autor Teste",
        genre = "Fantasia",
        totalPages = pages,
        currentPage = currentPage,
        cover = "",
        background = "black",
        updatedAt = updatedAt,
        sequenceNumber = seq,
        saga = saga,
    )

    @Test
    fun calculatesLeapYearsHealthSemanticsAndRealBarProgress() {
        assertEquals(29, AnnualPlanCalculator.daysInMonth(2028, 2))
        assertEquals(28, AnnualPlanCalculator.daysInMonth(2027, 2))

        val bDone = book("b1", "O Último Desejo", 900, currentPage = 900, saga = "The Witcher", seq = 1)
        val bPendingPast = book("b2", "Espada do Destino", 380, currentPage = 50, saga = "The Witcher", seq = 2)
        val bCurrent = book("b3", "A Torre da Andorinha", 456, currentPage = 156, saga = "The Witcher", seq = 6)
        val bHeavy1 = book("b4", "A Senhora do Lago", 600, currentPage = 0, saga = "The Witcher", seq = 7)
        val bHeavy2 = book("b5", "O Poço da Ascensão", 650, currentPage = 0, saga = "Mistborn", seq = 2)

        val plan = AnnualReadingPlan(
            year = 2027,
            goalMode = PlanGoalMode.PAGES_PER_DAY,
            dailyPagesTarget = 30,
            monthlyBooksTarget = 2,
            entries = listOf(
                PlanEntry(month = 1, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b1"),
                PlanEntry(month = 2, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b2"),
                PlanEntry(month = 6, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b3"),
                PlanEntry(month = 7, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b4"),
                PlanEntry(month = 7, position = 1, kind = PlanEntryKind.SHELF_BOOK, bookId = "b5"),
            ),
        ).validated()

        val janSession = ReadingSession("s1", "b1", 1000L, 72_001_000L, 72_000_000L, 0, 900, "2027-01-15")
        val febSession = ReadingSession("s2", "b2", 2000L, 3_602_000L, 3_600_000L, 0, 50, "2027-02-10")

        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = listOf(bDone, bPendingPast, bCurrent, bHeavy1, bHeavy2),
            sessions = listOf(janSession, febSession),
            currentBookId = "b3",
            todayYear = 2027,
            todayMonth = 6,
            todayDay = 21,
        )

        assertEquals(MonthHealth.COMPLETED, summary.months[0].health) // Jan
        assertTrue(summary.months[0].barProgress >= 0.95)
        assertEquals(MonthHealth.MISSED, summary.months[1].health)    // Feb (past & unfinished -> RED)
        assertTrue(summary.months[1].barProgress in 0.01..0.50)       // Real partial read progress in Feb!
        // Jun has ONLY bCurrent (300 rem / 10 days = 30.0 p/day -> ON_PACE); paused bPendingPast MUST NOT invade Jun!
        assertEquals(1, summary.months[5].items.size)
        assertEquals("b3", summary.months[5].items.first().bookId)
        assertEquals(MonthHealth.ON_PACE, summary.months[5].health)
        assertEquals(30.0, summary.months[5].pacePagesPerDay, 0.01)
        assertEquals(MonthHealth.INTENSE, summary.months[6].health)   // Jul (heavy future -> AMBER, never RED!)
        assertEquals(0.0, summary.months[6].barProgress, 0.001)       // Nothing read in Jul yet -> bar is 0%
        assertEquals(MonthHealth.EMPTY, summary.months[7].health)     // Aug

        val witcher = summary.sagas.firstOrNull { it.name == "The Witcher" }
        assertNotNull(witcher)
        assertEquals(1, witcher!!.completedCount)
        assertEquals(4, witcher.totalCount)

        assertNotNull(summary.estimatedMinutesPerDay)
        assertTrue(summary.estimatedMinutesPerDay!! > 0)
    }

    @Test
    fun ignoresUndatedCatalogedBooksAndPausedOlderBooks() {
        // Cataloged old read book: complete == true, updatedAt == TODAY, NO sessions, NO ReadingDates -> MUST NOT appear!
        val bOldCataloged = book("old_no_date", "O Nome do Vento", 656, currentPage = 656, updatedAt = System.currentTimeMillis())
        // Paused older book: currentPage > 0, NOT currentBookId, NO session in Nov -> MUST NOT appear in Nov!
        val bPausedOld = book("paused_old", "Guerra dos Tronos", 592, currentPage = 90)
        // Active current book -> MUST appear automatically as READING in current month (Nov)!
        val bActiveCurrent = book("active_now", "Mistborn", 500, currentPage = 120)
        // Book completed via ReadingDate in Nov -> MUST appear as COMPLETED in Nov!
        val bManualDate = book("dated_nov", "O Hobbit", 250, currentPage = 250)
        // Book read in Oct (150p), completed via session in Nov (150p -> 300p)
        val bMultiMonth = book("multi", "Duna", 300, currentPage = 300)

        val sessions = listOf(
            ReadingSession("s1", "multi", 1000L, 2000L, 3_600_000L, 0, 150, "2026-10-20"),
            ReadingSession("s2", "multi", 3000L, 4000L, 3_600_000L, 150, 300, "2026-11-05"),
        )
        val dates = listOf(
            ReadingDate("d1", "dated_nov", "2026-11-12"),
        )
        val plan = AnnualReadingPlan(
            year = 2026,
            goalMode = PlanGoalMode.NONE,
            entries = listOf(
                PlanEntry(month = 11, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "multi"),
                PlanEntry(month = 11, position = 1, kind = PlanEntryKind.JOKER, reservedPages = 200),
            ),
        ).validated()

        val allBooks = listOf(bOldCataloged, bPausedOld, bActiveCurrent, bManualDate, bMultiMonth)
        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = allBooks,
            sessions = sessions,
            dates = dates,
            currentBookId = "active_now",
            todayYear = 2026,
            todayMonth = 11,
            todayDay = 20,
        )

        // Undated cataloged book MUST NOT appear in ANY month!
        assertTrue(summary.months.all { m -> m.items.none { it.bookId == "old_no_date" } })
        // Paused old book MUST NOT appear in Nov (nor any month without sessions/dates)!
        assertTrue(summary.months.all { m -> m.items.none { it.bookId == "paused_old" } })

        // October: ONLY multi as READ_IN_MONTH (150p)
        val oct = summary.months[9]
        assertEquals(1, oct.items.size)
        assertEquals("multi", oct.items[0].bookId)
        assertEquals(PlanItemStatus.READ_IN_MONTH, oct.items[0].status)
        assertEquals(150, oct.items[0].pagesReadInMonth)

        // November: multi (once, manual+real), dated_nov (auto completed), active_now (auto current book) -> 3 items!
        val nov = summary.months[10]
        assertEquals(3, nov.items.size)
        assertEquals(1, nov.items.count { it.bookId == "multi" })
        assertEquals(PlanItemStatus.COMPLETED, nov.items.first { it.bookId == "multi" }.status)
        assertEquals(PlanItemStatus.COMPLETED, nov.items.first { it.bookId == "dated_nov" }.status)
        assertEquals(PlanItemStatus.READING, nov.items.first { it.bookId == "active_now" }.status)
        assertEquals(2, nov.actualBooksCompleted)
        assertEquals(400, nov.actualPagesRead)

        // PeriodCompletions for full year 2026 MUST count multi + dated_nov, and MUST NOT count old_no_date!
        val yearRange = ReadingPeriods.range("2026-01-01", ReadingPeriodUnit.YEAR)
        val yearCompleted = PeriodCompletions.completedBookIds(allBooks, sessions, dates, yearRange)
        assertEquals(setOf("multi", "dated_nov"), yearCompleted)
    }

    @Test
    fun integratesExistingReadingGoalsAndSupportsRereadsOfCompletedBooks() {
        val bOldDone = book("old", "Fundação", 280, currentPage = 280, updatedAt = 1_700_000_000_000L)
        val booksGoal = ReadingGoal(
            name = "Meta 2027",
            metric = GoalMetric.BOOKS,
            target = 24,
            period = GoalPeriod.YEAR,
            kind = "book",
            createdAt = 1000L,
            baseline = emptyMap(),
            excluded = emptySet(),
        )
        val plan = AnnualReadingPlan(
            year = 2027,
            goalMode = PlanGoalMode.NONE,
            entries = listOf(
                PlanEntry(month = 6, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "old", reread = true, completed = false, tag = "Releitura"),
                PlanEntry(month = 3, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "old", reread = false, completed = true),
            ),
        ).validated()

        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = listOf(bOldDone),
            sessions = emptyList(),
            dates = emptyList(),
            goals = listOf(booksGoal),
            todayYear = 2027,
            todayMonth = 5,
            todayDay = 1,
        )

        assertTrue(summary.effectiveTargets.derivedFromReadingGoals)
        assertEquals(PlanGoalMode.BOOKS_PER_MONTH, summary.effectiveTargets.mode)
        assertEquals(2, summary.effectiveTargets.monthlyBooksTarget)
        assertEquals(24, summary.annualBookGoal)

        val mar = summary.months[2]
        assertEquals(PlanItemStatus.COMPLETED, mar.items.first().status)
        assertEquals(1, mar.actualBooksCompleted)

        val jun = summary.months[5]
        assertEquals(PlanItemStatus.PLANNED, jun.items.first().status)
        assertTrue(jun.items.first().isReread)
        assertEquals(280, jun.items.first().remainingPages)
        assertFalse(jun.items.first().isAutoActivity)
    }
}
