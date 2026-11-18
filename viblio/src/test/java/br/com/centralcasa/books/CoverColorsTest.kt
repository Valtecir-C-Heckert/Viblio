package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class CoverColorsTest {
    @Test fun ignoresWhitePaperAndBlackTextAroundBlueArtwork() {
        val pixels = IntArray(100) { if (it < 70) -1 else if (it < 90) 0xff000000.toInt() else 0xff305a90.toInt() }
        assertEquals(0xff305a90.toInt(), CoverColors.dominant(pixels))
    }
    @Test fun dominantArtWinsOverTinySaturatedDetails() {
        val pixels = IntArray(100) { if (it < 95) 0xff345b83.toInt() else 0xffff2222.toInt() }
        assertEquals(0xff345b83.toInt(), CoverColors.dominant(pixels))
    }
    @Test fun neutralLetteringDoesNotHideSignificantArtworkHue() {
        assertEquals(0xffc87a30.toInt(), CoverColors.dominant(IntArray(100) {
            if (it < 70) 0xffb0b0b0.toInt() else 0xffc87a30.toInt()
        }))
    }
    @Test fun tinyColorDetailDoesNotRecolorNeutralCover() {
        assertEquals(0xff606060.toInt(), CoverColors.dominant(IntArray(100) {
            if (it < 98) 0xff606060.toInt() else 0xffee2222.toInt()
        }))
    }
    @Test fun monochromeCoversStayNeutral() {
        val color = CoverColors.accent(CoverColors.dominant(IntArray(100) { 0xff606060.toInt() })!!)
        assertEquals(color ushr 16 and 255, color and 255)
        assertEquals(color ushr 8 and 255, color and 255)
    }
    @Test fun missingUsefulArtworkFallsBackInsteadOfInventingColor() {
        assertNull(CoverColors.dominant(intArrayOf(-1, 0xff000000.toInt(), 0x003399ff)))
        assertNull(CoverColors.dominant(intArrayOf()))
    }
    @Test fun orangeSurfaceKeepsTheCoverSaturationWithoutWhiteBlending() {
        val surface = CoverColors.surface(0xffd0512d.toInt())
        val r = surface ushr 16 and 255; val g = surface ushr 8 and 255; val b = surface and 255
        assertTrue(r in 100..112)
        assertTrue(r > g * 2)
        assertTrue(g > b)
        assertEquals(45.0 / 208, b.toDouble() / r, .02)
    }
    @Test fun neutralSurfaceStaysNeutral() {
        val surface = CoverColors.surface(0xff909090.toInt())
        assertEquals(surface ushr 16 and 255, surface and 255)
    }
    @Test fun darkArtworkGetsVisibleAccentAndDarkSurfaceForWhiteText() {
        val accent = CoverColors.accent(0xff18334b.toInt())
        assertTrue(maxOf(accent ushr 16 and 255, accent ushr 8 and 255, accent and 255) >= 179)
        val surface = CoverColors.surface(0xff18334b.toInt())
        assertTrue(maxOf(surface ushr 16 and 255, surface ushr 8 and 255, surface and 255) <= 112)
        assertTrue((accent and 255) > (accent ushr 16 and 255))
    }
    @Test fun coverBordersContrastOnDarkAndLightSurfaces(){
        for(base in listOf(0xff152f50.toInt(),0xffffcc33.toInt(),0xff0000ff.toInt(),0xff666666.toInt(),0xff00aa66.toInt())){
            for(background in listOf(CoverColors.surface(base),0xffeeeeee.toInt()))assertTrue(CoverColors.contrast(CoverColors.border(base,background),background)>=3.0)
        }
    }
    @Test fun contrastingCoverToneIsNotNeedlesslyWhitened(){assertEquals(0xffffcc33.toInt(),CoverColors.border(0xffffcc33.toInt(),0xff111111.toInt()))}
    @Test fun borderKeepsNeutralCoversNeutral(){val border=CoverColors.border(0xff444444.toInt(),0xff333333.toInt());assertEquals(border ushr 16 and 255,border and 255)}
    @Test fun readingThemeAccentIsReadableOnDarkControls(){
        for(color in listOf(0xff050522.toInt(),0xff0000cc.toInt(),0xffcc1111.toInt(),0xff00aa22.toInt(),0xff666666.toInt(),0xffc6a15b.toInt())){
            val theme=CoverColors.border(color,0xff29211c.toInt(),4.6)
            assertTrue(CoverColors.contrast(theme,0xff29211c.toInt())>=4.5)
            assertTrue(CoverColors.contrast(theme,0xff110f0f.toInt())>=4.5)
        }
    }
}
