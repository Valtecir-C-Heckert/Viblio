package br.com.viblio.homeassistant
import br.com.centralcasa.books.LibraryBook
import org.junit.Test
import org.junit.Assert.*
import java.net.ServerSocket
import kotlin.concurrent.thread

class ReadingWebhookTest {
    private val path="/api/webhook/"+"a".repeat(64)
    @Test fun endpointRules() {
        assertTrue(ReadingWebhook.validUrl("http://192.168.0.10:8123$path"))
        assertTrue(ReadingWebhook.validUrl("http://100.64.0.10:8123$path"))
        assertTrue(ReadingWebhook.validUrl("https://ha.example.com$path"))
        listOf("http://public.example.com$path","https://u:p@ha.example.com$path","https://ha.example.com$path?x=1","https://ha.example.com/api/webhook/short").forEach { assertFalse(it,ReadingWebhook.validUrl(it)) }
    }
    @Test fun snapshotContainsOnlyCurrentReading() {
        val b=LibraryBook(title="1984",author="George Orwell",totalPages=336,currentPage=50,rating=4.5,cover="private.jpg")
        val row=ReadingWebhook.snapshot(b,"#D87242").getAsJsonObject("book")
        assertEquals(50,row.get("current_page").asInt);assertEquals("#D87242",row.get("color").asString)
        assertFalse(row.has("rating"));assertFalse(row.has("cover"));assertFalse(row.has("catalogId"))
        assertTrue(ReadingWebhook.snapshot(null,null).get("book").isJsonNull)
    }
    @Test fun rejectsUnknownWebhookSuccessAndRedirects() {
        for(response in listOf("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n","HTTP/1.1 302 Found\r\nLocation: https://example.com/\r\nContent-Length: 0\r\n\r\n")) {
            ServerSocket(0).use { server->
                val t=thread { server.accept().use { socket->socket.getInputStream().bufferedReader().let { reader->while(!reader.readLine().isNullOrEmpty()) {} };socket.getOutputStream().write(response.toByteArray()) } }
                val result=runCatching { ReadingWebhook.send("http://127.0.0.1:${server.localPort}$path",ReadingWebhook.snapshot(null,null)) }
                assertTrue(result.isFailure);t.join(2000)
            }
        }
    }
    @Test fun rejectsOversizedWebhookResponse() {
        ServerSocket(0).use { server->
            val body="{\"viblio\":true,\"pad\":\""+"x".repeat(4096)+"\"}"
            val t=thread { server.accept().use { socket->socket.getInputStream().bufferedReader().let { r->while(!r.readLine().isNullOrEmpty()) {} };runCatching { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray()) } } }
            val result=runCatching { ReadingWebhook.send("http://127.0.0.1:${server.localPort}$path",ReadingWebhook.snapshot(null,null)) }
            assertTrue(result.isFailure);t.join(2000)
        }
    }
    @Test fun acceptsOnlyIntegrationAck() {
        ServerSocket(0).use { server->
            val response="{\"viblio\":true}"
            val t=thread { server.accept().use { socket->socket.getInputStream().bufferedReader().let { r->while(!r.readLine().isNullOrEmpty()) {} };socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\n\r\n$response".toByteArray()) } }
            ReadingWebhook.send("http://127.0.0.1:${server.localPort}$path",ReadingWebhook.snapshot(null,null));t.join(2000)
        }
    }
}
