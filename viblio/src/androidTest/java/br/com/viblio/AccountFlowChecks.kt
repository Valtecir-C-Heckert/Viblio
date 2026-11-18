package br.com.viblio
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.viblio.account.AccountApi
import com.google.gson.JsonObject
import java.util.UUID
/** Actual forms and dialogs against the disposable API, never a personal server. */
object AccountFlowChecks {
    fun run(test:Instrumentation,address:String) {
        val c=test.targetContext;val profiles=c.getSharedPreferences("viblio_profiles",0);check(profiles.all.isEmpty())
        val config=c.getSharedPreferences("viblio_catalog",0);val previous=config.all.toMap()
        val admin=AccountApi(address).call("/v1/auth/login","POST",JsonObject().apply {addProperty("email","admin@example.test");addProperty("password","test-password-123456")})
        val invite=AccountApi(address,admin.get("token").asString).call("/v1/admin/invitations","POST",JsonObject()).get("code").asString
        val email="flow-${UUID.randomUUID()}@example.test";val username="flow_"+UUID.randomUUID().toString().replace("-","").take(12);val password="flow-password-123456"
        val localRepo=LibraryRepository(c)
        check(localRepo.books(true).isEmpty())
        val coverBitmap=android.graphics.Bitmap.createBitmap(32,48,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.CYAN)}
        val coverOutput=java.io.ByteArrayOutputStream();coverBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,coverOutput);coverBitmap.recycle()
        val cover=BookImages(c).save(java.io.ByteArrayInputStream(coverOutput.toByteArray()))
        val originalCover=BookImages(c).file(cover)!!.readBytes()
        val localBook=LibraryBook(id="flow-book",title="Minha Estante privada",totalPages=100,currentPage=14,rating=4.5,cover=cover)
        val localSession=ReadingSession(id="flow-session",bookId=localBook.id,startedAt=10000,finishedAt=20000,durationMs=10000,startPage=0,endPage=14,readingDay="2026-10-17")
        localRepo.importSnapshot(listOf(localBook),listOf(localSession),localBook.id,listOf(ReadingDate(id="flow-date",bookId=localBook.id,day="2026-10-17")))
        localRepo.close()
        var activity=test.startActivitySync(Intent(c,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));var database="";var suffix=""
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
        fun all()=views(activity.window.decorView)
        fun click(label:String)=test.checkedMain {all().filterIsInstance<TextView>().last {it.text.toString()==label}.performClick()}
        fun fill(hint:String,value:String)=test.checkedMain {all().filterIsInstance<EditText>().single {it.hint.toString()==hint}.setText(value)}
        fun await(label:String,condition:()->Boolean){val deadline=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<deadline){test.waitForIdleSync();if(condition())return;Thread.sleep(100)};var detail="";test.checkedMain {detail=all().filterIsInstance<TextView>().filterNot {it is EditText}.joinToString(" | "){it.text.toString()}.take(2500)};error("Account flow timed out: $label; screen=$detail")}
        fun hasText(label:String):Boolean {var result=false;test.checkedMain {result=all().filterIsInstance<TextView>().any {it.text.toString()==label}};return result}
        fun dialogClick(label:String,before:()->Unit={}){await(label){
            val root=test.uiAutomation.rootInActiveWindow ?: return@await false
            fun find(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {if(n.text?.toString()?.equals(label,ignoreCase=true)==true)return n;for(i in 0 until n.childCount){val child=n.getChild(i) ?: continue;find(child)?.let {return it}};return null}
            val button=find(root) ?: return@await false
            // The dialog can disappear during dispatch, making Android return false
            // after actually invoking its listener. Assert the resulting profile below.
            before();button.performAction(AccessibilityNodeInfo.ACTION_CLICK);true
        }}
        try {
            test.checkedMain {
                check(all().filterIsInstance<ImageView>().any {it.contentDescription=="Viblio"})
                check(all().filterIsInstance<EditText>().none {it.hint.toString().startsWith("Chave da instalação")})
            }
            click("Criar conta por convite");fill("Endereço do servidor",address);fill("E-mail",email);fill("Nome de usuário",username);fill("Senha (mínimo 12 caracteres)",password);fill("Convite",invite)
            fun displayedPassword():String {val input=all().filterIsInstance<EditText>().single {it.hint.toString()=="Senha (mínimo 12 caracteres)"};return input.transformationMethod?.getTransformation(input.text,input)?.toString() ?: input.text.toString()}
            test.checkedMain {check(displayedPassword()!=password)}
            click("Mostrar");test.checkedMain {check(displayedPassword()==password)}
            click("Ocultar");test.checkedMain {check(all().filterIsInstance<EditText>().single {it.hint.toString()=="Senha (mínimo 12 caracteres)"}.transformationMethod is android.text.method.PasswordTransformationMethod)}
            click("Criar conta");dialogClick("Usar minha estante atual") {
                val created=AccountApi(address).call("/v1/auth/login","POST",JsonObject().apply {addProperty("identifier",username);addProperty("password",password)})
                val waiting=AccountApi(address,created.get("token").asString)
                check(waiting.call("/v1/me/library/status").get("revision").asLong==0L)
                check(LibraryProfiles.owner(c).isBlank())
                val untouched=LibraryRepository(c);try {check(untouched.book(localBook.id)?.rating==4.5 && untouched.sessions(localBook.id)==listOf(localSession))}finally {untouched.close()}
            }
            await("navigation after signup"){var found=false;test.checkedMain {found=all().any {it is LinearLayout && it.contentDescription?.toString()=="Amigos"}};found}
            test.checkedMain {openAccountMenu(test,activity)}
            await("linked and synchronized"){hasText(email) && hasText("Estante sincronizada. Backup salvo na conta.")}
            test.checkedMain {check(all().none {it is EditText});check(all().filterIsInstance<TextView>().none {it.text.toString()=="Home Assistant"});check(all().filterIsInstance<TextView>().any {it.text.toString()=="@$username"})}
            database=LibraryProfiles.database(c);suffix=LibraryProfiles.suffix(c);check(database=="library.db")
            test.checkedMain {
                val field=ViblioActivity::class.java.getDeclaredField("library").apply {isAccessible=true}
                val panel=field.get(activity) as br.com.centralcasa.ui.BooksPanel
                check(!panel.jsonAllowed())
                check(panel.onActivityResult(811,android.app.Activity.RESULT_OK,Intent().setData(android.net.Uri.parse("content://forbidden/import.json"))))
                check(!panel.isEditor)
            }
            val repo=LibraryRepository(c);try {
                val claimed=requireNotNull(repo.book(localBook.id))
                check(claimed.currentPage==14 && claimed.rating==4.5 && repo.sessions(localBook.id)==listOf(localSession) && repo.dates(localBook.id).single().day=="2026-10-17")
                check(BookImages(c).file(claimed.cover)!!.readBytes().contentEquals(originalCover))
            }finally {repo.close()}
            click("Conferir sincronização")
            val token=AccountApi(address).call("/v1/auth/login","POST",JsonObject().apply {addProperty("email",email);addProperty("password",password)}).get("token").asString
            val client=AccountApi(address,token)
            await("book uploaded"){runCatching {client.call("/v1/me/library").getAsJsonObject("library").getAsJsonArray("books").size()==1}.getOrDefault(false)}
            Thread.sleep(300);test.waitForIdleSync()
            // Remote note and pending local progress combine automatically, no manual sync.
            val remote=client.call("/v1/me/library")
            remote.getAsJsonObject("library").getAsJsonArray("books")[0].asJsonObject.addProperty("rating",3.5)
            val revision=client.call("/v1/me/library/status").get("revision").asLong
            client.call("/v1/me/library/sync","POST",JsonObject().apply {addProperty("request_id",UUID.randomUUID().toString());addProperty("base_revision",revision);add("snapshot",remote)},gzip=true)
            val pending=LibraryRepository(c);try {pending.save(requireNotNull(pending.book("flow-book")).copy(currentPage=29))}finally {pending.close()}
            test.checkedMain {
                val field=ViblioActivity::class.java.getDeclaredField("accounts").apply {isAccessible=true}
                (field.get(activity) as br.com.viblio.account.AccountManager).localChanged()
            }
            await("automatic merge after pending local change") {
                val local=LibraryRepository(c);try {local.book("flow-book")?.let {it.rating==3.5 && it.currentPage==29}==true}finally {local.close()}
            }
            await("automatic remote progress") {client.call("/v1/me/library").getAsJsonObject("library").getAsJsonArray("books")[0].asJsonObject.get("currentPage").asInt==29}
            Thread.sleep(300);test.waitForIdleSync()
            test.checkedMain {activity.finish()};test.waitForIdleSync()
            activity=test.startActivitySync(Intent(c,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.checkedMain {
                check(all().filterIsInstance<EditText>().none {it.hint.toString()=="Usuário ou e-mail"})
                val tab=all().first {it is LinearLayout && it.contentDescription?.toString()=="Amigos"};check(tab.isShown);openAccountMenu(test,activity)
            }
            await("persistent session skips login"){hasText(email) && hasText("@$username")}
            Thread.sleep(300);test.waitForIdleSync()
            // Reopening starts a background identity/sync request. Dispatch logout
            // only when its documented busy guard permits it, in the same UI event.
            await("logout available") {
                var clicked=false
                test.checkedMain {
                    val managerField=ViblioActivity::class.java.getDeclaredField("accounts").apply {isAccessible=true}
                    val manager=managerField.get(activity)
                    val busyField=br.com.viblio.account.AccountManager::class.java.getDeclaredField("busy").apply {isAccessible=true}
                    if(!busyField.getBoolean(manager))clicked=all().filterIsInstance<TextView>().last {it.text.toString()=="Sair da conta"}.performClick()
                }
                clicked
            }
            dialogClick("Sair")
            await("guest profile"){LibraryProfiles.owner(c).isBlank() && hasText("Criar conta por convite")}
            val guest=LibraryRepository(c);try {check(guest.books(true).isEmpty())}finally {guest.close()}
            fill("Usuário ou e-mail",username);fill("Senha",password);click("Entrar")
            await("navigation after login"){var found=false;test.checkedMain {found=all().any {it is LinearLayout && it.contentDescription?.toString()=="Amigos"}};found}
            test.checkedMain {openAccountMenu(test,activity)}
            await("private profile reopened"){LibraryProfiles.database(c)==database && hasText(email) && hasText("Estante sincronizada. Backup salvo na conta.")}
            val reopened=LibraryRepository(c);try {check(reopened.book("flow-book")?.rating==3.5 && reopened.book("flow-book")?.currentPage==29)}finally {reopened.close()}
            // Revoking access on the server must hide the cached private profile too.
            val adminClient=AccountApi(address,admin.get("token").asString)
            val recovery=adminClient.call("/v1/admin/users/"+client.call("/v1/me").get("id").asString+"/recovery","POST").get("code").asString
            AccountApi(address).call("/v1/auth/reset-password","POST",JsonObject().apply {addProperty("code",recovery);addProperty("password","replacement-password-123456")})
            test.checkedMain {
                val field=ViblioActivity::class.java.getDeclaredField("accounts").apply {isAccessible=true}
                (field.get(activity) as br.com.viblio.account.AccountManager).localChanged()
            }
            await("revoked session hides private profile"){LibraryProfiles.owner(c).isBlank() && hasText("Criar conta por convite")}
            val preserved=LibraryRepository(c,database);try {check(preserved.book("flow-book")?.currentPage==29)}finally {preserved.close()}
            check(runCatching {client.call("/v1/me")}.isFailure)
        }finally {
            test.checkedMain {activity.finish()};test.waitForIdleSync();HomeAssistantCredentials(c,"viblio_account_session","viblio_account_session_key").clear()
            if(database.isNotBlank()){
                c.deleteDatabase(database);c.getSharedPreferences("panel_settings"+suffix,0).edit().clear().commit();c.getSharedPreferences("viblio_sync"+suffix,0).edit().clear().commit()
                java.io.File(c.filesDir,"account-safety"+suffix).deleteRecursively();java.io.File(c.filesDir,"book-covers"+suffix).deleteRecursively()
            }
            profiles.edit().clear().commit();val edit=config.edit().clear();previous.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Boolean->edit.putBoolean(k,v);is Float->edit.putFloat(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}};edit.commit()
        }
    }
}
