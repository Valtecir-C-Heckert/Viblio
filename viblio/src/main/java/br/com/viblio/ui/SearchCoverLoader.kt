package br.com.viblio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Visible covers first; completed thumbnails survive redraws, filters and app restarts. */
class SearchCoverLoader(context:Context) {
    private val directory=File(context.cacheDir,"catalog-thumbnails").apply { mkdirs() }
    private val client get() = br.com.centralcasa.books.catalog.ViblioHttp.searchThumbnails
    private val worker=Executors.newFixedThreadPool(3)
    private val handler=Handler(Looper.getMainLooper())
    private val memory=object:LruCache<String,Bitmap>(8*1024*1024) { override fun sizeOf(key:String,value:Bitmap)=value.byteCount }
    private val entries=linkedMapOf<ImageView,String>()
    private val loading=mutableSetOf<String>()
    private val failed=mutableSetOf<String>()
    private var root:View?=null
    private var observer:ViewTreeObserver?=null
    @Volatile private var closed=false
    private val scan=Runnable { loadVisible() }
    private val scrollListener=ViewTreeObserver.OnScrollChangedListener { schedule() }
    private val layoutListener=ViewTreeObserver.OnGlobalLayoutListener { schedule() }
    private val attachListener=object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view:View) { if(root===view) watch(view);schedule() }
        override fun onViewDetachedFromWindow(view:View) { if(root===view) unwatch() }
    }
    fun attach(view:View) {
        detach();root=view;failed.clear();view.addOnAttachStateChangeListener(attachListener)
        if(view.isAttachedToWindow) watch(view)
        schedule()
    }
    private fun watch(view:View) {
        unwatch();observer=view.viewTreeObserver
        observer?.addOnScrollChangedListener(scrollListener);observer?.addOnGlobalLayoutListener(layoutListener)
    }
    private fun unwatch() {
        observer?.takeIf { it.isAlive }?.let { it.removeOnScrollChangedListener(scrollListener);it.removeOnGlobalLayoutListener(layoutListener) };observer=null
    }
    fun reset() { entries.clear();failed.clear();handler.removeCallbacks(scan) }
    fun detach() { unwatch();root?.removeOnAttachStateChangeListener(attachListener);root=null;reset() }
    fun bind(view:ImageView,url:String) {
        entries.remove(view)
        if(url.isBlank()) return
        memory.get(url)?.let { view.setImageBitmap(it);return }
        entries[view]=url;schedule()
    }
    private fun schedule() { if(!closed) { handler.removeCallbacks(scan);handler.post(scan) } }
    private fun loadVisible() {
        if(closed || root?.isAttachedToWindow!=true) return
        val rect=Rect()
        val visible=entries.entries.filter { it.key.getGlobalVisibleRect(rect) }.map { it.key to it.value }
        visible.forEach { (view,url)->memory.get(url)?.let { view.setImageBitmap(it);entries.remove(view) } }
        for((_,url) in visible) {
            if(loading.size>=3) break
            if(url in loading || url in failed || memory.get(url)!=null) continue
            loading.add(url)
            worker.execute {
                val bitmap=runCatching { thumbnail(url) }.getOrNull()
                handler.post {
                    loading.remove(url)
                    if(closed) return@post
                    if(bitmap!=null) memory.put(url,bitmap) else failed.add(url)
                    schedule()
                }
            }
        }
    }
    private fun thumbnail(url:String):Bitmap? {
        val uri=URI(url);require(uri.scheme in listOf("http","https") && uri.userInfo==null && !uri.host.isNullOrBlank())
        val name=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val file=File(directory,"$name.jpg")
        if(file.isFile && System.currentTimeMillis()-file.lastModified()<7*24*60*60*1000L) {
            BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis());return it }
        }
        val decoded=client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful)
            val body=response.body ?: return null;require(body.contentLength()<=12*1024*1024)
            val bytes=body.byteStream().use { input->
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=input.read(buffer);if(n<0) break;require(out.size()+n<=12*1024*1024);out.write(buffer,0,n) };out.toByteArray()
            }
            val dimensions=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,dimensions)
            require(dimensions.outWidth>0 && dimensions.outHeight>0 && dimensions.outWidth.toLong()*dimensions.outHeight<=20_000_000)
            val opts=BitmapFactory.Options().apply { inSampleSize=1 };while(dimensions.outWidth/opts.inSampleSize>256 || dimensions.outHeight/opts.inSampleSize>384) opts.inSampleSize*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        } ?: return null
        runCatching {
            // The decoded image remains usable even if Android cannot write its disposable cache.
            synchronized(directoryLock) {
                val temporary=File.createTempFile(name,".tmp",directory)
                try { temporary.outputStream().use { check(decoded.compress(Bitmap.CompressFormat.JPEG,92,it)) };check(temporary.renameTo(file)) }
                finally { temporary.delete() }
                val cached=directory.listFiles()?.filter { it.extension=="jpg" }?.sortedByDescending { it.lastModified() }.orEmpty()
                var bytes=0L
                cached.forEachIndexed { index,item->bytes+=item.length();if(index>=80 || bytes>12*1024*1024) item.delete() }
            }
        }
        return decoded
    }
    fun close() { closed=true;detach();handler.removeCallbacksAndMessages(null);worker.shutdownNow();memory.evictAll() }
    private companion object { val directoryLock=Any() }
}
