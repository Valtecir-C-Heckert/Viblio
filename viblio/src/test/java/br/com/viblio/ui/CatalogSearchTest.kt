package br.com.viblio.ui

import br.com.centralcasa.books.catalog.CatalogBook
import org.junit.Assert.*
import org.junit.Test

class CatalogSearchTest {
    private fun row(id:String="1",source:String="server",category:String="",type:String="")=CatalogBook(id,source,"Título",category=category,type=type)
    @Test fun isbnRoutingRecognizesOnlyCompleteCodes() {
        assertEquals("9788599296493",CatalogSearch.isbn("978-85-99296-49-3"))
        assertEquals("123456789X",CatalogSearch.isbn("123456789x"))
        assertNull(CatalogSearch.isbn("Mistborn 2"));assertNull(CatalogSearch.isbn("9788599"))
    }
    @Test fun mergedSearchDoesNotDuplicateTitleAndAuthorHitsOrLoseOtherSources() {
        assertEquals(listOf(row(),row("2"),row(source="other")),CatalogSearch.merge(listOf(row(),row("2")),listOf(row(),row(source="other"))))
    }
    @Test fun typeFiltersUseServerMetadataWithAccents() {
        val books=listOf(row(),row("2",category="Mangá"),row("3",type="HQ"),row("4",type="Light Novel"))
        assertEquals(listOf("1","4"),CatalogSearch.filter(books,"book").map { it.id })
        assertEquals("2",CatalogSearch.filter(books,"manga").single().id)
        assertEquals("3",CatalogSearch.filter(books,"comic").single().id)
        assertEquals(books,CatalogSearch.filter(books,"all"))
    }
    @Test fun editionKeepsVolumeAndPublisherAndDoesNotInventSequence() {
        assertEquals("Mangá · Saga · Volume 2 · Panini",CatalogSearch.edition(row(category="Mangá").copy(saga="Saga",sequence=2,publisher="Panini")))
        assertEquals("Livro",CatalogSearch.edition(row()))
    }
}
