package br.com.centralcasa.books

import org.junit.Assert.assertEquals
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
    ) = LibraryBook(
        id = id,
        title = title,
        author = "Autor Teste",
        genre = "Fantasia",
        totalPages = pages,
        currentPage = currentPage,
        cover = "",
        background = "black",
        updatedAt = 1000L,
        sequenceNumber = seq,
        saga = saga,
    )

    @Test
    fun calculatesLeapYearsAndHealthSemanticsCorrectly() {
        assertEquals(29, AnnualPlanCalculator.daysInMonth(2028, 2))
        assertEquals(28, AnnualPlanCalculator.daysInMonth(2027, 2))

        val bDone = book("b1", "O Último Desejo", 320, currentPage = 320, saga = "The Witcher", seq = 1)
        val bPendingPast = book("b2", "Espada do Destino", 380, currentPage = 50, saga = "The Witcher", seq = 2)
        val bCurrent = book("b3", "A Torre da Andorinha", 456, currentPage = 156, saga = "The Witcher", seq = 6)
        val bHeavy1 = book("b4", "A Senhora do Lago", 600, currentPage = 0, saga = "The Witcher", seq = 7)
        val bHeavy2 = book("b5", "O Poço da Ascensão", 650, currentPage = 0, saga = "Mistborn", seq = 2)

        val plan = AnnualReadingPlan(
            year = 2027,
            goalMode = PlanGoalMode.PAGES_PER_DAY,
            dailyPagesTarget = 30,
            monthlyBooksTarget = 2,
            showPurchaseGoal = true,
            entries = listOf(
                // Past month 1 (Jan): 1 book completed -> COMPLETED (Green)
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

        // Timed session: 45 pages in 1 hour -> 45 pages/hour
        val session = ReadingSession("s1", "b1", 0, 45, 3_600_000L, 1000L, 3_601_000L, "2027-01-10")
        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = listOf(bDone, bPendingPast, bCurrent, bHeavy1, bHeavy2),
            sessions = listOf(session),
            todayYear = 2027,
            todayMonth = 6,
            todayDay = 21,
        )

        assertEquals(MonthHealth.COMPLETED, summary.months[0].health) // Jan
        assertEquals(MonthHealth.MISSED, summary.months[1].health)    // Feb (past & unfinished -> RED)
        assertEquals(MonthHealth.ON_PACE, summary.months[5].health)   // Jun (300 rem / 10 days = 30.0 -> GREEN)
        assertEquals(30.0, summary.months[5].pacePagesPerDay, 0.01)
        assertEquals(MonthHealth.INTENSE, summary.months[6].health)   // Jul (heavy future -> AMBER, never RED!)
        assertEquals(MonthHealth.EMPTY, summary.months[7].health)     // Aug

        // Saga tracking: The Witcher has 4 books in shelf/plan, 1 completed
        val witcher = summary.sagas.firstOrNull { it.name == "The Witcher" }
        assertNotNull(witcher)
        assertEquals(1, witcher!!.completedCount)
        assertEquals(4, witcher.totalCount)

        // Estimated minutes/day derived from real 45 pages/h speed
        assertNotNull(summary.estimatedMinutesPerDay)
        assertTrue(summary.estimatedMinutesPerDay!! > 0)
    }

    @Test
    fun supportsNoGoalPureListAndBooksPerMonthMode() {
        val b1 = book("b1", "Duna", 680, currentPage = 0)
        val plan = AnnualReadingPlan(
            year = 2027,
            goalMode = PlanGoalMode.NONE,
            showPurchaseGoal = false,
            entries = listOf(
                PlanEntry(month = 6, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b1"),
                PlanEntry(month = 6, position = 1, kind = PlanEntryKind.JOKER, reservedPages = 200),
            ),
        ).validated()

        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = listOf(b1),
            sessions = emptyList(),
            todayYear = 2027,
            todayMonth = 6,
            todayDay = 1,
        )
        assertEquals(MonthHealth.ON_PACE, summary.months[5].health)
        assertEquals(MonthHealth.EMPTY, summary.months[0].health) // Past empty month in NONE mode is NOT red!
        assertEquals(1, summary.months[5].jokerCount)
        assertEquals(880, summary.months[5].totalPlannedPages)
    }
}
