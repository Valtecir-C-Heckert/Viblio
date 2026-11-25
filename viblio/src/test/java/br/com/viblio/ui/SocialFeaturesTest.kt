package br.com.viblio.ui

import br.com.centralcasa.books.LibraryBook
import br.com.viblio.account.AccountManager
import br.com.viblio.reminder.SocialNotifier
import br.com.viblio.reminder.SocialSummary
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialFeaturesTest {
    @Test
    fun syncBackoffIsFastAndProgressive() {
        assertEquals(15_000L, AccountManager.nextRetryDelayMs(1))
        assertEquals(30_000L, AccountManager.nextRetryDelayMs(2))
        assertEquals(60_000L, AccountManager.nextRetryDelayMs(3))
        assertEquals(120_000L, AccountManager.nextRetryDelayMs(4))
        assertEquals(120_000L, AccountManager.nextRetryDelayMs(10))
    }

    @Test
    fun feedGroupingAggregatesReactionsAcrossMergedEvents() {
        fun event(id: String, occurredAt: Long, clap: Int, fire: Int, mine: String?): JsonObject = JsonObject().apply {
            addProperty("id", id)
            addProperty("kind", "read")
            addProperty("occurred_at", occurredAt)
            addProperty("pages", 20)
            addProperty("start_page", 80)
            addProperty("end_page", 100)
            addProperty("duration_ms", 600_000)
            add("actor", JsonObject().apply { addProperty("id", "u1"); addProperty("username", "ana") })
            add("book", JsonObject().apply { addProperty("id", "b1"); addProperty("title", "Duna"); addProperty("pages", 500) })
            add("reactions", JsonObject().apply {
                add("counts", JsonObject().apply {
                    if (clap > 0) addProperty("clap", clap)
                    if (fire > 0) addProperty("fire", fire)
                })
                if (mine != null) addProperty("mine", mine)
                addProperty("total", clap + fire)
            })
        }

        val now = 1_764_072_000_000L
        val groups = FeedGrouping.consolidate(listOf(event("e2", now + 60_000L, 1, 2, "fire"), event("e1", now, 2, 0, null)))
        assertEquals(1, groups.size)
        assertEquals("e2", groups.first().id)
        assertEquals(3, groups.first().reactionCounts["clap"])
        assertEquals(2, groups.first().reactionCounts["fire"])
        assertEquals("fire", groups.first().myReaction)
        assertEquals(5, groups.first().reactionTotal)
    }

    @Test
    fun friendCommonBooksMatchesByTitleAndAuthorAndFormatsSummary() {
        val ownBooks = listOf(
            LibraryBook(id = "1", title = "O Senhor dos Anéis", author = "J. R. R. Tolkien", totalPages = 1000, currentPage = 1000, rating = 5.0, readFinished = true),
            LibraryBook(id = "2", title = "Duna", author = "Frank Herbert", totalPages = 600, currentPage = 300, rating = 4.5),
            LibraryBook(id = "3", title = "Arquivado", author = "Alguém", totalPages = 200, currentPage = 200, archived = true),
        )
        val friendDuna = JsonObject().apply {
            addProperty("item_type", "book")
            addProperty("title", "duna")
            addProperty("author", "Frank Herbert")
            addProperty("pages", 600)
            addProperty("page", 600)
            addProperty("complete", true)
            addProperty("rating", 4.0)
        }
        val friendArchived = JsonObject().apply {
            addProperty("item_type", "book")
            addProperty("title", "Arquivado")
            addProperty("author", "Alguém")
        }
        val matched = FriendCommonBooks.findMatch(friendDuna, ownBooks)
        assertNotNull(matched)
        assertEquals("2", matched?.id)
        assertNull(FriendCommonBooks.findMatch(friendArchived, ownBooks))
        assertEquals("Lendo 50% · ★ 4.5", FriendCommonBooks.ownSummary(matched!!))
        assertEquals("Concluído · ★ 4.0", FriendCommonBooks.friendSummary(friendDuna))
    }

    @Test
    fun socialCacheRejectsOversizedAndMalformedPayloads() {
        val valid = JsonObject().apply {
            add("items", JsonArray().apply { add(JsonObject().apply { addProperty("id", "1") }) })
        }.toString()
        val parsed = SocialCache.parseBounded(valid)
        assertEquals(1, parsed?.getAsJsonArray("items")?.size())

        assertNull(SocialCache.parseBounded("not-json"))
        assertNull(SocialCache.parseBounded("a".repeat(SocialCache.MAX_JSON_BYTES + 10)))
    }

    @Test
    fun socialNotifierDeduplicatesAndFormatsSummary() {
        val s1 = SocialSummary(friendRequests = 1, clubInvites = 1, openVotes = 0, unreadMessages = 2)
        assertEquals(4, s1.total)
        assertTrue(SocialNotifier.shouldNotify("", 0, s1))
        assertFalse(SocialNotifier.shouldNotify(s1.signature(), s1.total, s1))
        assertTrue(SocialNotifier.shouldNotify(s1.signature(), s1.total, s1.copy(unreadMessages = 3)))
        assertFalse(SocialNotifier.shouldNotify(s1.signature(), s1.total, SocialSummary()))
        assertTrue(s1.notificationBody().contains("2 novas mensagens"))
        assertTrue(s1.notificationBody().contains("1 pedido de amizade"))
        assertTrue(s1.notificationBody().contains("1 convite de clube"))
    }
}
