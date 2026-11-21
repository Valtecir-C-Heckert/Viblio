package br.com.viblio.account
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import br.com.centralcasa.books.*
import br.com.centralcasa.data.HomeAssistantCredentials
import com.google.gson.*
import java.util.UUID
import java.util.concurrent.Executors
/** Network work stays off the UI thread; incoming changes require a guarded apply. */
class AccountManager(private val activity:Activity,private val changed:()->Unit,private val reloaded:()->Unit,private val canReplace:()->Boolean) {
    private val secret=HomeAssistantCredentials(activity,"viblio_account_session","viblio_account_session_key")
    private val worker=Executors.newSingleThreadExecutor();private val handler=Handler(Looper.getMainLooper())
    private val config=activity.getSharedPreferences("viblio_catalog",0)
    private var session=runCatching {JsonParser.parseString(secret.load()).asJsonObject}.getOrNull()
    private var closed=false;private var running=false;private var busy=false;private var retryAfter=0L
    private var automaticPending=false
    private var conflictProfile:String?=null
    private var progress:ProgressDialog?=null
    var status="";private set
    val signedIn get()=session!=null
    val email get()=session?.getAsJsonObject("user")?.get("email")?.asString.orEmpty()
    val username get()=session?.getAsJsonObject("user")?.get("username")?.takeUnless {it.isJsonNull}?.asString.orEmpty()
    val admin get()=session?.getAsJsonObject("user")?.get("admin")?.asBoolean==true
    val defaultAddress get()=br.com.viblio.ViblioServer.PUBLIC_API_URL
    val address get()=session?.get("address")?.asString ?: defaultAddress.ifBlank {config.getString("url","").orEmpty()}
    private val owner get()=session?.getAsJsonObject("user")?.let {LibraryProfiles.key(it.get("server_id").asString,it.get("id").asString)}.orEmpty()
    val linked get()=signedIn && LibraryProfiles.owner(activity)==owner
    private fun preferences()=activity.getSharedPreferences("viblio_sync"+LibraryProfiles.suffix(activity),0)
    fun catalogToken():String?=session?.get("token")?.asString
    fun personalMode()=config.getBoolean("personal_mode",false)
    private fun api()=AccountApi(address,catalogToken().orEmpty())
    private fun toast(text:String)=Toast.makeText(activity,text,Toast.LENGTH_LONG).show()
    private fun post(action:()->Unit){handler.post {if(!closed && !activity.isFinishing && !activity.isDestroyed)action()}}
    private val scheduled=Runnable {automatic()}
    private fun scheduleAutomatic(delay:Long){handler.removeCallbacks(scheduled);if(running && !closed)handler.postDelayed(scheduled,delay.coerceAtLeast(0))}
    private fun deferAutomatic(delay:Long){automaticPending=true;scheduleAutomatic(delay)}
    private fun resumePending(){if(automaticPending && running && !closed && !busy)scheduleAutomatic(maxOf(250L,retryAfter-System.currentTimeMillis()))}
    private val periodic=object:Runnable {override fun run(){if(running && !closed){automatic();handler.postDelayed(this,60000)}}}
    private var identityLoaded=false
    private var observingNetwork=false
    private val network=object:android.content.BroadcastReceiver(){
        override fun onReceive(context:android.content.Context?,intent:android.content.Intent?){
            retryAfter=0;deferAutomatic(1500)
        }
    }
    fun start(){
        running=true;handler.removeCallbacks(periodic)
        if(!observingNetwork){activity.registerReceiver(network,android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));observingNetwork=true}
        if(signedIn && !identityLoaded){identityLoaded=true;work("Atualizando conta…",modal=false,action={api().call("/v1/me")}) {user->session?.add("user",user);session?.let {secret.save(it.toString())};changed()}}
        handler.post(periodic)
    }
    fun stop(){if(observingNetwork){activity.unregisterReceiver(network);observingNetwork=false};running=false;handler.removeCallbacks(periodic);handler.removeCallbacks(scheduled)}
    fun localChanged(){deferAutomatic(5000)}
    private fun <T> work(label:String,modal:Boolean=true,action:()->T,done:(T)->Unit) {
        if(closed || busy){if(modal)toast("Aguarde a operação atual.");return};busy=true
        val profile=LibraryProfiles.database(activity)
        val dialog=if(modal)ProgressDialog.show(activity,"Viblio",label,true,false) else null;progress=dialog
        worker.execute {
            val result=runCatching {check(!closed && LibraryProfiles.database(activity)==profile){"O perfil mudou. Tente novamente nesta conta."};action()}
            post {busy=false;dialog?.dismiss();progress=null
                if(LibraryProfiles.database(activity)!=profile){resumePending();return@post}
                try {result.fold(done,{error->
                    if(error is AccountFailure && error.code==401 && signedIn){secret.clear();session=null;LibraryProfiles.anonymous(activity);reloaded()}
                    status=if(error is java.net.ConnectException || error is java.net.SocketTimeoutException)"Servidor indisponível. Sua Estante continua no aparelho." else error.message ?: "Não foi possível concluir."
                    retryAfter=System.currentTimeMillis()+300000;if(modal)toast(status);changed()
                })}finally {resumePending()}
            }
        }
    }
    fun authenticate(server:String,email:String,password:String,mode:String,code:String="",key:String="",username:String="") {
        if(!secret.supported){toast("Contas precisam do Android 6 ou superior. A Estante local continua disponível.");return}
        if(!canReplace()){toast("Salve ou cancele o formulário antes de entrar.");return}
        work("Conectando sua conta…",action={
            val client=AccountApi(server);val info=client.call("/v1/auth/info")
            check(info.get("username_login")?.asBoolean==true){"Atualize a API do servidor para usar nomes de usuário."}
            val body=JsonObject().apply {addProperty(if(mode=="login")"identifier" else "email",email.trim());addProperty("password",password);if(mode=="create")addProperty("username",username.trim())}
            val response=when(mode){
                "create"->{
                    if(!info.get("initialized").asBoolean){
                        check(defaultAddress.isBlank()) {"O administrador ainda precisa configurar esta instalação do Viblio."}
                        val installation=key.ifBlank {HomeAssistantCredentials(activity,"viblio_catalog_credentials","viblio_catalog_key").load()}
                        check(installation.length>=32){"A primeira conta precisa da chave da instalação do servidor."}
                        AccountApi(server,installation).call("/v1/auth/bootstrap","POST",body)
                    }else {body.addProperty("invitation",code.trim());client.call("/v1/auth/register","POST",body)}
                }
                "recover"->client.call("/v1/auth/reset-password","POST",JsonObject().apply {addProperty("code",code.trim());addProperty("password",password)})
                else->client.call("/v1/auth/login","POST",body)
            }
            val user=response.getAsJsonObject("user");UUID.fromString(user.get("id").asString);UUID.fromString(user.get("server_id").asString)
            check(response.get("token").asString.length in 32..200 && user.get("email").asString.isNotBlank()){"Resposta de conta inválida."}
            response.addProperty("address",server.trimEnd('/'));response
        }) {response->
            conflictProfile=null;secret.save(response.toString());session=response;config.edit().putString("url",server.trimEnd('/')).putBoolean("personal_mode",true).commit()
            val previous=LibraryProfiles.owner(activity)
            if(previous.isNotBlank() && previous!=owner){LibraryProfiles.anonymous(activity);reloaded()}
            retryAfter=0;status="Conta conectada. Escolha como vincular a Estante.";changed();link()
        }
    }
    fun editUsername() {
        val field=android.widget.EditText(activity).apply {hint="Nome de usuário";setText(username);isSingleLine=true;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS}
        AlertDialog.Builder(activity).setTitle(if(username.isBlank())"Definir nome de usuário" else "Nome de usuário").setMessage("De 3 a 32 letras, números, ponto ou sublinhado. Você também pode continuar entrando com seu e-mail.").setView(field)
            .setPositiveButton("Salvar") {_,_->
                val value=field.text.toString().trim().lowercase(java.util.Locale.ROOT)
                if(!value.matches(Regex("[a-z0-9][a-z0-9_.]{2,31}"))){toast("Use de 3 a 32 letras, números, ponto ou sublinhado.");return@setPositiveButton}
                work("Salvando nome de usuário…",action={
                    val client=api();check(client.call("/v1/auth/info").get("username_login")?.asBoolean==true){"Atualize a API para usar nomes de usuário."}
                    client.call("/v1/me","PATCH",JsonObject().apply {addProperty("username",value)})
                }) {user->session?.add("user",user);session?.let {secret.save(it.toString())};status="Nome de usuário salvo.";changed()}
            }.setNegativeButton("Cancelar",null).show()
    }
    fun link() {
        if(!signedIn)return
        if(!canReplace()){toast("Salve ou cancele o formulário antes de trocar a Estante.");return}
        val repo=LibraryRepository(activity);val draft=repo.draft();val hasBooks=repo.books(true).isNotEmpty() || repo.collections(true).isNotEmpty();repo.close()
        if(draft!=null){toast("Pause e salve a sessão antes de vincular ou trocar de perfil.");return}
        if(LibraryProfiles.mapped(activity,owner)!=null){selectProfile(false);return}
        val claim=LibraryProfiles.owner(activity).isBlank() && hasBooks
        val builder=AlertDialog.Builder(activity).setTitle("Sua Estante nesta conta")
            .setMessage(if(claim)"Quer usar sua estante atual nesta conta? Livros, mangás, quadrinhos, capas, notas, progresso e histórico serão preservados. Nada será enviado antes de você confirmar." else "Esta conta terá uma Estante própria neste aparelho. Bibliotecas de outras contas continuam separadas.")
            .setPositiveButton(if(claim)"Usar minha estante atual" else "Começar Estante própria") {_,_->selectProfile(claim)}.setNegativeButton("Decidir depois",null)
        if(claim)builder.setNeutralButton("Começar vazia") {_,_->selectProfile(false)}
        builder.show()
    }
    private fun selectProfile(claim:Boolean){
        LibraryProfiles.select(activity,owner,claim);status="Estante vinculada a $email.";reloaded();changed()
        if(preferences().getLong("revision",-1)>=0){retryAfter=0;automatic()}else sync()
    }
    fun logout() {
        if(!signedIn)return
        if(!canReplace() || busy){toast("Salve o formulário e aguarde a operação atual antes de sair.");return}
        AlertDialog.Builder(activity).setTitle("Sair da conta?").setMessage("A cópia local permanece guardada neste perfil. Entre novamente nessa conta para reabrir a Estante. Outras contas têm acervos separados.")
            .setPositiveButton("Sair") {_,_->val client=api();worker.execute {runCatching {client.call("/v1/auth/logout","POST")}};secret.clear();session=null;LibraryProfiles.anonymous(activity);status="Sessão encerrada. Sua Estante continua guardada no perfil.";reloaded();changed()}.setNegativeButton("Cancelar",null).show()
    }
    private fun ready():Boolean {
        if(!linked){toast("Entre e conclua a vinculação primeiro.");return false}
        if(!canReplace()){toast("Salve ou cancele o formulário antes de sincronizar.");return false}
        val repo=LibraryRepository(activity);val draft=repo.draft();repo.close()
        if(draft!=null){toast("Pause e salve a página da sessão antes de sincronizar.");return false};return true
    }
    private data class AutoSyncResult(val response:JsonObject,val uploadedHash:String,val conflict:Boolean,val useMedia:Boolean)
    private val mediaHeaders=mapOf("X-Viblio-Media" to "1")
    private fun sendLibrary(client:AccountApi,io:AccountLibrary,snapshot:JsonObject,base:Long,useMedia:Boolean,resolutions:JsonObject?=null,against:Long?=null):JsonObject {
        if(!useMedia)return LibrarySyncPolicy.submit(snapshot,base,resolutions=resolutions,against=against){client.call("/v1/me/library/sync","POST",it,gzip=true)}
        io.syncMediaUp(client,snapshot)
        return try {
            LibrarySyncPolicy.submit(snapshot,base,resolutions=resolutions,against=against){client.call("/v1/me/library/sync","POST",it,gzip=true,extraHeaders=mediaHeaders)}
        }catch(e:AccountFailure){
            val detail=if(e.code==409)e.response.get("detail")?.takeIf {it.isJsonObject}?.asJsonObject else null
            if(detail?.get("code")?.takeIf {it.isJsonPrimitive}?.asString=="missing_media"){
                val missing=detail.getAsJsonArray("missing")?.mapNotNull {it.takeIf {el->el.isJsonPrimitive}?.asString}.orEmpty()
                if(missing.isNotEmpty()){
                    io.syncMediaUp(client,snapshot,onlyHashes=missing)
                    return LibrarySyncPolicy.submit(snapshot,base,resolutions=resolutions,against=against){client.call("/v1/me/library/sync","POST",it,gzip=true,extraHeaders=mediaHeaders)}
                }
            }
            throw e
        }
    }
    private fun automatic() {
        if(!running || closed || !linked)return
        if(busy){automaticPending=true;return}
        val wait=retryAfter-System.currentTimeMillis()
        if(wait>0){deferAutomatic(wait);return}
        if(!canReplace()){deferAutomatic(5000);return}
        val repo=LibraryRepository(activity);val draft=repo.draft();repo.close();if(draft!=null){deferAutomatic(5000);return}
        val prefs=preferences();if(prefs.getLong("revision",-1)<0 || prefs.getBoolean("restore_pending",false)){automaticPending=false;return}
        if(prefs.getBoolean("remote_changed",false) && conflictProfile==LibraryProfiles.database(activity)){automaticPending=false;return}
        automaticPending=false
        val expected=owner;val client=api();val io=AccountLibrary(activity)
        val base=prefs.getLong("revision",0)
        work("Sincronizando…",modal=false,action={
            val remoteInfo=client.call("/v1/me/library/status")
            val remote=remoteInfo.get("revision").asLong
            val useMedia=remoteInfo.get("media")?.asBoolean==true
            val local=io.capture(useMedia);val hash=io.hash(local)
            if(remote==base && hash==prefs.getString("local_hash",""))return@work null
            try {
                val response=if(hash==prefs.getString("local_hash","") && remote!=base)JsonObject().apply {
                    addProperty("revision",remote)
                    add("snapshot",client.call("/v1/me/library?revision=$remote",extraHeaders=if(useMedia)mediaHeaders else emptyMap()))
                }else sendLibrary(client,io,local,base,useMedia)
                response.addProperty("_matches_local",io.hash(response.getAsJsonObject("snapshot"))==hash)
                AutoSyncResult(response,hash,false,useMedia)
            }catch(e:AccountFailure){if(e.code==409)AutoSyncResult(e.response,hash,true,useMedia) else throw e}
        }) {result->
            if(expected!=owner)return@work
            if(result==null){conflictProfile=null;prefs.edit().putBoolean("remote_changed",false).commit();status=if(automaticPending)"Alterações aguardando envio." else "Estante sincronizada. Backup salvo na conta.";changed();return@work}
            val (response,uploadedHash,conflict,useMedia)=result
            if(conflict){
                conflictProfile=if(response.get("detail")?.takeIf {it.isJsonObject}?.asJsonObject?.get("code")?.takeIf {it.isJsonPrimitive}?.asString=="changed_again")null else LibraryProfiles.database(activity)
                prefs.edit().putBoolean("remote_changed",true).commit()
                status="Há alterações que precisam da sua escolha. Toque em Conferir sincronização.";changed();return@work
            }
            if(response.get("_matches_local")?.asBoolean==true){
                conflictProfile=null
                prefs.edit().putLong("revision",response.get("revision").asLong).putString("local_hash",uploadedHash).putBoolean("remote_changed",false).putLong("synced_at",System.currentTimeMillis()).commit()
                status=if(automaticPending)"Alterações aguardando envio." else "Estante sincronizada. Backup salvo na conta.";changed();return@work
            }
            // Nothing is replaced while a form, active reading or newer local edits exist.
            if(!running || !canReplace()){localChanged();return@work}
            val repo=LibraryRepository(activity);val draft=repo.draft();repo.close();if(draft!=null)return@work
            work("Atualizando sua Estante…",action={
                if(io.hash(io.capture(useMedia))!=uploadedHash)return@work false
                val merged=response.getAsJsonObject("snapshot")
                if(io.hash(merged)!=uploadedHash){
                    if(useMedia)io.syncMediaDown(client,merged)
                    io.apply(merged)
                }
                prefs.edit().putLong("revision",response.get("revision").asLong).putString("local_hash",io.hash(io.capture(useMedia))).putBoolean("remote_changed",false).putLong("synced_at",System.currentTimeMillis()).commit()
                true
            }) {applied->
                if(applied){conflictProfile=null;status="Estante sincronizada. Backup salvo na conta.";reloaded();changed()}
                else localChanged()
            }
        }
    }

    fun sync() {
        if(!ready())return
        work("Conferindo o acervo da conta…",action={api().call("/v1/me/library/status")}) {remote->
            val base=preferences().getLong("revision",-1);val revision=remote.get("revision").asLong
            val useMedia=remote.get("media")?.asBoolean==true
            if(base<0 && revision>0){
                val repo=LibraryRepository(activity);val empty=repo.books(true).isEmpty() && repo.collections(true).isEmpty();repo.close()
                if(empty){restore(revision,useMedia=useMedia);return@work}
                val builder=AlertDialog.Builder(activity).setTitle("Esta conta já tem uma Estante").setMessage("Você pode baixar a Estante do servidor ou combinar os acervos, escolhendo eventuais conflitos. Uma cópia local será guardada antes das substituições.")
                    .setPositiveButton("Baixar do servidor") {_,_->restore(revision,useMedia=useMedia)}.setNegativeButton("Cancelar",null)
                if(!empty)builder.setNeutralButton("Combinar acervos") {_,_->performSync(0,useMedia=useMedia)}
                builder.show()
            }else if(base==revision && preferences().getString("local_hash",null)!=null && !preferences().getBoolean("restore_pending",false)){
                val io=AccountLibrary(activity)
                work("Conferindo alterações…",modal=false,action={io.hash(io.capture(useMedia))==preferences().getString("local_hash",null)}) {same->
                    if(same){conflictProfile=null;preferences().edit().putBoolean("remote_changed",false).commit();status="Estante sincronizada. Backup salvo na conta.";changed()}else performSync(base,useMedia=useMedia)
                }
            }else performSync(if(base<0)0 else base,useMedia=useMedia)
        }
    }
    private fun performSync(base:Long,snapshot:JsonObject?=null,resolutions:JsonObject?=null,against:Long?=null,useMedia:Boolean=false) {
        if(!ready())return
        val io=AccountLibrary(activity);val prefs=preferences();val client=api()
        work("Combinando sua Estante…",action={
            val local=snapshot ?: io.capture(useMedia)
            val response=try {sendLibrary(client,io,local,base,useMedia,resolutions,against)}catch(e:AccountFailure){if(e.code==409)return@work Triple(local,e.response.get("detail")?.takeIf {it.isJsonObject}?.asJsonObject ?: JsonObject(),false);throw e}
            val merged=response.getAsJsonObject("snapshot")
            if(io.hash(merged)!=io.hash(local)){
                if(useMedia)io.syncMediaDown(client,merged)
                io.apply(merged)
            }
            prefs.edit().putLong("revision",response.get("revision").asLong).putString("local_hash",io.hash(io.capture(useMedia))).putBoolean("remote_changed",false).putBoolean("restore_pending",false).putLong("synced_at",System.currentTimeMillis()).commit()
            Triple(local,response,true)
        }) {(local,response,success)->
            if(success){conflictProfile=null;status="Estante sincronizada. Backup salvo na conta.";reloaded();changed()}
            else if(response.get("code")?.asString=="conflicts"){
                conflictProfile=LibraryProfiles.database(activity);preferences().edit().putBoolean("remote_changed",true).commit();resolve(base,local,response,useMedia)
            }
            else if(response.get("code")?.asString=="base_expired")AlertDialog.Builder(activity).setTitle("Este aparelho ficou muito tempo offline").setMessage("A base antiga expirou. Podemos combinar como uma nova importação e pedir escolhas nos conflitos. Revise itens excluídos definitivamente, que podem existir no servidor.").setPositiveButton("Revisar e combinar") {_,_->performSync(0,local,useMedia=useMedia)}.setNegativeButton("Cancelar",null).show()
            else {status=response.get("message")?.asString ?: "Revise os dados antes de sincronizar.";toast(status);changed()}
        }
    }
    private fun resolve(base:Long,local:JsonObject,detail:JsonObject,useMedia:Boolean) {
        val all=detail.getAsJsonArray("conflicts");val choices=JsonObject()
        all.filter {LibrarySyncPolicy.localConflict(it.asJsonObject.get("path").asString)}.forEach {choices.addProperty(it.asJsonObject.get("path").asString,"local")}
        val conflicts=all.filterNot {LibrarySyncPolicy.localConflict(it.asJsonObject.get("path").asString)}
        fun choose(index:Int) {
            if(index>=conflicts.size){performSync(base,local,choices,detail.get("revision").asLong,useMedia=useMedia);return}
            val row=conflicts[index].asJsonObject;val path=row.get("path").asString
            AlertDialog.Builder(activity).setTitle("Conflito ${index+1} de ${conflicts.size}").setMessage("$path\n\nNeste aparelho: ${row.get("local").asString}\n\nServidor: ${row.get("server").asString}\n\nEscolha qual manter. Nenhuma mudança foi gravada ainda.")
                .setPositiveButton("Usar aparelho") {_,_->choices.addProperty(path,"local");choose(index+1)}.setNeutralButton("Usar servidor") {_,_->choices.addProperty(path,"server");choose(index+1)}.setNegativeButton("Cancelar",null).show()
        };choose(0)
    }
    fun backups() {
        if(!ready())return
        work("Carregando backups…",action={api().call("/v1/me/library/status")}) {info->
            val useMedia=info.get("media")?.asBoolean==true
            val rows=info.getAsJsonArray("versions");if(rows.size()==0){toast("Nenhum backup nesta conta ainda.");return@work}
            AlertDialog.Builder(activity).setTitle("Backups da sua conta").setItems(rows.map {r->r.asJsonObject.let {"Versão ${it.get("revision").asLong} · ${it.get("created_at").asString}"}}.toTypedArray()) {_,index->
                val revision=rows[index].asJsonObject.get("revision").asLong
                AlertDialog.Builder(activity).setTitle("Restaurar versão $revision?").setMessage("A Estante deste perfil será substituída. Um backup local será guardado antes. Outras contas e o catálogo não mudam.").setPositiveButton("Restaurar") {_,_->restore(revision,true,useMedia)}.setNegativeButton("Cancelar",null).show()
            }.setNegativeButton("Fechar",null).show()
        }
    }
    private fun restore(revision:Long,historical:Boolean=false,useMedia:Boolean?=null) {
        if(!ready())return
        val io=AccountLibrary(activity);val prefs=preferences();val client=api()
        work("Restaurando sua Estante…",action={
            val statusObj=if(historical || useMedia==null)client.call("/v1/me/library/status") else null
            val mediaMode=useMedia ?: (statusObj?.get("media")?.asBoolean==true)
            val snapshot=client.call("/v1/me/library?revision=$revision",extraHeaders=if(mediaMode)mediaHeaders else emptyMap())
            val baseline=if(historical)requireNotNull(statusObj).get("revision").asLong else revision
            if(mediaMode)io.syncMediaDown(client,snapshot)
            io.apply(snapshot);prefs.edit().putBoolean("restore_pending",historical).putLong("revision",baseline).putString("local_hash",io.hash(io.capture(mediaMode))).putBoolean("remote_changed",false).commit()
        }) {conflictProfile=null;status="Backup restaurado neste perfil. Sincronize para publicar novas alterações.";reloaded();changed()}
    }
    private fun copySensitive(label:String,text:String) {
        val clip=android.content.ClipData.newPlainText(label,text)
        if(android.os.Build.VERSION.SDK_INT>=33){
            clip.description.extras=android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true) }
        }
        (activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
    }
    private fun showCode(title:String,text:String){AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("Copiar") {_,_->copySensitive(title,text);toast("Copiado. Compartilhe apenas com a pessoa convidada.")}.setNegativeButton("Fechar",null).show()}
    fun createInvitation(){work("Gerando convite…",action={api().call("/v1/admin/invitations","POST",JsonObject())}) {r->
        val code=r.get("code").asString
        val ui=br.com.centralcasa.ui.DisplayUi(activity)
        val body=ui.column().apply {setPadding(ui.dp(20),ui.dp(12),ui.dp(20),0)}
        ui.label(body,"Autoriza a criação de uma conta no Viblio. Quem já tem conta pode entrar normalmente.",13,ui.muted)
        ui.label(body,code,16).apply {setTextIsSelectable(true);typeface=android.graphics.Typeface.MONOSPACE}
        val expires=runCatching {
            val utc=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",java.util.Locale.US).apply {timeZone=java.util.TimeZone.getTimeZone("UTC");isLenient=false}
            java.text.SimpleDateFormat("dd/MM/yyyy 'às' HH:mm",java.util.Locale("pt","BR")).format(requireNotNull(utc.parse(r.get("expires_at").asString.take(19))))
        }.getOrDefault(r.get("expires_at").asString)
        ui.label(body,"Uso único · válido até $expires",12,ui.muted)
        AlertDialog.Builder(activity).setTitle("Convite do Viblio").setView(body)
            .setPositiveButton("Copiar código"){_,_->
                copySensitive("Convite do Viblio",code)
                toast("Código copiado. Envie somente para a pessoa convidada.")
            }.setNegativeButton("Fechar",null).show()
    }}
    fun manageInvitations(){work("Carregando convites…",action={api().call("/v1/admin/invitations")}) {data->
        val rows=data.getAsJsonArray("items")
        AlertDialog.Builder(activity).setTitle("Convites · toque para revogar").setItems(rows.map {r->r.asJsonObject.let {"${it.get("id").asString.take(8)} · ${if(it.get("revoked").asBoolean)"revogado" else if(!it.get("used_at").isJsonNull)"utilizado" else "até "+it.get("expires_at").asString}"}}.toTypedArray()) {_,i->
            val id=rows[i].asJsonObject.get("id").asString
            val validId=runCatching { UUID.fromString(id).toString() }.getOrNull() ?: return@setItems
            work("Revogando convite…",action={api().call("/v1/admin/invitations/$validId","DELETE")}) {toast("Convite revogado.")}
        }.setNegativeButton("Fechar",null).show()
    }}
    fun recoverUser(){work("Carregando contas…",action={api().call("/v1/admin/users")}) {data->
        val rows=data.getAsJsonArray("items")
        AlertDialog.Builder(activity).setTitle("Recuperar acesso").setItems(rows.map {it.asJsonObject.get("email").asString}.toTypedArray()) {_,i->val row=rows[i].asJsonObject
            val validId=runCatching { UUID.fromString(row.get("id").asString).toString() }.getOrNull() ?: return@setItems
            AlertDialog.Builder(activity).setTitle("Gerar código para ${row.get("email").asString}?").setMessage("O código permite definir outra senha. Compartilhe somente com o dono da conta.").setPositiveButton("Gerar código") {_,_->work("Gerando código…",action={api().call("/v1/admin/users/$validId/recovery","POST")}) {showCode("Recuperação de acesso",it.get("code").asString+"\nValidade: 24 horas.")}}.setNegativeButton("Cancelar",null).show()
        }.setNegativeButton("Fechar",null).show()
    }}
    fun close(){stop();closed=true;running=false;progress?.dismiss();progress=null;handler.removeCallbacksAndMessages(null);worker.shutdown()}
}
