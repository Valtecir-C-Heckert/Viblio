package br.com.centralcasa.books

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ReadingDate(val id: String = UUID.randomUUID().toString(), val bookId: String, val day: String) {
    fun validated(): ReadingDate { require(id.isNotBlank() && id.length <= 200 && bookId.isNotBlank()); ReadingDays.validate(day); return this }
}

/** Calendar dates have no UTC conversion: a manually entered day stays the same on another phone. */
object ReadingDays {
    private fun formatter(zone: TimeZone = TimeZone.getDefault()) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = zone }
    fun validate(day: String): String {
        require(day.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "Data inválida." }
        val f = formatter(); val date = requireNotNull(f.parse(day)) { "Data inválida." }
        require(f.format(date) == day) { "Data inválida." }; return day
    }
    fun at(timestamp: Long, zone: TimeZone = TimeZone.getDefault()) = formatter(zone).format(java.util.Date(timestamp))
    fun label(day: String): String = SimpleDateFormat("dd 'de' MMM 'de' yyyy", Locale("pt", "BR")).format(formatter().parse(validate(day))!!)
    fun range(first: String, last: String): List<String> {
        validate(first); validate(last); require(first <= last) { "A data final deve ser igual ou posterior à inicial." }
        val f = formatter(TimeZone.getTimeZone("UTC")); val c = Calendar.getInstance(f.timeZone).apply { time = f.parse(first)!! }
        val result = mutableListOf<String>()
        while (f.format(c.time) <= last) {
            require(result.size < 3660) { "Escolha um período de até 3.660 dias." }
            result.add(f.format(c.time)); c.add(Calendar.DAY_OF_MONTH, 1)
        }; return result
    }
}
