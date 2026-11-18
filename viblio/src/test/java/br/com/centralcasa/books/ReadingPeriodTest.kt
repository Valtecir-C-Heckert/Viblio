package br.com.centralcasa.books
import org.junit.Assert.*
import org.junit.Test
class ReadingPeriodTest {
    @Test fun mondayWeeksCrossYearsAndMonths(){val r=ReadingPeriods.range("2026-01-01",ReadingPeriodUnit.WEEK);assertEquals("2025-12-29",r.start);assertEquals("2026-01-05",r.endExclusive);assertFalse(r.contains("2026-01-05"))}
    @Test fun leapMonthAndNavigation(){val r=ReadingPeriods.range("2024-02-29",ReadingPeriodUnit.MONTH);assertEquals("2024-02-01",r.start);assertEquals("2024-03-01",r.endExclusive);assertEquals("2024-03-01",ReadingPeriods.shift("2024-02-29",ReadingPeriodUnit.MONTH,1));assertEquals(12,ReadingPeriods.months("2024-02-29").size)}
    @Test fun recordedDatesOverrideEpochAndManualDatesDoNotInventPages(){
        val a=LibraryBook(id="a",title="A",totalPages=100)
        val b=LibraryBook(id="b",title="B",collectionId="c",pagesKnown=false,totalPages=1)
        val archived=a.copy(id="removed",archived=true)
        val s=ReadingSession(bookId="a",startedAt=0,finishedAt=1000,durationMs=1000,startPage=10,endPage=30,readingDay="2026-11-01")
        val unknown=s.copy(bookId="b",startPage=0,endPage=1)
        val result=PeriodReading.from(listOf(a,b,archived),listOf(s,unknown,s.copy(bookId="removed")),listOf(ReadingDate(bookId="a",day="2026-11-01"),ReadingDate(bookId="b",day="2026-11-01")),ReadingPeriods.range("2026-11-01",ReadingPeriodUnit.DAY))
        assertEquals(setOf("a","b"),result.bookIds);assertEquals(20L,result.pages);assertEquals(2000L,result.durationMs)
        assertTrue(PeriodReading.from(listOf(a),listOf(s),emptyList(),ReadingPeriods.range("2026-11-02",ReadingPeriodUnit.DAY)).bookIds.isEmpty())
    }
}
