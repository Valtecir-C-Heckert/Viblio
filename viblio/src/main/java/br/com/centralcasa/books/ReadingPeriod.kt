package br.com.centralcasa.books

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ReadingPeriodUnit(val label:String){DAY("Dia"),WEEK("Semana"),MONTH("Mês"),YEAR("Ano")}
data class ReadingPeriodRange(val start:String,val endExclusive:String,val label:String){fun contains(day:String)=day>=start && day<endExclusive}
object ReadingPeriods {
    private fun calendar(day:String)=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        time=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply {timeZone=TimeZone.getTimeZone("UTC");isLenient=false}.parse(ReadingDays.validate(day))!!
    }
    private fun format(c:Calendar,pattern:String)=SimpleDateFormat(pattern,Locale("pt","BR")).apply {timeZone=c.timeZone}.format(c.time)
    private fun step(c:Calendar,unit:ReadingPeriodUnit,n:Int){when(unit){ReadingPeriodUnit.DAY->c.add(Calendar.DAY_OF_YEAR,n);ReadingPeriodUnit.WEEK->c.add(Calendar.DAY_OF_YEAR,7*n);ReadingPeriodUnit.MONTH->c.add(Calendar.MONTH,n);ReadingPeriodUnit.YEAR->c.add(Calendar.YEAR,n)}}
    fun range(day:String,unit:ReadingPeriodUnit):ReadingPeriodRange {
        val c=calendar(day)
        when(unit){ReadingPeriodUnit.WEEK->c.add(Calendar.DAY_OF_YEAR,-((c.get(Calendar.DAY_OF_WEEK)+5)%7));ReadingPeriodUnit.MONTH->c.set(Calendar.DAY_OF_MONTH,1);ReadingPeriodUnit.YEAR->c.set(Calendar.DAY_OF_YEAR,1);else->Unit}
        val start=format(c,"yyyy-MM-dd");val end=c.clone() as Calendar;step(end,unit,1)
        val label=when(unit){ReadingPeriodUnit.DAY->format(c,"dd 'de' MMM 'de' yyyy");ReadingPeriodUnit.MONTH->format(c,"MMMM 'de' yyyy");ReadingPeriodUnit.YEAR->format(c,"yyyy");ReadingPeriodUnit.WEEK->{val last=end.clone() as Calendar;last.add(Calendar.DAY_OF_YEAR,-1);format(c,"dd/MM/yyyy")+" – "+format(last,"dd/MM/yyyy")}}
        return ReadingPeriodRange(start,format(end,"yyyy-MM-dd"),label)
    }
    fun shift(day:String,unit:ReadingPeriodUnit,n:Int):String {val c=calendar(range(day,unit).start);step(c,unit,n);return format(c,"yyyy-MM-dd")}
    fun months(day:String)= (0..11).map {n->val c=calendar(range(day,ReadingPeriodUnit.YEAR).start);c.add(Calendar.MONTH,n);range(format(c,"yyyy-MM-dd"),ReadingPeriodUnit.MONTH)}
}
data class PeriodReading(val bookIds:Set<String>,val pages:Long,val durationMs:Long,val sessions:List<ReadingSession>,val dates:List<ReadingDate>){
    companion object {
        fun from(books:List<LibraryBook>,sessions:List<ReadingSession>,dates:List<ReadingDate>,range:ReadingPeriodRange):PeriodReading {
            val byId=books.filterNot {it.archived}.associateBy {it.id}
            val timed=sessions.filter {it.bookId in byId && range.contains(it.readingDay ?: ReadingDays.at(it.startedAt))}
            val manual=dates.filter {it.bookId in byId && range.contains(it.day)}
            return PeriodReading((timed.map {it.bookId}+manual.map {it.bookId}).toSet(),timed.filter {byId[it.bookId]?.pagesKnown==true}.sumOf {it.pages.toLong()},timed.sumOf {it.durationMs},timed,manual)
        }
    }
}
