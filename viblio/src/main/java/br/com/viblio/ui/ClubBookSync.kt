package br.com.viblio.ui

import br.com.centralcasa.books.LibraryBook
import br.com.centralcasa.books.ShelfFilters

data class ClubBookDescriptor(
    val title: String,
    val author: String = "",
    val pages: Int = 0,
    val catalogId: String = "",
    val ownBookId: String = "",
)

data class ClubCurrentBookState(
    val bookId: String,
    val currentPage: Int,
    val startPage: Int,
    val hasDraft: Boolean,
    val draftRunning: Boolean,
    val draftElapsedMs: Long,
)

/** Matches a club's active book against the user's current book without touching unrelated books. */
object ClubBookSync {
    private val multiSpace = Regex("\\s+")
    private fun norm(value: String) = ShelfFilters.key(value).replace(multiSpace, " ")

    fun matchesClubBook(current: LibraryBook?, clubBook: ClubBookDescriptor): Boolean {
        if (current == null || current.archived || !current.pagesKnown) return false
        if (clubBook.pages <= 0 || current.totalPages != clubBook.pages) return false
        if (clubBook.ownBookId.isNotBlank() && current.id == clubBook.ownBookId) return true
        val localCatalog = if (!current.catalogId.isNullOrBlank() && current.catalogSource.isNotBlank()) {
            "${current.catalogSource}:${current.catalogId}"
        } else ""
        if (clubBook.catalogId.isNotBlank() && localCatalog.isNotBlank() && localCatalog == clubBook.catalogId) {
            return true
        }
        val titleKey = norm(clubBook.title)
        if (titleKey.isBlank() || norm(current.title) != titleKey) return false
        val clubAuthorKey = norm(clubBook.author)
        if (clubAuthorKey.isBlank()) return true
        return norm(current.author) == clubAuthorKey
    }

    /** Finds the matching shelf book for a club book, prioritizing the user's current book if it matches. */
    fun findMatchingBook(
        books: List<LibraryBook>,
        currentBookId: String?,
        clubBook: ClubBookDescriptor,
    ): LibraryBook? {
        if (!currentBookId.isNullOrBlank()) {
            val current = books.firstOrNull { it.id == currentBookId }
            if (matchesClubBook(current, clubBook)) return current
        }
        return books.firstOrNull { matchesClubBook(it, clubBook) }
    }
}
