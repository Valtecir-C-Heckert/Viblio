package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class CollectionTitleTest {
    private fun volume(id: String, collection: String = "series", read: Boolean = false) = LibraryBook(id = id, title = "Título", totalPages = 1, kind = "manga", collectionId = collection, pagesKnown = false, readFinished = read)
    @Test fun groupedCoverProgressUsesOnlyOwnedVolumesFromThisTitle() {
        val title = CollectionTitle(id = "series", title = "Série", rating = 4.5)
        val shelf = title.shelfBook(listOf(volume("a", read = true), volume("b"), volume("c", "other", true)))
        assertEquals(2, shelf.totalPages); assertEquals(1, shelf.currentPage); assertEquals(50, shelf.percent)
        assertEquals(4.5, shelf.rating!!, 0.0)
    }
    @Test fun emptyCollectionIsNotReportedAsComplete() {
        assertFalse(CollectionTitle(id = "series", title = "Série").shelfBook(emptyList()).complete)
    }
    @Test fun missingPageCountDoesNotInventPageProgressAndCanCarryReadStatus() {
        val b = volume("a", read = true).validated()
        assertTrue(b.complete); assertEquals(100, b.percent); assertEquals(0, b.currentPage)
        assertFalse(b.copy(readFinished = false).complete)
    }
    @Test fun unknownPagesCannotBeUsedForAnUnlinkedBook() {
        assertThrows(IllegalArgumentException::class.java) { volume("a", "").validated() }
    }
    @Test fun ratingsPreserveHalfStarsAndRejectInvalidValues() {
        listOf(0.0, 2.5, 5.0).forEach { validateRating(it) }
        listOf(-1.0, 6.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value -> assertThrows(IllegalArgumentException::class.java) { validateRating(value) } }
        assertEquals(8.0, CollectionTitle(title = "Série", sourceRating = 8.0, rating = 4.0).validated().sourceRating!!, 0.0)
    }
    @Test fun updatingRatingDoesNotAlterReadingFields() {
        val b = LibraryBook(id = "a", title = "Livro", totalPages = 200, currentPage = 77, saga = "Saga", subgenres = listOf("Dark"), genre = "Fantasia")
        val rated = b.copy(rating = 5.0).validated()
        assertEquals(b.currentPage, rated.currentPage); assertEquals(b.totalPages, rated.totalPages)
        assertEquals(b.updatedAt, rated.updatedAt); assertEquals(b.saga, rated.saga)
    }
    @Test fun mainCoverAlwaysUsesVolumeOneIndependentOfOrder() {
        val title = CollectionTitle(id = "series", title = "Série", cover = "old-title-cover")
        val first = volume("one").copy(sequenceNumber = 1, cover = "volume-one")
        val second = volume("two").copy(sequenceNumber = 2, cover = "volume-two")
        assertEquals("volume-one", title.shelfBook(listOf(second, first)).cover)
        assertEquals("", title.shelfBook(listOf(second, first.copy(cover = ""))).cover)
        assertEquals("", title.shelfBook(listOf(second)).cover)
    }

    @Test fun titleSubgenresParticipateInShelfFilters() {
        val c = CollectionTitle(title = "Série", genre = "Fantasia", subgenres = listOf("Dark", "Épica")).validated()
        val b = c.shelfBook(emptyList())
        assertEquals(listOf("Dark", "Épica"), b.selectedSubgenres)
        assertEquals(1, ShelfFilters(subgenre = "Dark").select(listOf(b), null).size)
        assertEquals(0, ShelfFilters(subgenre = "Romance").select(listOf(b), null).size)
    }
    @Test fun titleSubgenresRejectDuplicatesEmptyAndMoreThanThree() {
        listOf(listOf("Dark", "dark"), listOf(""), listOf("A", "B", "C", "D")).forEach { tags ->
            assertThrows(IllegalArgumentException::class.java) { CollectionTitle(title = "Série", subgenres = tags).validated() }
        }
        CollectionTitle(title = "Série", subgenres = listOf("A", "B", "C")).validated()
    }

    @Test fun lightNovelUsesBookKindAndVolumeOneCoverWithoutLosingClassification() {
        val title = CollectionTitle(id = "novel", kind = "book", title = "Novel",
            author = "Autor", publisher = "Editora", genre = "Fantasia", subgenres = listOf("Dark")).validated()
        val one = LibraryBook(id = "one", title = title.title, totalPages = 1, pagesKnown = false,
            kind = "book", collectionId = title.id, sequenceNumber = 1, cover = "one-cover", readFinished = true).validated()
        val two = one.copy(id = "two", sequenceNumber = 2, cover = "two-cover", readFinished = false)
        val shelf = title.shelfBook(listOf(two, one))
        assertEquals("book", shelf.kind)
        assertEquals("Editora", shelf.publisher)
        assertEquals("Fantasia", shelf.genre)
        assertEquals(listOf("Dark"), shelf.selectedSubgenres)
        assertEquals("one-cover", shelf.cover)
        assertEquals(50, shelf.percent)
        assertEquals(1, ShelfFilters(genre = "Fantasia", subgenre = "Dark").select(listOf(shelf), null).size)
    }

    @Test fun convertingBookToLightNovelKeepsReadingIdentityAndProgress() {
        val book = LibraryBook(id = "original", title = "Novel", totalPages = 300, currentPage = 87,
            cover = "cover", publisher = "Editora", rating = 4.5)
        val collection = CollectionTitle(kind = "book", title = book.title, publisher = book.publisher, cover = book.cover)
        val converted = collection.lightNovelVolumeOne(book)
        assertEquals(book.id, converted.id)
        assertEquals(book.currentPage, converted.currentPage)
        assertEquals(book.totalPages, converted.totalPages)
        assertEquals(book.rating, converted.rating)
        assertEquals(book.cover, converted.cover)
        assertEquals(collection.id, converted.collectionId)
        assertEquals(1, converted.sequenceNumber)
        val newVolume = collection.lightNovelVolumeOne(book.copy(id = "new", totalPages = 0, currentPage = 0))
        assertFalse(newVolume.pagesKnown)
        assertEquals(1, newVolume.totalPages)
    }

}
