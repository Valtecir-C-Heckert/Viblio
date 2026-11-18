package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class SagaSortTest {
    private fun book(id: String, saga: String = "", number: Int? = null, genre: String = "") =
        LibraryBook(id = id, title = id, totalPages = 100, saga = saga, sequenceNumber = number, genre = genre)
    @Test fun sagaUsesNameThenNumericOrderAndKeepsUnassignedLast() {
        val books = listOf(book("a"), book("b", "Zeta", 1), book("c", "Árvore", 10), book("d", "arvore", 2), book("e", "Árvore"))
        assertEquals(listOf("d", "c", "e", "b", "a"), ShelfFilters(sort = ShelfSort.SAGA).select(books, null).map { it.id })
    }
    @Test fun genreIgnoresAccentsAndKeepsUnassignedLast() {
        val books = listOf(book("a"), book("b", genre = "Ficção"), book("c", genre = "Épico"), book("d", genre = "epico"))
        assertEquals(listOf("c", "d", "b", "a"), ShelfFilters(sort = ShelfSort.GENRE).select(books, null).map { it.id })
    }
    @Test fun sagaNameIsSearchableTogetherWithAuthorAndTitle() {
        val matching = book("Segundo", "A Saga das Árvores", 2).copy(author = "Maria")
        assertEquals(listOf(matching), ShelfFilters(query = "arvores maria segundo").select(listOf(book("Outro"), matching), null))
    }
}
