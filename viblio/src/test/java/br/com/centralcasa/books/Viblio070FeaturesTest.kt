package br.com.centralcasa.books

import br.com.viblio.camera.PerspectiveGeometry
import br.com.viblio.camera.PhotoCropMode
import br.com.viblio.camera.QuadPoint
import org.junit.Assert.*
import org.junit.Test

class Viblio070FeaturesTest {
    private val square = listOf(QuadPoint(10f, 10f), QuadPoint(110f, 10f), QuadPoint(110f, 160f), QuadPoint(10f, 160f))

    @Test
    fun cornersAreOrderedClockwiseFromTopLeftWhateverTheInputOrder() {
        val shuffled = listOf(square[2], square[0], square[3], square[1])
        assertEquals(square, PerspectiveGeometry.orderQuad(shuffled))
        val tilted = listOf(QuadPoint(80f, 300f), QuadPoint(20f, 40f), QuadPoint(260f, 20f), QuadPoint(300f, 280f))
        val ordered = PerspectiveGeometry.orderQuad(tilted)
        assertEquals(QuadPoint(20f, 40f), ordered[0])
        assertEquals(QuadPoint(260f, 20f), ordered[1])
        assertEquals(QuadPoint(300f, 280f), ordered[2])
        assertEquals(QuadPoint(80f, 300f), ordered[3])
    }

    @Test
    fun convexityRejectsCrossedCollapsedAndInvalidQuads() {
        assertTrue(PerspectiveGeometry.isConvex(square))
        assertFalse(PerspectiveGeometry.isConvex(listOf(square[0], square[2], square[1], square[3])))
        assertFalse(PerspectiveGeometry.isConvex(listOf(square[0], square[0], square[2], square[3])))
        assertFalse(PerspectiveGeometry.isConvex(listOf(square[0], square[1], QuadPoint(Float.NaN, 1f), square[3])))
        assertFalse(PerspectiveGeometry.isConvex(square.take(3)))
        assertFalse(PerspectiveGeometry.isConvex(listOf(QuadPoint(0f, 0f), QuadPoint(4f, 0f), QuadPoint(4f, 4f), QuadPoint(0f, 4f)), minArea = 64f))
        assertEquals(15000f, PerspectiveGeometry.area(square), .01f)
    }

    @Test
    fun outputKeepsMeasuredProportionsAndIsBoundedForSync() {
        assertEquals(100 to 150, PerspectiveGeometry.outputSize(square))
        val huge = listOf(QuadPoint(0f, 0f), QuadPoint(3000f, 0f), QuadPoint(3000f, 4500f), QuadPoint(0f, 4500f))
        val (w, h) = PerspectiveGeometry.outputSize(huge)
        assertEquals(PerspectiveGeometry.MAX_OUTPUT_EDGE, h)
        assertEquals(1067, w)
        val spine = listOf(QuadPoint(0f, 0f), QuadPoint(2f, 0f), QuadPoint(2f, 1800f), QuadPoint(0f, 1800f))
        val (sw, sh) = PerspectiveGeometry.outputSize(spine)
        assertTrue(sw >= PerspectiveGeometry.MIN_OUTPUT_EDGE && sh <= PerspectiveGeometry.MAX_OUTPUT_EDGE)
        assertTrue(maxOf(sw, sh) <= 2048)
    }

    @Test
    fun defaultFramesMatchCoverAndSpineGuides() {
        val cover = PerspectiveGeometry.defaultQuad(1200, 1600, PhotoCropMode.COVER)
        val coverW = cover[1].x - cover[0].x; val coverH = cover[3].y - cover[0].y
        assertEquals(2f / 3f, coverW / coverH, .01f)
        assertTrue(cover.all { it.x in 0f..1200f && it.y in 0f..1600f })
        val spine = PerspectiveGeometry.defaultQuad(1200, 1600, PhotoCropMode.SPINE)
        val spineW = spine[1].x - spine[0].x; val spineH = spine[3].y - spine[0].y
        assertEquals(1f / 6f, spineW / spineH, .01f)
        assertTrue(spineW < coverW)
        assertEquals(PhotoCropMode.SPINE, PhotoCropMode.of("spine"))
        assertEquals(PhotoCropMode.COVER, PhotoCropMode.of("anything"))
        assertEquals(PhotoCropMode.COVER, PhotoCropMode.of(null))
    }

    @Test
    fun rotatingNinetyDegreesMovesCornersWithTheImage() {
        val rotated = PerspectiveGeometry.rotateQuad90(square, height = 200)
        assertEquals(listOf(QuadPoint(40f, 10f), QuadPoint(190f, 10f), QuadPoint(190f, 110f), QuadPoint(40f, 110f)), rotated)
        assertEquals(QuadPoint(0f, 50f), PerspectiveGeometry.clamp(QuadPoint(-5f, 50f), 100, 100))
        assertEquals(QuadPoint(100f, 100f), PerspectiveGeometry.clamp(QuadPoint(500f, 120f), 100, 100))
    }

    @Test
    fun detectionFindsABrightCoverOnDarkBackgroundAndRejectsFlatImages() {
        val w = 200; val h = 240
        val luma = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 40..159 && y in 30..209) 230 else 20 }
        val quad = requireNotNull(PerspectiveGeometry.detectQuad(luma, w, h, PhotoCropMode.COVER))
        assertEquals(40f, quad[0].x, 3f); assertEquals(30f, quad[0].y, 3f)
        assertEquals(159f, quad[2].x, 3f); assertEquals(209f, quad[2].y, 3f)
        assertNull(PerspectiveGeometry.detectQuad(IntArray(w * h) { 128 }, w, h, PhotoCropMode.COVER))
        assertNull(PerspectiveGeometry.detectQuad(IntArray(10), w, h, PhotoCropMode.COVER))
        assertNull(PerspectiveGeometry.detectQuad(IntArray(16 * 16), 16, 16, PhotoCropMode.SPINE))
    }

    @Test
    fun spineImageDefaultsToProceduralAndOnlyAcceptsStoredJpegNames() {
        val book = LibraryBook(title = "Livro", totalPages = 100)
        assertEquals("", book.spineImage)
        book.validated()
        book.copy(spineImage = "a".repeat(64) + ".jpg").validated()
        book.copy(spineImage = "123e4567-e89b-12d3-a456-426614174000.jpg").validated()
        for (bad in listOf("../x.jpg", "x.png", "/data/x.jpg", "a".repeat(90) + ".jpg", "x .jpg")) {
            assertThrows(IllegalArgumentException::class.java) { book.copy(spineImage = bad).validated() }
        }
    }
}
