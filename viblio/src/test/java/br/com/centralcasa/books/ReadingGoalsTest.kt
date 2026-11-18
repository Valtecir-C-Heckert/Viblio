package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class ReadingGoalsTest {
    private fun at(day:String)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {timeZone=TimeZone.getDefault()}.parse("$day 12:00:00")!!.time
    private val start=at("2026-01-01")
    private val now=at("2026-02-12")
    private fun goal(metric:GoalMetric=GoalMetric.BOOKS,target:Int=3,period:GoalPeriod=GoalPeriod.YEAR)=ReadingGoal(name="Teste",metric=metric,target=target,period=period,createdAt=start,baseline=emptyMap(),excluded=emptySet())
    private fun book(id:String="one",kind:String="book",complete:Boolean=true)=LibraryBook(id=id,title=id,totalPages=100,currentPage=if(complete)100 else 0,kind=kind,updatedAt=now)
    private fun session(id:String="s",book:String="one",end:Int=100,day:String="2026-02-11",begin:Int=0)=ReadingSession(id=id,bookId=book,startedAt=at(day)-60000,finishedAt=at(day),durationMs=60000,startPage=begin,endPage=end)
    @Test fun pagesUseSessionsNotCorrectedProgress(){val p=ReadingGoals.progress(goal(GoalMetric.PAGES,500),listOf(book(complete=false)),listOf(session(end=20)),now);assertEquals(20L,p.value)}
    @Test fun oldSessionsAndUntimedCorrectionsDoNotCount(){val p=ReadingGoals.progress(goal(),listOf(book()),listOf(session(day="2025-12-31")),now);assertEquals(0L,p.value)}
    @Test fun completionCountsOnceDespiteDuplicateReading(){val p=ReadingGoals.progress(goal(),listOf(book()),listOf(session(),session(id="again")),now);assertEquals(1L,p.value)}
    @Test fun rereadingDoesNotEraseEarlierCompletion(){val p=ReadingGoals.progress(goal(),listOf(book(complete=false)),listOf(session()),now);assertEquals(1L,p.value)}
    @Test fun filterKinds(){val b=book(kind="manga");assertEquals(0L,ReadingGoals.progress(goal(),listOf(b),listOf(session()),now).value);assertEquals(1L,ReadingGoals.progress(goal().copy(kind="manga"),listOf(b),listOf(session()),now).value)}
    @Test fun periodsUseCalendarBoundaries(){val g=goal(period=GoalPeriod.MONTH);val p=ReadingGoals.progress(g,listOf(book()),listOf(session(day="2026-01-31"),session(id="feb")),now);assertEquals(1L,p.value)}
    @Test fun pageBaselinesExcludeProgressFromDraftBeforeCreation(){val s=session().copy(startedAt=start-60000);val g=goal(GoalMetric.PAGES,500).copy(baseline=mapOf("one" to 40));assertEquals(60L,ReadingGoals.progress(g,listOf(book()),listOf(s),now).value)}
    @Test fun archivedReadBooksRemainCounted(){assertEquals(1L,ReadingGoals.progress(goal(),listOf(book().copy(archived=true)),listOf(session()),now).value)}
    @Test fun purchaseDoesNotUsePreviouslyCompletedBooks(){val g=goal(GoalMetric.PURCHASE,1).copy(excluded=setOf("one"));assertEquals(0L,ReadingGoals.progress(g,listOf(book()),listOf(session()),now).value)}
    @Test fun purchaseConsumesOnlyOneCycleAndKeepsSurplus(){val books=(1..4).map {book(it.toString())};val sessions=books.map {session(id=it.id,book=it.id)};val g=goal(GoalMetric.PURCHASE,3);val p=ReadingGoals.progress(g,books,sessions,now);val used=ReadingGoals.purchase(g,p,now);assertEquals(3,used.cycles.single().books.size);assertEquals(1L,ReadingGoals.progress(used,books,sessions,now).value)}
    @Test fun secondPurchaseCannotReuseBooks(){val g=goal(GoalMetric.PURCHASE,1);val p=ReadingGoals.progress(g,listOf(book()),listOf(session()),now);val used=ReadingGoals.purchase(g,p,now);val empty=ReadingGoals.progress(used,listOf(book()),listOf(session()),now);assertTrue(runCatching {ReadingGoals.purchase(used,empty,now)}.isFailure)}
    @Test fun futureEventsDoNotUnlock(){assertEquals(0L,ReadingGoals.progress(goal(),listOf(book()),listOf(session(day="2026-02-13")),now).value)}
    @Test fun editingMetadataCannotInventCompletion(){assertEquals(0L,ReadingGoals.progress(goal(),listOf(book().copy(updatedAt=now)),emptyList(),now).value)}
    @Test fun pagesPreserveTraceToBookAndSession(){val p=ReadingGoals.progress(goal(GoalMetric.PAGES,100),listOf(book()),listOf(session(end=25)),now);assertEquals("one",p.credits.single().bookId);assertEquals(listOf("s"),p.credits.single().sessionIds)}
    @Test fun malformedLimitsRejected(){assertTrue(runCatching {goal(target=0).validated()}.isFailure);assertTrue(runCatching {goal(GoalMetric.PURCHASE,101).validated()}.isFailure)}
    @Test fun purchaseRejectsForgedCounter(){assertTrue(runCatching {ReadingGoals.purchase(goal(GoalMetric.PURCHASE),GoalProgress(emptyList(),3,""),now)}.isFailure)}
}
