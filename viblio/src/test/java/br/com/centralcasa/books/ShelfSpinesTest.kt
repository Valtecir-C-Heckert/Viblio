package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class ShelfSpinesTest {
    @Test fun widthIncreasesOnlyOnHundredPageBoundaries(){
        assertEquals(28,ShelfSpines.width(1));assertEquals(28,ShelfSpines.width(100))
        assertEquals(32,ShelfSpines.width(101));assertEquals(32,ShelfSpines.width(200))
        assertEquals(36,ShelfSpines.width(201))
        assertEquals(104,ShelfSpines.width(2000))
        assertTrue(ShelfSpines.width(2000)>ShelfSpines.width(1000))
    }
    @Test fun unknownAndExtremePageCountsRemainBounded(){
        assertEquals(34,ShelfSpines.width(0,false));assertEquals(52,ShelfSpines.width(2000,collection=true))
        assertEquals(28,ShelfSpines.width(Int.MIN_VALUE))
        assertEquals(428,ShelfSpines.width(Int.MAX_VALUE))
    }
    @Test fun textHasAtLeastNormalTextContrastAcrossCoverColours(){
        // Sample neutrals, saturated artwork and intermediate colours, including old weak spots.
        for(r in 0..255 step 17)for(g in 0..255 step 17)for(b in 0..255 step 17){
            val color=(255 shl 24) or (r shl 16) or (g shl 8) or b
            assertTrue(CoverColors.contrast(color,ShelfSpines.ink(color))>=4.5)
        }
    }
    @Test fun authorsKeepHorizontalFirstAndRemainingNames(){
        assertEquals("George\nOrwell",ShelfSpines.authorLines(" George  Orwell "))
        assertEquals("Machado\nde Assis",ShelfSpines.authorLines("Machado de Assis"))
        assertEquals("J. R. R.\nTolkien",ShelfSpines.authorLines("J. R. R. Tolkien"))
        assertEquals("",ShelfSpines.authorLines(" "))
        assertEquals("Homero",ShelfSpines.authorLines("Homero"))
    }
    @Test fun texturedFaceRetainsContrastEvenAtGrainExtremes(){
        for(r in 0..255 step 17)for(g in 0..255 step 17)for(b in 0..255 step 17){
            val cover=(255 shl 24) or (r shl 16) or (g shl 8) or b
            val face=ShelfSpines.materialFace(cover);val ink=ShelfSpines.materialInk(face)
            assertTrue(CoverColors.contrast(ShelfSpines.textureDark(face),ink)>=4.5)
            assertTrue(CoverColors.contrast(ShelfSpines.textureLight(face),ink)>=4.5)
        }
    }
}
