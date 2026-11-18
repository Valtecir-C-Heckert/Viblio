package br.com.centralcasa.books

import br.com.centralcasa.ui.ShelfPinch
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfPinchTest {
    @Test fun spreadingEnlargesCoversAndClosingAddsColumns() {
        assertEquals(2, ShelfPinch.columns(3, 1.2, 400f, 1f))
        assertEquals(4, ShelfPinch.columns(3, 1.0 / 1.2, 400f, 1f))
    }
    @Test fun tinyMovementsDoNotChangeDensity() {
        assertEquals(3, ShelfPinch.columns(3, 1.05, 400f, 1f))
        assertEquals(3, ShelfPinch.columns(3, .95, 400f, 1f))
    }
    @Test fun narrowPhoneSkipsAnUnsupportedFourColumnStep() {
        assertEquals(5, ShelfPinch.columns(3, .8, 280f, 1f))
        assertEquals(3, ShelfPinch.columns(5, 1.2, 280f, 1f))
    }
    @Test fun strongerPinchCanChangeSeveralStepsAndRespectsBounds() {
        assertEquals(1, ShelfPinch.columns(3, 2.0, 400f, 1f))
        assertEquals(6, ShelfPinch.columns(3, .4, 400f, 1f))
        assertEquals(1, ShelfPinch.columns(1, 2.0, 400f, 1f))
        assertEquals(6, ShelfPinch.columns(6, .5, 400f, 1f))
    }
    @Test fun calmerPinchIgnoresSmallZoomAndChangesOneColumnPerStep() {
        assertEquals(3,ShelfPinch.columns(3,1.25,400f,1f,1.4,1))
        assertEquals(3,ShelfPinch.columns(3,.8,400f,1f,1.4,1))
        assertEquals(2,ShelfPinch.columns(3,1.4,400f,1f,1.4,1))
        assertEquals(4,ShelfPinch.columns(3,1.0/1.4,400f,1f,1.4,1))
        assertEquals(2,ShelfPinch.columns(3,4.0,400f,1f,1.4,1))
    }
    @Test fun invalidDetectorValuesAreIgnored() {
        assertEquals(3, ShelfPinch.columns(3, Double.NaN, 400f, 1f))
        assertEquals(3, ShelfPinch.columns(3, 0.0, 400f, 1f))
    }
}
