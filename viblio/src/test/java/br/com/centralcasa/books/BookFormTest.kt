package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class BookFormTest {
    private fun book(tags: List<String>) = LibraryBook(id = "b", title = "Livro", genre = "Fantasia", totalPages = 100, currentPage = 37, subgenres = tags)
    @Test fun acceptsZeroToThreeAndRejectsFour() {
        for (tags in listOf(emptyList(), listOf("Dark"), listOf("Dark", "Épica"), listOf("Dark", "Épica", "Urbana"))) assertEquals(tags, book(tags).validated().selectedSubgenres)
        assertTrue(runCatching { book(listOf("Dark", "Épica", "Urbana", "Histórica")).validated() }.isFailure)
        assertTrue(runCatching { BookGenres.normalize(book(listOf("Dark", "Épica", "Urbana", "Histórica"))).validated() }.isFailure)
    }
    @Test fun rejectsEmptyAndRepeatedTags() {
        assertTrue(runCatching { book(listOf("")).validated() }.isFailure)
        assertTrue(runCatching { book(listOf("Épica", "epica")).validated() }.isFailure)
        assertEquals(listOf("Dark", "Épica"), BookGenres.normalize(book(listOf(" Dark ", "dark", "Épica"))).selectedSubgenres)
    }
    @Test fun oldSingleSubgenreRemainsAvailableAndCanBeCleared() {
        val old = book(emptyList()).copy(subgenre = "Dark")
        assertEquals(listOf("Dark"), old.selectedSubgenres)
        val migrated = BookGenres.normalize(old)
        assertEquals(listOf("Dark"), migrated.subgenres)
        assertEquals(old.currentPage, migrated.currentPage)
        assertTrue(BookGenres.normalize(migrated.copy(subgenre = "", subgenres = emptyList())).selectedSubgenres.isEmpty())
    }
    @Test fun everyTileParticipatesInSearchAndFilter() {
        val all = listOf(book(listOf("Dark", "Épica", "Urbana")))
        for (name in listOf("dark", "epica", "urbana")) {
            assertEquals(1, ShelfFilters(query = name).select(all, null).size)
            assertEquals(1, ShelfFilters(subgenre = name).select(all, null).size)
        }
        assertTrue(ShelfFilters(subgenre = "Sem subgênero").select(all, null).isEmpty())
        assertEquals(listOf("Dark", "Épica", "Urbana"), BookGenres.subgenres(all, "Fantasia"))
    }
    @Test fun completionFindsSurnamesAccentsAndSmallMistakes() {
        val authors = listOf("John Gwynne", "José Saramago", "R. F. Kuang", "John Gwynne")
        assertEquals(listOf("John Gwynne"), BookMetadata.suggestions(authors, "Gwynne"))
        assertEquals(listOf("José Saramago"), BookMetadata.suggestions(authors, "jose"))
        assertEquals(listOf("John Gwynne"), BookMetadata.suggestions(authors, "Jhon Gwynne"))
        assertEquals(listOf("R. F. Kuang"), BookMetadata.suggestions(authors, "Kuagn"))
        assertTrue(BookMetadata.suggestions(authors, "Autor totalmente diferente").isEmpty())
    }
    @Test fun sagaCompletionMatchesMiddleWordsAndDoesNotInventNames() {
        val names = listOf("Bloodsworn Saga", "A Roda do Tempo", "Saga Mistborn")
        assertEquals(listOf("A Roda do Tempo"), BookMetadata.suggestions(names, "roda"))
        assertEquals(listOf("Bloodsworn Saga"), BookMetadata.suggestions(names, "blood"))
        assertTrue(BookMetadata.suggestions(names, "Minha nova saga").isEmpty())
        assertEquals(12, BookMetadata.suggestions((1..30).map { "Autor $it" }).size)
    }
}
