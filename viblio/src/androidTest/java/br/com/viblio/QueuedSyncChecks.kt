package br.com.viblio

import android.app.Instrumentation
import android.content.Intent
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import br.com.centralcasa.books.*
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.viblio.account.AccountManager
import com.google.gson.*
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/** A delayed identity call/upload must not swallow a newer page; no reading session is open. */
object QueuedSyncChecks {
    fun run(test:Instrumentation){
        val c=test.targetContext
        val activity=test.startActivitySync(Intent(c,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val profiles=c.getSharedPreferences("viblio_profiles",0);val previous=profiles.all.toMap()
        val secret=HomeAssistantCredentials(c,"viblio_account_session","viblio_account_session_key");val old=secret.load()
        val user="e26ce8bd-55b5-47dd-acdb-b5bab2cf1ce7";val source="55aad441-1127-4e08-b0d1-6f86c91c634b"
        val key=LibraryProfiles.key(source,user)
        LibraryProfiles.select(c,key,false)
        val database=LibraryProfiles.database(c);val suffix=LibraryProfiles.suffix(c)
        val repo=LibraryRepository(c);val book=LibraryBook(id="queued-page",title="Leitura pendente",totalPages=100,currentPage=14)
        repo.save(book);repo.setCurrent(book.id)
        val io=br.com.viblio.account.AccountLibrary(c);val baseline=io.capture()
        val prefs=c.getSharedPreferences("viblio_sync"+suffix,0)
        prefs.edit().putLong("revision",1).putString("local_hash",io.hash(baseline)).commit()
        val legacyZoom=java.util.concurrent.atomic.AtomicBoolean(false);val bookConflict=java.util.concurrent.atomic.AtomicBoolean(false)
        val pagePath="/library/books/queued-page/currentPage";val zoomPath="/appearance/SHELF_SPINE_ZOOM"
        val remote=AtomicReference(baseline);val revision=AtomicLong(1);val failure=AtomicReference<Throwable?>()
        val identitySeen=CountDownLatch(1);val releaseIdentity=CountDownLatch(1);val uploadSeen=CountDownLatch(1);val releaseUpload=CountDownLatch(1)
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val token="queued-sync-test-token-abcdefghijklmnopqrstuvwxyz"
        val identity=JsonObject().apply {addProperty("id",user);addProperty("server_id",source);addProperty("username","queued");addProperty("email","queued@example.test");addProperty("admin",false)}
        val thread=Thread {
            while(!server.isClosed)try {server.accept().use {socket->
                socket.soTimeout=10000;val input=socket.getInputStream().buffered()
                fun line():String {val out=java.io.ByteArrayOutputStream();while(true){val b=input.read();check(b>=0);if(b==10)break;if(b!=13)out.write(b);check(out.size()<8192)};return out.toString("UTF-8")}
                val first=line().split(' ');val headers=mutableMapOf<String,String>();while(true){val value=line();if(value.isEmpty())break;headers[value.substringBefore(':').lowercase()]=value.substringAfter(':').trim()}
                check(headers["authorization"]=="Bearer $token")
                val length=headers["content-length"]?.toIntOrNull() ?: 0;check(length in 0..1_048_576)
                val body=ByteArray(length);var offset=0;while(offset<length){val n=input.read(body,offset,length-offset);check(n>0);offset+=n}
                var status=200
                val response=when(first[1]){
                    "/v1/me"->{identitySeen.countDown();check(releaseIdentity.await(15,TimeUnit.SECONDS));identity}
                    "/v1/me/library/status"->JsonObject().apply {addProperty("revision",revision.get())}
                    "/v1/me/library/sync"->{
                        val request=JsonParser.parseString(GZIPInputStream(body.inputStream()).bufferedReader().use {it.readText()}).asJsonObject
                        if(revision.get()==1L){uploadSeen.countDown();check(releaseUpload.await(15,TimeUnit.SECONDS))}
                        val choices=request.getAsJsonObject("resolutions")
                        if(legacyZoom.get() && (choices==null || !choices.has(zoomPath) || bookConflict.get() && !choices.has(pagePath))){
                            status=409
                            JsonObject().apply {add("detail",JsonObject().apply {
                                addProperty("code","conflicts");addProperty("revision",revision.get())
                                add("conflicts",JsonArray().apply {
                                    if(choices?.has(zoomPath)!=true)add(JsonObject().apply {addProperty("path",zoomPath);addProperty("local","(excluído)");addProperty("server","1.15")})
                                    if(bookConflict.get())add(JsonObject().apply {addProperty("path",pagePath);addProperty("local","72");addProperty("server","80")})
                                })
                            })}
                        }else {
                            if(legacyZoom.get())check(request.get("against_revision").asLong==revision.get())else check(request.get("base_revision").asLong==revision.get())
                            val committed=request.getAsJsonObject("snapshot").deepCopy()
                            if(bookConflict.get() && choices?.get(pagePath)?.asString=="server")committed.getAsJsonObject("library").getAsJsonArray("books")[0].asJsonObject.addProperty("currentPage",80)
                            remote.set(committed);revision.incrementAndGet();legacyZoom.set(false);bookConflict.set(false)
                            JsonObject().apply {addProperty("revision",revision.get());add("snapshot",remote.get())}
                        }
                    }
                    else->error("Unexpected sync fixture route")
                }
                val bytes=response.toString().toByteArray();socket.getOutputStream().apply {write("HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
            }}catch(error:Throwable){if(!server.isClosed)failure.compareAndSet(null,error)}
        }.apply {isDaemon=true;start()}
        var manager:AccountManager?=null
        fun page()=remote.get().getAsJsonObject("library").getAsJsonArray("books")[0].asJsonObject.get("currentPage").asInt
        fun await(label:String,check:()->Boolean){val deadline=System.currentTimeMillis()+4000;while(System.currentTimeMillis()<deadline){failure.get()?.let {throw it};test.waitForIdleSync();if(check())return;Thread.sleep(30)};error("Queued sync timeout: $label")}
        fun fireDue(){
            val scheduled=AccountManager::class.java.getDeclaredField("scheduled").apply {isAccessible=true}.get(manager) as Runnable
            val handler=AccountManager::class.java.getDeclaredField("handler").apply {isAccessible=true}.get(manager) as Handler
            handler.removeCallbacks(scheduled);scheduled.run()
        }
        try {
            secret.save(JsonObject().apply {addProperty("token",token);addProperty("address","http://127.0.0.1:${server.localPort}");add("user",identity)}.toString())
            test.checkedMain {manager=AccountManager(activity,{}, {},{true});manager!!.start()}
            check(identitySeen.await(5,TimeUnit.SECONDS));test.waitForIdleSync()
            test.checkedMain {repo.updateProgress(book.id,29);check(repo.draft()==null);manager!!.localChanged();fireDue()}
            releaseIdentity.countDown()
            check(uploadSeen.await(4,TimeUnit.SECONDS)){"Pending page was swallowed while identity was busy"}
            test.checkedMain {repo.updateProgress(book.id,36);manager!!.localChanged();fireDue()}
            releaseUpload.countDown()
            await("new page uploaded after an older upload"){page()==36 && prefs.getLong("revision",0)==3L}
            check(repo.book(book.id)!!.currentPage==36 && prefs.getString("local_hash","")==io.hash(io.capture()))
            test.checkedMain {manager!!.stop();repo.updateProgress(book.id,44);manager!!.localChanged();fireDue()}
            check(page()==36)
            test.checkedMain {manager!!.start()}
            await("pending page after foreground return"){page()==44 && prefs.getLong("revision",0)==4L}
            check(repo.draft()==null)
            val settings=c.getSharedPreferences(LibraryProfiles.settings(c),0)
            settings.edit().putString("SHELF_SPINE_ZOOM","0.75").commit()
            remote.set(remote.get().deepCopy().apply {getAsJsonObject("appearance").addProperty("SHELF_SPINE_ZOOM","1.15")});revision.set(5);legacyZoom.set(true)
            prefs.edit().putString("local_hash","legacy-client-hash").putBoolean("remote_changed",true).commit()
            test.checkedMain {repo.updateProgress(book.id,61);manager!!.localChanged();fireDue()}
            await("legacy zoom conflict recovered without a dialog"){page()==61 && prefs.getLong("revision",0)==6L && !prefs.getBoolean("remote_changed",true)}
            check(settings.getString("SHELF_SPINE_ZOOM",null)=="0.75" && !remote.get().getAsJsonObject("appearance").has("SHELF_SPINE_ZOOM"))
            // A real page conflict must still wait for the user's choice, with zoom hidden from that choice.
            remote.set(remote.get().deepCopy().apply {getAsJsonObject("appearance").addProperty("SHELF_SPINE_ZOOM","1.15");getAsJsonObject("library").getAsJsonArray("books")[0].asJsonObject.addProperty("currentPage",80)})
            revision.set(7);legacyZoom.set(true);bookConflict.set(true)
            test.checkedMain {repo.updateProgress(book.id,72);manager!!.localChanged();fireDue()}
            await("real page conflict blocks automatic overwrite"){prefs.getBoolean("remote_changed",false) && manager!!.status.startsWith("Há alterações")}
            check(page()==80 && repo.book(book.id)!!.currentPage==72)
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
            fun roots():List<View>{val type=Class.forName("android.view.WindowManagerGlobal");val instance=type.getDeclaredMethod("getInstance").invoke(null);return (type.getDeclaredField("mViews").apply {isAccessible=true}.get(instance) as List<*>).filterIsInstance<View>().flatMap(::views)}
            test.checkedMain {manager!!.sync()}
            await("single data conflict dialog"){
                var shown=false;test.checkedMain {shown=roots().filterIsInstance<TextView>().any {it.text.toString()=="Conflito 1 de 1"}};shown
            }
            test.checkedMain {
                val text=roots().filterIsInstance<TextView>().joinToString("\n"){it.text.toString()}
                check(text.contains(pagePath) && !text.contains(zoomPath))
                roots().filterIsInstance<Button>().single {it.text.toString()=="Usar servidor"}.performClick()
            }
            await("explicit page choice keeps local zoom"){page()==80 && prefs.getLong("revision",0)==8L && !prefs.getBoolean("remote_changed",true)}
            check(repo.book(book.id)!!.currentPage==80 && settings.getString("SHELF_SPINE_ZOOM",null)=="0.75")
        }finally {
            releaseIdentity.countDown();releaseUpload.countDown()
            test.checkedMain {manager?.close();activity.finish()}
            server.close();thread.join(2000);repo.close();c.deleteDatabase(database)
            java.io.File(c.filesDir,"account-safety"+suffix).deleteRecursively()
            c.getSharedPreferences("panel_settings"+suffix,0).edit().clear().commit();prefs.edit().clear().commit()
            profiles.edit().clear().apply {previous.forEach {(k,v)->if(v is String)putString(k,v)}}.commit()
            if(old.isBlank())secret.clear()else secret.save(old)
        }
    }
}
