package br.com.viblio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ViblioIntroMotionTest {
    @Test
    fun bookshelfVisibleAtStartAndRecedesBeforeOpenCompletes() {
        assertTrue(ViblioIntroMotion.shelfAlpha(0.10f) > 0.95f)
        assertEquals(0f, ViblioIntroMotion.openCover(0.10f), 0.001f)
        assertEquals(0f, ViblioIntroMotion.shelfAlpha(0.45f), 0.001f)
    }

    @Test
    fun staggeredOpeningConvergesToOfficialViblioSilhouette() {
        // Cover leads lower page, which leads upper page during mid-opening.
        val cMid = ViblioIntroMotion.openCover(0.52f)
        val p1Mid = ViblioIntroMotion.openPage1(0.52f)
        val p2Mid = ViblioIntroMotion.openPage2(0.52f)
        assertTrue("Expected cover > page1 > page2 at mid-open ($cMid, $p1Mid, $p2Mid)", cMid > p1Mid && p1Mid > p2Mid)

        // Fully settled on exact official ic_books_nav.png coordinates by t = 0.85.
        assertEquals(1f, ViblioIntroMotion.openCover(0.85f), 0.001f)
        assertEquals(1f, ViblioIntroMotion.openPage1(0.85f), 0.001f)
        assertEquals(1f, ViblioIntroMotion.openPage2(0.85f), 0.001f)
        assertEquals(1f, ViblioIntroMotion.brandAlpha(0.85f), 0.001f)

        // Exact endpoint convergence on cover outer top tip (0.972, 0.264) and upper page tip (0.854, 0.193).
        assertEquals(0.972f, ViblioIntroMotion.hingeX(0.972f, 1f, 0.550f, ViblioIntroMotion.PHI_COVER, false), 0.0001f)
        assertEquals(0.264f, ViblioIntroMotion.hingeY(0.264f, 1f, 0.269f, 0.052f), 0.0001f)
        assertEquals(0.854f, ViblioIntroMotion.hingeX(0.854f, 1f, 0.526f, ViblioIntroMotion.PHI_PAGE2, false), 0.0001f)
        assertEquals(0.193f, ViblioIntroMotion.hingeY(0.193f, 1f, 0.281f, 0.056f), 0.0001f)
    }

    @Test
    fun hingeProjectionIsStrictlySymmetricAndContinuousAcrossTimeline() {
        var prevX = ViblioIntroMotion.hingeX(0.972f, 0f, 0.550f, ViblioIntroMotion.PHI_COVER, false)
        var prevY = ViblioIntroMotion.hingeY(0.264f, 0f, 0.269f, 0.052f)
        for (i in 1..100) {
            val t = i / 100f
            val u = ViblioIntroMotion.openCover(t)
            val rx = ViblioIntroMotion.hingeX(0.972f, u, 0.550f, ViblioIntroMotion.PHI_COVER, false)
            val lx = ViblioIntroMotion.hingeX(0.972f, u, 0.550f, ViblioIntroMotion.PHI_COVER, true)
            val y = ViblioIntroMotion.hingeY(0.264f, u, 0.269f, 0.052f)

            assertTrue(rx.isFinite() && lx.isFinite() && y.isFinite())
            assertEquals("Left and right wings must mirror around x=0.5", 1f, lx + rx, 0.0001f)
            assertTrue("Step discontinuity detected in X at t=$t", abs(rx - prevX) < 0.04f)
            assertTrue("Step discontinuity detected in Y at t=$t", abs(y - prevY) < 0.03f)
            prevX = rx
            prevY = y
        }
    }

    @Test
    fun overlayExitFadeIsMonotonicAndClean() {
        assertEquals(1f, ViblioIntroMotion.overlayAlpha(0.85f), 0.001f)
        assertTrue(ViblioIntroMotion.overlayAlpha(0.95f) in 0.1f..0.9f)
        assertEquals(0f, ViblioIntroMotion.overlayAlpha(1f), 0.001f)
    }
}
