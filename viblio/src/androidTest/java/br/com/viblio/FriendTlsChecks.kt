package br.com.viblio

import android.app.Instrumentation
import br.com.viblio.account.AccountApi
import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.*

/** Local-only TLS fixture; its private CA is trusted by this test client alone. */
object FriendTlsChecks {
    fun run(test:Instrumentation){
        val store=KeyStore.getInstance("PKCS12").apply {test.context.assets.open("localhost-test.p12").use {load(it,"fixture-only".toCharArray())}}
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(store,"fixture-only".toCharArray())}
        val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(store)}
        val context=SSLContext.getInstance("TLS").apply {init(keys.keyManagers,trust.trustManagers,null)}
        val server=context.serverSocketFactory.createServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val finished=java.util.concurrent.CountDownLatch(1)
        val thread=Thread {
            try {server.accept().use {socket->
                socket.soTimeout=10000
                val reader=socket.getInputStream().bufferedReader()
                while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break}
                val body="{}".toByteArray()
                socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\n".toByteArray());write(body);flush()}
                while(socket.getInputStream().read()!=-1){}
            }}catch(_:java.io.IOException){}finally {finished.countDown()}
        }.apply {isDaemon=true;start()}
        val client=OkHttpClient.Builder().sslSocketFactory(context.socketFactory,trust.trustManagers.filterIsInstance<X509TrustManager>().single()).build()
        val api=AccountApi("https://127.0.0.1:${server.localPort}","",client)
        try {
            api.call("/v1/auth/info")
            check(client.connectionPool.idleConnectionCount()==1)
            test.checkedMain {api.close();api.close()}
            check(finished.await(10,java.util.concurrent.TimeUnit.SECONDS)) {"TLS connection was not released"}
        }finally {server.close();thread.join(1000)}
    }
}
