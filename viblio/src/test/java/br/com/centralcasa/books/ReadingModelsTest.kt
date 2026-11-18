package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class ReadingModelsTest {
    @Test fun progressCorrectionsPreserveActiveReadingBoundaries() {
        val book = LibraryBook(id = "book", title = "Livro", totalPages = 592, currentPage = 400)
        val timer = ReadingDraft("book", 354, 1000, 42000, 5000, 4)
        assertEquals(380, readingProgress(book, 380, timer).currentPage)
        assertEquals(592, readingProgress(book, 592, timer).currentPage)
        assertEquals(42000L, timer.elapsedMs)
        assertEquals(5000L, timer.runningSince)
        assertThrows(IllegalArgumentException::class.java) { readingProgress(book, 353, timer) }
        assertThrows(IllegalArgumentException::class.java) { readingProgress(book, 593, timer) }
        assertEquals(0, readingProgress(book, 0, null).currentPage)
        assertEquals(0, readingProgress(book, 0, timer.copy(bookId = "other")).currentPage)
    }
    @Test fun progressAndInputBoundaries() {
        assertEquals(59, LibraryBook(title = "Deusa em chamas", totalPages = 592, currentPage = 354).validated().percent)
        assertTrue(LibraryBook(title = "Livro", totalPages = 468, currentPage = 468).validated().complete)
        for (book in listOf(LibraryBook(title = "", totalPages = 1), LibraryBook(title = "X", totalPages = 0), LibraryBook(title = "X", totalPages = 10, currentPage = 11))) {
            assertThrows(IllegalArgumentException::class.java) { book.validated() }
        }
    }
    @Test fun timerPauseResumeAndReboot() {
        val running = ReadingDraft("book", 354, 1000, 10000, 5000, 4)
        assertEquals(15000, running.duration(10000, 4))
        val paused = running.checkpoint(10000, 4).copy(runningSince = null)
        assertEquals(15000, paused.duration(20000, 4))
        val resumed = paused.copy(runningSince = 20000)
        assertEquals(18000, resumed.duration(23000, 4))
        // After reboot retain checkpoints, without counting powered-off time or negative uptime.
        assertEquals(10000, running.duration(100, 5))
        assertNull(running.checkpoint(100, 5).runningSince)
        assertEquals(10000, running.duration(100, 4))
    }
    @Test fun weightedPaceUsesTotalsAndHandlesZeroPages() {
        fun session(ms: Long, pages: Int) = ReadingSession(bookId = "book", startedAt = 0, finishedAt = ms, durationMs = ms, startPage = 0, endPage = pages)
        val total = ReadingSummary.from(listOf(session(120000, 2), session(900000, 3), session(60000, 0)))
        assertEquals(1080000, total.durationMs)
        assertEquals(5, total.timedPages)
        assertEquals(216000L, total.millisPerPage)
        assertNull(session(1000, 0).millisPerPage)
        assertNull(ReadingSummary.from(emptyList()).millisPerPage)
        assertEquals("3 min 36 s / página", readingPace(total.millisPerPage))
    }
    @Test fun sessionRejectsBackwardOrOutOfRangePages() {
        val session = ReadingSession(bookId = "book", startedAt = 0, finishedAt = 60000, durationMs = 60000, startPage = 354, endPage = 364)
        assertEquals(10, session.validated(592).pages)
        assertThrows(IllegalArgumentException::class.java) { session.copy(endPage = 353).validated(592) }
        assertThrows(IllegalArgumentException::class.java) { session.copy(endPage = 593).validated(592) }
        assertEquals("25:00:00", readingTime(90000000))
    }
}
