package br.com.centralcasa.books.catalog

import br.com.centralcasa.books.LibraryBook
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CatalogApiTest {
    private data class Request(val line:String,val headers:Map<String,String>,val body:String)
    private data class Response(val body:String="",val code:Int=200,val extra:String="")
    private fun server(respond:(Request)->Response,test:(CatalogApi,String)->Unit) {
        val socket=ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));socket.soTimeout=5000
        val worker=Executors.newSingleThreadExecutor()
        val future=worker.submit {
            socket.accept().use { client->
                client.soTimeout=5000;val input=client.getInputStream()
                fun line():String { val bytes=java.io.ByteArrayOutputStream();while(true) { val byte=input.read();if(byte<0 || byte==10) break;if(byte!=13) bytes.write(byte) };return bytes.toString("UTF-8") }
                val first=line();val headers=mutableMapOf<String,String>()
                while(true) { val header=line();if(header.isEmpty()) break;headers[header.substringBefore(':').lowercase()]=header.substringAfter(':').trim() }
                val body=ByteArray(headers["content-length"]?.toInt() ?: 0);var offset=0
                while(offset<body.size) { val read=input.read(body,offset,body.size-offset);check(read>0);offset+=read }
                val response=respond(Request(first,headers,String(body,Charsets.UTF_8)));val bytes=response.body.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply { write("HTTP/1.1 ${response.code} Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n${response.extra}\r\n".toByteArray());write(bytes);flush() }
            }
        }
        try { val url="http://127.0.0.1:${socket.localPort}";test(CatalogApi(url,"t".repeat(32)),url);future.get(5,TimeUnit.SECONDS) }
        finally { socket.close();worker.shutdownNow() }
    }
    @Test fun searchIsBoundedAuthenticatedAndPreservesBigintAndNulls() {
        server({ request->
            assertEquals("Bearer "+"t".repeat(32),request.headers["authorization"])
            assertTrue(request.line.contains("limit=20"));assertTrue(request.line.contains("S%C3%A1bio"));assertTrue(request.line.contains("isbn=9780000000001"))
            Response("""{"catalog_source":"fixture","items":[{"id":"9007199254740993","title":"O Temor do Sábio","author":null,"pages":null,"sequence":null,"category":"Mangá","type":"Manga","cover_url":"https://example.org/capa.jpg"}]}""")
        }) { api,_-> val found=api.search("Sábio","Autor","9780000000001").single();assertEquals("9007199254740993",found.id);assertEquals("",found.author);assertNull(found.pages);assertEquals("manga",found.kind);assertEquals("Mangá",found.category) }
    }
    @Test fun coverGalleryUsesTheBookIdAndPreservesPrimaryAndAlternatives() {
        server({ request->
            assertTrue(request.line.startsWith("GET /v1/books/9007199254740993/covers?limit=30&cursor=0 "))
            assertEquals("Bearer "+"t".repeat(32),request.headers["authorization"])
            Response("""{"catalog_source":"fixture","items":[{"id":"primary","url":"https://example.org/old.jpg","primary":true},{"id":"alternative","url":"https://example.org/new.jpg","primary":false}]}""")
        }) { api,_->val result=api.covers("9007199254740993");assertEquals("fixture",result.first);assertTrue(result.second[0].primary);assertFalse(result.second[1].primary);assertEquals("https://example.org/new.jpg",result.second[1].url) }
    }
    @Test fun galleryPagePreservesEditionMetadataAndCursor(){
        server({request->
            assertTrue(request.line.startsWith("GET /v1/books/123/covers?limit=30&cursor=30 "))
            Response("""{"catalog_source":"fixture","next_cursor":60,"items":[{"id":"edition:456","url":"https://example.org/edition.jpg","book_id":"456","publisher":"Editora","isbn":"9780000000001","pages":300,"sequence":1}]}""")
        }){api,_->val page=api.coverPage("123",30);assertEquals(60,page.nextCursor);assertEquals("Editora",page.items.single().publisher);assertEquals("456",page.items.single().bookId);assertEquals(300,page.items.single().pages)}
    }
    @Test fun healthReadsGalleryCapabilityWithoutBreakingLegacyIdentity() {
        server({ Response("""{"catalog_source":"fixture","api_version":2}""") }) { api,_->assertEquals("fixture" to 2,api.healthInfo()) }
    }
    @Test fun redirectsCannotCarryCredentialToAnotherDestination() {
        server({ Response(code=302,extra="Location: http://127.0.0.1:1/private\r\n") }) { api,_->
            try { api.health();fail("Redirect must fail") } catch(e:CatalogHttpException) { assertEquals(302,e.status) }
        }
    }
    @Test fun externalCoverRequestContainsNoAccountHeader() {
        server({ request->assertNull(request.headers["authorization"]);Response("image") }) { api,url->
            assertEquals("image",api.image("$url/cover.jpg") { String(it.readBytes(),Charsets.UTF_8) })
        }
    }
    @Test fun multipartSendsIdempotencyKeyWithoutPersonalReadingData() {
        server({ request->
            assertTrue(request.line.startsWith("POST /v1/books "));assertTrue(request.headers["content-type"]!!.startsWith("multipart/form-data"))
            assertTrue(request.body.contains("request_id"));assertTrue(request.body.contains("request-key"));assertFalse(request.body.contains("currentPage"))
            Response("""{"catalog_source":"source","uploaded_url":null,"book":{"id":"12","title":"Title"}}""")
        }) { api,_->val result=api.write("""{"request_id":"request-key","title":"Title"}""",null);assertEquals("12",result.first.id);assertNull(result.third) }
    }
    @Test fun privateHttpAndHttpsAreAcceptedButCredentialUrlsAreRejected() {
        assertTrue(CatalogApi.validAddress("http://192.168.0.10:8088"));assertTrue(CatalogApi.validAddress("http://100.64.0.10:8088"))
        assertTrue(CatalogApi.validAddress("https://viblio.com.br"));assertFalse(CatalogApi.validAddress("http://example.org"))
        assertFalse(CatalogApi.validAddress("https://user:password@example.org"));assertFalse(CatalogApi.validAddress("file:///tmp/a"))
        assertFalse(CatalogApi.validAddress("https://example.org/?token=secret"))
    }
    @Test fun fillRetainsLocalIdentityProgressRatingAndManualCoverWithoutInventingPages() {
        val original=LibraryBook(id="personal-id",title="Rascunho",author="Antigo",totalPages=200,currentPage=0,cover="manual.jpg",rating=4.5)
        val result=CatalogBook("12","source","Título",subgenre="Dark; Épica").fill(original)
        assertEquals(original.id,result.id);assertEquals("manual.jpg",result.cover);assertEquals(original.rating,result.rating)
        assertEquals("",result.author);assertEquals(0,result.totalPages);assertFalse(result.pagesKnown)
        assertEquals(listOf("Dark","Épica"),result.selectedSubgenres);assertEquals("12",result.catalogId)
    }
}
