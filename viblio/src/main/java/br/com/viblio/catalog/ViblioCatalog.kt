package br.com.viblio.catalog

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import br.com.centralcasa.books.BookImages
import br.com.centralcasa.books.LibraryBook
import br.com.centralcasa.books.LibraryRepository
import br.com.centralcasa.books.catalog.*
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.centralcasa.ui.DisplayUi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import java.util.concurrent.Executors

class ViblioCatalog(private val activity:Activity,private val changed:(Boolean)->Unit,private val personalToken:()->String?={null},private val personalMode:()->Boolean={false},private val scanner:(()->Unit)?=null):BookCatalog {
    override val supportsIsbnScan get()=scanner!=null
    override fun scanIsbn(){scanner?.invoke()}
    private val ui=DisplayUi(activity)
    private val prefs=activity.getSharedPreferences("viblio_catalog",0)
    private val credentials=HomeAssistantCredentials(activity,"viblio_catalog_credentials","viblio_catalog_key")
    private val browse=Executors.newFixedThreadPool(2)
    private val writer=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val images=BookImages(activity)
    private val outbox=CatalogStore(activity)
    private val library=LibraryRepository(activity)
    @Volatile private var closed=false
    override val configured get()=prefs.getString("url","").orEmpty().isNotBlank() && (personalToken() ?: credentials.load()).isNotBlank()
    private fun api()=CatalogApi(prefs.getString("url","").orEmpty(),personalToken() ?: credentials.load())
    private fun post(action:()->Unit) { handler.post { if(!closed && !activity.isFinishing) action() } }
    private fun toast(value:String) { post { Toast.makeText(activity,value,Toast.LENGTH_LONG).show() } }
    override fun configure() {
        if(personalMode()){toast("Configure a conexão em Conta.");return}
        if(!credentials.supported) { toast("O catálogo precisa do Android 6 ou superior. A biblioteca local continua disponível.");return }
        val body=ui.column().apply { setPadding(ui.dp(18),ui.dp(12),ui.dp(18),0) }
        ui.label(body,"Servidor do catálogo",13,ui.muted)
        val address=EditText(activity).apply { setText(prefs.getString("url",""));hint="http://IP-do-servidor:8088";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setTextColor(ui.text);setHintTextColor(ui.muted) };body.addView(address)
        ui.label(body,"Chave de acesso",13,ui.muted)
        val token=EditText(activity).apply { hint=if(configured) "Deixe vazio para manter a chave" else "Chave gerada no servidor";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setTextColor(ui.text);setHintTextColor(ui.muted) };body.addView(token)
        ui.label(body,"${outbox.count()} envios pendentes. Sua biblioteca continua disponível sem conexão.",12,ui.muted)
        val dialog=AlertDialog.Builder(activity).setTitle("Catálogo do servidor").setView(body).setPositiveButton("Conectar",null).setNeutralButton("Reenviar pendentes",null).setNegativeButton("Fechar",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { retry();toast("Tentando enviar os livros pendentes.") }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url=address.text.toString().trim().trimEnd('/');val key=token.text.toString().trim().ifBlank { credentials.load() }
            if(!CatalogApi.validAddress(url) || key.length<32) { toast("Confira o endereço e a chave do catálogo.");return@setOnClickListener }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            browse.execute {
                val response=runCatching { CatalogApi(url,key).health() }
                post { response.fold({ source ->
                    credentials.save(key);prefs.edit().putString("url",url).putString("source",source).commit()
                    dialog.dismiss();toast("Catálogo conectado.");changed(true);retry()
                },{ dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true;toast(it.message ?: "Não foi possível conectar.") }) }
            }
        }
    }
    override fun search(title:String,author:String,isbn:String,done:(Result<List<CatalogBook>>)->Unit) {
        browse.execute { val response=runCatching { api().search(title,author,isbn).also { rows->rows.firstOrNull()?.let { prefs.edit().putString("source",it.source).apply() } } };post { done(response) } }
    }
    override fun recommendations(kind:String,seed:Int,done:(Result<CatalogRecommendations>)->Unit) {
        if(!configured || !personalMode()) { done(Result.success(CatalogRecommendations("","none",emptyList())));return }
        browse.execute {
            val response=runCatching { api().recommendations(kind,seed) }.recoverCatching { err ->
                if(err is CatalogHttpException && err.status in listOf(404,405)) CatalogRecommendations("","none",emptyList()) else throw err
            }
            post { done(response) }
        }
    }
    override fun wishlist(done:(Result<List<CatalogBook>>)->Unit) {
        if(!configured || !personalMode()) { done(Result.success(emptyList()));return }
        browse.execute {
            val response=runCatching { api().wishlist() }.recoverCatching { err ->
                if(err is CatalogHttpException && err.status in listOf(404,405)) emptyList() else throw err
            }
            post { done(response) }
        }
    }
    override fun addWishlist(book:CatalogBook,done:(Result<CatalogBook>)->Unit) {
        if(!configured || !personalMode()) { done(Result.failure(IllegalStateException("Entre na sua conta para salvar em Quero comprar.")));return }
        browse.execute {
            val response=runCatching { api().addWishlist(book) }
            post { done(response) }
        }
    }
    override fun removeWishlist(catalogId:String,done:(Result<Unit>)->Unit) {
        if(!configured || !personalMode()) { done(Result.success(Unit));return }
        browse.execute {
            val response=runCatching { api().removeWishlist(catalogId) }
            post { done(response) }
        }
    }
    override fun loadCover(book:CatalogBook,done:(Result<String>)->Unit) {
        browse.execute {
            val response=runCatching { api().image(book.coverUrl,images::save).also { prefs.edit().putBoolean("cached:$it",true).commit() } }
            post { done(response) }
        }
    }
    override fun chooseCover(id:String,source:String,done:(Result<CatalogCover>)->Unit) {
        if(!configured) { done(Result.failure(IllegalStateException("Conecte o catálogo para ver as alternativas.")));return }
        browse.execute {
            val response=runCatching {
                val client=api();val health=client.healthInfo()
                check(health.second>=2) { "Atualize a API do servidor para usar capas alternativas." }
                check(health.first==source) { "Este item pertence a outro catálogo." }
                client.coverPage(id).also { check(it.source==source) { "O catálogo mudou. Tente novamente." } }
            }
            post { response.fold({ variants ->
                if(variants.items.isEmpty()) { done(Result.failure(IllegalStateException("Este registro ainda não tem capas.")));return@fold }
                br.com.viblio.ui.CoverGalleryDialog.show(activity,variants.items,more=if(variants.nextCursor==null)null else {cursor,callback->
                    browse.execute {
                        val page=runCatching {api().coverPage(id,cursor).also {check(it.source==source){"O catálogo mudou. Tente novamente."}}}
                        post {callback(page)}
                    }
                },nextCursor=variants.nextCursor) {done(Result.success(it))}
            },{ done(Result.failure(it)) }) }
        }
    }
    override fun saved(input:LibraryBook) {
        if(personalMode())return
        val book=library.book(input.id)?.takeIf { input.catalogId==null && it.catalogId!=null } ?: input
        if(book.collectionId.isNotBlank() && book.sequenceNumber==null && book.catalogId==null) { toast("Informe o número do volume para enviar sua capa ao catálogo.");return }
        val source=prefs.getString("source","").orEmpty()
        val linked=book.catalogId!=null && book.catalogSource==source
        val manual=book.cover.takeIf { it.isNotBlank() && !prefs.getBoolean("cached:$it",false) && prefs.getString("uploaded:${book.id}","")!=it }.orEmpty()
        if(linked && manual.isBlank()) return
        val metadata=JsonObject().apply {
            addProperty("request_id",UUID.randomUUID().toString());if(linked) addProperty("catalog_id",book.catalogId)
            if(book.kind!="book") addProperty("kind",book.kind)
            if(book.collectionId.isNotBlank()) addProperty("is_volume",true)
            addProperty("title",book.title);addProperty("author",book.author);addProperty("publisher",book.publisher);addProperty("genre",book.genre)
            add("subgenres",com.google.gson.JsonArray().apply { book.selectedSubgenres.forEach(::add) });addProperty("saga",book.saga)
            book.sequenceNumber?.let { addProperty("sequence",it) };if(book.pagesKnown) addProperty("pages",book.totalPages)
        }
        outbox.queue(PendingBook(book.id,metadata.toString(),manual,book.catalogSource))
        if(configured) retry() else toast("Salvo no aparelho. O envio ficará pendente até conectar o catálogo.")
    }
    private val repairing=java.util.concurrent.atomic.AtomicBoolean(false)
    private fun repairMissingCovers(){
        if(closed || !configured || !repairing.compareAndSet(false,true))return
        browse.execute {
            try {
                val candidates=library.books().filter {it.cover.isBlank() && it.catalogId!=null}
                if(candidates.isEmpty())return@execute
                val client=api();val source=client.health()
                val missing=candidates.filter {it.cover.isBlank() && it.catalogId!=null && it.catalogSource==source}.take(8)
                var applied=false
                for(book in missing){
                    if(closed)break
                    runCatching {
                        val page=client.coverPage(requireNotNull(book.catalogId));check(page.source==source)
                        // Only this exact edition's primary cover is filled automatically.
                        val primary=page.items.firstOrNull {it.primary} ?: return@runCatching
                        val name=client.image(primary.url,images::save)
                        if(!closed && library.fillMissingCover(book.id,requireNotNull(book.catalogId),source,name))applied=true
                    }
                }
                if(applied)post {changed(false)}
            }catch(_:Exception){/* Offline remains local; another start can retry. */}
            finally {repairing.set(false)}
        }
    }
    fun retry() {
        repairMissingCovers()
        if(personalMode() || !configured || closed) return
        writer.execute {
            val pendingRows=outbox.rows()
            if(pendingRows.isEmpty()) return@execute
            val client=runCatching { api() }.getOrElse { return@execute }
            val health=runCatching { client.healthInfo() }.getOrElse { post { toast("Envio pendente. ${it.message ?: "Catálogo indisponível."}") };return@execute }
            val source=health.first
            for(pending in pendingRows) {
                var row=pending
                if(row.source.isNotBlank() && row.source!=source) {
                    val data=JsonParser.parseString(row.metadata).asJsonObject
                    data.remove("catalog_id");data.addProperty("request_id",UUID.randomUUID().toString())
                    row=row.copy(metadata=data.toString(),source=source);outbox.queue(row)
                }
                if(closed) break
                val current=library.book(row.id)
                if(current==null) { outbox.complete(row);continue }
                val queued=JsonParser.parseString(row.metadata).asJsonObject
                if(!queued.has("catalog_id") && current.catalogId!=null && current.catalogSource==source) {
                    queued.addProperty("catalog_id",current.catalogId);queued.addProperty("request_id",UUID.randomUUID().toString())
                    row=row.copy(metadata=queued.toString(),source=source);outbox.queue(row)
                }
                try {
                    val cover=row.cover.takeIf(String::isNotBlank)?.let { images.file(it) ?: error("Capa local não encontrada. Salve o livro novamente.") }
                    val data=JsonParser.parseString(row.metadata).asJsonObject
                    if(health.second<2 && (data.get("kind")?.asString in listOf("manga","comic") || data.get("is_volume")?.asBoolean==true)) error("Atualize a API do servidor para enviar capas de volumes.")
                    val result=client.write(row.metadata,cover)
                    library.setCatalogBinding(row.id,result.first.id,result.second)
                    if(row.cover.isNotBlank()) prefs.edit().putString("uploaded:${row.id}",row.cover).apply()
                    outbox.complete(row)
                    post { toast("Livro disponível no catálogo.");changed(false) }
                } catch(e:Exception) {
                    post { toast("Salvo no aparelho; envio pendente. ${e.message ?: "Confira a conexão."}") };break
                }
            }
        }
    }
    fun close() {
        closed=true;handler.removeCallbacksAndMessages(null);browse.shutdown()
        writer.execute { outbox.close();library.close() };writer.shutdown()
    }
}
