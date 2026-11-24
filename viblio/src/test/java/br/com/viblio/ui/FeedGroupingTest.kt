package br.com.viblio.ui

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedGroupingTest {
    private fun event(
        id: String,
        kind: String,
        at: Long,
        actorId: String = "u1",
        username: String = "ana",
        bookId: String = "b1",
        title: String = "1984",
        bookPages: Int = 328,
        pages: Int = 0,
        startPage: Int = 0,
        endPage: Int = 0,
        durationMs: Long = 0L,
        totalDurationMs: Long = 0L,
        rating: Double? = null,
        pagesKnown: Boolean = true,
    ): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("kind", kind)
        addProperty("occurred_at", at)
        add("actor", JsonObject().apply {
            addProperty("id", actorId)
            addProperty("username", username)
        })
        add("book", JsonObject().apply {
            addProperty("id", bookId)
            addProperty("title", title)
            addProperty("pages", bookPages)
            addProperty("page", endPage)
        })
        if (kind == "read") {
            addProperty("pages", pages)
            addProperty("start_page", startPage)
            addProperty("end_page", endPage)
            addProperty("duration_ms", durationMs)
            addProperty("pages_known", pagesKnown)
        } else {
            if (totalDurationMs > 0L) addProperty("total_duration_ms", totalDurationMs)
            if (rating != null) addProperty("rating", rating)
        }
    }

    @Test
    fun filtersZeroPageSubSecondNoiseEvents() {
        val noise = event("e1", "read", 1732000000000L, pages = 0, durationMs = 400L)
        val valid = event("e2", "read", 1732000001000L, pages = 12, startPage = 10, endPage = 22, durationMs = 900_000L)
        assertTrue(FeedGrouping.isNoise(noise))
        assertFalse(FeedGrouping.isNoise(valid))

        val out = FeedGrouping.consolidate(listOf(noise, valid))
        assertEquals(1, out.size)
        assertEquals("e2", out[0].id)
    }

    @Test
    fun mergesFinishedAndRatedSameDayIntoSingleCelebrationCard() {
        val base = 1732030000000L // same calendar day
        val rated = event("e2", "rated", base + 60_000L, rating = 5.0)
        val finished = event("e1", "finished", base, totalDurationMs = 21_120_000L)

        val out = FeedGrouping.consolidate(listOf(rated, finished))
        assertEquals(1, out.size)
        val item = out[0]
        assertTrue(item.hasFinished && item.hasRated)
        assertEquals(5.0, item.rating!!, 0.001)
        assertEquals(21_120_000L, item.totalDurationMs)
        assertEquals("Concluiu · ★ 5.0", FeedGrouping.actionBadge(item))
        assertEquals("★★★★★", FeedGrouping.stars(5.0))
        assertEquals(100, FeedGrouping.progressPercent(item))
    }

    @Test
    fun mergesStartedAndMultipleReadSessionsAndComputesProgress() {
        val base = 1732030000000L
        val s2 = event("e3", "read", base + 7_200_000L, bookId = "b2", title = "Duna", bookPages = 400, pages = 20, startPage = 20, endPage = 40, durationMs = 1_200_000L)
        val s1 = event("e2", "read", base + 3_600_000L, bookId = "b2", title = "Duna", bookPages = 400, pages = 20, startPage = 0, endPage = 20, durationMs = 900_000L)
        val st = event("e1", "started", base, bookId = "b2", title = "Duna", bookPages = 400)

        val out = FeedGrouping.consolidate(listOf(s2, s1, st))
        assertEquals(1, out.size)
        val item = out[0]
        assertTrue(item.hasStarted && item.hasRead)
        assertEquals(40L, item.pagesRead)
        assertEquals(0L, item.startPage)
        assertEquals(40L, item.endPage)
        assertEquals(2_100_000L, item.durationMs)
        assertEquals(2, item.sessionCount)
        assertEquals("Começou · +40p", FeedGrouping.actionBadge(item))
        assertEquals(10, FeedGrouping.progressPercent(item))
        assertTrue(FeedGrouping.matchesFilter(item, "read"))
        assertFalse(FeedGrouping.matchesFilter(item, "finished"))
    }

    @Test
    fun handlesAddedBookEventsAndFilter() {
        val base = 1732030000000L
        val added = event("e10", "added", base, bookId = "b3", title = "Condição Artificial", bookPages = 160)
        val out = FeedGrouping.consolidate(listOf(added))
        assertEquals(1, out.size)
        val item = out[0]
        assertTrue(item.hasAdded)
        assertEquals("added", item.primaryType)
        assertEquals("Adicionou", FeedGrouping.actionBadge(item))
        assertEquals(null, FeedGrouping.progressPercent(item))
        assertTrue(FeedGrouping.matchesFilter(item, "all"))
        assertTrue(FeedGrouping.matchesFilter(item, "added"))
        assertFalse(FeedGrouping.matchesFilter(item, "read"))
    }

    @Test
    fun identifiesSelfEntriesAndKeepsOwnAndFriendSameBookSeparate() {
        val base = 1732030000000L
        val mine = event("e20", "read", base + 5_000L, actorId = "me-uuid", username = "valtecir", bookId = "b1", pages = 15, startPage = 10, endPage = 25, durationMs = 600_000L)
        val friend = event("e21", "read", base, actorId = "friend-uuid", username = "ana", bookId = "b1", pages = 10, startPage = 0, endPage = 10, durationMs = 400_000L)
        val out = FeedGrouping.consolidate(listOf(mine, friend))
        assertEquals(2, out.size)
        assertTrue(FeedGrouping.isSelf(out[0], "ME-UUID", "other"))
        assertFalse(FeedGrouping.isSelf(out[1], "me-uuid", "valtecir"))
        assertTrue(FeedGrouping.isSelf(out[0], "", "VALTECIR"))
        assertFalse(FeedGrouping.isSelf(out[1], "", "valtecir"))
    }
}
