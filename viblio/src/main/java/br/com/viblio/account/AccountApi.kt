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
open class AccountConnection {
    private var cancelled=false
    private var call:okhttp3.Call?=null
    @Synchronized internal fun attach(value:okhttp3.Call):Boolean {
        if(cancelled){value.cancel();return false}
        call=value;return true
    }
    @Synchronized internal fun detach(){call=null}
    @Synchronized fun cancel(){cancelled=true;call?.cancel();call=null}
}
class DiscussionConnection:AccountConnection()
class AccountFailure(val code:Int,val response:JsonObject,message:String):java.io.IOException(message)
class AccountApi internal constructor(private val address:String,private val token:String,private val client:OkHttpClient) {
    constructor(address:String,token:String=""):this(address,token,OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build())
    init {require(CatalogApi.validAddress(address)) {"Use HTTPS ou o endereço privado/Tailscale do servidor."}}
    @Volatile private var closed=false
    private val activeCalls:MutableSet<okhttp3.Call> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<okhttp3.Call,Boolean>())
    private val picturesClient by lazy {client.newBuilder().readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()}
    fun call(path:String,method:String="GET",body:JsonObject?=null,gzip:Boolean=false,responseLimit:Int=34*1024*1024,timeoutSeconds:Long=0,tag:Any?=null,connection:AccountConnection?=null):JsonObject {
        check(!closed){"Conexão encerrada."}
        val content=body?.toString()?.toByteArray()
        require(!gzip || content==null || content.size<=32*1024*1024) {"O backup excede 32 MB. Sua Estante local foi preservada."}
        val bytes=if(gzip && content!=null)ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(content)}}.toByteArray() else content
        require(timeoutSeconds in 0..120){"Prazo da requisição inválido."}
        val request=Request.Builder().url(address.trimEnd('/')+path).tag(tag)
        if(token.isNotBlank())request.header("Authorization","Bearer $token")
        if(method!="GET")request.method(method,(bytes ?: ByteArray(0)).toRequestBody((if(gzip)"application/gzip" else "application/json").toMediaType()))
        val call=client.newCall(request.build())
        if(timeoutSeconds>0)call.timeout().timeout(timeoutSeconds,TimeUnit.SECONDS)
        if(connection?.attach(call)==false)throw java.io.IOException("Requisição cancelada.")
        try {return execute(call) {response->
            val stream=requireNotNull(response.body).byteStream()
            val input=if(response.header("Content-Type").orEmpty().startsWith("application/gzip"))GZIPInputStream(stream) else stream
            val out=ByteArrayOutputStream();input.use {source->val buffer=ByteArray(8192);while(true){val n=source.read(buffer);if(n<0)break;require(out.size()+n<=responseLimit){"Resposta do servidor muito grande."};out.write(buffer,0,n)}}
            val parsed=runCatching {JsonParser.parseString(out.toString("UTF-8")).asJsonObject}.getOrDefault(JsonObject())
            if(!response.isSuccessful){
                if(response.code==404 && path.startsWith("/v1/auth/"))throw AccountFailure(404,parsed,"Atualize a API do servidor para habilitar contas.")
                val detail=parsed.get("detail");val message=when {detail==null->"Servidor recusou a operação (HTTP ${response.code}).";detail.isJsonPrimitive->detail.asString;detail.isJsonObject->detail.asJsonObject.get("message")?.asString ?: "Confira os dados da conta.";else->"Confira os dados informados."}
                throw AccountFailure(response.code,parsed,message)
            };parsed
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
                while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=96*1024){"Capa acima do limite."};output.write(buffer,0,count)}
            };output.toByteArray()
        }
    }
    private fun <T> execute(call:okhttp3.Call,read:(okhttp3.Response)->T):T {
        // Dispatcher stops tracking synchronous calls when headers arrive. Keep them
        // cancellable until the body is consumed, including stalled mobile transfers.
        activeCalls.add(call)
        try {check(!closed){"Conexão encerrada."};return call.execute().use(read)}
        finally {activeCalls.remove(call)}
    }
    fun cancel(){activeCalls.forEach {it.cancel()};client.dispatcher.cancelAll()}
    fun cancel(tag:Any){
        if(tag is AccountConnection)tag.cancel()
        activeCalls.filter {it.request().tag()===tag}.forEach {it.cancel()}
    }
    @Synchronized fun close(){
        if(closed)return
        closed=true
        // Closing an idle TLS socket writes close_notify. It must never run on the UI thread.
        client.dispatcher.executorService.execute {
            try {cancel();client.connectionPool.evictAll()}
            finally {client.dispatcher.executorService.shutdown()}
        }
    }
}
