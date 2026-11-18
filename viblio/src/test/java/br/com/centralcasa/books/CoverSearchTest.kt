package br.com.centralcasa.books

import org.junit.Test
import org.junit.Assert.*

class CoverSearchTest {
    @Test fun mangaSearchNamesItsActualVolume() {
        val query = CoverSearch.query("Berserk", "Kentaro Miura", "manga", 7, "Panini")
        assertTrue(query.contains("mangá volume 7")); assertTrue(query.contains("Panini")); assertFalse(query.contains("capa livro"))
    }
    @Test fun comicSearchNamesItsActualVolume() {
        assertEquals("Sandman Neil Gaiman capa quadrinho volume 3", CoverSearch.query("Sandman", "Neil Gaiman", "comic", 3))
    }
    @Test fun bookSearchPreservesSequenceWithoutInventingNumbers() {
        assertEquals("O Temor do Sábio Patrick Rothfuss capa livro livro 2", CoverSearch.query("O Temor do Sábio", "Patrick Rothfuss", "book", 2))
        assertEquals("1984 George Orwell capa livro", CoverSearch.query("1984", "George Orwell", "book", null))
    }
}
