package br.com.viblio.scanner

import br.com.centralcasa.books.Isbn
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test

class BarcodeDecoderTest {
    @Test fun validatesAndNormalizesBookCodes(){
        assertEquals("9780134685991",Isbn.normalize("978-0-13-468599-1"))
        assertEquals("0134685997",Isbn.normalize("0 134685997"))
        assertEquals("080442957X",Isbn.normalize("080442957x"))
        listOf("9780134685992","4006381333931","https://example.test/9780134685991","9".repeat(1000),"", "0134685990").forEach {assertNull(it,Isbn.normalize(it))}
    }
    @Test fun searchesTheEquivalentTenAndThirteenDigitEdition(){
        assertEquals(listOf("9780134685991","0134685997"),Isbn.variants("9780134685991"))
        assertEquals(listOf("0134685997","9780134685991"),Isbn.variants("0134685997"))
    }
    private fun barcode(value:String):ByteArray {
        val matrix=MultiFormatWriter().encode(value,BarcodeFormat.EAN_13,640,180)
        return ByteArray(640*180){i->if(matrix[i%640,i/640])0 else 255.toByte()}
    }
    @Test fun readsUprightAndRotatedLocally(){
        val data=barcode("9780134685991");val decoder=BarcodeDecoder()
        assertEquals("9780134685991",decoder.decode(data,640,180))
        val rotated=ByteArray(data.size)
        for(y in 0 until 180)for(x in 0 until 640)rotated[x*180+179-y]=data[y*640+x]
        assertEquals("9780134685991",decoder.decode(rotated,180,640))
    }
    @Test fun ignoresNonBooksAndInvalidOrOversizedFrames(){
        val decoder=BarcodeDecoder()
        assertNull(decoder.decode(barcode("4006381333931"),640,180))
        assertNull(decoder.decode(ByteArray(1),640,480))
        assertNull(decoder.decode(ByteArray(1),Int.MAX_VALUE,Int.MAX_VALUE))
        assertNull(decoder.decode(ByteArray(640*480){255.toByte()},640,480))
    }
}
