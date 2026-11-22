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

        // Deterministic A/B/C variant: same ID is stable; same saga always gets the exact same art regardless of bookId/case/accents.
        val v1 = SpineThemes.deterministicVariant("book-fixed-id-42")
        assertEquals(v1, SpineThemes.deterministicVariant("book-fixed-id-42"))
        assertTrue(v1 in 0..2)
        val seen = (1..40).map { SpineThemes.deterministicVariant("book-$it") }.toSet()
        assertEquals(setOf(0, 1, 2), seen)
        assertEquals(
            SpineThemes.assetName(SpineGenreTheme.FANTASIA, "vol-1", saga = "A Crônica do Matador do Rei"),
            SpineThemes.assetName(SpineGenreTheme.FANTASIA, "vol-2", saga = "a cronica do matador do rei")
        )
        assertEquals(
            SpineThemes.assetName(SpineGenreTheme.MANGA, "m-1", collectionId = "col-99"),
            SpineThemes.assetName(SpineGenreTheme.MANGA, "m-2", collectionId = "col-99")
        )

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

        // Bookcase styles & 4 independent spine modes (with backward compatibility for "standard").
        assertEquals(ShelfWoodStyle.EDITORIAL, ShelfWoodStyle.of("editorial"))
        assertEquals(ShelfWoodStyle.COLLECTOR, ShelfWoodStyle.of("unknown"))
        assertEquals(SpineDesignMode.ORIGINAL, SpineDesignMode.of("original"))
        assertEquals(SpineDesignMode.ANTIQUE_BROWN, SpineDesignMode.of("antique"))
        assertEquals(SpineDesignMode.ANTIQUE_BROWN, SpineDesignMode.of("standard"))
        assertEquals(SpineDesignMode.EDITORIAL, SpineDesignMode.of("editorial"))
        assertEquals(SpineDesignMode.BY_GENRE, SpineDesignMode.of(null))

        // Wall-to-wall ShelfRowPacker: full rows absorb remaining pixels across ALL books in the row so sum == innerWidth exactly.
        val packed = ShelfRowPacker.pack(listOf(120, 140, 100, 110, 130, 90), innerWidthPx = 400)
        assertEquals(2, packed.size)
        assertEquals(3, packed[0].size) // 120+140+100=360 <= 400, next (110) moves to row 2
        assertEquals(400, packed[0].sumOf { it.widthPx }) // absorbed 40px across all 3 books!
        assertTrue(packed[0][1].widthPx > packed[0][0].widthPx && packed[0][0].widthPx > packed[0][2].widthPx)
        // Last row is 110+130+90=330 (82.5% of 400) -> also closes wall-to-wall cleanly!
        assertEquals(400, packed[1].sumOf { it.widthPx })

        // Half-empty last row (< 82% of innerWidth) keeps natural book widths instead of stretching 2 books giant.
        val sparse = ShelfRowPacker.pack(listOf(200, 150, 80, 90), innerWidthPx = 400)
        assertEquals(2, sparse.size)
        assertEquals(400, sparse[0].sumOf { it.widthPx })
        assertEquals(170, sparse[1].sumOf { it.widthPx })
    }

    @Test
    fun friendCoverBoundsAndAccountSyncStatus() {
        // Accepts both legacy 160x240 and sharp 360x540 friend/club thumbnails while rejecting oversized payloads.
        assertTrue(br.com.viblio.ui.FriendCoverLoader.validDimensions(160, 240))
        assertTrue(br.com.viblio.ui.FriendCoverLoader.validDimensions(360, 540))
        assertTrue(br.com.viblio.ui.FriendCoverLoader.validDimensions(480, 720))
        assertFalse(br.com.viblio.ui.FriendCoverLoader.validDimensions(0, 240))
        assertFalse(br.com.viblio.ui.FriendCoverLoader.validDimensions(481, 540))
        assertFalse(br.com.viblio.ui.FriendCoverLoader.validDimensions(360, 721))

        // Clean Account card sync badge states (no JSON/backup jargon; clear sync error/conflict alerts).
        val ok = br.com.viblio.account.AccountManager.resolveSyncStatus(signedIn = true, linked = true, hasConflict = false, hasSyncError = false, pending = false)
        assertEquals("Sincronizado", ok.label)
        assertFalse(ok.isError || ok.isPending || ok.isOffline)

        val err = br.com.viblio.account.AccountManager.resolveSyncStatus(signedIn = true, linked = true, hasConflict = false, hasSyncError = true, pending = false)
        assertTrue(err.isError)
        assertTrue(err.label.contains("Erro ao sincronizar"))

        val conflict = br.com.viblio.account.AccountManager.resolveSyncStatus(signedIn = true, linked = true, hasConflict = true, hasSyncError = false, pending = false)
        assertTrue(conflict.isError)
        assertTrue(conflict.label.contains("Conflito"))

        val pending = br.com.viblio.account.AccountManager.resolveSyncStatus(signedIn = true, linked = true, hasConflict = false, hasSyncError = false, pending = true)
        assertTrue(pending.isPending)

        val offline = br.com.viblio.account.AccountManager.resolveSyncStatus(signedIn = false, linked = false, hasConflict = false, hasSyncError = false, pending = false)
        assertTrue(offline.isOffline)
        assertEquals("Estante offline", offline.label)
    }

    @Test
    fun spineVariantNeighborDeduplicationAndPageSorting() {
        // 1) Same saga volumes stay unified; adjacent different sagas/standalones of the same genre never repeat A/B/C.
        val saga1a = LibraryBook(id = "s1a", title = "Mistborn 1", saga = "Mistborn", genre = "Fantasia", totalPages = 540)
        val saga1b = LibraryBook(id = "s1b", title = "Mistborn 2", saga = "Mistborn", genre = "Fantasia", totalPages = 620)
        val saga2a = LibraryBook(id = "s2a", title = "Stormlight 1", saga = "Stormlight", genre = "Fantasia", totalPages = 1007)
        val saga2b = LibraryBook(id = "s2b", title = "Stormlight 2", saga = "Stormlight", genre = "Fantasia", totalPages = 1088)
        val solo1 = LibraryBook(id = "solo1", title = "Elantris", genre = "Fantasia", totalPages = 638)
        val solo2 = LibraryBook(id = "solo2", title = "Warbreaker", genre = "Fantasia", totalPages = 592)
        val solo3 = LibraryBook(id = "solo3", title = "Tress", genre = "Fantasia", totalPages = 380)

        for (order in listOf(
            listOf(saga1a, saga1b, saga2a, saga2b, solo1, solo2, solo3),
            listOf(solo3, saga2b, saga1a, solo1, saga2a, solo2, saga1b),
            listOf(solo1, solo2, solo3, saga1a, saga1b, saga2a, saga2b),
        )) {
            val map = SpineThemes.assignVariants(order)
            assertEquals(map[saga1a.id], map[saga1b.id])
            assertEquals(map[saga2a.id], map[saga2b.id])
            for (i in 1 until order.size) {
                val prev = order[i - 1]
                val curr = order[i]
                val sameGroup = prev.saga.isNotBlank() && prev.saga.equals(curr.saga, ignoreCase = true)
                if (!sameGroup) {
                    assertNotEquals("Adjacent different books should not repeat spine variant", map[prev.id], map[curr.id])
                }
            }
        }

        // 2) Page-count sorting (ascending & descending) always places unknown-page books last.
        val shortBook = LibraryBook(id = "p1", title = "Conto", totalPages = 120, pagesKnown = true)
        val midBook = LibraryBook(id = "p2", title = "Romance", totalPages = 350, pagesKnown = true)
        val longBook = LibraryBook(id = "p3", title = "Épico", totalPages = 980, pagesKnown = true)
        val unknownBook = LibraryBook(id = "p4", title = "Sem páginas", totalPages = 0, pagesKnown = false)
        val books = listOf(unknownBook, longBook, shortBook, midBook)

        val asc = ShelfFilters(order = listOf(ShelfOrder(ShelfField.PAGES, descending = false))).select(books, null)
        assertEquals(listOf("p1", "p2", "p3", "p4"), asc.map { it.id })

        val desc = ShelfFilters(order = listOf(ShelfOrder(ShelfField.PAGES, descending = true))).select(books, null)
        assertEquals(listOf("p3", "p2", "p1", "p4"), desc.map { it.id })

        val bySortLow = ShelfFilters(sort = ShelfSort.PAGES_LOW).select(books, null)
        assertEquals(listOf("p1", "p2", "p3", "p4"), bySortLow.map { it.id })

        val bySortHigh = ShelfFilters(sort = ShelfSort.PAGES_HIGH).select(books, null)
        assertEquals(listOf("p3", "p2", "p1", "p4"), bySortHigh.map { it.id })

        // Quick Z-A alphabetical order.
        val za = ShelfFilters(order = listOf(ShelfOrder(ShelfField.TITLE, descending = true))).select(listOf(shortBook, midBook, longBook), null)
        assertEquals(listOf("Romance", "Épico", "Conto"), za.map { it.title })
    }
}
