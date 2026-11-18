package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class BookGenresTest {
    @Test fun splitsHorrorAndNonFictionSuffixesAndCanonicalizesParent() {
        assertEquals(BookGenres.Classification("Terror", "Psicológico"), BookGenres.split("Terror Psicológico"))
        assertEquals(BookGenres.Classification("Terror", "Cósmico"), BookGenres.split("terror - Cósmico"))
        assertEquals(BookGenres.Classification("Não-Ficção", "História"), BookGenres.split("Não-Ficção História"))
        assertEquals(BookGenres.Classification("Não-Ficção", "BIOGRAFIA"), BookGenres.split("NÃO FICÇÃO BIOGRAFIA"))
        assertEquals(BookGenres.Classification("Não-Ficção"), BookGenres.split("nao-ficcao"))
    }
    @Test fun splittingPreservesExistingTagsAndDoesNotSilentlyLoseFourthTag() {
        val old = LibraryBook(title = "X", genre = "Terror Cósmico", subgenres = listOf("Suspense", "Gótico"), totalPages = 100)
        val book = BookGenres.normalize(old)
        assertEquals("Terror", book.genre)
        assertEquals(listOf("Cósmico", "Suspense", "Gótico"), book.selectedSubgenres)
        assertEquals(book, BookGenres.normalize(book))
        assertTrue(runCatching { BookGenres.normalize(old.copy(subgenres = listOf("A", "B", "C"))).validated() }.isFailure)
    }
    @Test fun recognizesUsersLegacyFantasyClassifications() {
        assertEquals(BookGenres.Classification("Fantasia", "Dark"), BookGenres.split("  fantasia   dark "))
        assertEquals(BookGenres.Classification("Fantasia", "Épica"), BookGenres.split("FANTASIA EPICA"))
        assertEquals(BookGenres.Classification("Fantasia", "Épica"), BookGenres.split("Fantasia Épica"))
    }
    @Test fun preservesUnknownGenresAndExplicitSubgenres() {
        assertEquals(BookGenres.Classification("Ficção científica"), BookGenres.split("Ficção científica"))
        assertEquals(BookGenres.Classification("Fantasia experimental"), BookGenres.split("Fantasia experimental"))
        val book = LibraryBook(id = "b", title = "X", genre = "Meu gênero", subgenre = "Meu subgênero", totalPages = 100)
        assertEquals(book.copy(subgenres = listOf("Meu subgênero")), BookGenres.normalize(book))
    }
    @Test fun conversionRetainsBookIdentityProgressAndMetadata() {
        val book = LibraryBook(id = "original", title = "X", genre = "Fantasia Dark", totalPages = 100, currentPage = 37, cover = "cover.jpg", saga = "Saga", updatedAt = 123)
        assertEquals(book.copy(genre = "Fantasia", subgenre = "Dark", subgenres = listOf("Dark")), BookGenres.normalize(book))
        assertEquals(BookGenres.normalize(book), BookGenres.normalize(BookGenres.normalize(book)))
    }
    @Test fun suggestionsReuseLibraryAndStayWithinSelectedGenre() {
        val books = listOf(
            LibraryBook(title = "A", genre = "Fantasia", subgenre = "Dark", totalPages = 1),
            LibraryBook(title = "B", genre = "Fantasia", subgenre = "Épica", totalPages = 1),
            LibraryBook(title = "C", genre = "Fantasia", subgenre = "dark", totalPages = 1),
            LibraryBook(title = "D", genre = "Terror", subgenre = "Psicológico", totalPages = 1))
        assertEquals(listOf("Dark", "Épica"), BookGenres.subgenres(books, "fantasia"))
        assertEquals(listOf("Psicológico"), BookGenres.subgenres(books, "Terror"))
        assertTrue(BookGenres.subgenres(books, "Novo").isEmpty())
    }
    @Test fun searchFiltersAndOrderingDistinguishBothLevels() {
        val dark = LibraryBook(id = "dark", title = "B", genre = "Fantasia", subgenre = "Dark", totalPages = 1)
        val epic = LibraryBook(id = "epic", title = "A", genre = "Fantasia", subgenre = "Épica", totalPages = 1)
        val horror = LibraryBook(id = "horror", title = "C", genre = "Terror", subgenre = "Dark", totalPages = 1)
        val books = listOf(epic, horror, dark)
        assertEquals(setOf("dark", "epic"), ShelfFilters(genre = "Fantasia").select(books, null).map { it.id }.toSet())
        assertEquals(listOf("dark"), ShelfFilters(genre = "Fantasia", subgenre = "Dark").select(books, null).map { it.id })
        assertEquals(listOf("epic"), ShelfFilters(query = "fantasia epica").select(books, null).map { it.id })
        assertEquals(listOf("dark", "epic"), ShelfFilters(genre = "Fantasia", order = listOf(ShelfOrder(ShelfField.SUBGENRE))).select(books, null).map { it.id })
    }
    @Test fun subgenreRequiresParent() {
        assertTrue(runCatching { LibraryBook(title = "X", subgenre = "Dark", totalPages = 1).validated() }.isFailure)
    }
    @Test fun suggestionsRecoverLegacyAndCollectionClassifications() {
        val books = listOf(
            LibraryBook(title = "A", genre = "Fantasia Dark", totalPages = 1),
            LibraryBook(title = "B", genre = "FANTASIA", subgenres = listOf("Épica"), totalPages = 1),
            CollectionTitle(kind = "comic", title = "C", genre = "Fantasia", subgenres = listOf("Urbana")).shelfBook(emptyList()),
            LibraryBook(title = "D", genre = "Terror", subgenre = "Psicológico", totalPages = 1))
        assertEquals(listOf("Dark", "Épica", "Urbana"), BookGenres.subgenres(books, "fantasia"))
    }
}
