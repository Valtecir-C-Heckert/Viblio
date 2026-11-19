package br.com.viblio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.concurrent.Executors

/** Private thumbnails: visible first, at most two jobs, RAM only, cleared on leaving Friends. */
class FriendCoverLoader(private val fetch:(String)->ByteArray) {
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newFixedThreadPool(2)
    private val memory=object:LruCache<String,Bitmap>(4*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.byteCount}
    private val colors=mutableMapOf<String,Int?>()
    private val colorCallbacks=mutableMapOf<String,MutableList<(Int?)->Unit>>()
    private val bitmapCallbacks=mutableMapOf<String,MutableList<(Bitmap?)->Unit>>()
    private val eagerColors=mutableSetOf<String>()
    private val entries=linkedMapOf<ImageView,String>()
    private val loading=mutableSetOf<String>();private val failed=mutableSetOf<String>()
    private var root:View?=null;private var observer:ViewTreeObserver?=null
    @Volatile private var closed=false
    private val listener=ViewTreeObserver.OnScrollChangedListener {schedule()}
    private val layout=View.OnLayoutChangeListener {_,_,_,_,_,_,_,_,_->schedule()}
    private val scan=Runnable {load()}
    fun attach(view:View){
        if(closed)return
        observer?.takeIf {it.isAlive}?.removeOnScrollChangedListener(listener);root?.removeOnLayoutChangeListener(layout)
        root=view;entries.clear();failed.clear();view.addOnLayoutChangeListener(layout)
        fun watch(){if(!closed && root===view){observer=view.viewTreeObserver;observer?.takeIf {it.isAlive}?.addOnScrollChangedListener(listener);schedule()}}
        if(view.isAttachedToWindow)watch() else view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View){v.removeOnAttachStateChangeListener(this);watch()}
            override fun onViewDetachedFromWindow(v:View){}
        })
    }
    fun bind(view:ImageView,path:String){if(closed || path.isBlank())return;memory.get(path)?.let {view.setImageBitmap(it);return};entries[view]=path;schedule()}
    /** Reuses visible thumbnails; only selected book themes are eager. */
    fun color(path:String,eager:Boolean=false,done:(Int?)->Unit){
        if(closed || path.isBlank())return
        if(colors.containsKey(path)){handler.post {if(!closed)done(colors[path])};return}
        colorCallbacks.getOrPut(path){mutableListOf()}.add(done)
        if(eager)eagerColors.add(path)
        schedule()
    }
    /** Reuses the decoded RAM thumbnail for blurred club chat backdrops without extra fetches. */
    fun bitmap(path:String,eager:Boolean=false,done:(Bitmap?)->Unit){
        if(closed || path.isBlank())return
        memory.get(path)?.let {cached->handler.post {if(!closed && !cached.isRecycled)done(cached)};return}
        if(path in failed){handler.post {if(!closed)done(null)};return}
        bitmapCallbacks.getOrPut(path){mutableListOf()}.add(done)
        if(eager)eagerColors.add(path)
        schedule()
    }
    private fun schedule(){if(!closed){handler.removeCallbacks(scan);handler.post(scan)}}
    private fun load(){
        if(closed || root?.isAttachedToWindow!=true)return
        val rect=Rect();val visible=entries.toList().filter {it.first.getGlobalVisibleRect(rect)}
        visible.forEach {(view,path)->memory.get(path)?.let {view.setImageBitmap(it);entries.remove(view)}}
        for(path in (eagerColors.toList()+visible.map {it.second}).distinct()){
            if(loading.size>=2)break
            if(path in loading || path in failed || memory.get(path)!=null)continue
            loading.add(path)
            worker.execute {
                if(closed)return@execute
                var color:Int?=null
                val bitmap=runCatching {
                    val bytes=fetch(path);val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth in 1..160 && bounds.outHeight in 1..240 && bounds.outMimeType=="image/jpeg")
                    requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)).also {bitmap->
                        val pixels=IntArray(bitmap.width*bitmap.height)
                        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                        color=br.com.centralcasa.books.CoverColors.dominant(pixels)
                    }
                }.getOrNull()
                handler.post {loading.remove(path);if(closed){bitmap?.recycle();return@post};if(bitmap==null)failed.add(path) else memory.put(path,bitmap);eagerColors.remove(path);colors[path]=color;colorCallbacks.remove(path)?.forEach {it(color)};bitmapCallbacks.remove(path)?.forEach {it(bitmap)};schedule()}
            }
        }
    }
    fun close(){closed=true;observer?.takeIf {it.isAlive}?.removeOnScrollChangedListener(listener);root?.removeOnLayoutChangeListener(layout);root=null;entries.clear();colors.clear();colorCallbacks.clear();bitmapCallbacks.clear();eagerColors.clear();memory.evictAll();worker.shutdownNow();handler.removeCallbacksAndMessages(null)}
}
