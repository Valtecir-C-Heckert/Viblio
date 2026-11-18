package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ReadingDatesTest {
    @Test fun strictDatesRejectImpossibleAndPartialInput() {
        for (day in listOf("2026-02-29", "2026-04-31", "2026-13-01", "2026-1-07", "2026-10-07junk")) {
            assertTrue(day, runCatching { ReadingDays.validate(day) }.isFailure)
        }
        assertEquals("2024-02-29", ReadingDays.validate("2024-02-29"))
    }
    @Test fun periodIncludesBothEndsAndLeapDay() {
        assertEquals(listOf("2024-02-28", "2024-02-29", "2024-03-01"), ReadingDays.range("2024-02-28", "2024-03-01"))
        assertEquals(listOf("2026-10-07"), ReadingDays.range("2026-10-07", "2026-10-07"))
        assertTrue(runCatching { ReadingDays.range("2026-10-08", "2026-10-07") }.isFailure)
    }
    @Test fun rangesSurviveDaylightSavingTransitions() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(listOf("2026-03-07", "2026-03-08", "2026-03-09"), ReadingDays.range("2026-03-07", "2026-03-09"))
            assertEquals(listOf("2026-10-31", "2026-11-01", "2026-11-02"), ReadingDays.range("2026-10-31", "2026-11-02"))
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun automaticDayUsesLocalTimezone() {
        assertEquals("1969-12-31", ReadingDays.at(0, TimeZone.getTimeZone("America/Sao_Paulo")))
        assertEquals("1970-01-01", ReadingDays.at(0, TimeZone.getTimeZone("UTC")))
    }
    @Test fun correctingSessionDayPreservesMeasuredValues() {
        val session = ReadingSession(bookId = "book", startedAt = 1000, finishedAt = 2000, durationMs = 750, startPage = 2, endPage = 8)
        val edited = session.copy(readingDay = "2026-10-06").validated(100)
        assertEquals(session.pages, edited.pages)
        assertEquals(session.durationMs, edited.durationMs)
        assertEquals(session.startedAt, edited.startedAt)
        assertEquals(ReadingSummary.from(listOf(session)), ReadingSummary.from(listOf(edited)))
        assertTrue(runCatching { edited.copy(readingDay = "2026-02-30").validated(100) }.isFailure)
    }
    @Test fun excessiveRangeIsBounded() {
        assertTrue(runCatching { ReadingDays.range("2000-01-01", "2026-10-07") }.isFailure)
    }
}
