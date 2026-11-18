package br.com.centralcasa.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import br.com.centralcasa.books.BookImages
import java.util.concurrent.Executors

/** Only visible shelf covers are decoded, outside the UI thread. Bindings survive pinch safely. */
class ShelfThumbnailLoader(private val images:BookImages) {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val cache=object:LinkedHashMap<String,Bitmap>(48,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Bitmap>?)=size>48
    }
    private val entries=linkedMapOf<ImageView,String>()
    private val loading=mutableSetOf<ImageView>()
    private var observer:ViewTreeObserver?=null
    private var root:View?=null;private var generation=0;private var closed=false
    private val listener=ViewTreeObserver.OnScrollChangedListener { schedule() }
    private val layout=View.OnLayoutChangeListener { _,_,_,_,_,_,_,_,_->schedule() }
    private val scan=Runnable { loadVisible() }
    fun attach(view:View) {
        unwatch();root?.removeOnLayoutChangeListener(layout)
        generation++;entries.clear();loading.clear();root=view
        view.addOnLayoutChangeListener(layout)
        if(view.isAttachedToWindow) watch(view)
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) { if(root===v) watch(v);schedule() }
            override fun onViewDetachedFromWindow(v:View) { if(root===v) { unwatch();generation++;entries.clear();loading.clear();handler.removeCallbacks(scan) } }
        })
    }
    private fun watch(view:View) { unwatch();observer=view.viewTreeObserver;observer?.addOnScrollChangedListener(listener) }
    private fun unwatch() { observer?.takeIf { it.isAlive }?.removeOnScrollChangedListener(listener);observer=null }
    fun reset() { generation++;entries.clear();loading.clear() }
    fun bind(view:ImageView,name:String) {
        if(name.isBlank()) return
        cache[name]?.let { view.setImageBitmap(it);return }
        entries[view]=name;schedule()
    }
    private fun schedule() { handler.removeCallbacks(scan);handler.post(scan) }
    private fun loadVisible() {
        if(closed || root?.isAttachedToWindow!=true) return
        val rect=Rect();val current=generation
        entries.toList().forEach { (view,name)->
            if(view in loading || !view.getGlobalVisibleRect(rect)) return@forEach
            cache[name]?.let { view.setImageBitmap(it);entries.remove(view);return@forEach }
            loading.add(view)
            worker.execute {
                val bitmap=runCatching { images.thumbnail(name) }.getOrNull()
                handler.post {
                    if(closed) return@post
                    if(bitmap!=null) cache[name]=bitmap
                    if(current==generation && entries[view]==name) { loading.remove(view);entries.remove(view);if(bitmap!=null && view.isAttachedToWindow) view.setImageBitmap(bitmap) }
                }
            }
        }
    }
    fun close() {
        closed=true;generation++;handler.removeCallbacksAndMessages(null);worker.shutdownNow();entries.clear();loading.clear();cache.clear()
        unwatch();root?.removeOnLayoutChangeListener(layout);root=null
    }
}
