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
                // Past month 1 (Jan): 1 book completed with 900 pages -> meets 30p/day * 31 * 0.85 -> COMPLETED (Green)
                PlanEntry(month = 1, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b1"),
                // Past month 2 (Feb): 1 unfinished book -> MISSED (Red!)
                PlanEntry(month = 2, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b2"),
                // Current month 6 (Jun, day 21 -> 10 remaining days): 300 remaining pages -> 30.0 p/day -> ON_PACE (Green)
                PlanEntry(month = 6, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b3"),
                // Future month 7 (Jul, 31 days): 1250 pages -> 40.3 p/day > 30 -> INTENSE (Amber/Gold, NOT Red!)
                PlanEntry(month = 7, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b4"),
                PlanEntry(month = 7, position = 1, kind = PlanEntryKind.SHELF_BOOK, bookId = "b5"),
            ),
        ).validated()

        // Timed session in Jan: 900 pages -> 45 pages/hour (20h)
        val janSession = ReadingSession(
            id = "s1",
            bookId = "b1",
            startedAt = 1000L,
            finishedAt = 72_001_000L,
            durationMs = 72_000_000L,
            startPage = 0,
            endPage = 900,
            readingDay = "2027-01-15",
        )
        // Partial session in Feb: 50 pages read -> barProgress should reflect real pages read (not 0 and not 100%)
        val febSession = ReadingSession(
            id = "s2",
            bookId = "b2",
            startedAt = 2000L,
            finishedAt = 3_602_000L,
            durationMs = 3_600_000L,
            startPage = 0,
            endPage = 50,
            readingDay = "2027-02-10",
        )
        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = listOf(bDone, bPendingPast, bCurrent, bHeavy1, bHeavy2),
            sessions = listOf(janSession, febSession),
            todayYear = 2027,
            todayMonth = 6,
            todayDay = 21,
        )

        assertEquals(MonthHealth.COMPLETED, summary.months[0].health) // Jan
        assertTrue(summary.months[0].barProgress >= 0.95)
        assertEquals(MonthHealth.MISSED, summary.months[1].health)    // Feb (past & unfinished -> RED)
        assertTrue(summary.months[1].barProgress in 0.01..0.50)       // Real partial read progress in Feb!
        // Jun has bCurrent (300 rem) + auto-included bPendingPast (330 rem, currently reading!)
        assertTrue(summary.months[5].items.any { it.bookId == "b3" && it.status == PlanItemStatus.READING })
        assertTrue(summary.months[5].items.any { it.bookId == "b2" && it.isAutoActivity && it.status == PlanItemStatus.READING })
        assertEquals(MonthHealth.INTENSE, summary.months[6].health)   // Jul (heavy future -> AMBER, never RED!)
        assertEquals(0.0, summary.months[6].barProgress, 0.001)       // Nothing read in Jul yet -> bar is 0%
        assertEquals(MonthHealth.EMPTY, summary.months[7].health)     // Aug

        // Saga tracking: The Witcher has 4 books in shelf/plan, 1 completed
        val witcher = summary.sagas.firstOrNull { it.name == "The Witcher" }
        assertNotNull(witcher)
        assertEquals(1, witcher!!.completedCount)
        assertEquals(4, witcher.totalCount)

        assertNotNull(summary.estimatedMinutesPerDay)
        assertTrue(summary.estimatedMinutesPerDay!! > 0)
    }

    @Test
    fun autoPopulatesCompletedReadAndCurrentlyReadingBooksWithoutDuplication() {
        // Book A: read in Oct (150p), completed in Nov (150p -> 300p)
        val bMultiMonth = book("a", "Duna", 300, currentPage = 300)
        // Book B: completed manually with ReadingDate in Nov (no sessions)
        val bManualDate = book("b", "O Hobbit", 250, currentPage = 250)
        // Book C: currently being read (no session in Nov, still appears automatically in current month Nov!)
        val bReadingNow = book("c", "Mistborn", 500, currentPage = 120)

        val sessions = listOf(
            ReadingSession("s1", "a", 1000L, 2000L, 3_600_000L, 0, 150, "2026-10-20"),
            ReadingSession("s2", "a", 3000L, 4000L, 3_600_000L, 150, 300, "2026-11-05"),
        )
        val dates = listOf(
            ReadingDate("d1", "b", "2026-11-12"),
        )
        // Plan also has book A manually added in Nov -> MUST NOT duplicate!
        val plan = AnnualReadingPlan(
            year = 2026,
            goalMode = PlanGoalMode.NONE,
            entries = listOf(
                PlanEntry(month = 11, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "a"),
                // Legacy Joker entry should be ignored safely!
                PlanEntry(month = 11, position = 1, kind = PlanEntryKind.JOKER, reservedPages = 200),
            ),
        ).validated()

        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = listOf(bMultiMonth, bManualDate, bReadingNow),
            sessions = sessions,
            dates = dates,
            todayYear = 2026,
            todayMonth = 11,
            todayDay = 20,
        )

        // October: Book A appears automatically as READ_IN_MONTH with 150 pages
        val oct = summary.months[9]
        assertEquals(1, oct.items.size)
        assertEquals("a", oct.items[0].bookId)
        assertEquals(PlanItemStatus.READ_IN_MONTH, oct.items[0].status)
        assertEquals(150, oct.items[0].pagesReadInMonth)
        assertEquals(0, oct.actualBooksCompleted)

        // November: Book A (once, manual+real), Book B (auto completed), Book C (auto reading) -> 3 items, no Joker!
        val nov = summary.months[10]
        assertEquals(3, nov.items.size)
        assertEquals(1, nov.items.count { it.bookId == "a" })
        assertEquals(PlanItemStatus.COMPLETED, nov.items.first { it.bookId == "a" }.status)
        assertEquals(PlanItemStatus.COMPLETED, nov.items.first { it.bookId == "b" }.status)
        assertEquals(250, nov.items.first { it.bookId == "b" }.pagesReadInMonth)
        assertEquals(PlanItemStatus.READING, nov.items.first { it.bookId == "c" }.status)
        assertEquals(2, nov.actualBooksCompleted)
        assertEquals(400, nov.actualPagesRead) // 150p session on A + 250p completed B

        // PeriodCompletions for full year 2026 counts both A and B (2 books)
        val yearRange = ReadingPeriods.range("2026-01-01", ReadingPeriodUnit.YEAR)
        val yearCompleted = PeriodCompletions.completedBookIds(listOf(bMultiMonth, bManualDate, bReadingNow), sessions, dates, yearRange)
        assertEquals(setOf("a", "b"), yearCompleted)
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
                // Already-read book added as a planned reread in June -> status MUST be PLANNED, not COMPLETED!
                PlanEntry(month = 6, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "old", reread = true, completed = false, tag = "Releitura"),
                // Already-read book added as "Já li neste mês" in March -> status MUST be COMPLETED!
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
        assertEquals(2, summary.effectiveTargets.monthlyBooksTarget) // 24 / 12 = 2 books/month
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
