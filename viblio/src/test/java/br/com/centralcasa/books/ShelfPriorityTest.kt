package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class ShelfPriorityTest {
    private fun book(id: String, title: String, genre: String = "Fantasia", saga: String = "Saga", volume: Int? = null) =
        LibraryBook(id = id, title = title, genre = genre, saga = saga, sequenceNumber = volume, totalPages = 100)
    @Test fun prioritiesCanChangeWhichFieldBreaksTheTie() {
        val volume2 = book("2", "A chegada", volume = 2)
        val volume1 = book("1", "Z partida", volume = 1)
        val otherGenre = book("d", "1984", "Distopia", "", null)
        val input = listOf(volume2, volume1, otherGenre)
        assertEquals(listOf(otherGenre, volume1, volume2), ShelfFilters(order = ShelfOrder.defaults).select(input, null))
        assertEquals(listOf(otherGenre, volume2, volume1), ShelfFilters(order = listOf(ShelfOrder(ShelfField.GENRE), ShelfOrder(ShelfField.TITLE), ShelfOrder(ShelfField.SEQUENCE))).select(input, null))
    }
    @Test fun descendingKeepsMissingSagaAndVolumeLastAndUsesAccentFreeNames() {
        val a = book("a", "A", saga = "Água", volume = 1)
        val z = book("z", "Z", saga = "Zebra", volume = 2)
        val missing = book("m", "0", saga = "", volume = null)
        assertEquals(listOf(z, a, missing), ShelfFilters(order = listOf(ShelfOrder(ShelfField.SAGA, true))).select(listOf(missing, a, z), null))
        assertEquals(listOf(z, a, missing), ShelfFilters(order = listOf(ShelfOrder(ShelfField.SEQUENCE, true))).select(listOf(a, missing, z), null))
        assertEquals(listOf(a, z, missing), ShelfFilters(order = listOf(ShelfOrder(ShelfField.SAGA))).select(listOf(z, missing, a), null))
    }
    @Test fun sagaCombinesWithOtherFiltersAndDoesNotIncludeUnknownMetadata() {
        val yes = book("a", "Épico", saga = "Água", volume = 1).copy(currentPage = 10)
        val no = yes.copy(id = "b", saga = "Outro")
        val filters = ShelfFilters(query = "epico", genre = "fantasia", saga = "agua", status = ShelfStatus.READING, order = ShelfOrder.defaults)
        assertEquals(listOf(yes), filters.select(listOf(no, yes, yes.copy(id = "c", saga = "")), null))
        assertEquals(3, filters.activeCount)
    }
    @Test fun savedCriteriaRoundTripAndRejectBrokenEntries() {
        val order = listOf(ShelfOrder(ShelfField.SAGA, true), ShelfOrder(ShelfField.SEQUENCE), ShelfOrder(ShelfField.PROGRESS, true))
        assertEquals(order, ShelfOrder.decode(ShelfOrder.encode(order)))
        assertEquals(listOf(ShelfOrder(ShelfField.SAGA)), ShelfOrder.decode("garbage,SAGA:false,SAGA:true,TITLE:no"))
    }
}
