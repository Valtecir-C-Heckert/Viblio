package br.com.centralcasa.books

import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.centralcasa.ui.RetrospectiveDialog
import br.com.viblio.account.AccountApi
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread

class AuditSecurityFixesTest {
    @Test
    fun libraryJsonRejectsExcessiveNestingAndIgnoresBracesInsideStrings() {
        val deep = "[".repeat(13) + "]".repeat(13)
        assertFalse(LibraryJson.withinMaxDepth(deep))

        val okDepth = "[".repeat(12) + "]".repeat(12)
        assertTrue(LibraryJson.withinMaxDepth(okDepth))

        val bracesInString = """{"books":[{"title":"{{{{{{{{{{{{{{{{{{{{{{{[[[[[[[","author":"a\"}"}]}"""
        assertTrue(LibraryJson.withinMaxDepth(bracesInString))
    }

    @Test
    fun annualPlanDoesNotDoubleCountTotalPagesAcrossMonths() {
        val book = LibraryBook(
            id = "book-1",
            title = "Duna",
            author = "Frank Herbert",
            totalPages = 680,
            currentPage = 680,
            pagesKnown = true,
            readFinished = true,
        )
        val janEntry = PlanEntry(id = "e1", month = 1, kind = PlanEntryKind.SHELF_BOOK, bookId = "book-1", completed = false)
        // Finished in March (not January), with 0 timed sessions in January
        val janResolved = AnnualPlanCalculator.resolveItem(
            entry = janEntry,
            booksById = mapOf(book.id to book),
            pagesReadInMonth = 0,
            completedInMonth = false,
            readInMonth = false,
        )
        assertEquals(PlanItemStatus.COMPLETED, janResolved.status)
        assertEquals(0, janResolved.pagesReadInMonth)

        // Completed in March without timed sessions should credit totalPages in March
        val marResolved = AnnualPlanCalculator.resolveItem(
            entry = janEntry.copy(month = 3),
            booksById = mapOf(book.id to book),
            pagesReadInMonth = 0,
            completedInMonth = true,
            readInMonth = true,
        )
        assertEquals(680, marResolved.pagesReadInMonth)
    }

    @Test
    fun homeAssistantCredentialsOnlyClearsOnIrrecoverableCryptoErrors() {
        assertTrue(HomeAssistantCredentials.isIrrecoverable(AEADBadTagException("bad tag")))
        assertTrue(HomeAssistantCredentials.isIrrecoverable(IllegalArgumentException("corrupt base64")))
        assertTrue(HomeAssistantCredentials.isIrrecoverable(RuntimeException(AEADBadTagException("wrapped"))))
        assertFalse(HomeAssistantCredentials.isIrrecoverable(IOException("transient keystore ipc")))
        assertFalse(HomeAssistantCredentials.isIrrecoverable(IllegalStateException("binder busy")))
    }

    @Test
    fun retrospectiveSafePngNameNeverExceeds60CharsAndAlwaysEndsWithPng() {
        val longSlug = "viblio_retrospectiva_mes_2026_novembro_stories_extra_very_long_name.png"
        val safe = RetrospectiveDialog.safePngName(longSlug)
        assertTrue(safe.endsWith(".png"))
        assertTrue("Length was ${safe.length}: $safe", safe.length <= 60)
        assertTrue(safe.matches(Regex("[a-z0-9_-]{1,56}\\.png")))

        val edge61 = "a".repeat(57) + ".png"
        val safeEdge = RetrospectiveDialog.safePngName(edge61)
        assertTrue(safeEdge.endsWith(".png"))
        assertEquals(60, safeEdge.length)
    }

    @Test
    fun accountApiRejectsCorruptHtmlOnHttp200() {
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { socket ->
                    val r = socket.getInputStream().bufferedReader()
                    while (!r.readLine().isNullOrEmpty()) {}
                    val body = "<html><body>Proxy Error</body></html>"
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray()
                    )
                }
            }
            val api = AccountApi("http://127.0.0.1:${server.localPort}", "token-test", OkHttpClient())
            try {
                val err = runCatching { api.call("/v1/me") }.exceptionOrNull()
                assertNotNull(err)
                assertEquals("Resposta inválida do servidor. Tente novamente.", err?.message)
            } finally {
                api.close()
                t.join(2000)
            }
        }
    }
}
