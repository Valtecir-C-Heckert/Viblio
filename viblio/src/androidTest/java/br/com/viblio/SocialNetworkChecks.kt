package br.com.viblio

import android.app.Instrumentation
import android.content.Intent
import android.view.*
import android.widget.*
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.centralcasa.ui.*
import br.com.viblio.account.AccountManager
import br.com.viblio.ui.SocialPanel
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** A reachable origin can still stall/reject an authenticated call. Never leave a blank tab. */
object SocialNetworkChecks {
    fun run(test:Instrumentation){
        val activity=test.startActivitySync(Intent(test.targetContext,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val secret=HomeAssistantCredentials(activity,"viblio_account_session","viblio_account_session_key");val before=secret.load()
        val server=ServerSocket(0);val workers=Executors.newFixedThreadPool(2);val release=CountDownLatch(1);val queueRelease=CountDownLatch(1);val clubReads=AtomicInteger(0)
        val slow=AtomicBoolean(true);val clubFail=AtomicBoolean(true);val friendFail=AtomicBoolean(true);val failure=AtomicReference<Throwable?>()
        val accept=Thread {
            while(!server.isClosed)try {
                val socket=server.accept()
                workers.execute {socket.use {
                    try {
                        val input=it.getInputStream().bufferedReader();val route=input.readLine().split(' ')[1];var auth=""
                        while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Authorization:",true))auth=line.substringAfter(':').trim()}
                        check(auth=="Bearer social-network-fixture-only")
                        if(route=="/v1/friends/feed" && slow.compareAndSet(true,false)){
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{".toByteArray())
                            check(release.await(25,TimeUnit.SECONDS));return@use
                        }
                        if(route=="/v1/clubs")clubReads.incrementAndGet()
                        val failed=route=="/v1/clubs" && clubFail.compareAndSet(true,false) || route=="/v1/friends" && friendFail.compareAndSet(true,false)
                        val body=if(failed)"""{"detail":"Falha temporária de teste"}""" else """{"items":[],"requests":[],"visible_actor_ids":[]}"""
                        it.getOutputStream().write(("HTTP/1.1 ${if(failed)503 else 200} Response\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"+body).toByteArray())
                    }catch(e:Throwable){if(e !is java.io.IOException)failure.compareAndSet(null,e)}
                }}
            }catch(e:java.io.IOException){if(!server.isClosed)failure.compareAndSet(null,e)}
        }.apply {isDaemon=true;start()}
        var panel:SocialPanel?=null;var accounts:AccountManager?=null;lateinit var parent:LinearLayout
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun all()=views(parent)
        fun await(label:String,predicate:()->Boolean){
            val until=System.currentTimeMillis()+20000
            while(System.currentTimeMillis()<until){failure.get()?.let {throw it};test.waitForIdleSync();var ok=false;test.checkedMain {ok=predicate()};if(ok)return;Thread.sleep(30)}
            error("Social network timeout: $label")
        }
        fun text(value:String)=all().filterIsInstance<TextView>().first {it.text.toString()==value}
        try {
            test.checkedMain {
                secret.save("""{"token":"social-network-fixture-only","address":"http://127.0.0.1:${server.localPort}","user":{"id":"e26ce8bd-55b5-47dd-acdb-b5bab2cf1ce7","server_id":"55aad441-1127-4e08-b0d1-6f86c91c634b","username":"fixture","admin":false}}""")
                accounts=AccountManager(activity,{}, {},{true});parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                activity.setContentView(ReadingScrollView(activity).apply {id=br.com.centralcasa.R.id.content_scroll;addView(parent)})
                panel=SocialPanel(activity,DisplayUi(activity),accounts!!){parent.removeAllViews();panel!!.render(parent)};panel!!.render(parent)
            }
            await("visible progress instead of blank feed"){all().any {it.tag=="social-loading"}}
            test.checkedMain {check(all().filterIsInstance<TextView>().none {it.text.toString()=="Carregando…" || it.text.toString()=="Nenhuma atividade dos seus amigos."})}
            await("bounded authenticated timeout with retry"){all().filterIsInstance<TextView>().any {it.text.toString().contains("127.0.0.1 demorou")}}
            release.countDown();test.checkedMain {text("Tentar novamente").performClick()}
            await("successful empty feed"){all().filterIsInstance<TextView>().any {it.text.toString()=="Nenhuma atividade dos seus amigos."}}
            for((tab,empty) in listOf("clubs" to "Nenhum clube ainda.","friends" to "Nenhum amigo.")){
                test.checkedMain {all().first {it.tag=="social-tab:$tab"}.performClick()}
                await("$tab errors appear"){all().filterIsInstance<TextView>().any {it.text.toString()=="Falha temporária de teste"}}
                test.checkedMain {text("Tentar novamente").performClick()}
                await("$tab recovers"){all().filterIsInstance<TextView>().any {it.text.toString()==empty}}
            }
            test.checkedMain {all().first {it.tag=="social-tab:feed"}.performClick()}
            await("feed before queued navigation"){all().filterIsInstance<TextView>().any {it.text.toString()=="Nenhuma atividade dos seus amigos."}}
            val entered=CountDownLatch(1)
            val worker=SocialPanel::class.java.getDeclaredField("worker").apply {isAccessible=true}.get(panel) as ExecutorService
            worker.execute {entered.countDown();check(queueRelease.await(5,TimeUnit.SECONDS))}
            check(entered.await(2,TimeUnit.SECONDS));val previous=clubReads.get()
            test.checkedMain {all().first {it.tag=="social-tab:clubs"}.performClick()}
            test.waitForIdleSync()
            test.checkedMain {check(all().any {it.tag=="social-loading"});all().first {it.tag=="social-tab:feed"}.performClick()}
            test.waitForIdleSync();queueRelease.countDown()
            await("latest tab recovers without the queued old request"){all().filterIsInstance<TextView>().any {it.text.toString()=="Nenhuma atividade dos seus amigos."} && all().none {it.tag=="social-loading"}}
            check(clubReads.get()==previous)
        }finally {
            queueRelease.countDown();release.countDown();test.checkedMain {panel?.close();accounts?.close();if(before.isBlank())secret.clear()else secret.save(before);activity.finish()}
            server.close();accept.join(1000);workers.shutdown();check(workers.awaitTermination(3,TimeUnit.SECONDS))
        }
        failure.get()?.let {throw it}
    }
}
