package br.com.viblio.ui

import br.com.centralcasa.books.LibraryBook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubBookSyncTest {
    private fun book(
        id: String = "b1",
        title: String = "Duna",
        author: String = "Frank Herbert",
        totalPages: Int = 680,
        catalogId: String? = null,
        catalogSource: String = "",
        pagesKnown: Boolean = true,
        archived: Boolean = false,
    ) = LibraryBook(
        id = id,
        title = title,
        author = author,
        totalPages = totalPages,
        pagesKnown = pagesKnown,
        catalogId = catalogId,
        catalogSource = catalogSource,
        archived = archived,
    )

    @Test
    fun matchesByOwnBookIdWhenTotalPagesMatch() {
        val current = book(id = "local-1", title = "Título editado", author = "Outro", totalPages = 320)
        val club = ClubBookDescriptor(title = "Original", author = "Autor", pages = 320, ownBookId = "local-1")
        assertTrue(ClubBookSync.matchesClubBook(current, club))
    }

    @Test
    fun matchesByCatalogIdWhenTotalPagesMatch() {
        val current = book(id = "other-id", title = "Edição especial", author = "F. Herbert", totalPages = 680, catalogId = "42", catalogSource = "srv-1")
        val club = ClubBookDescriptor(title = "Duna", author = "Frank Herbert", pages = 680, catalogId = "srv-1:42")
        assertTrue(ClubBookSync.matchesClubBook(current, club))
    }

    @Test
    fun matchesByNormalizedTitleAuthorAndPages() {
        val current = book(title = "  O Temor do Sábio ", author = "Patrick   Rothfuss", totalPages = 960)
        val club = ClubBookDescriptor(title = "o temor do sabio", author = "patrick rothfuss", pages = 960)
        assertTrue(ClubBookSync.matchesClubBook(current, club))
    }

    @Test
    fun rejectsDifferentPageCountsOrArchivedOrUnknownPages() {
        val club = ClubBookDescriptor(title = "Duna", author = "Frank Herbert", pages = 680, catalogId = "srv-1:42", ownBookId = "b1")
        assertFalse(ClubBookSync.matchesClubBook(book(totalPages = 500, catalogId = "42", catalogSource = "srv-1"), club))
        assertFalse(ClubBookSync.matchesClubBook(book(archived = true, catalogId = "42", catalogSource = "srv-1"), club))
        assertFalse(ClubBookSync.matchesClubBook(book(pagesKnown = false, catalogId = "42", catalogSource = "srv-1"), club))
        assertFalse(ClubBookSync.matchesClubBook(null, club))
    }

    @Test
    fun rejectsUnrelatedBook() {
        val current = book(title = "O Hobbit", author = "J.R.R. Tolkien", totalPages = 320)
        val club = ClubBookDescriptor(title = "Duna", author = "Frank Herbert", pages = 320)
        assertFalse(ClubBookSync.matchesClubBook(current, club))
    }
}
