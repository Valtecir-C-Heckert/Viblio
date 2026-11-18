package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.centralcasa.ui.*
import br.com.viblio.account.*
import br.com.viblio.ui.FriendsPanel
import com.google.gson.*
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/** Native read-only friend flow against an authenticated localhost HTTP fixture. */
object FriendsChecks {
    fun run(test:Instrumentation,activity:Activity){
        FriendsRequestChecks.run(test,activity)
        FriendTlsChecks.run(test)
        FriendCoverLifecycleChecks.run(test,activity)
        val repo=LibraryRepository(activity);check(repo.books().isEmpty())
        val own=LibraryBook(id="friend-check-own",title="Só meu",totalPages=100,currentPage=15)
        repo.save(own);repo.setCurrent(own.id)
        val bob="b1c6f3ea-1acd-4c9d-9168-d94d21d7de6b"
        val token="friend-fixture-token-abcdefghijklmnopqrstuvwxyz"
        val secret=HomeAssistantCredentials(activity,"viblio_account_session","viblio_account_session_key");val before=secret.load()
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val failure=AtomicReference<Throwable?>()
        val received=java.util.concurrent.atomic.AtomicBoolean(true)
        val accepted=java.util.concurrent.atomic.AtomicBoolean(false)
        val added=java.util.concurrent.atomic.AtomicBoolean(false)
        val failNext=java.util.concurrent.atomic.AtomicBoolean(false)
        val paginate=java.util.concurrent.atomic.AtomicBoolean(false)
        val conflictNext=java.util.concurrent.atomic.AtomicBoolean(false)
        val shelfRevision=java.util.concurrent.atomic.AtomicInteger(1)
        val moreCalls=java.util.concurrent.atomic.AtomicInteger(0)
        val readingPage=java.util.concurrent.atomic.AtomicInteger(42)
        val cover="/v1/friends/$bob/cover?book_id=b1&revision=1"
        val photo=java.io.ByteArrayOutputStream().also {out->Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}.let {it.compress(Bitmap.CompressFormat.JPEG,90,out);it.recycle()}}.toByteArray()
        fun obj(value:String)=JsonParser.parseString(value).asJsonObject
        val thread=Thread {
            while(!server.isClosed){
                try {server.accept().use {socket->
                    socket.soTimeout=10000
                    val input=socket.getInputStream().bufferedReader();val first=input.readLine() ?: return@use
                    val parts=first.split(' ');val method=parts[0];val path=parts[1];if(path.contains("cursor=40"))moreCalls.incrementAndGet();val headers=mutableMapOf<String,String>()
                    while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                    check(headers["authorization"]=="Bearer $token") {"Missing authenticated friend request"}
                    val length=headers["content-length"]?.toIntOrNull() ?: 0;check(length<=16384)
                    val body=CharArray(length);var offset=0;while(offset<length){val n=input.read(body,offset,length-offset);check(n>0);offset+=n}
                    var type="application/json";var status=200
                    val bytes=when {
                        method=="POST" && path=="/v1/friends/$bob/accept"->{received.set(false);accepted.set(true);"{}".toByteArray()}
                        method=="POST" && path=="/v1/friends/requests"->{if(obj(String(body)).get("username").asString=="missing"){status=404;"""{"detail":"Nome de usuário não encontrado."}""".toByteArray()}else {check(obj(String(body)).get("username").asString=="carol");added.set(true);"{}".toByteArray()}}
                        method=="DELETE" && path=="/v1/friends/$bob"->{accepted.set(false);"{}".toByteArray()}
                        method=="GET" && path=="/v1/me"->{status=503;"{}".toByteArray()}
                        method=="GET" && path==cover->{if(accepted.get()){type="image/jpeg";photo}else {status=404;"{}".toByteArray()}}
                        method=="GET" && path=="/v1/friends/feed"->"""{"sharing":false,"visible_actor_ids":[],"items":[]}""".toByteArray()
                        method=="GET" && path=="/v1/friends"->{
                            val rows=if(accepted.get())"""[{"id":"$bob","username":"bob","current":{"id":"b1","title":"1984","cover":"$cover","page":${readingPage.get()},"pages":100}}]""" else "[]"
                            val pending=if(received.get())"""[{"id":"$bob","username":"bob","incoming":true}]""" else if(added.get())"""[{"id":"9ea34f8d-3d37-47d4-b5df-a9ad9a99c221","username":"carol","incoming":false}]""" else "[]"
                            """{"items":$rows,"requests":$pending,"next_cursor":null}""".toByteArray()
                        }
                        method=="GET" && path.startsWith("/v1/friends/$bob/library?") && failNext.compareAndSet(true,false)->{status=503;"""{"detail":"Falha temporária"}""".toByteArray()}
                        method=="GET" && path.contains("cursor=40") && conflictNext.compareAndSet(true,false)->{status=409;shelfRevision.set(2);"""{"detail":"A estante mudou"}""".toByteArray()}
                        method=="GET" && path.startsWith("/v1/friends/$bob/library?")->{
                            check(accepted.get())
                            val query=path.substringAfter('?').split('&').associate {it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8")}
                            val items=if(query["collection_id"]=="title")"""[{"id":"v1","title":"Volume 1","author":"Autor","item_type":"book","kind":"manga","page":0,"pages":100,"complete":false,"cover":""}]""" else """[{"id":"b1","title":"1984","author":"George Orwell","item_type":"book","kind":"book","page":${readingPage.get()},"pages":100,"complete":false,"cover":"$cover"},{"id":"title","title":"Mangá compartilhado","author":"Autor","item_type":"collection","kind":"manga","volumes":2,"cover":""}]"""
                            """{"friend":{"id":"$bob","username":"bob"},"revision":${shelfRevision.get()},"items":$items,"next_cursor":${if(paginate.get() && shelfRevision.get()==1 && query["collection_id"].isNullOrBlank())"40" else "null"},"collection":null}""".toByteArray()
                        }
                        else->error("Unexpected friend fixture route: $method $path")
                    }
                    socket.getOutputStream().apply {write("HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                }}catch(error:Throwable){if(error !is java.io.IOException)failure.compareAndSet(null,error)}
            }
        }.apply {isDaemon=true;start()}
        val ownPalette=DisplayUi(activity);val ownAccent=ownPalette.accent
        var panel:FriendsPanel?=null;var accounts:AccountManager?=null
        lateinit var parent:LinearLayout
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun all()=views(parent)
        fun await(label:String,condition:()->Boolean){
            val deadline=System.currentTimeMillis()+15000
            while(System.currentTimeMillis()<deadline){failure.get()?.let {throw it};test.waitForIdleSync();var ok=false;test.checkedMain {ok=condition()};if(ok)return;Thread.sleep(50)}
            error("Friend UI timeout: $label")
        }
        fun roots():List<View>{val type=Class.forName("android.view.WindowManagerGlobal");val instance=type.getDeclaredMethod("getInstance").invoke(null);return (type.getDeclaredField("mViews").apply {isAccessible=true}.get(instance) as List<*>).filterIsInstance<View>().flatMap(::views)}
        try {
            test.checkedMain {
                secret.save("""{"token":"$token","address":"http://127.0.0.1:${server.localPort}","user":{"id":"e26ce8bd-55b5-47dd-acdb-b5bab2cf1ce7","server_id":"55aad441-1127-4e08-b0d1-6f86c91c634b","username":"alice","email":"alice@example.test","admin":false}}""")
                accounts=AccountManager(activity,{}, {},{true})
                parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(20,12,20,24)}
                activity.setContentView(ReadingScrollView(activity).apply {id=br.com.centralcasa.R.id.content_scroll;addView(parent)})
                panel=FriendsPanel(activity,DisplayUi(activity),accounts!!){parent.removeAllViews();panel!!.render(parent)}
                panel!!.render(parent)
            }
            await("incoming request"){all().any {it.tag=="friend-accept:$bob"}}
            test.checkedMain {all().single {it.tag=="friend-accept:$bob"}.performClick()}
            await("accepted friend and cover"){
                val row=all().firstOrNull {it.tag=="friend:$bob"} ?: return@await false
                views(row).filterIsInstance<ImageView>().any {(it.drawable as? BitmapDrawable)?.bitmap?.width==32}
            }
            await("friend row follows blue cover"){
                val name=all().filterIsInstance<TextView>().firstOrNull {it.text.toString()=="@bob"} ?: return@await false
                android.graphics.Color.blue(name.currentTextColor)>android.graphics.Color.red(name.currentTextColor)
            }
            test.checkedMain {check(ownPalette.accent==ownAccent)}
            // The timer callback updates a visible friend without a manual reload or hiding the tab.
            fun poll(){FriendsPanel::class.java.getDeclaredField("lastEditAt").apply {isAccessible=true}.setLong(panel,0L);FriendsPanel::class.java.getDeclaredMethod("refreshVisible").apply {isAccessible=true}.invoke(panel)}
            test.checkedMain {readingPage.set(57);poll()}
            await("automatic friend-list page"){all().filterIsInstance<TextView>().any {it.tag=="friend-current-page:$bob" && it.text.toString().startsWith("57 /")}}
            test.checkedMain {readingPage.set(42);poll()}
            await("automatic friend-list correction"){all().filterIsInstance<TextView>().any {it.tag=="friend-current-page:$bob" && it.text.toString().startsWith("42 /")}}
            // Leaving a fully loaded Friends screen must dispose it safely and allow return.
            repeat(3) {
                test.checkedMain {panel!!.hide();parent.removeAllViews();TextView(activity).also {it.text="Outra aba";parent.addView(it)}}
                test.waitForIdleSync()
                test.checkedMain {parent.removeAllViews();panel!!.render(parent)}
                await("return after leaving loaded Friends"){all().any {it.tag=="friend:$bob"}}
            }
            await("returned friend cover and palette"){
                val row=all().firstOrNull {it.tag=="friend:$bob"} ?: return@await false
                views(row).filterIsInstance<ImageView>().any {(it.drawable as? BitmapDrawable)?.bitmap?.width==32} &&
                    views(row).filterIsInstance<TextView>().any {it.text.toString()=="@bob" && android.graphics.Color.blue(it.currentTextColor)>android.graphics.Color.red(it.currentTextColor)}
            }
            test.checkedMain {captureFixture(activity,parent,"friends");(all().single {it.tag=="friend-username"} as EditText).setText("missing");all().single {it.tag=="friend-add"}.performClick()}
            await("unknown username message"){all().filterIsInstance<TextView>().any {it.text.toString()=="Nome de usuário não encontrado."}}
            test.checkedMain {check(all().filterIsInstance<TextView>().none {it.text.toString().startsWith("Atualize a API")});(all().single {it.tag=="friend-username"} as EditText).setText("carol");all().single {it.tag=="friend-add"}.performClick()}
            await("request sent"){added.get() && all().filterIsInstance<TextView>().any {it.text.toString()=="Cancelar pedido"}}
            test.checkedMain {all().single {it.tag=="friend:$bob"}.performClick()}
            await("shared shelf"){all().any {it.tag=="friend-book:b1"}}
            await("friend shelf follows blue cover"){
                val selected=all().filterIsInstance<TextView>().firstOrNull {it.text.toString()=="Todos"} ?: return@await false
                android.graphics.Color.blue(selected.currentTextColor)>android.graphics.Color.red(selected.currentTextColor)
            }
            test.checkedMain {all().single {it.tag=="friend-book:b1"}.performClick();check(roots().filterIsInstance<TextView>().any {it.tag=="book-info-progress" && it.text.toString().contains("42 / 100")});roots().filterIsInstance<Button>().last {it.text.toString()=="Fechar"}.performClick()}
            test.waitForIdleSync()
            test.checkedMain {readingPage.set(64);poll()}
            await("automatic friend shelf page"){
                val field=FriendsPanel::class.java.getDeclaredField("items").apply {isAccessible=true}
                @Suppress("UNCHECKED_CAST") val rows=field.get(panel) as List<JsonObject>
                rows.any {it.get("id").asString=="b1" && it.get("page").asInt==64}
            }
            test.checkedMain {all().single {it.tag=="friend-book:b1"}.performClick();check(roots().filterIsInstance<TextView>().any {it.tag=="book-info-progress" && it.text.toString().contains("64 / 100")});roots().filterIsInstance<Button>().last {it.text.toString()=="Fechar"}.performClick();readingPage.set(42)}
            test.waitForIdleSync()
            test.checkedMain {accepted.set(false);poll()}
            await("automatic revoked friend clears private books"){all().none {it.tag=="friend-book:b1"} && all().filterIsInstance<TextView>().any {it.text.toString().startsWith("Amizade encerrada")}}
            test.checkedMain {accepted.set(true);all().first {it.contentDescription?.toString()=="Atualizar amigos"}.performClick()}
            await("friend shelf reload after permission fixture restored"){all().any {it.tag=="friend-book:b1"}}
            test.checkedMain {failNext.set(true);all().first {it.contentDescription?.toString()=="Atualizar amigos"}.performClick()}
            await("temporary failure keeps books"){all().filterIsInstance<TextView>().any {it.text.toString()=="Falha temporária"}}
            test.checkedMain {check(all().any {it.tag=="friend-book:b1"});paginate.set(true);all().filterIsInstance<TextView>().single {it.text.toString()=="Tentar novamente"}.performClick()}
            await("paginated shelf"){all().filterIsInstance<TextView>().any {it.text.toString()=="Carregar mais"}}
            test.checkedMain {failNext.set(true);all().filterIsInstance<TextView>().single {it.text.toString()=="Carregar mais"}.performClick()}
            await("page failure preserves books"){all().filterIsInstance<TextView>().any {it.text.toString()=="Falha temporária"}}
            test.checkedMain {check(all().any {it.tag=="friend-book:b1"});all().filterIsInstance<TextView>().single {it.text.toString()=="Tentar novamente"}.performClick()}
            await("retry keeps cursor"){all().firstOrNull {it.contentDescription=="Atualizar amigos"}?.isEnabled==true && moreCalls.get()==2 && all().filterIsInstance<TextView>().none {it.text.toString()=="Carregando…" || it.text.toString()=="Falha temporária"}}
            test.checkedMain {conflictNext.set(true);all().filterIsInstance<TextView>().single {it.text.toString()=="Carregar mais"}.performClick();check(all().any {it.tag=="friend-book:b1"})}
            await("changed revision recovered"){all().firstOrNull {it.contentDescription=="Atualizar amigos"}?.isEnabled==true && shelfRevision.get()==2 && all().any {it.tag=="friend-book:b1"} && all().filterIsInstance<TextView>().none {it.text.toString()=="Carregar mais" || it.text.toString()=="Carregando…"}}
            test.checkedMain {
                val grid=all().single {it.tag=="friend-shelf-grid"} as LinearLayout
                check((grid.getChildAt(0) as LinearLayout).childCount==3)
                captureFixture(activity,parent,"friend-shelf-3-columns")
                check(panel!!.pinchColumns(1.5));check(((all().single {it.tag=="friend-shelf-grid"} as LinearLayout).getChildAt(0) as LinearLayout).childCount==2)
                captureFixture(activity,parent,"friend-shelf-2-columns")
                repeat(4){check(panel!!.pinchColumns(.6))}
                check(((all().single {it.tag=="friend-shelf-grid"} as LinearLayout).getChildAt(0) as LinearLayout).childCount==6)
                captureFixture(activity,parent,"friend-shelf-6-columns")
                repeat(3){check(panel!!.pinchColumns(1.5))}
            }
            test.checkedMain {check(ownPalette.accent==ownAccent);check(all().filterIsInstance<TextView>().none {it.text.toString()=="Salvar"});check(repo.books()==listOf(repo.book(own.id)!!));check(repo.book(own.id)?.currentPage==15);captureFixture(activity,parent,"friend-shelf");all().single {it.tag=="friend-book:title"}.performClick()}
            await("collection volumes"){all().any {it.tag=="friend-book:v1"}}
            test.checkedMain {check(panel!!.back())}
            await("shelf again"){all().any {it.tag=="friend-book:b1"}}
            test.checkedMain {check(panel!!.back())}
            await("friend list again"){all().any {it.tag=="friend:$bob"}}
            test.checkedMain {all().single {it.tag=="friend:$bob"}.performClick()}
            await("remove action"){all().any {it.tag=="friend-book:b1"}}
            test.checkedMain {
                all().first {it.contentDescription?.toString()=="Opções da amizade"}.performClick()
                val list=roots().filterIsInstance<ListView>().last();list.performItemClick(View(activity),0,list.adapter.getItemId(0))
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button1}.performClick()
            }
            await("friend removed"){!accepted.get() && all().none {it.tag=="friend:$bob"} && all().filterIsInstance<TextView>().any {it.text.toString().equals("Amigos",true)}}
            test.checkedMain {check(repo.book(own.id)?.currentPage==15 && repo.currentId()==own.id);check(repo.books().size==1)}
            val client=AccountApi("http://127.0.0.1:${server.localPort}",token)
            try {check(runCatching {client.thumbnail("https://example.test/leak")}.isFailure)}finally {client.close()}
            // Exercise the real bottom navigation with loaded data, not just a detached panel.
            accepted.set(true)
            test.checkedMain {panel!!.hide();activity.finish()}
            test.waitForIdleSync()
            val host=test.startActivitySync(android.content.Intent(test.targetContext,ViblioActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                fun hostViews()=views(host.window.decorView)
                fun tab(label:String)=hostViews().firstOrNull {it is LinearLayout && it.contentDescription?.toString()==label} ?: error("Missing bottom tab $label; labels="+hostViews().filterIsInstance<TextView>().joinToString {it.text.toString().take(80)})
                for(destination in listOf("Estante","Livro atual","Pesquisa","Estatísticas")){
                    test.checkedMain {tab("Amigos").performClick();hostViews().firstOrNull {it.tag=="social-tab:friends"}?.performClick()}
                    await("host Friends before $destination"){hostViews().any {it.tag=="friend:$bob"}}
                    test.checkedMain {check(hostViews().none {it.tag=="friend-book:b1"});check(hostViews().filterIsInstance<TextView>().any {it.tag=="friend-current-page:$bob" && it.text.toString().contains("42 / 100")})}
                    test.checkedMain {hostViews().firstOrNull {it.tag=="friend:$bob"}?.performClick()}
                    await("friend background before $destination"){
                        hostViews().any {it.tag=="friend-book:b1"} && android.graphics.Color.blue(host.window.statusBarColor)>android.graphics.Color.red(host.window.statusBarColor)
                    }
                    test.checkedMain {tab(destination).performClick()}
                    test.waitForIdleSync()
                    test.checkedMain {check(tab(destination).isSelected);check(host.window.statusBarColor==ownPalette.background)}
                }
            }finally {test.checkedMain {host.finish()}}
        }finally {
            test.checkedMain {panel?.close();accounts?.close();if(before.isBlank())secret.clear()else secret.save(before);repo.delete(own.id);repo.close()}
            server.close();thread.join(1000)
        }
        failure.get()?.let {throw it}
    }
}
