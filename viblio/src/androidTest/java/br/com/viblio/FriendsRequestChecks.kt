package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.centralcasa.ui.DisplayUi
import br.com.centralcasa.ui.ReadingScrollView
import br.com.viblio.account.AccountManager
import br.com.viblio.ui.FriendsPanel
import com.google.gson.JsonParser
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A late response must not keep another session/navigation busy or replace its private data. */
object FriendsRequestChecks {
    fun run(test:Instrumentation,activity:Activity){
        val secret=HomeAssistantCredentials(activity,"viblio_account_session","viblio_account_session_key");val before=secret.load()
        val server=ServerSocket(0);val workers=Executors.newFixedThreadPool(3)
        val oldEntered=CountDownLatch(1);val oldRelease=CountDownLatch(1)
        val refreshEntered=CountDownLatch(1);val refreshRelease=CountDownLatch(1)
        val holdRefresh=AtomicBoolean(false);val failure=AtomicReference<Throwable?>()
        val friend="b1c6f3ea-1acd-4c9d-9168-d94d21d7de6b"
        val accept=Thread {
            while(!server.isClosed)try {
                val socket=server.accept()
                workers.execute {socket.use {
                    try {
                        val reader=it.getInputStream().bufferedReader();val path=reader.readLine().split(' ')[1];var token=""
                        while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Authorization:",true))token=line.substringAfter("Bearer ")}
                        check(token in listOf("old-fixture-session","new-fixture-session"))
                        val body=if(path=="/v1/friends"){
                            if(token=="old-fixture-session"){oldEntered.countDown();check(oldRelease.await(10,TimeUnit.SECONDS))}
                            else if(holdRefresh.compareAndSet(true,false)){refreshEntered.countDown();check(refreshRelease.await(10,TimeUnit.SECONDS))}
                            """{"items":[{"id":"$friend","username":"${if(token=="old-fixture-session")"stale_old_account" else "fresh"}","current":null}],"requests":[]}"""
                        }else {
                            check(path.startsWith("/v1/friends/$friend/library?"))
                            """{"friend":{"id":"$friend","username":"fresh"},"revision":1,"items":[{"id":"fresh-book","title":"Fresh book","author":"Author","kind":"book","item_type":"book","page":12,"pages":100,"cover":""}],"next_cursor":null}"""
                        }
                        it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"+body).toByteArray())
                    }catch(e:Throwable){if(e !is java.io.IOException)failure.compareAndSet(null,e)}
                }}
            }catch(e:java.io.IOException){if(!server.isClosed)failure.compareAndSet(null,e)}
        }.apply {isDaemon=true;start()}
        fun session(token:String)="""{"token":"$token","address":"http://127.0.0.1:${server.localPort}","user":{"id":"e26ce8bd-55b5-47dd-acdb-b5bab2cf1ce7","server_id":"55aad441-1127-4e08-b0d1-6f86c91c634b","username":"fixture","admin":false}}"""
        var panel:FriendsPanel?=null;var accounts:AccountManager?=null;lateinit var parent:LinearLayout
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun await(label:String,predicate:()->Boolean){
            val until=System.currentTimeMillis()+6000
            while(System.currentTimeMillis()<until){failure.get()?.let {throw it};test.waitForIdleSync();var ok=false;test.checkedMain {ok=predicate()};if(ok)return;Thread.sleep(25)}
            error("Friends lifecycle timeout: $label")
        }
        try {
            test.checkedMain {
                secret.save(session("old-fixture-session"));accounts=AccountManager(activity,{}, {},{true})
                parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                activity.setContentView(ReadingScrollView(activity).apply {id=br.com.centralcasa.R.id.content_scroll;addView(parent)})
                panel=FriendsPanel(activity,DisplayUi(activity),accounts!!){parent.removeAllViews();panel!!.render(parent)};panel!!.render(parent)
            }
            check(oldEntered.await(4,TimeUnit.SECONDS))
            test.checkedMain {
                AccountManager::class.java.getDeclaredField("session").apply {isAccessible=true}.set(accounts,JsonParser.parseString(session("new-fixture-session")).asJsonObject)
                parent.removeAllViews();panel!!.render(parent)
            }
            await("new session loads before old reply is released"){views(parent).filterIsInstance<TextView>().any {it.text.toString()=="@fresh"}}
            oldRelease.countDown()
            test.checkedMain {holdRefresh.set(true);panel!!.refresh()}
            check(refreshEntered.await(4,TimeUnit.SECONDS))
            test.checkedMain {views(parent).first {it.tag=="friend:$friend"}.performClick()}
            await("navigation loads before cancelled refresh finishes"){views(parent).any {it.tag=="friend-book:fresh-book"}}
            refreshRelease.countDown()
            test.waitForIdleSync()
            test.checkedMain {check(views(parent).filterIsInstance<TextView>().none {it.text.toString().contains("stale_old_account")})}
        }finally {
            oldRelease.countDown();refreshRelease.countDown()
            test.checkedMain {panel?.close();accounts?.close();if(before.isBlank())secret.clear()else secret.save(before)}
            server.close();accept.join(1000);workers.shutdown();check(workers.awaitTermination(3,TimeUnit.SECONDS))
        }
        failure.get()?.let {throw it}
    }
}
