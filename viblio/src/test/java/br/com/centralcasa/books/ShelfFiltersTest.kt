package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class ShelfFiltersTest {
    private val a = LibraryBook(id = "a", title = "Fúria Vermelha", author = "Pierce Brown", genre = "Distopia", totalPages = 468, currentPage = 234, cover = "a.jpg", updatedAt = 10, sequenceNumber = 5)
    private val b = LibraryBook(id = "b", title = "1984", author = "George Orwell", genre = "Distopia", totalPages = 328, currentPage = 328, updatedAt = 20)
    private val c = LibraryBook(id = "c", title = "A Deusa em Chamas", author = "R. F. Kuang", genre = "Fantasia", totalPages = 592, updatedAt = 30, sequenceNumber = 3)
    private val books = listOf(c, b, a)

    @Test fun combinedFiltersAndAccentFreeSearchFindOnlyTheMatchingBook() {
        val filters = ShelfFilters(query = "furia brown", status = ShelfStatus.READING, genre = "distopia", author = "pierce brown", covers = ShelfCovers.WITH)
        assertEquals(listOf(a), filters.select(books, a.id))
        assertTrue(filters.copy(covers = ShelfCovers.WITHOUT).select(books, a.id).isEmpty())
    }
    @Test fun readingDoesNotIncludeCompletedAndCurrentIsIndependentOfProgress() {
        assertEquals(listOf(a), ShelfFilters(status = ShelfStatus.READING).select(books, b.id))
        assertEquals(listOf(b), ShelfFilters(status = ShelfStatus.CURRENT).select(books, b.id))
        assertTrue(ShelfFilters(status = ShelfStatus.CURRENT).select(books, null).isEmpty())
        assertEquals(listOf(c), ShelfFilters(status = ShelfStatus.UNREAD).select(books, a.id))
    }
    @Test fun sortUsesPercentageRatherThanRawPagesAndPutsMissingSequenceLast() {
        val larger = c.copy(currentPage = 296)
        assertEquals(listOf(b, larger, a), ShelfFilters(sort = ShelfSort.PROGRESS_HIGH).select(listOf(a, b, larger), null))
        assertEquals(listOf(c, a, b), ShelfFilters(sort = ShelfSort.SEQUENCE).select(books, null))
        assertEquals(listOf(c, b, a), ShelfFilters(sort = ShelfSort.RECENT).select(books, null))
    }
    @Test fun clearingReturnsAllBooksAndBlankMetadataCanBeSelected() {
        val blank = c.copy(genre = "", author = "")
        assertEquals(listOf(blank), ShelfFilters(genre = "Sem gênero", author = "Sem autor").select(listOf(a, blank), null))
        assertEquals(books.size, ShelfFilters().select(books, null).size)
        assertEquals(listOf(a, c, b), ShelfFilters(sort = ShelfSort.TITLE_REVERSE).select(books, null))
        assertEquals(listOf(b, a), ShelfFilters(query = "DISTOPIA").select(books, null))
    }
}
