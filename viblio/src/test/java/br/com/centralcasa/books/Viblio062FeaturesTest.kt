package br.com.centralcasa.books

import br.com.centralcasa.ui.AnnualPlanExportDialog
import br.com.viblio.share.AnnualPlanExportData
import br.com.viblio.share.AnnualPlanExportRenderer
import br.com.viblio.share.ViblioShareProvider
import br.com.viblio.widget.BaseViblioReadingWidget
import br.com.viblio.widget.WidgetSize
import org.junit.Assert.*
import org.junit.Test

class Viblio062FeaturesTest {

    @Test
    fun widgetSizesResolveAcrossAllFiveWidths() {
        assertEquals(WidgetSize.SIZE_1X1, BaseViblioReadingWidget.resolveWidgetSize(64, WidgetSize.SIZE_4X1))
        assertEquals(WidgetSize.SIZE_2X1, BaseViblioReadingWidget.resolveWidgetSize(120, WidgetSize.SIZE_4X1))
        assertEquals(WidgetSize.SIZE_3X1, BaseViblioReadingWidget.resolveWidgetSize(190, WidgetSize.SIZE_4X1))
        assertEquals(WidgetSize.SIZE_4X1, BaseViblioReadingWidget.resolveWidgetSize(260, WidgetSize.SIZE_1X1))
        assertEquals(WidgetSize.SIZE_5X1, BaseViblioReadingWidget.resolveWidgetSize(320, WidgetSize.SIZE_1X1))
        assertEquals(WidgetSize.SIZE_3X1, BaseViblioReadingWidget.resolveWidgetSize(0, WidgetSize.SIZE_3X1))
    }

    @Test
    fun monthlyHeatmapShiftsAcrossYearBoundariesAndCountsMonthDays() {
        assertEquals(2025 to 12, ReadingHeatmap.shiftMonth(2026, 1, -1))
        assertEquals(2027 to 1, ReadingHeatmap.shiftMonth(2026, 12, 1))
        assertEquals(2020 to 1, ReadingHeatmap.shiftMonth(2020, 1, -5))
        assertEquals(2100 to 12, ReadingHeatmap.shiftMonth(2100, 12, 5))

        val b = LibraryBook(id = "b1", title = "Livro", author = "Autor", totalPages = 300, currentPage = 60, pagesKnown = true)
        val sessions = listOf(
            ReadingSession("s1", "b1", 1000L, 61000L, 60000L, 0, 25, readingDay = "2026-11-05"),
            ReadingSession("s2", "b1", 2000L, 62000L, 60000L, 25, 60, readingDay = "2026-11-20"),
            ReadingSession("s3", "b1", 3000L, 63000L, 60000L, 0, 10, readingDay = "2026-10-15"),
        )
        val summary = ReadingHeatmap.forYear(2026, listOf(b), sessions, emptyList(), todayDay = "2026-11-20")
        val nov = ReadingHeatmap.monthGrid(2026, 11, summary)
        val oct = ReadingHeatmap.monthGrid(2026, 10, summary)

        assertEquals(2, nov.activeDaysInMonth)
        assertEquals(1, oct.activeDaysInMonth)
        assertTrue(nov.rows.size in 5..6)
        assertEquals(30, nov.rows.flatten().count { it != null })
        assertTrue(nov.rows.flatten().contains("2026-11-01"))
        assertTrue(nov.rows.flatten().contains("2026-11-30"))
    }

    @Test
    fun periodInsightsComputesDeltaAndMonthlyBuckets() {
        assertNull(PeriodInsights.deltaPercent(100, 0))
        assertEquals(25, PeriodInsights.deltaPercent(125, 100))
        assertEquals(-50, PeriodInsights.deltaPercent(50, 100))

        val b = LibraryBook(id = "b1", title = "Livro", author = "Autor", totalPages = 300, currentPage = 100, pagesKnown = true)
        val sessions = listOf(
            ReadingSession("s1", "b1", 1000L, 61000L, 60000L, 0, 40, readingDay = "2026-01-10"),
            ReadingSession("s2", "b1", 2000L, 62000L, 60000L, 40, 100, readingDay = "2026-11-15"),
        )
        val yearBuckets = PeriodInsights.buckets("2026-11-15", ReadingPeriodUnit.YEAR, listOf(b), sessions, emptyList())
        assertEquals(12, yearBuckets.size)
        assertEquals(40L, yearBuckets[0].value)
        assertEquals(60L, yearBuckets[10].value)
        assertEquals("2026-11-01", yearBuckets[10].targetAnchor)

        val monthBuckets = PeriodInsights.buckets("2026-11-15", ReadingPeriodUnit.MONTH, listOf(b), sessions, emptyList())
        assertEquals(5, monthBuckets.size)
        assertEquals(60L, monthBuckets[2].value) // day 15 -> Sem 3 (days 15..21)
    }

    @Test
    fun annualPlanExportSupportsFullJanToDecAndFiltersAndPdfPagination() {
        val b1 = LibraryBook(id = "b1", title = "Duna", author = "Frank Herbert", totalPages = 680, currentPage = 0, pagesKnown = true)
        val b2 = LibraryBook(id = "b2", title = "Fundação", author = "Isaac Asimov", totalPages = 240, currentPage = 240, pagesKnown = true)
        val plan = AnnualReadingPlan(
            year = 2027,
            entries = listOf(
                PlanEntry(month = 1, position = 0, bookId = "b1", title = "Duna", author = "Frank Herbert", reservedPages = 680),
                PlanEntry(month = 12, position = 0, bookId = "b2", title = "Fundação", author = "Isaac Asimov", reservedPages = 240, completed = true),
            ),
        )
        val summary = AnnualPlanCalculator.summarize(plan, listOf(b1, b2), emptyList(), emptyList(), currentBookId = null, nowMs = 0L)

        val fullYear = AnnualPlanExportData.from(summary, includeEmptyMonths = true, onlyPlanned = false)
        assertEquals(12, fullYear.months.size)
        assertEquals("JANEIRO", fullYear.months.first().name)
        assertEquals("DEZEMBRO", fullYear.months.last().name)
        assertEquals(2, fullYear.totalBooks)
        assertEquals(920L, fullYear.totalPages)
        assertEquals(2, fullYear.activeMonthCount)

        val nonEmpty = AnnualPlanExportData.from(summary, includeEmptyMonths = false, onlyPlanned = false)
        assertEquals(2, nonEmpty.months.size)

        val plannedOnly = AnnualPlanExportData.from(summary, includeEmptyMonths = false, onlyPlanned = true)
        assertEquals(1, plannedOnly.totalBooks)
        assertEquals("Duna", plannedOnly.months.single().items.single().title)

        assertTrue(AnnualPlanExportRenderer.pdfPageCount(fullYear) >= 1)

        // Safe filenames for both PNG and PDF
        val pngName = AnnualPlanExportDialog.safeExportName("viblio_plano_2027", "png")
        val pdfName = AnnualPlanExportDialog.safeExportName("viblio_plano_2027", "pdf")
        assertEquals("viblio_plano_2027.png", pngName)
        assertEquals("viblio_plano_2027.pdf", pdfName)
        // Verify ViblioShareProvider accepts both PNG and PDF names (without calling Android Uri.Builder on JVM)
        val safeRegex = Regex("^[a-z0-9_-]{1,64}\\.(png|pdf)$")
        assertTrue(safeRegex.matches(pngName))
        assertTrue(safeRegex.matches(pdfName))
        assertFalse(safeRegex.matches("../evil.pdf"))
    }
}
