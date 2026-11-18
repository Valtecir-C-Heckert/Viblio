package br.com.viblio.scanner

import br.com.centralcasa.books.Isbn
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/** One bounded frame at a time, processed locally without saving camera images. */
class BarcodeDecoder {
    fun decode(luminance:ByteArray,width:Int,height:Int):String? {
        if(width !in 1..1920 || height !in 1..1920 || width.toLong()*height>1920L*1080 || luminance.size<width*height)return null
        fun read(data:ByteArray,w:Int,h:Int):String? {
            val reader=MultiFormatReader()
            return try {
                val source=PlanarYUVLuminanceSource(data,w,h,0,0,w,h,false)
                val hints=mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13,BarcodeFormat.CODE_128,BarcodeFormat.CODE_39),DecodeHintType.TRY_HARDER to true)
                Isbn.normalize(reader.decode(BinaryBitmap(HybridBinarizer(source)),hints).text)
            } catch(_:ReaderException){null} finally {reader.reset()}
        }
        read(luminance,width,height)?.let{return it}
        val rotated=ByteArray(width*height)
        for(y in 0 until height)for(x in 0 until width)rotated[x*height+height-1-y]=luminance[y*width+x]
        return read(rotated,height,width)
    }
}
