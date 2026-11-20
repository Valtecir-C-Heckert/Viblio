package br.com.centralcasa.books.catalog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** API credentials are never attached to external cover requests or redirected requests. */
class CatalogApi(val address:String,private val token:String) {
    private val api=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val publicImages=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
    init { require(validAddress(address)) { "Use HTTPS ou um endereço HTTP da rede local/Tailscale." };require(token.length>=32) { "Confira a chave do catálogo." } }
    fun health():String=healthInfo().first
    fun healthInfo():Pair<String,Int> {
        val body=call(Request.Builder().url(address.trimEnd('/')+"/v1/health"))
        return body.get("catalog_source").asString to (body.get("api_version")?.asInt ?: 1)
    }
    fun covers(id:String):Pair<String,List<CatalogCover>> = coverPage(id).let {it.source to it.items}
    fun coverPage(id:String,cursor:Int=0):CatalogCoverPage {
        require(id.toLongOrNull()!=null && cursor in 0..10000) {"Identificador do catálogo inválido."}
        val response=call(Request.Builder().url(address.trimEnd('/')+"/v1/books/$id/covers?limit=30&cursor=$cursor"))
        fun text(row:JsonObject,key:String)=row.get(key)?.takeUnless {it.isJsonNull}?.asString.orEmpty()
        val items=response.getAsJsonArray("items").map {element->
            val row=element.asJsonObject
            CatalogCover(text(row,"id"),text(row,"url"),row.get("primary")?.asBoolean ?: false,text(row,"book_id"),text(row,"title"),text(row,"publisher"),text(row,"isbn"),text(row,"pages").toIntOrNull(),text(row,"sequence").toIntOrNull())
        }
        val next=response.get("next_cursor")?.takeUnless {it.isJsonNull}?.asInt
        require(next==null || next>cursor && next<=10000){"Paginação de capas inválida."}
        return CatalogCoverPage(response.get("catalog_source").asString,items,next,response.get("truncated")?.asBoolean ?: false)
    }
    fun search(title:String,author:String="",isbn:String=""):List<CatalogBook> {
        val url=okhttp3.HttpUrl.Builder().scheme(URI(address).scheme).host(URI(address).host).port(URI(address).port.takeIf { it>0 } ?: if(URI(address).scheme=="https") 443 else 80)
            .addPathSegments(URI(address).path.trim('/')+if(URI(address).path.trim('/').isBlank()) "v1/books" else "/v1/books")
            .addQueryParameter("q",title).addQueryParameter("author",author).addQueryParameter("isbn",isbn).addQueryParameter("limit","20").build()
        val body=call(Request.Builder().url(url));val source=body.get("catalog_source").asString
        return body.getAsJsonArray("items").map { decode(it.asJsonObject,source) }
    }
    fun recommendations(kind:String="all",seed:Int=0,anchorTitle:String="",anchorAuthor:String="",anchorGenre:String="",anchorSubgenre:String=""):CatalogRecommendations {
        val safeKind=if(kind in listOf("all","book","manga","comic")) kind else "all"
        val safeSeed=seed.coerceIn(0,10000)
        val urlBuilder=okhttp3.HttpUrl.Builder().scheme(URI(address).scheme).host(URI(address).host).port(URI(address).port.takeIf { it>0 } ?: if(URI(address).scheme=="https") 443 else 80)
            .addPathSegments(URI(address).path.trim('/')+if(URI(address).path.trim('/').isBlank()) "v1/books/recommendations" else "/v1/books/recommendations")
            .addQueryParameter("kind",safeKind).addQueryParameter("seed",safeSeed.toString()).addQueryParameter("per_section","10")
        if(anchorTitle.isNotBlank()) urlBuilder.addQueryParameter("anchor_title",anchorTitle.trim().take(200))
        if(anchorAuthor.isNotBlank()) urlBuilder.addQueryParameter("anchor_author",anchorAuthor.trim().take(200))
        if(anchorGenre.isNotBlank()) urlBuilder.addQueryParameter("anchor_genre",anchorGenre.trim().take(200))
        if(anchorSubgenre.isNotBlank()) urlBuilder.addQueryParameter("anchor_subgenre",anchorSubgenre.trim().take(200))
        val body=call(Request.Builder().url(urlBuilder.build()))
        val source=body.get("catalog_source")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val resAnchorTitle=body.get("anchor_title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val resAnchorKind=body.get("anchor_kind")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val sections=body.getAsJsonArray("sections")?.mapNotNull { el ->
            val obj=el.asJsonObject
            val key=obj.get("key")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val title=obj.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val tag=obj.get("tag")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val items=obj.getAsJsonArray("items")?.mapNotNull { item -> runCatching { decode(item.asJsonObject,source) }.getOrNull() } ?: emptyList()
            if(items.isEmpty()) null else RecommendationSection(key,title,tag,items)
        } ?: emptyList()
        return CatalogRecommendations(resAnchorTitle,resAnchorKind,sections)
    }
    fun wishlist():List<CatalogBook> {
        val body=call(Request.Builder().url(address.trimEnd('/')+"/v1/me/wishlist"))
        val source=body.get("catalog_source")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        return body.getAsJsonArray("items")?.mapNotNull { item ->
            val obj=item.asJsonObject
            val itemSource=obj.get("source")?.takeUnless { it.isJsonNull }?.asString?.ifBlank { source } ?: source
            runCatching { decode(obj,itemSource) }.getOrNull()
        } ?: emptyList()
    }
    fun addWishlist(book:CatalogBook):CatalogBook {
        require(book.id.toLongOrNull()!=null) { "Identificador do catálogo inválido." }
        val payload=JsonObject().apply {
            addProperty("catalog_id",book.id)
            addProperty("source",book.source)
            addProperty("title",book.title.take(500))
            addProperty("author",book.author.take(300))
            addProperty("genre",book.genre.take(200))
            addProperty("subgenre",book.subgenre.take(300))
            addProperty("publisher",book.publisher.take(200))
            addProperty("saga",book.saga.take(200))
            book.pages?.let { addProperty("pages",it) }
            book.sequence?.let { addProperty("sequence",it) }
            addProperty("cover_url",book.coverUrl.take(2048))
            addProperty("isbn",book.isbn.take(32))
            addProperty("category",book.category.take(100))
            addProperty("type",book.type.take(100))
        }
        val body=call(Request.Builder().url(address.trimEnd('/')+"/v1/me/wishlist").post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())))
        val source=body.get("catalog_source")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        return decode(body.getAsJsonObject("item"),book.source.ifBlank { source })
    }
    fun removeWishlist(catalogId:String) {
        require(catalogId.toLongOrNull()!=null) { "Identificador do catálogo inválido." }
        call(Request.Builder().url(address.trimEnd('/')+"/v1/me/wishlist/$catalogId").delete())
    }
    fun write(metadata:String,cover:File?):Triple<CatalogBook,String,String?> {
        val multipart=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("metadata",metadata)
        cover?.let { require(it.length()<=12*1024*1024) { "A capa precisa ter até 12 MB." };multipart.addFormDataPart("cover","cover.jpg",it.asRequestBody("image/jpeg".toMediaType())) }
        val response=call(Request.Builder().url(address.trimEnd('/')+"/v1/books").post(multipart.build()))
        val source=response.get("catalog_source").asString
        return Triple(decode(response.getAsJsonObject("book"),source),source,response.get("uploaded_url")?.takeUnless { it.isJsonNull }?.asString)
    }
    fun image(url:String,accept:(java.io.InputStream)->String):String {
        val uri=URI(url);require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null) { "URL de capa inválida." }
        // Independent client with no account headers, even for covers stored on the API server.
        return publicImages.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Não foi possível carregar a capa." }
            require(response.body!!.contentLength()<=12*1024*1024) { "A capa precisa ter até 12 MB." }
            response.body!!.byteStream().use(accept)
        }
    }
    private fun call(builder:Request.Builder):JsonObject = api.newCall(builder.header("Authorization","Bearer $token").build()).execute().use { response ->
        if(!response.isSuccessful) throw CatalogHttpException(response.code,when(response.code) { 401,403->"Confira a chave de acesso do catálogo.";404->"Confira o endereço e o livro selecionado no catálogo.";409->"O envio mudou. Salve o livro novamente.";413->"A capa precisa ter até 12 MB.";else->"Catálogo indisponível. Tente novamente." })
        val body=response.body ?: error("Resposta vazia do catálogo.")
        val bytes=body.byteStream().use { input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var count:Int;while(input.read(buffer).also { count=it }!=-1) { require(out.size()+count<=1024*1024) { "Resposta do catálogo muito grande." };out.write(buffer,0,count) };out.toByteArray() }
        JsonParser.parseString(String(bytes,Charsets.UTF_8)).asJsonObject
    }
    companion object {
        fun validAddress(value:String):Boolean = runCatching {
            val uri=URI(value.trimEnd('/'));val host=uri.host?.lowercase() ?: return false
            if(uri.userInfo!=null || uri.query!=null || uri.fragment!=null || uri.scheme !in listOf("http","https")) return false
            val octets=host.split('.').mapNotNull(String::toIntOrNull)
            val privateIp=octets.size==4 && octets.all { it in 0..255 } && (octets[0]==10 || octets[0]==127 || octets[0]==192 && octets[1]==168 || octets[0]==172 && octets[1] in 16..31 || octets[0]==100 && octets[1] in 64..127)
            uri.scheme=="https" || privateIp || host=="localhost" || host.endsWith(".local") || host.endsWith(".ts.net")
        }.getOrDefault(false)
        private fun decode(row:JsonObject,source:String):CatalogBook {
            fun text(key:String)=row.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            fun number(key:String)=text(key).toIntOrNull()?.takeIf { it>0 }
            val id=text("id");require(id.toLongOrNull()!=null && source.isNotBlank() && text("title").isNotBlank()) { "Resposta de livro inválida." }
            return CatalogBook(id,source,text("title"),text("author"),text("genre"),text("subgenre"),text("publisher"),text("saga"),number("pages")?.takeIf { it<=100000 },number("sequence")?.takeIf { it<=10000 },text("cover_url"),text("isbn"),text("category"),text("type"))
        }
    }
}
class CatalogHttpException(val status:Int,message:String):java.io.IOException(message)
