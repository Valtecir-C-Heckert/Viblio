package br.com.viblio.account

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DiscussionEventsTest {
    private val path="/v1/clubs/11111111-1111-4111-8111-111111111111/stages/22222222-2222-4222-8222-222222222222/events"
    @Test fun receivesEventsWithBearerAndStopsOnRevocation(){
        ServerSocket(0).use { server->
            var request=""
            val worker=thread {
                server.accept().use {socket->
                    val reader=socket.getInputStream().bufferedReader()
                    request=generateSequence {reader.readLine()?.takeIf {it.isNotEmpty()}}.joinToString("\n")
                    val body="event: ready\ndata: {}\n\n: heartbeat\n\nevent: changed\ndata: {}\n\nevent: revoked\ndata: {}\n\nevent: changed\ndata: {}\n\n"
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.length}\r\n\r\n"+body).toByteArray())
                }
            }
            val api=AccountApi("http://127.0.0.1:${server.localPort}","private-test-token",OkHttpClient())
            try {
                val events=mutableListOf<String>();api.discussionEvents(path,DiscussionConnection()){events.add(it)}
                assertEquals(listOf("ready","changed","revoked"),events)
                assertTrue(request.contains("Authorization: Bearer private-test-token"))
                assertFalse(request.lineSequence().first().contains("private-test-token"))
            }finally {api.close();worker.join(2000)}
        }
    }
    @Test fun rejectsForeignUrlsAndOversizedEvents(){
        val api=AccountApi("http://127.0.0.1:1","",OkHttpClient())
        try {assertTrue(runCatching {api.discussionEvents("https://other.example/events",DiscussionConnection()) {}}.isFailure)}finally {api.close()}
        ServerSocket(0).use {server->
            val worker=thread {server.accept().use {socket->
                val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
                val body="data: "+"x".repeat(5000)+"\n\n"
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.length}\r\n\r\n"+body).toByteArray())
            }}
            val client=AccountApi("http://127.0.0.1:${server.localPort}","",OkHttpClient())
            try {assertTrue(runCatching {client.discussionEvents(path,DiscussionConnection()) {fail("Invalid event")}}.isFailure)}finally {client.close();worker.join(2000)}
        }
    }
    @Test fun cancelsAStalledBodyAfterHeadersAndKeepsTheClientUsable(){
        ServerSocket(0).use {server->
            val bodyStarted=CountDownLatch(1);val release=CountDownLatch(1)
            val worker=thread {
                server.accept().use {socket->
                    val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{".toByteArray())
                    release.await(4,TimeUnit.SECONDS)
                }
                server.accept().use {socket->
                    val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                }
            }
            val client=OkHttpClient.Builder().eventListener(object:okhttp3.EventListener(){override fun responseBodyStart(call:okhttp3.Call){bodyStarted.countDown()}}).build()
            val api=AccountApi("http://127.0.0.1:${server.localPort}","",client);val tag=Any()
            val stalled=thread {runCatching {api.call("/v1/friends",tag=tag)}}
            try {
                assertTrue(bodyStarted.await(3,TimeUnit.SECONDS));api.cancel(tag);stalled.join(1500);assertFalse(stalled.isAlive)
                release.countDown();assertTrue(api.call("/v1/friends").entrySet().isEmpty())
            }finally {release.countDown();api.close();worker.join(2000);stalled.join(2000)}
        }
    }
    @Test fun navigationCanCancelBeforeTheWorkerStarts(){
        val api=AccountApi("http://127.0.0.1:1","",OkHttpClient());val connection=DiscussionConnection()
        try {api.cancel(connection);api.discussionEvents(path,connection){fail("Cancelled navigation")}}finally {api.close()}
    }
    @Test fun cancelsOnlyTheTaggedLiveConnection(){
        ServerSocket(0).use {server->
            val release=CountDownLatch(1);val ready=CountDownLatch(1)
            val worker=thread {server.accept().use {socket->
                val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n17\r\nevent: ready\ndata: {}\n\n\r\n".toByteArray())
                release.await(5,TimeUnit.SECONDS)
            }}
            val api=AccountApi("http://127.0.0.1:${server.localPort}","",OkHttpClient());val tag=DiscussionConnection()
            val stream=thread {runCatching {api.discussionEvents(path,tag){ready.countDown()}}}
            try {
                assertTrue(ready.await(3,TimeUnit.SECONDS));api.cancel(tag);stream.join(2000);assertFalse(stream.isAlive)
            }finally {release.countDown();api.close();worker.join(2000);stream.join(2000)}
        }
    }
}
