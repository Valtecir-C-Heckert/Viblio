package br.com.centralcasa.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingHeatmapTest {
    private fun book(
        id: String,
        title: String,
        pages: Int,
        currentPage: Int = 0,
        genre: String = "Fantasia",
        rating: Double? = null,
        updatedAt: Long = 1000L,
    ) = LibraryBook(
        id = id,
        title = title,
        author = "Autor Teste",
        genre = genre,
        totalPages = pages,
        currentPage = currentPage,
        cover = "",
        background = "black",
        updatedAt = updatedAt,
        rating = rating,
    )

    @Test
    fun calculatesYearlyHeatmapStreaksAndLevels() {
        val b1 = book("b1", "Duna", 400, currentPage = 400, genre = "Ficção Científica", rating = 5.0)
        val b2 = book("b2", "O Hobbit", 300, currentPage = 300, genre = "Fantasia", rating = 4.5)
        // Old cataloged book with today's updatedAt but no sessions/dates -> MUST NOT affect heatmap or retrospective!
        val bUndated = book("b3", "Antigo Sem Data", 500, currentPage = 500, updatedAt = System.currentTimeMillis())

        val sessions = listOf(
            ReadingSession("s1", "b1", 1000L, 2000L, 1_800_000L, 0, 15, "2026-11-10"),   // lvl 1
            ReadingSession("s2", "b1", 3000L, 4000L, 3_600_000L, 15, 55, "2026-11-11"),  // 40p -> lvl 2
            ReadingSession("s3", "b1", 5000L, 6000L, 7_200_000L, 55, 130, "2026-11-12"), // 75p -> lvl 3
            ReadingSession("s4", "b1", 7000L, 8000L, 7_200_000L, 130, 400, "2026-11-19"),// 270p -> lvl 3 (yesterday)
        )
        val dates = listOf(
            ReadingDate("d1", "b2", "2026-11-18"), // manual date -> lvl 1 (2 days ago, consecutive with Nov 19!)
        )
        val books = listOf(b1, b2, bUndated)

        val heatmap = ReadingHeatmap.forYear(2026, books, sessions, dates, todayDay = "2026-11-20")
        assertEquals(5, heatmap.totalActiveDays)
        assertEquals(3, heatmap.bestStreak)    // Nov 10, 11, 12
        assertEquals(2, heatmap.currentStreak) // Nov 18, 19 (grace window on Nov 20!)
        assertEquals(1, heatmap.daysByDate["2026-11-10"]?.level)
        assertEquals(2, heatmap.daysByDate["2026-11-11"]?.level)
        assertEquals(3, heatmap.daysByDate["2026-11-12"]?.level)
        assertEquals(1, heatmap.daysByDate["2026-11-18"]?.level)
        assertTrue(heatmap.weeks.size in 52..54)

        // Streak drops to 0 if 2+ days passed without reading
        val broken = ReadingHeatmap.forYear(2026, books, sessions, dates, todayDay = "2026-11-21")
        assertEquals(0, broken.currentStreak)

        // hasReadToday checks sessions & dates accurately
        assertFalse(ReadingHeatmap.hasReadToday(books, sessions, dates, todayDay = "2026-11-20"))
        assertTrue(ReadingHeatmap.hasReadToday(books, sessions, dates, todayDay = "2026-11-19"))
        assertTrue(ReadingHeatmap.hasReadToday(books, sessions, dates, todayDay = "2026-11-18"))

        // RetrospectiveBuilder counts ONLY dated/session completions (ignores bUndated!)
        val yearRange = ReadingPeriods.range("2026-01-01", ReadingPeriodUnit.YEAR)
        val retro = RetrospectiveBuilder.build(books, sessions, dates, yearRange, "Meu ano em livros", "2026")
        assertEquals(2, retro.completedCount)
        assertEquals(700L, retro.totalPagesRead) // 400 (b1 sessions) + 300 (b2 manual completion)
        assertEquals(5, retro.activeDays)
        assertEquals(3, retro.bestStreakInPeriod)
        assertNotNull(retro.topRatedBook)
        assertEquals("Duna", retro.topRatedBook!!.title)
        assertTrue(retro.topGenres.contains("Ficção Científica"))
    }
}
