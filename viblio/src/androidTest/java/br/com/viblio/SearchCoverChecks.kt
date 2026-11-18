package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.catalog.*
import br.com.centralcasa.ui.DisplayUi
import br.com.viblio.ui.SearchPanel
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import java.security.MessageDigest

/** Downloads a real PNG over loopback and verifies that a result replaces its placeholder. */
object SearchCoverChecks {
    fun run(test:Instrumentation,activity:Activity) {
        val bitmap=Bitmap.createBitmap(512,768,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val output=java.io.ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));bitmap.recycle()
        val bytes=output.toByteArray()
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).apply { soTimeout=15000 }
        val executor=Executors.newSingleThreadExecutor()
        val request=executor.submit {
            server.accept().use { connection ->
                connection.soTimeout=10000
                val reader=connection.getInputStream().bufferedReader()
                check(reader.readLine().startsWith("GET /cover.png "))
                val headers=mutableListOf<String>()
                while(true) { val line=reader.readLine() ?: break;if(line.isEmpty()) break;headers+=line }
                check(headers.none { it.startsWith("Authorization:",true) })
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()
                }
            }
        }
        val row=CatalogBook("1","fixture","Capa de teste",coverUrl="http://127.0.0.1:${server.localPort}/cover.png")
        val filename=MessageDigest.getInstance("SHA-256").digest(row.coverUrl.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val fixtureFile=File(File(activity.cacheDir,"catalog-thumbnails"),"$filename.jpg");fixtureFile.delete()
        val gateway=object:BookCatalog {
            override val configured=true
            override fun configure() {}
            override fun saved(book:br.com.centralcasa.books.LibraryBook) {}
            override fun loadCover(book:CatalogBook,done:(Result<String>)->Unit) { error("Thumbnail must not save a library cover") }
            override fun search(title:String,author:String,isbn:String,done:(Result<List<CatalogBook>>)->Unit) { done(Result.success(listOf(row))) }
        }
        var panel:SearchPanel?=null;lateinit var parent:LinearLayout
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        try {
            test.checkedMain {
                parent=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };activity.setContentView(parent)
                panel=SearchPanel(activity,DisplayUi(activity),gateway) {}
                panel!!.render(parent);views(parent).filterIsInstance<EditText>().single().setText("Capa")
            }
            request.get(20,TimeUnit.SECONDS)
            var loaded=false
            repeat(40) {
                if(!loaded) {
                    Thread.sleep(100)
                    test.checkedMain {
                        val image=views(parent).filterIsInstance<ImageView>().firstOrNull { it.contentDescription=="Capa de Capa de teste" }
                        val decoded=(image?.drawable as? BitmapDrawable)?.bitmap
                        loaded=decoded!=null && decoded.width==256 && decoded.height==384 && decoded.getPixel(0,0)==Color.MAGENTA
                    }
                }
            }
            check(loaded) { "Search thumbnail was downloaded but did not replace the placeholder" }
            test.checkedMain {
                // Switching filters reuses the decoded bitmap; the fixture accepts only one request.
                views(parent).filterIsInstance<TextView>().first { it.text=="Livros" }.performClick()
                val image=views(parent).filterIsInstance<ImageView>().first { it.contentDescription=="Capa de Capa de teste" }
                check((image.drawable as BitmapDrawable).bitmap.getPixel(0,0)==Color.MAGENTA)
            }
            check(fixtureFile.isFile)
            server.close() // A new loader must reuse the disk cache with the source offline.
            test.checkedMain {
                panel!!.close()
                parent=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };activity.setContentView(parent)
                panel=SearchPanel(activity,DisplayUi(activity),gateway) {}
                panel!!.render(parent);views(parent).filterIsInstance<EditText>().single().setText("Capa")
            }
            var diskLoaded=false
            repeat(40) {
                if(!diskLoaded) {
                    Thread.sleep(100)
                    test.checkedMain {
                        val image=views(parent).filterIsInstance<ImageView>().firstOrNull { it.contentDescription=="Capa de Capa de teste" }
                        val decoded=(image?.drawable as? BitmapDrawable)?.bitmap
                        val color=decoded?.getPixel(0,0) ?: 0
                        diskLoaded=decoded!=null && decoded.width==256 && decoded.height==384 && Color.red(color)>240 && Color.blue(color)>240 && Color.green(color)<10
                    }
                }
            }
            check(diskLoaded) { "Search thumbnail must survive a new loader while the source is offline" }
        } finally {
            test.checkedMain { panel?.close() }
            server.close();executor.shutdownNow();fixtureFile.delete()
        }
    }
}
