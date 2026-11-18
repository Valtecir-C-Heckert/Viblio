package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class RatingFiltersTest {
    private fun book(id: String, rating: Double?, genre: String = "Fantasia") = LibraryBook(id = id, title = id, totalPages = 100, rating = rating, genre = genre)
    private val books = listOf(book("unrated", null), book("zero", 0.0), book("medium", 3.5), book("high", 4.5), book("other", 5.0, "Terror"))
    @Test fun filterMinimumHalfStarCombinesWithGenre() {
        val result = ShelfFilters(genre = "Fantasia", ratings = ShelfRatings.RATED, minimumRating = 3.5).select(books, null)
        assertEquals(setOf("medium", "high"), result.map { it.id }.toSet())
    }
    @Test fun zeroRatingIsDistinctFromNoRating() {
        assertEquals(listOf("unrated"), ShelfFilters(ratings = ShelfRatings.UNRATED).select(books, null).map { it.id })
        assertTrue(ShelfFilters(ratings = ShelfRatings.RATED).select(books, null).any { it.id == "zero" })
    }
    @Test fun orderedNotesKeepMissingLastInBothDirections() {
        val high = ShelfFilters(order = listOf(ShelfOrder(ShelfField.RATING, true))).select(books, null)
        val low = ShelfFilters(order = listOf(ShelfOrder(ShelfField.RATING))).select(books, null)
        assertEquals(listOf("other", "high", "medium", "zero", "unrated"), high.map { it.id })
        assertEquals(listOf("zero", "medium", "high", "other", "unrated"), low.map { it.id })
        assertEquals(listOf(ShelfOrder(ShelfField.RATING, true)), ShelfOrder.decode(ShelfOrder.encode(listOf(ShelfOrder(ShelfField.RATING, true)))))
    }
    @Test fun sliderMapsHalfStepsAndClampsEdges() {
        assertEquals(3.5, halfStarRating(.7), 0.0); assertEquals(4.5, halfStarRating(.9), 0.0)
        assertEquals(0.5, halfStarRating(.05), 0.0); assertEquals(0.0, halfStarRating(-1.0), 0.0)
        assertEquals(5.0, halfStarRating(2.0), 0.0)
        assertThrows(IllegalArgumentException::class.java) { halfStarRating(Double.NaN) }
    }
    @Test fun legacySortKeepsUnratedLastAndThresholdRejectsInvalidValue() {
        assertEquals("unrated", ShelfFilters(sort = ShelfSort.RATING_HIGH).select(books, null).last().id)
        assertEquals("zero", ShelfFilters(sort = ShelfSort.RATING_LOW).select(books, null).first().id)
        assertThrows(IllegalArgumentException::class.java) { ShelfFilters(minimumRating = 5.5) }
    }
}
