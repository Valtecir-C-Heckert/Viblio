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
    fun recalculatesImmediatelyWhenBooksPerMonthOrPagesTargetsChange() {
        assertEquals(29, AnnualPlanCalculator.daysInMonth(2028, 2))
        assertEquals(28, AnnualPlanCalculator.daysInMonth(2027, 2))

        val b1 = book("b1", "O Último Desejo", 600, currentPage = 600, saga = "The Witcher", seq = 1)
        val b2 = book("b2", "Espada do Destino", 380, currentPage = 50, saga = "The Witcher", seq = 2)
        val bHeavy1 = book("b4", "A Senhora do Lago", 600, currentPage = 0, saga = "The Witcher", seq = 7)
        val bHeavy2 = book("b5", "O Poço da Ascensão", 650, currentPage = 0, saga = "Mistborn", seq = 2)

        val janSession = ReadingSession("s1", "b1", 1000L, 48_001_000L, 48_000_000L, 0, 600, "2027-01-15")
        val febSession = ReadingSession("s2", "b2", 2000L, 3_602_000L, 3_600_000L, 0, 50, "2027-02-10")

        // 1) Pages goal = 20 pages/day (620/Jan) -> 600 pages read in Jan meets 90% threshold -> COMPLETED!
        val planLowPages = AnnualReadingPlan(
            year = 2027,
            pagesGoalType = PagesGoalType.PER_DAY,
            pagesGoalValue = 20,
            goalConfigured = true,
            entries = listOf(
                PlanEntry(month = 2, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b2"),
                PlanEntry(month = 7, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "b4"),
                PlanEntry(month = 7, position = 1, kind = PlanEntryKind.SHELF_BOOK, bookId = "b5"),
            ),
        ).validated()

        val lowSummary = AnnualPlanCalculator.summarizeAt(
            plan = planLowPages,
            books = listOf(b1, b2, bHeavy1, bHeavy2),
            sessions = listOf(janSession, febSession),
            todayYear = 2027,
            todayMonth = 6,
            todayDay = 21,
        )
        assertEquals(MonthHealth.COMPLETED, lowSummary.months[0].health) // Jan met 20p/day
        assertTrue(lowSummary.months[0].barProgress >= 0.95)
        assertEquals(MonthHealth.MISSED, lowSummary.months[1].health)    // Feb unfinished -> RED
        assertEquals(MonthHealth.INTENSE, lowSummary.months[6].health)   // Jul heavy -> AMBER, never RED!

        // 2) Increase Pages goal to 40 pages/day (1240/Jan) -> Jan IMMEDIATELY drops to ~48% and MISSED!
        val planHighPages = planLowPages.copy(pagesGoalValue = 40)
        val highSummary = AnnualPlanCalculator.summarizeAt(
            plan = planHighPages,
            books = listOf(b1, b2, bHeavy1, bHeavy2),
            sessions = listOf(janSession, febSession),
            todayYear = 2027,
            todayMonth = 6,
            todayDay = 21,
        )
        assertEquals(1240, highSummary.months[0].monthlyPagesTarget)
        assertEquals(MonthHealth.MISSED, highSummary.months[0].health)
        assertTrue(highSummary.months[0].barProgress in 0.45..0.52)

        // 3) Switch to Books per month = 2/month (24/year) -> Jan has 1/2 -> NOT completed, annualBookGoal == 24!
        val planBooksPerMonth = AnnualReadingPlan(
            year = 2027,
            booksGoalType = BooksGoalType.PER_MONTH,
            booksGoalValue = 2,
            pagesGoalType = PagesGoalType.NONE,
            goalConfigured = true,
        ).validated()
        val booksSummary = AnnualPlanCalculator.summarizeAt(
            plan = planBooksPerMonth,
            books = listOf(b1, b2),
            sessions = listOf(janSession),
            todayYear = 2027,
            todayMonth = 6,
            todayDay = 21,
        )
        assertEquals(24, booksSummary.annualBookGoal)
        assertEquals(2, booksSummary.months[0].monthlyBooksTarget)
        assertEquals(1, booksSummary.completedCount)
        assertEquals(MonthHealth.MISSED, booksSummary.months[0].health) // 1 of 2 books in past Jan
    }

    @Test
    fun isolatesYearsAndSupportsRemovingAutomaticBooksFromMonthOrYear() {
        val bOldCataloged = book("old_no_date", "O Nome do Vento", 656, currentPage = 656, updatedAt = System.currentTimeMillis())
        val bPausedOld = book("paused_old", "Guerra dos Tronos", 592, currentPage = 90)
        val bActiveCurrent = book("active_now", "Mistborn", 500, currentPage = 120)
        val bNovDone = book("nov_done", "O Hobbit", 250, currentPage = 250)
        val bMulti = book("multi", "Duna", 300, currentPage = 300)

        val sessions = listOf(
            ReadingSession("s1", "multi", 1000L, 2000L, 3_600_000L, 0, 150, "2026-10-20"),
            ReadingSession("s2", "multi", 3000L, 4000L, 3_600_000L, 150, 300, "2026-11-05"),
        )
        val dates = listOf(ReadingDate("d1", "nov_done", "2026-11-12"))
        val allBooks = listOf(bOldCataloged, bPausedOld, bActiveCurrent, bNovDone, bMulti)

        val plan2026 = AnnualReadingPlan(
            year = 2026,
            booksGoalType = BooksGoalType.PER_YEAR,
            booksGoalValue = 20,
            goalConfigured = true,
        ).validated()

        val sum2026 = AnnualPlanCalculator.summarizeAt(
            plan = plan2026,
            books = allBooks,
            sessions = sessions,
            dates = dates,
            currentBookId = "active_now",
            todayYear = 2026,
            todayMonth = 11,
            todayDay = 20,
        )
        assertEquals(20, sum2026.annualBookGoal)
        assertEquals(2, sum2026.completedCount) // multi + nov_done
        assertEquals(2, sum2026.completedBooksInYear.size)
        assertTrue(sum2026.months.all { m -> m.items.none { it.bookId == "old_no_date" || it.bookId == "paused_old" } })

        // 2027 MUST be completely isolated from 2026 goals and progress!
        val plan2027 = AnnualReadingPlan(year = 2027, goalConfigured = false).validated()
        val sum2027 = AnnualPlanCalculator.summarizeAt(
            plan = plan2027,
            books = allBooks,
            sessions = sessions,
            dates = dates,
            currentBookId = "active_now",
            todayYear = 2026,
            todayMonth = 11,
            todayDay = 20,
        )
        assertEquals(0, sum2027.annualBookGoal)
        assertEquals(0, sum2027.completedCount)
        assertEquals(0, sum2027.totalPagesReadInYear)

        // Removing an automatic book from Nov ("11:nov_done") removes it from Nov AND from 2026 count!
        val planAfterMonthExclude = plan2026.copy(
            excludedMonthBooks = setOf(AnnualReadingPlan.monthExclusionKey(11, "nov_done")),
        )
        val sumAfterMonthExclude = AnnualPlanCalculator.summarizeAt(
            plan = planAfterMonthExclude,
            books = allBooks,
            sessions = sessions,
            dates = dates,
            currentBookId = "active_now",
            todayYear = 2026,
            todayMonth = 11,
            todayDay = 20,
        )
        assertEquals(1, sumAfterMonthExclude.completedCount)
        assertTrue(sumAfterMonthExclude.months[10].items.none { it.bookId == "nov_done" })

        // Removing an automatic book from the entire year ("multi") removes it from both Oct & Nov AND 2026 count!
        val planAfterYearExclude = planAfterMonthExclude.copy(
            excludedYearBooks = setOf("multi"),
        )
        val sumAfterYearExclude = AnnualPlanCalculator.summarizeAt(
            plan = planAfterYearExclude,
            books = allBooks,
            sessions = sessions,
            dates = dates,
            currentBookId = "active_now",
            todayYear = 2026,
            todayMonth = 11,
            todayDay = 20,
        )
        assertEquals(0, sumAfterYearExclude.completedCount)
        assertTrue(sumAfterYearExclude.months.all { m -> m.items.none { it.bookId == "multi" } })
    }

    @Test
    fun supportsRereadsAndManualAlreadyReadEntries() {
        val bOldDone = book("old", "Fundação", 280, currentPage = 280, updatedAt = 1_700_000_000_000L)
        val plan = AnnualReadingPlan(
            year = 2027,
            booksGoalType = BooksGoalType.PER_MONTH,
            booksGoalValue = 2,
            goalConfigured = true,
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
            todayYear = 2027,
            todayMonth = 5,
            todayDay = 1,
        )

        assertEquals(24, summary.annualBookGoal)
        assertEquals(1, summary.completedCount)
        assertEquals(PlanItemStatus.COMPLETED, summary.months[2].items.first().status)

        val junItem = summary.months[5].items.first()
        assertEquals(PlanItemStatus.PLANNED, junItem.status)
        assertTrue(junItem.isReread)
        assertEquals(280, junItem.remainingPages)
        assertFalse(junItem.isAutoActivity)
        assertNotNull(junItem.entry)
    }

    @Test
    fun creditsAdvancedFuturePlannedBookToPlannedMonthWithoutDoubleCounting() {
        val novBook = book("nov", "Livro de Novembro", 200, currentPage = 200)
        val decBook = book("dec", "Livro de Dezembro", 300, currentPage = 300)
        val decPartial = book("dec_part", "Parcial de Dezembro", 400, currentPage = 150)

        val sessions = listOf(
            ReadingSession("s1", "nov", 1000L, 2000L, 3_600_000L, 0, 200, "2026-11-05"),
            ReadingSession("s2", "dec", 3000L, 4000L, 5_400_000L, 0, 300, "2026-11-20"),
            ReadingSession("s3", "dec_part", 5000L, 6000L, 2_700_000L, 0, 150, "2026-11-24"),
        )
        val plan = AnnualReadingPlan(
            year = 2026,
            booksGoalType = BooksGoalType.PER_MONTH,
            booksGoalValue = 1,
            pagesGoalType = PagesGoalType.PER_MONTH,
            pagesGoalValue = 300,
            goalConfigured = true,
            entries = listOf(
                PlanEntry(month = 12, position = 0, kind = PlanEntryKind.SHELF_BOOK, bookId = "dec"),
                PlanEntry(month = 12, position = 1, kind = PlanEntryKind.SHELF_BOOK, bookId = "dec_part"),
            ),
        ).validated()

        val summary = AnnualPlanCalculator.summarizeAt(
            plan = plan,
            books = listOf(novBook, decBook, decPartial),
            sessions = sessions,
            currentBookId = "dec_part",
            todayYear = 2026,
            todayMonth = 11,
            todayDay = 24,
        )

        val nov = summary.months[10]
        val dec = summary.months[11]

        // November keeps only its own book and pages; future-planned books do not duplicate here.
        assertEquals(listOf("nov"), nov.items.map { it.bookId })
        assertEquals(200, nov.actualPagesRead)
        assertEquals(1, nov.completedBookCount)

        // December receives both the completion and the advanced pages from November.
        val decDone = dec.items.first { it.bookId == "dec" }
        val decReading = dec.items.first { it.bookId == "dec_part" }
        assertEquals(PlanItemStatus.COMPLETED, decDone.status)
        assertEquals(300, decDone.pagesReadInMonth)
        assertEquals(0, decDone.remainingPages)
        assertEquals(11, decDone.advancedFromMonth)

        assertEquals(PlanItemStatus.READING, decReading.status)
        assertEquals(150, decReading.pagesReadInMonth)
        assertEquals(250, decReading.remainingPages)
        assertEquals(11, decReading.advancedFromMonth)

        assertEquals(450, dec.actualPagesRead)
        assertEquals(1, dec.completedBookCount)

        // Year totals remain exact with zero double counting!
        assertEquals(2, summary.completedCount)
        assertEquals(650, summary.totalPagesReadInYear)
    }
}
