package br.com.viblio.account
import br.com.centralcasa.books.catalog.CatalogApi
import com.google.gson.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
private val cancelExecutor=java.util.concurrent.Executors.newCachedThreadPool {r->Thread(r,"viblio-cancel").apply {isDaemon=true}}
private fun cancelCallAsync(value:okhttp3.Call){cancelExecutor.execute {runCatching {value.cancel()}}}
open class AccountConnection {
    private var cancelled=false
    private var call:okhttp3.Call?=null
    @Synchronized internal fun attach(value:okhttp3.Call):Boolean {
        if(cancelled){cancelCallAsync(value);return false}
        call=value;return true
    }
    @Synchronized internal fun detach(){call=null}
    @Synchronized fun cancel(){cancelled=true;val pending=call;call=null;if(pending!=null)cancelCallAsync(pending)}
}
class DiscussionConnection:AccountConnection()
class AccountFailure(val code:Int,val response:JsonObject,message:String):java.io.IOException(message)
class AccountApi internal constructor(private val address:String,private val token:String,private val client:OkHttpClient,private val ownsClient:Boolean) {
    internal constructor(address:String,token:String,client:OkHttpClient):this(address,token,client,ownsClient=true)
    constructor(address:String,token:String=""):this(address,token,br.com.centralcasa.books.catalog.ViblioHttp.account,ownsClient=false)
    init {require(CatalogApi.validAddress(address)) {"Use HTTPS ou o endereço privado/Tailscale do servidor."}}
    @Volatile private var closed=false
    private val activeCalls:MutableSet<okhttp3.Call> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<okhttp3.Call,Boolean>())
    private val picturesClient by lazy {client.newBuilder().readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()}
    fun call(path:String,method:String="GET",body:JsonObject?=null,gzip:Boolean=false,responseLimit:Int=34*1024*1024,timeoutSeconds:Long=0,tag:Any?=null,connection:AccountConnection?=null,extraHeaders:Map<String,String> = emptyMap()):JsonObject {
        check(!closed){"Conexão encerrada."}
        val content=body?.toString()?.toByteArray()
        require(!gzip || content==null || content.size<=32*1024*1024) {"O backup excede 32 MB. Sua Estante local foi preservada."}
        val bytes=if(gzip && content!=null)ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(content)}}.toByteArray() else content
        require(timeoutSeconds in 0..120){"Prazo da requisição inválido."}
        val request=Request.Builder().url(address.trimEnd('/')+path).tag(tag)
        if(token.isNotBlank())request.header("Authorization","Bearer $token")
        extraHeaders.forEach {(k,v)->request.header(k,v)}
        if(method!="GET")request.method(method,(bytes ?: ByteArray(0)).toRequestBody((if(gzip)"application/gzip" else "application/json").toMediaType()))
        val call=client.newCall(request.build())
        if(timeoutSeconds>0)call.timeout().timeout(timeoutSeconds,TimeUnit.SECONDS)
        if(connection?.attach(call)==false)throw java.io.IOException("Requisição cancelada.")
        try {return execute(call) {response->
            val stream=requireNotNull(response.body).byteStream()
            val input=if(response.header("Content-Type").orEmpty().startsWith("application/gzip"))GZIPInputStream(stream) else stream
            val out=ByteArrayOutputStream();input.use {source->val buffer=ByteArray(8192);while(true){val n=source.read(buffer);if(n<0)break;require(out.size()+n<=responseLimit){"Resposta do servidor muito grande."};out.write(buffer,0,n)}}
            val text=out.toString("UTF-8")
            val parsed=runCatching {JsonParser.parseString(text).asJsonObject}.getOrNull()
            if(!response.isSuccessful){
                val errorBody=parsed ?: JsonObject()
                if(response.code==404 && path.startsWith("/v1/auth/"))throw AccountFailure(404,errorBody,"Atualize a API do servidor para habilitar contas.")
                val detail=errorBody.get("detail");val message=when {detail==null->"Servidor recusou a operação (HTTP ${response.code}).";detail.isJsonPrimitive->detail.asString;detail.isJsonObject->detail.asJsonObject.get("message")?.asString ?: "Confira os dados da conta.";else->"Confira os dados informados."}
                throw AccountFailure(response.code,errorBody,message)
            }
            if(parsed==null){
                if(text.isBlank() && response.code==204) return@execute JsonObject()
                throw java.io.IOException("Resposta inválida do servidor. Tente novamente.")
            }
            parsed
        }}finally {connection?.detach()}
    }
    /** One authenticated SSE stream, independently cancellable from writes and cover loads. */
    fun discussionEvents(path:String,tag:DiscussionConnection,onEvent:(String)->Unit) {
        check(!closed){"Conexão encerrada."}
        require(path.matches(Regex("/v1/clubs/[a-f0-9-]{36}/stages/[a-f0-9-]{36}/events"))){"Discussão inválida."}
        val request=Request.Builder().url(address.trimEnd('/')+path).tag(tag)
            .header("Authorization","Bearer $token").header("Accept","text/event-stream").build()
        val streaming=client.newBuilder().readTimeout(45,TimeUnit.SECONDS).callTimeout(0,TimeUnit.SECONDS).build()
        val call=streaming.newCall(request)
        if(!tag.attach(call))return
        try {
            check(!closed){"Conexão encerrada."}
            execute(call) {response->
            if(!response.isSuccessful)throw AccountFailure(response.code,JsonObject(),"Discussão indisponível (HTTP ${response.code}).")
            check(response.header("Content-Type").orEmpty().startsWith("text/event-stream")){"Atualize a API para comentários ao vivo."}
            val source=requireNotNull(response.body).source()
            var event="";var size=0L
            while(!closed){
                if(source.exhausted())break
                val line=source.readUtf8LineStrict(4096);size+=line.length
                check(size<=8192){"Evento acima do limite."}
                if(line.isEmpty()){
                    if(event in listOf("ready","changed","revoked"))onEvent(event)
                    if(event=="revoked")return@execute
                    event="";size=0
                }else if(line.startsWith("event:"))event=line.substringAfter(':').trim()
            }
        }
        }finally {tag.detach()}
    }
    fun thumbnail(path:String):ByteArray {
        check(!closed){"Conexão encerrada."}
        val uri=java.net.URI(path)
        require(path.length<=2048 && uri.scheme==null && uri.host==null && uri.fragment==null && uri.path.matches(Regex("/v1/(friends/[a-f0-9-]{36}/cover|clubs/[a-f0-9-]{36}/(?:proposals/[a-f0-9-]{36}/cover|library-cover))"))) {"Caminho de capa inválido."}
        val request=Request.Builder().url(address.trimEnd('/')+path).header("Authorization","Bearer $token").header("Cache-Control","no-store").build()
        return execute(picturesClient.newCall(request)) {response->
            check(response.isSuccessful && response.header("Content-Type").orEmpty().startsWith("image/jpeg")){"Capa indisponível."}
            val output=ByteArrayOutputStream();requireNotNull(response.body).byteStream().use {input->
                val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=256*1024){"Capa acima do limite."};output.write(buffer,0,count)}
            };output.toByteArray()
        }
    }
    fun checkMedia(hashes:List<String>):List<String> {
        check(!closed){"Conexão encerrada."}
        val clean=hashes.distinct().onEach {require(it.matches(SHA_HEX_RE)){"Identificador de mídia inválido."}}
        if(clean.isEmpty())return emptyList()
        val missing=mutableListOf<String>()
        for(batch in clean.chunked(500)){
            val payload=JsonObject().apply {add("hashes",JsonArray().apply {batch.forEach(::add)})}
            val resp=call("/v1/me/media/check","POST",payload,responseLimit=512*1024,timeoutSeconds=30)
            resp.getAsJsonArray("missing")?.forEach {el->
                val sha=el.asString
                require(sha.matches(SHA_HEX_RE)){"Resposta de mídia inválida."}
                missing.add(sha)
            }
        }
        return missing
    }
    fun uploadMedia(sha256:String,jpegBytes:ByteArray) {
        check(!closed){"Conexão encerrada."}
        require(sha256.matches(SHA_HEX_RE)){"Identificador de mídia inválido."}
        require(jpegBytes.size in 3..MAX_MEDIA_BYTES){"Imagem acima do limite permitido (12 MB)."}
        val request=Request.Builder().url(address.trimEnd('/')+"/v1/me/media/$sha256")
            .header("Authorization","Bearer $token")
            .put(jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val call=client.newCall(request)
        call.timeout().timeout(60,TimeUnit.SECONDS)
        execute(call) {response->
            if(!response.isSuccessful){
                val text=runCatching {response.body?.string().orEmpty()}.getOrDefault("")
                val parsed=runCatching {JsonParser.parseString(text).asJsonObject}.getOrNull() ?: JsonObject()
                val detail=parsed.get("detail")
                val message=when {
                    detail==null->"Não foi possível enviar a imagem (HTTP ${response.code})."
                    detail.isJsonPrimitive->detail.asString
                    detail.isJsonObject->detail.asJsonObject.get("message")?.asString ?: "Não foi possível enviar a imagem."
                    else->"Não foi possível enviar a imagem."
                }
                throw AccountFailure(response.code,parsed,message)
            }
        }
    }
    fun downloadMedia(sha256:String):ByteArray {
        check(!closed){"Conexão encerrada."}
        require(sha256.matches(SHA_HEX_RE)){"Identificador de mídia inválido."}
        val request=Request.Builder().url(address.trimEnd('/')+"/v1/me/media/$sha256")
            .header("Authorization","Bearer $token")
            .build()
        val call=client.newCall(request)
        call.timeout().timeout(60,TimeUnit.SECONDS)
        return execute(call) {response->
            if(!response.isSuccessful){
                throw AccountFailure(response.code,JsonObject(),"Imagem indisponível no servidor (HTTP ${response.code}).")
            }
            check(response.header("Content-Type").orEmpty().startsWith("image/jpeg")){"Formato de imagem inválido."}
            val output=ByteArrayOutputStream()
            requireNotNull(response.body).byteStream().use {input->
                val buffer=ByteArray(8192)
                while(true){
                    val count=input.read(buffer)
                    if(count<0)break
                    require(output.size()+count<=MAX_MEDIA_BYTES){"Imagem acima do limite permitido."}
                    output.write(buffer,0,count)
                }
            }
            output.toByteArray()
        }
    }
    private companion object {
        val SHA_HEX_RE=Regex("[a-f0-9]{64}")
        const val MAX_MEDIA_BYTES=12*1024*1024
    }
    private fun <T> execute(call:okhttp3.Call,read:(okhttp3.Response)->T):T {
        // Dispatcher stops tracking synchronous calls when headers arrive. Keep them
        // cancellable until the body is consumed, including stalled mobile transfers.
        activeCalls.add(call)
        try {check(!closed){"Conexão encerrada."};return call.execute().use(read)}
        finally {activeCalls.remove(call)}
    }
    fun cancel(){
        val calls=activeCalls.toList()
        if(calls.isNotEmpty() || ownsClient) cancelExecutor.execute {
            calls.forEach {runCatching {it.cancel()}}
            if(ownsClient) runCatching {client.dispatcher.cancelAll()}
        }
    }
    fun cancel(tag:Any){
        if(tag is AccountConnection)tag.cancel()
        val calls=activeCalls.filter {it.request().tag()===tag}
        if(calls.isNotEmpty()) cancelExecutor.execute {calls.forEach {runCatching {it.cancel()}}}
    }
    @Synchronized fun close(){
        if(closed)return
        closed=true
        if(!ownsClient){
            val calls=activeCalls.toList()
            if(calls.isNotEmpty()) cancelExecutor.execute { calls.forEach { runCatching { it.cancel() } } }
            return
        }
        // Closing an idle TLS socket writes close_notify. It must never run on the UI thread.
        client.dispatcher.executorService.execute {
            try {activeCalls.toList().forEach {runCatching {it.cancel()}};client.dispatcher.cancelAll();client.connectionPool.evictAll()}
            finally {client.dispatcher.executorService.shutdown()}
        }
    }
}
