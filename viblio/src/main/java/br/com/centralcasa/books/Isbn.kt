package br.com.centralcasa.books

/** Only ISBN data is accepted; barcode contents can never become an executable URI. */
object Isbn {
    fun variants(raw:String):List<String> {
        val value=normalize(raw) ?: return listOf(raw)
        val other=if(value.length==10){
            val head="978"+value.take(9)
            val sum=head.mapIndexed {i,c->(c-'0')*if(i%2==0)1 else 3}.sum()
            head+((10-sum%10)%10)
        }else if(value.startsWith("978")){
            val head=value.substring(3,12);val digit=(11-head.mapIndexed {i,c->(c-'0')*(10-i)}.sum()%11)%11
            head+if(digit==10)"X" else digit.toString()
        }else return listOf(value)
        return listOf(value,other)
    }
    fun normalize(raw:String):String? {
        if(raw.length>64)return null
        val value=raw.replace("-","").filterNot(Char::isWhitespace).uppercase()
        return when {
            value.length==13 && value.all {it in '0'..'9'} && (value.startsWith("978") || value.startsWith("979")) && value.mapIndexed {i,c->(c-'0')*if(i%2==0)1 else 3}.sum()%10==0 -> value
            value.length==10 && value.take(9).all {it in '0'..'9'} && (value.last() in '0'..'9' || value.last()=='X') && value.mapIndexed {i,c->(if(c=='X')10 else c-'0')*(10-i)}.sum()%11==0 -> value
            else->null
        }
    }
}
