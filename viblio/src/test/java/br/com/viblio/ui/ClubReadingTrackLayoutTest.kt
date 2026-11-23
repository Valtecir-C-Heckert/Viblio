package br.com.viblio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClubReadingTrackLayoutTest {
    @Test
    fun fractionClampsWithinBookBounds() {
        assertEquals(0f, ClubReadingTrackLayout.fraction(0, 400), 0.0001f)
        assertEquals(0.5f, ClubReadingTrackLayout.fraction(200, 400), 0.0001f)
        assertEquals(1f, ClubReadingTrackLayout.fraction(600, 400), 0.0001f)
        assertEquals(0f, ClubReadingTrackLayout.fraction(-10, 400), 0.0001f)
        assertEquals(0f, ClubReadingTrackLayout.fraction(50, 0), 0.0001f)
    }

    @Test
    fun clusteredPeersReceiveDistinctVerticalLanesWhileCurrentUserStaysCentered() {
        val members = listOf(
            ClubTrackMember("A", 100, isMe = false),
            ClubTrackMember("B", 102, isMe = false),
            ClubTrackMember("V", 101, isMe = true),
            ClubTrackMember("C", 360, isMe = false),
        )
        val placed = ClubReadingTrackLayout.place(members, totalPages = 400)
        assertEquals(4, placed.size)
        // Current user always sits directly on the central rail (lane 0)
        assertEquals(0, placed[2].second)
        // Clustered peers A (25%) and B (25.5%) get separated into different lanes
        assertNotEquals(placed[0].second, placed[1].second)
        // Distant peer C (90%) sits cleanly on lane 0
        assertEquals(0, placed[3].second)
        assertEquals(0.9f, placed[3].first, 0.0001f)
    }
}
