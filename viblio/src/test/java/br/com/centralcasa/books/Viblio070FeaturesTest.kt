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

    @Test
    fun dynamicAppIconMapsBookCoverColorsToClosestAlias() {
        assertEquals("default", br.com.viblio.ui.DynamicAppIcon.nearestKey(null))
        assertEquals("default", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFFF5F5F5.toInt()))
        assertEquals("default", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFF101010.toInt()))
        assertEquals("red", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFFD63B3B.toInt()))
        assertEquals("blue", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFF3370D8.toInt()))
        assertEquals("green", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFF38A060.toInt()))
        assertEquals("purple", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFF914EE0.toInt()))
        assertEquals("orange", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFFE07630.toInt()))
        assertEquals("yellow", br.com.viblio.ui.DynamicAppIcon.nearestKey(0xFFE5C845.toInt()))
        assertEquals(16, br.com.viblio.ui.DynamicAppIcon.aliases.size)
    }

    @Test
    fun spineDetectionFindsNarrowCentralSpineDespiteInternalLettering() {
        val w = 200; val h = 280
        val luma = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val inSpine = x in 78..122 && y in 24..256
            when {
                inSpine && x in 92..108 && (y in 70..95 || y in 120..145 || y in 170..195) -> 235 // bright letters inside dark spine
                inSpine -> 35
                else -> 195
            }
        }
        val quad = requireNotNull(PerspectiveGeometry.detectQuad(luma, w, h, PhotoCropMode.SPINE))
        assertEquals(78f, quad[0].x, 4f)
        assertEquals(122f, quad[1].x, 4f)
        assertEquals(24f, quad[0].y, 5f)
        assertEquals(256f, quad[2].y, 5f)
    }

    @Test
    fun liveScannerHelpersSmoothNormalizeAndBoundExportsForServer() {
        assertEquals(1400, PerspectiveGeometry.maxEdgeFor(PhotoCropMode.SPINE))
        assertEquals(1600, PerspectiveGeometry.maxEdgeFor(PhotoCropMode.COVER))
        assertEquals(92, PerspectiveGeometry.jpegQualityFor(PhotoCropMode.SPINE))
        assertEquals(92, PerspectiveGeometry.jpegQualityFor(PhotoCropMode.COVER))

        val norm = PerspectiveGeometry.normalizeQuad(square, 200, 200)
        assertEquals(0.05f, norm[0].x, 0.001f)
        assertEquals(0.80f, norm[2].y, 0.001f)
        val restored = PerspectiveGeometry.denormalizeQuad(norm, 1000, 1000)
        assertEquals(50f, restored[0].x, 1f)
        assertEquals(800f, restored[2].y, 1f)

        val shifted = norm.map { QuadPoint(it.x + 0.02f, it.y) }
        assertEquals(0.02f, PerspectiveGeometry.quadDelta(norm, shifted), 0.001f)
        val smooth = PerspectiveGeometry.smoothQuad(norm, shifted, alpha = 0.5f)
        assertEquals(norm[0].x + 0.01f, smooth[0].x, 0.001f)

        // 320x240 landscape NV21 rotated 90 deg -> portrait upright frame <= 160px tall
        val nv21 = ByteArray(320 * 240) { 120 }
        val gray = requireNotNull(PerspectiveGeometry.downsampleNv21Oriented(nv21, 320, 240, 90, mirrorX = false, maxEdge = 160))
        assertEquals(120, gray.width)
        assertEquals(160, gray.height)
        assertEquals(120, gray.luma[0])
    }

    @Test
    fun touchStabilizerCancelsLiftOffDriftButPreservesFastDrags() {
        val diag = 2000f
        val aimed = listOf(QuadPoint(100f, 100f), QuadPoint(300f, 100f), QuadPoint(300f, 900f), QuadPoint(100f, 900f))
        val drifted = listOf(QuadPoint(118f, 114f), QuadPoint(300f, 100f), QuadPoint(300f, 900f), QuadPoint(100f, 900f))

        // Scenario 1: User aims steady, pauses 100ms, then finger drifts 30ms before ACTION_UP -> restores aimed.
        val st1 = br.com.viblio.camera.TouchStabilizer()
        st1.reset(1000L, aimed)
        st1.record(1120L, aimed)
        st1.record(1210L, drifted)
        assertEquals(aimed, st1.resolveRelease(1240L, drifted, diag))

        // Scenario 2: User drags fast continuously without pausing -> keeps final position.
        val st2 = br.com.viblio.camera.TouchStabilizer()
        val p0 = aimed
        val p1 = aimed.map { QuadPoint(it.x + 120f, it.y) }
        val p2 = aimed.map { QuadPoint(it.x + 240f, it.y) }
        val p3 = aimed.map { QuadPoint(it.x + 320f, it.y) }
        st2.reset(1000L, p0)
        st2.record(1050L, p1)
        st2.record(1100L, p2)
        st2.record(1140L, p3)
        assertEquals(p3, st2.resolveRelease(1160L, p3, diag))

        // Precision damping: small tremor is attenuated to 0.55x; fast drag remains 1.0x.
        val (sx, sy) = br.com.viblio.camera.TouchStabilizer.dampenDelta(2f, 0f, fineThresholdPx = 10f)
        assertEquals(1.1f, sx, 0.01f)
        assertEquals(0f, sy, 0.01f)
        val (fx, _) = br.com.viblio.camera.TouchStabilizer.dampenDelta(30f, 0f, fineThresholdPx = 10f)
        assertEquals(30f, fx, 0.01f)
    }

    @Test
    fun mediaRefCollectionExtractsValidDistinctHashesAndIgnoresLegacyOrInvalidValues() {
        val h1 = "a".repeat(64)
        val h2 = "b".repeat(64)
        val h3 = "0123456789abcdef".repeat(4)
        val snapshot = com.google.gson.JsonParser.parseString(
            """
            {
              "library": {
                "books": [
                  { "id": "b1", "coverData": "media:$h1", "spineData": "media:$h2" },
                  { "id": "b2", "coverData": "media:$h1", "spineData": "data:image/jpeg;base64,/9j/4AAQ" },
                  { "id": "b3", "coverData": "media:../escape", "spineData": "media:${"A".repeat(64)}" }
                ],
                "collections": [
                  { "id": "c1", "coverData": "media:$h3" },
                  { "id": "c2", "coverData": "" }
                ]
              }
            }
            """.trimIndent()
        ).asJsonObject
        val hashes = br.com.viblio.account.AccountLibrary.collectMediaHashes(snapshot)
        assertEquals(listOf(h1, h2, h3), hashes)
        assertTrue(LibraryJson.MEDIA_REF_RE.matches("media:$h1"))
        assertFalse(LibraryJson.MEDIA_REF_RE.matches("media:short"))
        assertFalse(LibraryJson.MEDIA_REF_RE.matches("media:${"A".repeat(64)}"))
        assertFalse(LibraryJson.MEDIA_REF_RE.matches("data:image/jpeg;base64,abc"))
    }

    @Test
    fun spineThemesResolveGenresMixDeterministicallyAndPreserveContrast() {
        assertEquals(SpineGenreTheme.FANTASIA, SpineThemes.resolveGenre("Fantasia Sombria"))
        assertEquals(SpineGenreTheme.TERROR, SpineThemes.resolveGenre("Terror - Gótico"))
        assertEquals(SpineGenreTheme.SCIFI, SpineThemes.resolveGenre("Ficção Científica"))
        assertEquals(SpineGenreTheme.SCIFI, SpineThemes.resolveGenre("Sci-Fi"))
        assertEquals(SpineGenreTheme.ROMANCE, SpineThemes.resolveGenre("Romance Histórico"))
        assertEquals(SpineGenreTheme.SUSPENSE, SpineThemes.resolveGenre("Suspense / Mistério"))
        assertEquals(SpineGenreTheme.NAO_FICCAO, SpineThemes.resolveGenre("Não-Ficção - História"))
        assertEquals(SpineGenreTheme.AVENTURA, SpineThemes.resolveGenre("Aventura"))
        assertEquals(SpineGenreTheme.DISTOPIA, SpineThemes.resolveGenre("Distopia"))
        assertEquals(SpineGenreTheme.CLASSICOS, SpineThemes.resolveGenre("Clássicos"))
        assertEquals(SpineGenreTheme.MANGA, SpineThemes.resolveGenre("", kind = "manga"))
        assertEquals(SpineGenreTheme.MANGA, SpineThemes.resolveGenre("", kind = "comic"))
        assertEquals(SpineGenreTheme.DEFAULT, SpineThemes.resolveGenre("Culinária"))
        assertEquals(SpineGenreTheme.DEFAULT, SpineThemes.resolveGenre(""))

        // Deterministic A/B/C variant: same ID is always stable; across IDs all 3 variants appear.
        val v1 = SpineThemes.deterministicVariant("book-fixed-id-42")
        assertEquals(v1, SpineThemes.deterministicVariant("book-fixed-id-42"))
        assertTrue(v1 in 0..2)
        val seen = (1..40).map { SpineThemes.deterministicVariant("book-$it") }.toSet()
        assertEquals(setOf(0, 1, 2), seen)

        // Asset names match shipped WebP files for all 10 genres and return null for DEFAULT.
        val asset = requireNotNull(SpineThemes.assetName(SpineGenreTheme.FANTASIA, "book-fixed-id-42"))
        assertTrue(asset.matches(Regex("spines/fantasia_[abc]\\.webp")))
        assertNull(SpineThemes.assetName(SpineGenreTheme.DEFAULT, "book-fixed-id-42"))

        // Foil contrast remains readable on both dark and light cover colors.
        for (theme in SpineGenreTheme.entries) {
            val darkCover = ShelfSpines.materialFace(0xFF1F2D24.toInt())
            val lightCover = ShelfSpines.materialFace(0xFFF0E6D2.toInt())
            assertTrue(CoverColors.contrast(darkCover, SpineThemes.foilColor(darkCover, theme)) >= 3.0)
            assertTrue(CoverColors.contrast(lightCover, SpineThemes.foilColor(lightCover, theme)) >= 3.0)
        }

        // Organic editorial height variation stays bounded 0..12 dp and deterministic.
        val off = SpineThemes.organicHeightOffsetDp("book-fixed-id-42", 240)
        assertEquals(off, SpineThemes.organicHeightOffsetDp("book-fixed-id-42", 240))
        assertTrue(off in 0..12)
        assertEquals(ShelfWoodStyle.EDITORIAL, ShelfWoodStyle.of("editorial"))
        assertEquals(ShelfWoodStyle.COLLECTOR, ShelfWoodStyle.of("unknown"))
        assertEquals(SpineDesignMode.STANDARD, SpineDesignMode.of("standard"))
        assertEquals(SpineDesignMode.BY_GENRE, SpineDesignMode.of(null))
    }
}
