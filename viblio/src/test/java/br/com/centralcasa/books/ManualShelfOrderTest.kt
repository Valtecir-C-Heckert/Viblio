package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class ManualShelfOrderTest {
    @Test fun archivedTitlesKeepTheirSlotsDuringReordering() {
        assertEquals(listOf("c","hidden","a","new"),ManualShelfOrder.withHidden(listOf("a","hidden","c"),listOf("c","a","new")))
    }
    @Test fun moveShiftsNeighboursInBothDirections() {
        assertEquals(listOf("b","c","a","d"),ManualShelfOrder.move(listOf("a","b","c","d"),"a","c"))
        assertEquals(listOf("a","d","b","c"),ManualShelfOrder.move(listOf("a","b","c","d"),"d","b"))
    }
    @Test fun deletedAndRepeatedIdsAreRemovedAndNewTitlesGoAtTheEnd() {
        assertEquals(listOf("c","a","d"),ManualShelfOrder.reconcile(listOf("c","deleted","a","c"),listOf("a","c","d")))
        assertEquals(listOf("c","a","d","e"),ManualShelfOrder.reconcile(listOf("c","a","d"),listOf("e","a","c","d")))
    }
    @Test fun metadataEditsDoNotChangePositionAndFilteringDoesNotDiscardOtherSavedIds() {
        val order=listOf("c","a","b")
        val items=listOf(LibraryBook(id="a",title="Renomeado",totalPages=100,currentPage=20),LibraryBook(id="c",title="Título",totalPages=20))
        assertEquals(listOf("c","a"),ManualShelfOrder.select(items,order).map { it.id })
        assertEquals(listOf("c","a","b"),order)
    }
    @Test fun staleOrSameDestinationDoesNotMoveAnything() {
        val order=listOf("a","b")
        assertEquals(order,ManualShelfOrder.move(order,"unknown","b"));assertEquals(order,ManualShelfOrder.move(order,"a","a"))
    }
}
