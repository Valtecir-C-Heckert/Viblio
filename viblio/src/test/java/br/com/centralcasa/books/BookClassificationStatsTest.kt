package br.com.centralcasa.books

import org.junit.Assert.assertEquals
import org.junit.Test

class BookClassificationStatsTest {
    private fun book(vararg tags: String) = LibraryBook(title = "Livro", genre = "Fantasia", totalPages = 100, subgenres = tags.toList())

    @Test fun countsAllTagsOncePerBookAndSortsByFrequencyThenName() {
        val result = BookClassificationStats.subgenres(listOf(book("Dark", "Épica", "Urbana"), book(" dark ", "DARK", "Épica")))
        assertEquals(listOf(BookClassificationStats.Count("Dark", 2), BookClassificationStats.Count("Épica", 2), BookClassificationStats.Count("Urbana", 1)), result)
    }

    @Test fun preservesLegacySingleTagAndIgnoresEmptyTags() {
        val legacy = book().copy(subgenre = "Dark")
        assertEquals(listOf(BookClassificationStats.Count("Dark", 1)), BookClassificationStats.subgenres(listOf(legacy, book(" "))))
        assertEquals(emptyList<BookClassificationStats.Count>(), BookClassificationStats.subgenres(emptyList()))
    }

    @Test fun groupsEquivalentGenreNamesAndKeepsUnclassifiedBooks() {
        assertEquals(listOf(BookClassificationStats.Count("Fantasia", 2), BookClassificationStats.Count("Sem gênero", 1)),
            BookClassificationStats.genres(listOf(book(), book().copy(genre = " fantasia "), book().copy(genre = ""))))
    }
}
