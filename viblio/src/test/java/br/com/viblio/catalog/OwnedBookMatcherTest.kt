package br.com.viblio.catalog

import br.com.centralcasa.books.LibraryBook
import br.com.centralcasa.books.catalog.CatalogBook
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedBookMatcherTest {
    private fun owned(
        id: String,
        title: String,
        author: String,
        catalogId: String? = null,
        sequence: Int? = null,
    ) = LibraryBook(
        id = id,
        title = title,
        author = author,
        genre = "Ficção Científica",
        totalPages = 200,
        currentPage = 10,
        cover = "",
        background = "black",
        updatedAt = 1L,
        sequenceNumber = sequence,
        catalogId = catalogId,
        catalogSource = if (catalogId != null) "src" else "",
    )

    @Test
    fun excludesOwnedIdSubtitleAndEditionVariantsWhileKeepingOtherBooksAndVolumes() {
        val shelf = listOf(
            owned("b1", "Alerta Vermelho", "Martha Wells", catalogId = "770010"),
            owned("b2", "Vagabond Vol. 1", "Takehiko Inoue", catalogId = "770014", sequence = 1),
        )
        val candidates = listOf(
            CatalogBook("770010", "src", "Alerta Vermelho", "Martha Wells"),
            CatalogBook("770011", "src", "Alerta Vermelho (Diários de um Robô-Assassino Livro 1)", "Martha Wells"),
            CatalogBook("770012", "src", "Condição Artificial", "Wells, Martha"),
            CatalogBook("770014", "src", "Vagabond Vol. 1", "Takehiko Inoue", sequence = 1),
            CatalogBook("770015", "src", "Vagabond Vol. 2", "Takehiko Inoue", sequence = 2),
        )

        val kept = OwnedBookMatcher.filterUnowned(candidates, shelf).map { it.id }
        assertEquals(listOf("770012", "770015"), kept)
    }
}
