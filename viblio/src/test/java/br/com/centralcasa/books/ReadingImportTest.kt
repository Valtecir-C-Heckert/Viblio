package br.com.centralcasa.books

import org.junit.Assert.*
import org.junit.Test

class ReadingImportTest {
    private val current = LibraryBook(id = "active", title = "Livro", totalPages = 300, currentPage = 45)
    private val paused = ReadingDraft(current.id, 40, 1000, elapsedMs = 9000)
    @Test fun pausedImportKeepsPagesAndImportsMetadata() {
        val incoming = current.copy(totalPages = 100, currentPage = 0, saga = "Saga", rating = 4.5, cover = "new-cover")
        val result = preserveReadingOnImport(incoming, current, paused)
        assertEquals(300, result.totalPages); assertEquals(45, result.currentPage)
        assertEquals("Saga", result.saga); assertEquals(4.5, result.rating!!, 0.0); assertEquals("new-cover", result.cover)
        assertEquals(9000L, paused.elapsedMs)
    }
    @Test fun unrelatedVolumeRemainsUnchanged() {
        val volume = LibraryBook(id = "volume", title = "Volume", totalPages = 1, collectionId = "series", pagesKnown = false, readFinished = true)
        assertSame(volume, preserveReadingOnImport(volume, null, paused))
    }
    @Test fun noSessionUsesImportedProgress() {
        val incoming = current.copy(currentPage = 100)
        assertSame(incoming, preserveReadingOnImport(incoming, current, null))
    }
    @Test(expected = IllegalArgumentException::class) fun runningSessionRequiresPause() {
        preserveReadingOnImport(current, current, paused.copy(runningSince = 5000))
    }
    @Test(expected = IllegalArgumentException::class) fun missingActiveBookFails() {
        preserveReadingOnImport(current, null, paused)
    }
}
