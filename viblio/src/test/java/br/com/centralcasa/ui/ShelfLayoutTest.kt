package br.com.centralcasa.ui

import org.junit.Assert.*
import org.junit.Test

class ShelfLayoutTest {
    @Test fun compactModesAllowSixCoversOrFiveOnNarrowerPhones() {
        assertEquals(6, ShelfLayout.columns(352f, 6, 1f))
        assertEquals(5, ShelfLayout.columns(280f, 6, 1f))
        assertEquals(4, ShelfLayout.columns(352f, 4, 1f))
        assertEquals(4, ShelfLayout.columns(320f, 4, 1f))
        assertEquals(2, ShelfLayout.columns(240f, 6, 1f))
    }
    @Test fun wideScreensCanUseAllSixColumns() {
        assertEquals(6, ShelfLayout.columns(760f, 6, 1f))
        assertEquals(2, ShelfLayout.columns(760f, 2, 1f))
    }
    @Test fun largerFontsReduceDensity() {
        assertEquals(4, ShelfLayout.columns(352f, 4, 1.3f))
        assertEquals(2, ShelfLayout.columns(352f, 3, 1.3f))
        assertEquals(3, ShelfLayout.columns(280f, 4, 2f))
        assertEquals(1, ShelfLayout.columns(280f, 3, 2f))
    }
    @Test fun everyMultiColumnCardKeepsItsMinimumReadableWidth() {
        for (width in listOf(240f, 280f, 320f, 352f, 560f, 760f)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                val columns = ShelfLayout.columns(width, 4, scale)
                if (columns > 1) assertTrue((width - (columns - 1) * 8f) / columns >= 72f)
            }
        }
    }
}
