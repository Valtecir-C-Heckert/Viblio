package br.com.viblio.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClubFormattingTest {
    @Test
    fun filledTicksClampsAndRounds() {
        assertEquals(0, ClubFormatting.filledTicks(-10))
        assertEquals(0, ClubFormatting.filledTicks(0))
        assertEquals(12, ClubFormatting.filledTicks(50))
        assertEquals(24, ClubFormatting.filledTicks(100))
        assertEquals(24, ClubFormatting.filledTicks(150))
    }

    @Test
    fun remainingLabelHandlesStatesAndPluralization() {
        assertEquals("Encerrada", ClubFormatting.remainingLabel("completed", 86400000L * 5))
        assertEquals("Prazo encerrado", ClubFormatting.remainingLabel("reading", 0L))
        assertEquals("Prazo encerrado", ClubFormatting.remainingLabel("reading", -1000L))
        assertEquals("1 dia", ClubFormatting.remainingLabel("reading", 3600_000L))
        assertEquals("3 dias", ClubFormatting.remainingLabel("reading", 86400000L * 2 + 1000L))
    }

    @Test
    fun voteShareAndCounts() {
        assertEquals(0, ClubFormatting.voteShare(0, 0))
        assertEquals(0, ClubFormatting.voteShare(0, 5))
        assertEquals(67, ClubFormatting.voteShare(2, 3))
        assertEquals(100, ClubFormatting.voteShare(5, 5))
        assertEquals("1 nova", ClubFormatting.countLabel(1, "nova", "novas"))
        assertEquals("6 novas", ClubFormatting.countLabel(6, "nova", "novas"))
    }

    @Test
    fun activeStageSelectsLatestOpenOrFirstScheduled() {
        assertEquals(-1, ClubFormatting.activeStageIndex(emptyList()))
        assertEquals(0, ClubFormatting.activeStageIndex(listOf(false, false)))
        assertEquals(1, ClubFormatting.activeStageIndex(listOf(true, true, false)))
        assertEquals(2, ClubFormatting.activeStageIndex(listOf(true, true, true)))
    }

    @Test
    fun monogramHandlesClubNamesAndUsernames() {
        assertEquals("CA", ClubFormatting.monogram("Clube Aurora"))
        assertEquals("A", ClubFormatting.monogram("alice", 1))
        assertEquals("AL", ClubFormatting.monogram("alice", 2))
        assertEquals("?", ClubFormatting.monogram("   "))
    }
}
