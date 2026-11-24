package br.com.viblio.ui

import br.com.centralcasa.books.ReadingDays
import com.google.gson.JsonObject
import kotlin.math.roundToInt

/**
 * Pure filtering, same-day consolidation, and formatting helpers for the Friends Feed.
 * Removes empty sub-second sessions (0 pages & < 1s) and merges duplicate same-book/same-day cards.
 */
internal object FeedGrouping {
    data class Entry(
        val id: String,
        val dayKey: String,
        val occurredAt: Long,
        val actorId: String,
        val actorUsername: String,
        val book: JsonObject,
        val bookId: String,
        val hasAdded: Boolean,
        val hasStarted: Boolean,
        val hasFinished: Boolean,
        val hasRead: Boolean,
        val hasRated: Boolean,
        val pagesKnown: Boolean,
        val pagesRead: Long,
        val startPage: Long?,
        val endPage: Long?,
        val durationMs: Long,
        val totalDurationMs: Long,
        val rating: Double?,
        val sessionCount: Int,
    ) {
        val primaryType: String
            get() = when {
                hasFinished -> "finished"
                hasRated && !hasRead && !hasStarted -> "rated"
                hasStarted && !hasRead -> "started"
                hasRead -> "read"
                hasAdded -> "added"
                else -> "read"
            }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.runCatching { asString }?.getOrNull().orEmpty()

    private fun JsonObject.num(key: String): Long =
        get(key)?.takeUnless { it.isJsonNull }?.runCatching { asLong }?.getOrNull() ?: 0L

    private fun JsonObject.dbl(key: String): Double? =
        get(key)?.takeUnless { it.isJsonNull }?.runCatching { asDouble }?.getOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeUnless { it.isJsonNull }?.runCatching { asBoolean }?.getOrNull() ?: false

    private fun JsonObject.obj(key: String): JsonObject =
        get(key)?.takeUnless { it.isJsonNull }?.runCatching { asJsonObject }?.getOrNull() ?: JsonObject()

    /** Empty reading sessions (0 pages and < 1s) add noise ("0 páginas · 0 s") and are dropped. */
    fun isNoise(event: JsonObject): Boolean {
        if (event.str("kind") != "read") return false
        val pages = event.num("pages")
        val duration = event.num("duration_ms")
        return pages <= 0L && duration < 1000L
    }

    /**
     * Consolidates feed events belonging to the same (actor, book, calendar day) while preserving
     * reverse-chronological ordering by the latest activity in each consolidated card.
     */
    fun consolidate(events: List<JsonObject>): List<Entry> {
        val buckets = LinkedHashMap<String, MutableList<JsonObject>>()
        for (event in events) {
            if (isNoise(event)) continue
            val whenMs = event.num("occurred_at")
            val dayKey = ReadingDays.at(whenMs)
            val actor = event.obj("actor")
            val book = event.obj("book")
            val actorId = actor.str("id").ifBlank { actor.str("username") }
            val bookId = book.str("id").ifBlank { book.str("title") }
            val groupKey = "$dayKey|$actorId|$bookId"
            buckets.getOrPut(groupKey) { ArrayList() }.add(event)
        }

        val out = ArrayList<Entry>(buckets.size)
        for ((_, list) in buckets) {
            // Sort newest first inside the group so metadata/id come from the latest event.
            val sorted = list.sortedByDescending { it.num("occurred_at") }
            val latest = sorted.first()
            val actor = latest.obj("actor")
            val book = latest.obj("book")
            val whenMs = latest.num("occurred_at")

            var hasAdded = false
            var hasStarted = false
            var hasFinished = false
            var hasRead = false
            var hasRated = false
            var pagesKnown = false
            var pagesRead = 0L
            var minStart: Long? = null
            var maxEnd: Long? = null
            var durationMs = 0L
            var totalDurationMs = 0L
            var rating: Double? = null
            var sessionCount = 0

            for (ev in sorted) {
                when (ev.str("kind")) {
                    "added" -> hasAdded = true
                    "started" -> hasStarted = true
                    "finished" -> {
                        hasFinished = true
                        totalDurationMs = maxOf(totalDurationMs, ev.num("total_duration_ms"))
                        if (rating == null) rating = ev.dbl("rating")
                        if (ev.bool("pages_known")) {
                            pagesKnown = true
                            val ep = ev.num("end_page")
                            if (ep > 0L) maxEnd = maxOf(maxEnd ?: ep, ep)
                        }
                    }
                    "rated" -> {
                        hasRated = true
                        if (rating == null) rating = ev.dbl("rating")
                        totalDurationMs = maxOf(totalDurationMs, ev.num("total_duration_ms"))
                    }
                    else -> {
                        hasRead = true
                        sessionCount++
                        durationMs += ev.num("duration_ms").coerceAtLeast(0L)
                        if (ev.bool("pages_known")) {
                            pagesKnown = true
                            pagesRead += ev.num("pages").coerceAtLeast(0L)
                            val sp = ev.num("start_page")
                            val ep = ev.num("end_page")
                            minStart = if (minStart == null) sp else minOf(minStart, sp)
                            maxEnd = if (maxEnd == null) ep else maxOf(maxEnd, ep)
                        }
                    }
                }
            }

            out.add(
                Entry(
                    id = latest.str("id"),
                    dayKey = ReadingDays.at(whenMs),
                    occurredAt = whenMs,
                    actorId = actor.str("id"),
                    actorUsername = actor.str("username"),
                    book = book,
                    bookId = book.str("id"),
                    hasAdded = hasAdded,
                    hasStarted = hasStarted,
                    hasFinished = hasFinished,
                    hasRead = hasRead,
                    hasRated = hasRated,
                    pagesKnown = pagesKnown,
                    pagesRead = pagesRead,
                    startPage = minStart,
                    endPage = maxEnd,
                    durationMs = durationMs,
                    totalDurationMs = totalDurationMs,
                    rating = rating,
                    sessionCount = sessionCount,
                ),
            )
        }
        return out.sortedByDescending { it.occurredAt }
    }

    fun isSelf(entry: Entry, currentUserId: String, currentUsername: String): Boolean {
        if (currentUserId.isNotBlank() && entry.actorId.isNotBlank()) {
            return entry.actorId.equals(currentUserId, ignoreCase = true)
        }
        return currentUsername.isNotBlank() && entry.actorUsername.equals(currentUsername, ignoreCase = true)
    }

    fun matchesFilter(entry: Entry, filter: String): Boolean = when (filter) {
        "read" -> entry.hasRead || entry.hasStarted
        "finished" -> entry.hasFinished
        "rated" -> entry.hasRated || entry.rating != null
        "added" -> entry.hasAdded
        else -> true
    }

    fun actionBadge(entry: Entry): String {
        val starTag = entry.rating?.let { "★ ${formatRating(it)}" }
        return when {
            entry.hasFinished && starTag != null -> "Concluiu · $starTag"
            entry.hasFinished -> "Concluiu"
            entry.hasRated && !entry.hasRead && !entry.hasStarted -> "Avaliou"
            entry.hasStarted && entry.pagesKnown && entry.pagesRead > 0L -> "Começou · +${entry.pagesRead}p"
            entry.hasStarted -> "Começou"
            entry.pagesKnown && entry.pagesRead > 0L -> "+${entry.pagesRead} págs"
            entry.hasRead -> "Leu"
            entry.hasAdded -> "Adicionou"
            else -> "Leu"
        }
    }

    fun formatRating(rating: Double): String {
        val rounded = (rating * 10.0).roundToInt() / 10.0
        return String.format(java.util.Locale.US, "%.1f", rounded)
    }

    fun stars(rating: Double): String {
        val full = rating.roundToInt().coerceIn(0, 5)
        return "★".repeat(full) + "☆".repeat(5 - full)
    }

    fun progressPercent(entry: Entry): Int? {
        if (entry.primaryType == "added") return null
        if (entry.hasFinished || entry.book.bool("complete")) return 100
        val total = entry.book.num("pages")
        if (total <= 0L) return null
        val current = entry.endPage ?: entry.book.num("page")
        if (current <= 0L) return if (entry.hasStarted) 2 else 0
        return ((current * 100L) / total).toInt().coerceIn(0, 100)
    }
}
