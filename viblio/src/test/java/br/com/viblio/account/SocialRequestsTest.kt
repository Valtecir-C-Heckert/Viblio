package br.com.viblio.account

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SocialRequestsTest {
    private fun reply(socket:java.net.Socket,body:String){
        val input=socket.getInputStream().bufferedReader();while(!input.readLine().isNullOrEmpty()){}
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"+body).toByteArray())
    }
    @Test fun rejectsInvalidListsWithoutTreatingThemAsAnEmptyShelf(){
        ServerSocket(0).use {server->
            val worker=thread {for(body in listOf("<html>blocked</html>","{}","{\"items\":{}}","{\"items\":[]}"))server.accept().use {reply(it,body)}}
            val api=AccountApi("http://127.0.0.1:${server.localPort}","test-only",OkHttpClient())
            try {
                repeat(3){assertEquals("Resposta inválida do servidor. Tente novamente.",runCatching {SocialRequests.call(api,"/v1/friends/feed")}.exceptionOrNull()?.message)}
                assertEquals(0,SocialRequests.call(api,"/v1/friends/feed").getAsJsonArray("items").size())
            }finally {api.close();worker.join(2000)}
        }
    }
    @Test fun stalledSocialReadTimesOutAndCanRetryWithoutChangingTheBackupBudget(){
        ServerSocket(0).use {server->
            val release=CountDownLatch(1)
            val worker=thread {
                server.accept().use {socket->
                    val input=socket.getInputStream().bufferedReader();while(!input.readLine().isNullOrEmpty()){}
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n{".toByteArray())
                    release.await(18,TimeUnit.SECONDS)
                }
                server.accept().use {reply(it,"{\"items\":[]}")}
            }
            val client=OkHttpClient.Builder().readTimeout(90,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
            val api=AccountApi("http://127.0.0.1:${server.localPort}","test-only",client)
            try {
                val start=System.nanoTime();val error=runCatching {SocialRequests.call(api,"/v1/friends")}.exceptionOrNull()
                assertTrue(error is java.io.InterruptedIOException)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) in 11000..16000)
                assertTrue(SocialRequests.message(error!!,"https://api.example.test").contains("api.example.test"))
                release.countDown();assertEquals(0,SocialRequests.call(api,"/v1/friends").getAsJsonArray("items").size())
                assertEquals(120000,client.callTimeoutMillis)
            }finally {release.countDown();api.close();worker.join(2000)}
        }
    }
    @Test fun cancelledQueuedReadNeverConnects(){
        ServerSocket(0).use {server->
            server.soTimeout=200
            val api=AccountApi("http://127.0.0.1:${server.localPort}","test-only",OkHttpClient());val connection=AccountConnection()
            try {
                connection.cancel()
                assertEquals("Requisição cancelada.",runCatching {SocialRequests.call(api,"/v1/clubs",connection=connection)}.exceptionOrNull()?.message)
                assertTrue(runCatching {server.accept().close()}.exceptionOrNull() is java.net.SocketTimeoutException)
            }finally {api.close()}
        }
    }
    @Test fun writesAcceptTheExistingEmptyAcknowledgementAndErrorsNeverExposeCredentials(){
        ServerSocket(0).use {server->
            val worker=thread {server.accept().use {reply(it,"{}")}}
            val api=AccountApi("http://127.0.0.1:${server.localPort}","test-only",OkHttpClient())
            try {assertTrue(SocialRequests.call(api,"/v1/clubs/x/vote","POST").entrySet().isEmpty())}finally {api.close();worker.join(2000)}
        }
        assertEquals("Não foi possível acessar api.example.test. Tente novamente.",SocialRequests.message(java.io.IOException("Bearer secret-test"),"https://api.example.test"))
        assertEquals("Sua sessão expirou.",SocialRequests.message(AccountFailure(401,com.google.gson.JsonObject(),"Sua sessão expirou."),"https://api.example.test"))
    }
}
