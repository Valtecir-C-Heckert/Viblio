package br.com.viblio.homeassistant

import br.com.centralcasa.books.LibraryBook
import br.com.centralcasa.books.catalog.CatalogApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URI
import java.util.concurrent.TimeUnit

object ReadingWebhook {
    fun validUrl(value:String):Boolean=runCatching {
        val u=URI(value)
        u.userInfo==null && u.query==null && u.fragment==null &&
            u.path.matches(Regex("/api/webhook/[a-f0-9]{64}")) &&
            CatalogApi.validAddress("${u.scheme}://${u.rawAuthority}")
    }.getOrDefault(false)
    fun snapshot(book:LibraryBook?,color:String?):JsonObject=JsonObject().apply {
        addProperty("version",1);addProperty("source","viblio")
        if(book==null) add("book",com.google.gson.JsonNull.INSTANCE)
        else add("book",JsonObject().apply {
            addProperty("id",book.id);addProperty("title",book.title.take(500));addProperty("author",book.author.take(500))
            addProperty("kind",book.kind);addProperty("pages_known",book.pagesKnown)
            addProperty("current_page",book.currentPage);addProperty("total_pages",book.totalPages)
            addProperty("finished",book.complete);book.sequenceNumber?.let { addProperty("volume",it) }
            color?.let { addProperty("color",it) }
        })
    }
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun send(url:String,body:JsonObject) {
        require(validUrl(url)) { "Confira a URL de pareamento do Home Assistant." }
        client.newCall(Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            check(response.isSuccessful) { "O Home Assistant recusou o envio (HTTP ${response.code})." }
            val text=response.body?.string().orEmpty()
            check(text.length<1024 && runCatching { JsonParser.parseString(text).asJsonObject.get("viblio").asBoolean }.getOrDefault(false)) {
                "Integração Viblio não encontrada. Confira a instalação e o pareamento no HA."
            }
        }
    }
}
