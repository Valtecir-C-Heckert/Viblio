package br.com.viblio.ui

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Pure club display calculations kept free of Android views for fast JVM verification. */
internal object ClubFormatting {
    const val RHYTHM_TICKS = 24

    fun filledTicks(percent: Int, total: Int = RHYTHM_TICKS): Int {
        val clamped = percent.coerceIn(0, 100)
        return ((clamped * total) / 100f).roundToInt().coerceIn(0, total)
    }

    fun remainingLabel(state: String, remainingMs: Long): String = when {
        state == "completed" -> "Encerrada"
        remainingMs <= 0L -> "Prazo encerrado"
        else -> {
            val days = ceil(remainingMs / 86400000.0).toLong().coerceAtLeast(1L)
            if (days == 1L) "1 dia" else "$days dias"
        }
    }

    fun countLabel(count: Long, singular: String, plural: String): String =
        "$count ${if (count == 1L) singular else plural}"

    fun voteShare(votes: Long, totalVotes: Long): Int =
        if (totalVotes <= 0L || votes <= 0L) 0
        else ((votes * 100.0) / totalVotes).roundToInt().coerceIn(0, 100)

    /** Highlights the latest open stage, or the first scheduled stage if none is open yet. */
    fun activeStageIndex(openFlags: List<Boolean>): Int {
        if (openFlags.isEmpty()) return -1
        val lastOpen = openFlags.indexOfLast { it }
        return if (lastOpen >= 0) lastOpen else 0
    }

    fun monogram(name: String, maxChars: Int = 2): String {
        val clean = name.trim().filter { it.isLetterOrDigit() || it.isWhitespace() }
        if (clean.isBlank()) return "?"
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (maxChars >= 2 && words.size >= 2) {
            (words[0].take(1) + words[1].take(1)).uppercase()
        } else {
            words.firstOrNull()?.take(maxChars)?.uppercase() ?: "?"
        }
    }
}
