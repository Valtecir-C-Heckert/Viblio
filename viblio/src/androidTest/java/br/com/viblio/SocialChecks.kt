package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.*
import android.widget.*
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.centralcasa.ui.*
import br.com.viblio.account.*
import br.com.viblio.ui.SocialPanel
import java.net.*
import java.util.concurrent.atomic.*

/** Authenticated loopback fixtures exercise social rendering and navigation on an emulator. */
object SocialChecks {
    fun run(test:Instrumentation){
        val activity=test.startActivitySync(Intent(test.targetContext,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val secret=HomeAssistantCredentials(activity,"viblio_account_session","viblio_account_session_key");val before=secret.load()
        val token="social-fixture-only-abcdefghijklmnopqrstuvwxyz";val bob="b1c6f3ea-1acd-4c9d-9168-d94d21d7de6b";val club="a1c6f3ea-1acd-4c9d-9168-d94d21d7de6b";val round="c1c6f3ea-1acd-4c9d-9168-d94d21d7de6b";val stage="d1c6f3ea-1acd-4c9d-9168-d94d21d7de6b"
        val clubCover="/v1/clubs/$club/proposals/$bob/cover"
        val photo=java.io.ByteArrayOutputStream().also {out->android.graphics.Bitmap.createBitmap(32,48,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}.let {it.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);it.recycle()}}.toByteArray()
        val feedCover="/v1/friends/$bob/cover?book_id=book&revision=1"
        val pink=java.io.ByteArrayOutputStream().also {out->android.graphics.Bitmap.createBitmap(32,48,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(0xffff66aa.toInt())}.let {it.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);it.recycle()}}.toByteArray()
        val remote=AtomicBoolean(false);val voting=AtomicBoolean(false);val nominated=AtomicBoolean(false);val sharing=AtomicBoolean(true);val requested=AtomicBoolean(false);val message=AtomicBoolean(false);val failure=AtomicReference<Throwable?>()
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val thread=Thread {
            while(!server.isClosed)try {server.accept().use {socket->
                val input=socket.getInputStream().bufferedReader();val first=(input.readLine() ?: return@use).split(' ');val method=first[0];val path=first[1];val headers=mutableMapOf<String,String>()
                while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                check(headers["authorization"]=="Bearer $token");val length=headers["content-length"]?.toIntOrNull() ?: 0;check(length<=16384);val chars=CharArray(length);var count=0;while(count<length){val n=input.read(chars,count,length-count);check(n>0);count+=n};val body=String(chars)
                val content=if(path==clubCover)photo else if(path==feedCover)pink else when {
                    path=="/v1/friends/feed"->"""{"sharing":${sharing.get()},"visible_actor_ids":["$bob"],"items":[{"id":"event","actor":{"id":"$bob","username":"bob"},"kind":"read","occurred_at":${System.currentTimeMillis()},"book":{"id":"book","title":"Piranesi","author":"Susanna Clarke","pages":232,"page":68,"cover":"$feedCover"},"start_page":44,"end_page":68,"pages":24,"pages_known":true,"duration_ms":2100000}]}"""
                    path=="/v1/me/activity" && method=="PATCH"->{sharing.set(body.contains("true"));"""{"enabled":${sharing.get()}}"""}
                    path=="/v1/me"->"""{"id":"e26ce8bd-55b5-47dd-acdb-b5bab2cf1ce7","username":"alice","admin":false}"""
                    path=="/v1/friends"->"""{"items":[],"requests":[]}"""
                    path=="/v1/friends/people"->"""{"items":[{"id":"$bob","username":"bob","relationship":"${if(requested.get())"outgoing" else "none"}"}]}"""
                    path=="/v1/friends/requests"->{check(body.contains("bob"));requested.set(true);"{}"}
                    path=="/v1/clubs"->"""{"items":[{"id":"$club","name":"Clube Aurora","role":"admin","status":"active","book":{"title":"Piranesi","cover":"$clubCover"}}]}"""
                    path.startsWith("/v1/clubs/$club/feed?")->"""{"items":[{"id":"e:1","actor":{"id":"$bob","username":"bob"},"kind":"progress","occurred_at":${System.currentTimeMillis()},"data":{"page":68,"previous_page":44}}]}"""
                    path.startsWith("/v1/clubs/$club/books?")->"""{"items":[{"id":"book","title":"Livro da estante de Bob","author":"Autor","pages":232,"owner":{"id":"$bob","username":"bob"},"cover":"$clubCover"},{"id":"book","title":"Outra cópia local","author":"Autor","pages":100,"owner":{"id":"e26ce8bd-55b5-47dd-acdb-b5bab2cf1ce7","username":"alice"},"cover":""}]}"""
                    path=="/v1/clubs/$club/proposals" && method=="POST"->{check(body.contains("$bob") && body.contains("source_user_id"));nominated.set(true);"{}"}
                    path=="/v1/clubs/$club"->"""{"id":"$club","name":"Clube Aurora","role":"admin","round":{"id":"$round","state":"${if(voting.get())"voting" else "reading"}","selected_id":${if(voting.get())"null" else "\"$bob\""},"due_at":"2027-02-20T20:00:00Z"},"members":[{"id":"$bob","username":"bob","role":"member","status":"active","page":68}],"proposals":[{"id":"$bob","title":"Piranesi","author":"Susanna Clarke","pages":232,"cover":"$clubCover"}],"stages":[{"id":"$stage","page_limit":80,"discuss_at":"2027-02-01T20:00:00Z","is_open":true,"comments":${if(message.get())1 else 0}}],"history":[{"id":"$round","title":"Leitura anterior","state":"completed","cover":"$clubCover","completed_at":"2026-11-15T20:00:00Z"}]}"""
                    path=="/v1/clubs/$club/stages/$stage/events"->"event: ready\ndata: {}\n\n"
                    path=="/v1/clubs/$club/stages/$stage/messages" && method=="POST"->{check(body.contains("Gostei"));message.set(true);"{}"}
                    path=="/v1/clubs/$club/stages/$stage/messages"->{
                        val rows=mutableListOf<String>()
                        if(remote.get())rows.add("""{"id":2,"username":"bob","body":"Comentário recebido ao vivo.","sent_at":"2026-11-15T20:01:00Z","can_delete":false}""")
                        if(message.get())rows.add("""{"id":1,"username":"alice","body":"Gostei desta etapa.","sent_at":"2026-11-15T20:00:00Z","can_delete":true}""")
                        """{"stage":{"id":"$stage","page_limit":80,"discuss_at":"2027-02-01T20:00:00Z","is_open":true},"items":[${rows.joinToString(",")}] }"""
                    }
                    else->error("Unexpected social request: $method $path")
                }.toByteArray()
                socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: ${if(path==clubCover || path==feedCover)"image/jpeg" else if(path.endsWith("/events"))"text/event-stream" else "application/json"}\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray());write(content);flush()}
            }}catch(e:Throwable){if(e !is java.io.IOException)failure.compareAndSet(null,e)}
        }.apply {isDaemon=true;start()}
        val ownUi=DisplayUi(activity);val ownAccent=ownUi.accent
        var panel:SocialPanel?=null;var accounts:AccountManager?=null;lateinit var parent:LinearLayout
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun all()=views(parent)
        fun roots():List<View>{val type=Class.forName("android.view.WindowManagerGlobal");val instance=type.getDeclaredMethod("getInstance").invoke(null);return (type.getDeclaredField("mViews").apply {isAccessible=true}.get(instance) as List<*>).filterIsInstance<View>().flatMap(::views)}
        fun option(label:String){test.checkedMain {all().first {it.tag=="club-options"}.performClick();val list=roots().filterIsInstance<ListView>().last();val index=(0 until list.adapter.count).first {list.adapter.getItem(it).toString()==label};list.performItemClick(View(activity),index,list.adapter.getItemId(index))}}
        fun await(label:String,predicate:()->Boolean){val until=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<until){failure.get()?.let {throw it};test.waitForIdleSync();var ok=false;test.checkedMain {ok=predicate()};if(ok)return;Thread.sleep(50)};error("Social timeout: $label")}
        fun tap(tag:String){test.checkedMain {all().first {it.tag==tag}.performClick()}}
        try {
            test.checkedMain {
                secret.save("""{"token":"$token","address":"http://127.0.0.1:${server.localPort}","user":{"id":"e26ce8bd-55b5-47dd-acdb-b5bab2cf1ce7","server_id":"55aad441-1127-4e08-b0d1-6f86c91c634b","username":"alice","email":"alice@example.test","admin":false}}""")
                accounts=AccountManager(activity,{}, {},{true});parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(20,12,20,24)}
                activity.setContentView(ReadingScrollView(activity).apply {id=br.com.centralcasa.R.id.content_scroll;addView(parent)})
                panel=SocialPanel(activity,ownUi,accounts!!){parent.removeAllViews();panel!!.render(parent)};panel!!.render(parent)
            }
            await("feed and saved session"){all().filterIsInstance<TextView>().any {it.text.toString()=="Páginas 44 → 68"}}
            await("pink reading card"){val c=(all().first {it.tag=="feed-event:event"}.background as? android.graphics.drawable.GradientDrawable)?.color?.defaultColor ?: 0;android.graphics.Color.red(c)>android.graphics.Color.blue(c)}
            test.checkedMain {check(all().filterIsInstance<TextView>().any {it.text.toString()=="24 páginas"});check(all().filterIsInstance<TextView>().any {it.text.toString()=="35 min"});captureFixture(activity,parent,"social-feed");check(all().none {it.tag=="activity-sharing"})}
            tap("social-tab:friends");await("friends directory control"){all().any {it.tag=="friends-discover"}}
            tap("friends-discover");await("directory"){all().any {it.tag=="person-add:$bob"}}
            tap("person-add:$bob");await("request requires acceptance"){requested.get() && all().filterIsInstance<TextView>().any {it.text.toString()=="Pedido enviado"}}
            test.checkedMain {check(panel!!.back())};await("back to friends"){all().any {it.tag=="friends-discover"}}
            tap("social-tab:clubs");await("club list"){all().any {it.tag=="club-open:$club"}}
            tap("club-open:$club");await("club stage and admin actions"){all().any {it.tag=="club-discussion:$stage"} && all().any {it.tag=="club-final-deadline"} && all().none {it.tag=="club-invite"}}
            await("club follows its blue cover"){val c=panel!!.displayTheme.accent;android.graphics.Color.blue(c)>android.graphics.Color.red(c)}
            test.checkedMain {check(ownUi.accent==ownAccent);check(all().any {it.tag=="club-feed:e:1"});captureFixture(activity,parent,"social-club")}
            option("Configurações do clube");await("separate settings"){all().any {it.tag=="club-invite"} && all().none {it.tag=="club-discussion:$stage"}}
            test.checkedMain {check(panel!!.back())};await("home after settings"){all().any {it.tag=="club-discussion:$stage"}}
            tap("club-discussion:$stage");await("discussion"){(all().firstOrNull {it.tag=="club-message"} as? EditText)?.isEnabled==true && all().any {it.tag=="club-send"} && all().filterIsInstance<TextView>().none {it.text.toString()=="Carregando…"}}
            lateinit var composer:EditText
            test.checkedMain {
                composer=all().single {it.tag=="club-message"} as EditText
                composer.setText("Gostei desta etapa.");composer.requestFocus();composer.setSelection(3)
                check(all().single {it.tag=="club-send"}.isEnabled);remote.set(true)
            }
            await("live messages preserve composer and selection"){all().any {it.tag=="club-message:2"} && all().single {it.tag=="club-message"}===composer}
            test.checkedMain {check(composer.text.toString()=="Gostei desta etapa." && composer.selectionStart==3 && composer.hasFocus())}
            test.checkedMain {val saved=android.os.Bundle();panel!!.save(saved);panel!!.hide(resetSelection=true);panel!!.restore(saved);parent.removeAllViews();panel!!.render(parent)}
            await("draft restored in its own session"){(all().firstOrNull {it.tag=="club-message"} as? EditText)?.text?.toString()=="Gostei desta etapa." && all().singleOrNull {it.tag=="club-send"}?.isEnabled==true}
            tap("club-send");await("saved discussion message"){message.get() && all().any {it.tag=="club-message:1"}}
            test.checkedMain {check(ownUi.accent==ownAccent)}
            test.checkedMain {check(panel!!.back())};await("home after discussion"){all().any {it.tag=="club-final-deadline"}}
            option("Histórico de leituras");await("club reading history"){all().any {it.tag=="club-history:$round"}}
            test.checkedMain {check(panel!!.back())};await("home after history"){all().any {it.tag=="club-final-deadline"}}
            await("club refresh is available"){all().firstOrNull {it.contentDescription=="Atualizar"}?.isEnabled==true}
            test.checkedMain {voting.set(true);all().first {it.contentDescription=="Atualizar"}.performClick()}
            await("voting"){all().any {it.tag=="club-propose"}}
            tap("club-propose");await("other member shelf"){all().count {it.tag?.toString()?.startsWith("club-candidate:")==true}==2 && all().any {it.tag=="club-nominate:$bob:book"}}
            tap("club-nominate:$bob:book");await("nomination from member shelf"){nominated.get() && all().any {it.tag=="club-propose"}}
            // A later page fills the third slot without changing which cover is selected.
            var picked=""
            test.checkedMain {
                val covers=(1..6).map {br.com.centralcasa.books.catalog.CatalogCover("$it","",primary=it==1)}
                // Non-network synthetic URLs still remain distinct for pagination deduplication.
                val distinct=covers.mapIndexed {i,c->c.copy(url="https://fixture.invalid/cover-${i+1}.jpg")}
                val dialog=br.com.viblio.ui.CoverGalleryDialog.show(activity,distinct.take(4),more={_,done->done(Result.success(br.com.centralcasa.books.catalog.CatalogCoverPage("fixture",distinct.drop(3))))},nextCursor=4){picked=it.id}
                val grid=views(dialog.window!!.decorView).first {it.tag=="catalog-cover-grid"} as LinearLayout
                check(grid.childCount==2)
                views(dialog.window!!.decorView).filterIsInstance<TextView>().first {it.text.toString()=="Carregar mais capas"}.performClick()
                check(grid.childCount==2 && (0 until grid.childCount).all {(grid.getChildAt(it) as LinearLayout).childCount==3})
                views(grid).first {it.tag=="catalog-cover:https://fixture.invalid/cover-6.jpg"}.performClick()
                check(picked=="6" && !dialog.isShowing)
            }
            // Stale background calls and image callbacks must never redraw a hidden panel.
            repeat(3){test.checkedMain {panel!!.hide(resetSelection=true);parent.removeAllViews();panel!!.render(parent)};await("return after hide"){all().any {it.tag=="club-open:$club"} && panel!!.displayTheme.accent==ownAccent}}
        }finally {
            test.checkedMain {panel?.close();accounts?.close();if(before.isBlank())secret.clear()else secret.save(before);activity.finish()}
            server.close();thread.join(1000)
        }
        failure.get()?.let {throw it}
    }
}
