package br.com.centralcasa.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomShelvesTest {
    @Test
    fun createRenameDeleteAndAssignBooksRoundTrip() {
        val (s1, fav) = CustomShelves.create(emptyList(), "  Favoritos  do Ano  ", initialBookId = "book-1")
        assertEquals("Favoritos do Ano", fav.name)
        assertEquals(setOf("book-1"), fav.bookIds)

        val (s2, tbr) = CustomShelves.create(s1, "Quero ler")
        assertEquals(2, s2.size)

        val renamed = CustomShelves.rename(s2, fav.id, "Favoritos")
        assertEquals("Favoritos", renamed.first { it.id == fav.id }.name)

        val assigned = CustomShelves.setBookShelves(renamed, "book-2", setOf(fav.id, tbr.id))
        assertEquals(listOf(fav.id, tbr.id), CustomShelves.shelvesForBook(assigned, "book-2").map { it.id })

        val encoded = CustomShelves.encode(assigned)
        val decoded = CustomShelves.decode(encoded)
        assertEquals(assigned, decoded)

        val afterDelete = CustomShelves.delete(decoded, tbr.id)
        assertEquals(1, afterDelete.size)
        assertEquals(listOf(fav.id), CustomShelves.shelvesForBook(afterDelete, "book-2").map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateShelfNamesCaseAndAccentInsensitive() {
        val (s1, _) = CustomShelves.create(emptyList(), "Ficção")
        CustomShelves.create(s1, "ficcao")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankAndTooLongNames() {
        CustomShelves.create(emptyList(), "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNameOver40Chars() {
        CustomShelves.create(emptyList(), "a".repeat(41))
    }

    @Test
    fun enforcesMaxShelvesLimit() {
        var shelves = emptyList<CustomShelf>()
        for (i in 1..CustomShelves.MAX_SHELVES) {
            shelves = CustomShelves.create(shelves, "Prateleira $i").first
        }
        assertEquals(CustomShelves.MAX_SHELVES, shelves.size)
        val failed = runCatching { CustomShelves.create(shelves, "Extra") }.isFailure
        assertTrue(failed)
    }
}
