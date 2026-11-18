package br.com.centralcasa.books

/** Visual page bands; these are not physical measurements of paper thickness. */
object ShelfSpines {
    fun width(pages:Int,known:Boolean=true,collection:Boolean=false):Int = when {
        collection -> 52
        !known -> 34
        else -> 28 + ((pages.coerceAtLeast(1)-1)/100).coerceAtMost(100)*4
    }
    fun ink(color:Int):Int = if(CoverColors.contrast(color,-1)>=CoverColors.contrast(color,0xff000000.toInt())) -1 else 0xff000000.toInt()
    fun authorLines(raw:String):String {
        val words=raw.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if(words.size<2)return words.joinToString(" ")
        val initials=words.takeWhile {it.length==2 && it[0].isLetter() && it[1]=='.'}.size
        val first=if(initials in 1 until words.size)initials else 1
        return words.take(first).joinToString(" ")+"\n"+words.drop(first).joinToString(" ")
    }
    fun textureDark(color:Int)=blend(color,0xff000000.toInt(),.08)
    fun textureLight(color:Int)=blend(color,-1,.08)
    fun materialInk(face:Int):Int=if(CoverColors.contrast(textureLight(face),-1)>=CoverColors.contrast(textureDark(face),0xff000000.toInt())) -1 else 0xff000000.toInt()
    fun materialFace(cover:Int):Int {
        fun readable(face:Int):Boolean {val ink=materialInk(face);return minOf(CoverColors.contrast(textureLight(face),ink),CoverColors.contrast(textureDark(face),ink))>=4.6}
        if(readable(cover))return cover
        val target=if(materialInk(cover)==-1)0xff000000.toInt() else -1
        var low=0.0;var high=1.0
        repeat(16){val middle=(low+high)/2;if(readable(blend(cover,target,middle)))high=middle else low=middle}
        return blend(cover,target,high)
    }
    private fun blend(from:Int,to:Int,amount:Double):Int {
        fun channel(shift:Int)=(((from ushr shift and 255)*(1-amount)+(to ushr shift and 255)*amount).toInt().coerceIn(0,255))
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
