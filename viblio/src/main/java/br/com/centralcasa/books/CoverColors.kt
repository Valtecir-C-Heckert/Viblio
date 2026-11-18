package br.com.centralcasa.books

/** Quantized dominant artwork color, excluding paper whites, deep black and transparency. */
object CoverColors {
    private data class Bucket(var count: Int = 0, var red: Long = 0, var green: Long = 0, var blue: Long = 0)
    fun dominant(pixels: IntArray): Int? {
        val bins = mutableMapOf<Int, Bucket>()
        pixels.forEach { color ->
            if (color ushr 24 < 128) return@forEach
            val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
            val high = maxOf(r, g, b); val low = minOf(r, g, b)
            if (high < 24 || low > 235) return@forEach
            val key = (r / 32 shl 6) or (g / 32 shl 3) or (b / 32)
            bins.getOrPut(key) { Bucket() }.apply { count++; red += r; green += g; blue += b }
        }
        // Prefer artwork hues when they occupy a meaningful area; retain neutral covers.
        val chromatic = bins.values.filter {
            val channels = listOf(it.red.toDouble() / it.count, it.green.toDouble() / it.count, it.blue.toDouble() / it.count)
            channels.max() - channels.min() >= 32 && (channels.max() - channels.min()) / channels.max() >= .25
        }
        val candidates = if (chromatic.sumOf { it.count } >= bins.values.sumOf { it.count } * .15) chromatic else bins.values
        val winner = candidates.maxByOrNull {
            val r = it.red.toDouble() / it.count; val g = it.green.toDouble() / it.count; val b = it.blue.toDouble() / it.count
            val high = maxOf(r, g, b)
            val saturation = if (high == 0.0) 0.0 else (high - minOf(r, g, b)) / high
            it.count * (1.0 + saturation * .35)
        } ?: return null
        return rgb((winner.red / winner.count).toInt(), (winner.green / winner.count).toInt(), (winner.blue / winner.count).toInt())
    }
    fun accent(color: Int): Int {
        val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
        val high = maxOf(r, g, b)
        val amount = if (high < 180) (180.0 - high) / (255.0 - high) else .12
        return rgb((r + (255 - r) * amount).toInt(), (g + (255 - g) * amount).toInt(), (b + (255 - b) * amount).toInt())
    }
    /** Preserve the cover hue instead of tinting the surface with the whitened border color. */
    fun surface(color: Int): Int {
        val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
        val high = maxOf(r, g, b)
        if (high == 0) return rgb(0, 0, 0)
        val level = (high * .55).coerceIn(72.0, 112.0)
        val scale = level / high
        return rgb((r * scale).toInt(), (g * scale).toInt(), (b * scale).toInt())
    }
    fun contrast(first:Int,second:Int):Double {
        fun luminance(color:Int):Double {
            fun channel(shift:Int):Double {val value=(color ushr shift and 255)/255.0;return if(value<=.04045)value/12.92 else Math.pow((value+.055)/1.055,2.4)}
            return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
        }
        val a=luminance(first);val b=luminance(second)
        return (maxOf(a,b)+.05)/(minOf(a,b)+.05)
    }
    /** Adjust lightness while retaining the artwork hue/saturation. */
    fun border(cover:Int,background:Int,minimum:Double=3.1):Int {
        require(minimum in 1.0..21.0)
        val base=cover or (255 shl 24)
        if(contrast(base,background)>=minimum)return base
        val r=(base ushr 16 and 255)/255.0;val g=(base ushr 8 and 255)/255.0;val b=(base and 255)/255.0
        val high=maxOf(r,g,b);val low=minOf(r,g,b);val delta=high-low;val light=(high+low)/2
        val saturation=if(delta==0.0)0.0 else delta/(1-kotlin.math.abs(2*light-1))
        val hue=if(delta==0.0)0.0 else when(high){r->((g-b)/delta+6)%6;g->(b-r)/delta+2;else->(r-g)/delta+4}
        fun shade(l:Double):Int {
            val c=(1-kotlin.math.abs(2*l-1))*saturation;val x=c*(1-kotlin.math.abs(hue%2-1));val m=l-c/2
            val channels=when(hue.toInt()){0->doubleArrayOf(c,x,0.0);1->doubleArrayOf(x,c,0.0);2->doubleArrayOf(0.0,c,x);3->doubleArrayOf(0.0,x,c);4->doubleArrayOf(x,0.0,c);else->doubleArrayOf(c,0.0,x)}
            return rgb(((channels[0]+m)*255).toInt().coerceIn(0,255),((channels[1]+m)*255).toInt().coerceIn(0,255),((channels[2]+m)*255).toInt().coerceIn(0,255))
        }
        fun candidate(end:Double):Pair<Int,Double>? {
            if(contrast(shade(end),background)<minimum)return null
            var left=0.0;var right=1.0
            repeat(16){val middle=(left+right)/2;if(contrast(shade(light+(end-light)*middle),background)>=minimum)right=middle else left=middle}
            val target=light+(end-light)*right
            return shade(target) to kotlin.math.abs(target-light)
        }
        return listOfNotNull(candidate(0.0),candidate(1.0)).minByOrNull {it.second}?.first ?: accent(base)
    }
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
}
