package br.com.viblio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViblioIntroMotionTest {
    @Test
    fun closedHorizontalAtStartAndFullOpenVAtEnd() {
        assertEquals(0f, ViblioIntroMotion.openProgress(0f), 0.001f)
        assertEquals(0f, ViblioIntroMotion.leftCoverAngle(0f), 0.001f)
        assertEquals(0f, ViblioIntroMotion.rightCoverAngle(0f), 0.001f)

        val full = ViblioIntroMotion.openProgress(0.75f)
        assertEquals(1f, full, 0.001f)
        assertEquals(150f, ViblioIntroMotion.leftCoverAngle(full), 0.001f)
        assertEquals(30f, ViblioIntroMotion.rightCoverAngle(full), 0.001f)
    }

    @Test
    fun glyphBlendAndOverlayFadeStayMonotonic() {
        assertEquals(0f, ViblioIntroMotion.glyphBlend(0.5f), 0.001f)
        assertEquals(1f, ViblioIntroMotion.glyphBlend(0.85f), 0.001f)
        assertEquals(1f, ViblioIntroMotion.overlayAlpha(0.5f), 0.001f)
        assertTrue(ViblioIntroMotion.overlayAlpha(0.92f) in 0.01f..0.99f)
        assertEquals(0f, ViblioIntroMotion.overlayAlpha(1f), 0.001f)
    }
}
