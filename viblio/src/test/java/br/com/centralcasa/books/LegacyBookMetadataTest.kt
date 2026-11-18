package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class LegacyBookMetadataTest {
    @Test fun ordinalSeparatesNumberAndRestoresKnownTitle() {
        val book = LegacyBookMetadata.read("3º Deusa em chamas", "R.F. Kuang")
        assertEquals("A Deusa em Chamas", book.title)
        assertEquals(3, book.sequenceNumber)
        assertEquals("Harry Potter e as Relíquias da Morte", LegacyBookMetadata.read("6º Relíquias da Morte", "J. K. Rowling").title)
        assertEquals(6, LegacyBookMetadata.read("6º Relíquias da Morte", "J. K. Rowling").sequenceNumber)
        assertEquals("Nome desconhecido", LegacyBookMetadata.read("5º Nome desconhecido", "Autor").title)
    }
    @Test fun numericTitlesAndUnknownArticlesRemainIntact() {
        for (title in listOf("1984", "7 Pilares da Saúde Alimentar", "Sherlock Holmes 1", "Nome do Vento")) {
            val metadata = LegacyBookMetadata.read(title, "Outro autor")
            assertEquals(title, metadata.title); assertNull(metadata.sequenceNumber)
        }
    }
    @Test fun explicitNumberWinsAndNormalizationIsIdempotent() {
        val metadata = LegacyBookMetadata.read("1º Nome do Vento", "Patrick Rothfuss", 4)
        assertEquals(4, metadata.sequenceNumber)
        assertEquals(metadata, LegacyBookMetadata.read(metadata.title, "Patrick Rothfuss", metadata.sequenceNumber))
        assertEquals(5, LegacyBookMetadata.read("5 Deusa em chamas", "R F Kuang").sequenceNumber)
    }
    @Test fun optionalNumberHasBounds() {
        LibraryBook(title = "Teste", totalPages = 10, sequenceNumber = null).validated()
        LibraryBook(title = "Teste", totalPages = 10, sequenceNumber = 5).validated()
        for (number in listOf(0, -1, 10001)) assertThrows(IllegalArgumentException::class.java) {
            LibraryBook(title = "Teste", totalPages = 10, sequenceNumber = number).validated()
        }
    }
}
