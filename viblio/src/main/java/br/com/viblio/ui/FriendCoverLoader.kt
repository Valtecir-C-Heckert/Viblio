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

/** Private thumbnails: visible first + 1-screen lookahead, up to 6 concurrent jobs, RAM only (cleared on logout/close). */
class FriendCoverLoader(private val fetch:(String)->ByteArray) {
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newFixedThreadPool(MAX_CONCURRENT)
    private val memory=object:LruCache<String,Bitmap>(16*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.byteCount}
    private val colors=mutableMapOf<String,Int?>()
    private val colorCallbacks=mutableMapOf<String,MutableList<(Int?)->Unit>>()
    private val bitmapCallbacks=mutableMapOf<String,MutableList<(Bitmap?)->Unit>>()
    private val eagerColors=mutableSetOf<String>()
    private val entries=linkedMapOf<ImageView,String>()
    private val loading=mutableSetOf<String>()
    private val failed=mutableSetOf<String>()
    private val retries=mutableMapOf<String,Int>()
    private var root:View?=null;private var observer:ViewTreeObserver?=null
    @Volatile private var closed=false
    private val listener=ViewTreeObserver.OnScrollChangedListener {schedule()}
    private val layout=View.OnLayoutChangeListener {_,_,_,_,_,_,_,_,_->schedule()}
    private val scan=Runnable {load()}

    fun attach(view:View){
        if(closed)return
        observer?.takeIf {it.isAlive}?.removeOnScrollChangedListener(listener);root?.removeOnLayoutChangeListener(layout)
        root=view
        // Keep in-flight jobs & RAM cache; prune only detached ImageViews from previous layouts.
        entries.entries.removeAll { !it.key.isAttachedToWindow }
        view.addOnLayoutChangeListener(layout)
        fun watch(){if(!closed && root===view){observer=view.viewTreeObserver;observer?.takeIf {it.isAlive}?.addOnScrollChangedListener(listener);schedule()}}
        if(view.isAttachedToWindow)watch() else view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View){v.removeOnAttachStateChangeListener(this);watch()}
            override fun onViewDetachedFromWindow(v:View){}
        })
    }

    /** Detach view observers when switching tabs while keeping the session RAM cache warm. */
    fun detach(){
        observer?.takeIf {it.isAlive}?.removeOnScrollChangedListener(listener)
        observer=null
        root?.removeOnLayoutChangeListener(layout)
        root=null
        entries.clear()
        colorCallbacks.clear()
        bitmapCallbacks.clear()
        eagerColors.clear()
        failed.clear()
        retries.clear()
        handler.removeCallbacks(scan)
    }

    fun bind(view:ImageView,path:String){
        if(closed || path.isBlank())return
        memory.get(path)?.takeIf {!it.isRecycled}?.let {view.setImageBitmap(it);return}
        entries[view]=path
        schedule()
    }

    /** Reuses cached RAM bitmaps when available; only selected themes are eager. */
    fun color(path:String,eager:Boolean=false,done:(Int?)->Unit){
        if(closed || path.isBlank())return
        if(colors.containsKey(path)){handler.post {if(!closed)done(colors[path])};return}
        val cached=memory.get(path)?.takeIf {!it.isRecycled}
        if(cached!=null){
            colorCallbacks.getOrPut(path){mutableListOf()}.add(done)
            worker.execute {
                if(closed)return@execute
                val c=runCatching {sampleDominantColor(cached)}.getOrNull()
                handler.post {
                    if(closed)return@post
                    colors[path]=c
                    colorCallbacks.remove(path)?.forEach {it(c)}
                }
            }
            return
        }
        colorCallbacks.getOrPut(path){mutableListOf()}.add(done)
        if(eager)eagerColors.add(path)
        schedule()
    }

    /** Reuses the decoded RAM thumbnail for blurred club chat backdrops without extra fetches. */
    fun bitmap(path:String,eager:Boolean=false,done:(Bitmap?)->Unit){
        if(closed || path.isBlank())return
        memory.get(path)?.takeIf {!it.isRecycled}?.let {cached->handler.post {if(!closed && !cached.isRecycled)done(cached)};return}
        if(path in failed){handler.post {if(!closed)done(null)};return}
        bitmapCallbacks.getOrPut(path){mutableListOf()}.add(done)
        if(eager)eagerColors.add(path)
        schedule()
    }

    private fun schedule(){if(!closed){handler.removeCallbacks(scan);handler.post(scan)}}

    private fun load(){
        if(closed)return
        val host=root
        val screenH=host?.resources?.displayMetrics?.heightPixels ?: 1200
        val rect=Rect()
        val visiblePaths=mutableListOf<String>()
        val lookaheadPaths=mutableListOf<String>()
        val toRemove=mutableListOf<ImageView>()
        for((view,path) in entries.toList()){
            val cached=memory.get(path)?.takeIf {!it.isRecycled}
            if(cached!=null){
                view.setImageBitmap(cached)
                toRemove.add(view)
                continue
            }
            if(view.getGlobalVisibleRect(rect)){
                visiblePaths.add(path)
            } else if(view.isAttachedToWindow){
                val loc=IntArray(2)
                view.getLocationOnScreen(loc)
                if(loc[1] in -screenH/2..(screenH*2)) lookaheadPaths.add(path)
            }
        }
        toRemove.forEach {entries.remove(it)}
        val queue=(eagerColors.toList()+visiblePaths+lookaheadPaths).distinct()
        for(path in queue){
            if(loading.size>=MAX_CONCURRENT)break
            if(path in loading || path in failed || memory.get(path)?.takeIf {!it.isRecycled}!=null)continue
            val needColor=colorCallbacks.containsKey(path) || path in eagerColors
            loading.add(path)
            worker.execute {
                if(closed)return@execute
                var color:Int?=null
                val bitmap=runCatching {
                    val bytes=fetch(path)
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(validDimensions(bounds.outWidth,bounds.outHeight) && bounds.outMimeType=="image/jpeg")
                    requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)).also {bmp->
                        if(needColor) color=sampleDominantColor(bmp)
                    }
                }.getOrNull()
                handler.post {
                    loading.remove(path)
                    if(closed){bitmap?.recycle();return@post}
                    if(bitmap==null){
                        val count=(retries[path] ?: 0)+1
                        retries[path]=count
                        if(count<=MAX_RETRIES){
                            handler.postDelayed({if(!closed)schedule()},RETRY_DELAY_MS)
                        } else {
                            failed.add(path)
                            eagerColors.remove(path)
                            colors[path]=null
                            colorCallbacks.remove(path)?.forEach {it(null)}
                            bitmapCallbacks.remove(path)?.forEach {it(null)}
                        }
                    } else {
                        retries.remove(path)
                        memory.put(path,bitmap)
                        eagerColors.remove(path)
                        if(needColor){
                            colors[path]=color
                            colorCallbacks.remove(path)?.forEach {it(color)}
                        } else if(colorCallbacks.containsKey(path)){
                            val pending=colorCallbacks.remove(path).orEmpty()
                            worker.execute {
                                if(closed)return@execute
                                val c=runCatching {sampleDominantColor(bitmap)}.getOrNull()
                                handler.post {if(!closed){colors[path]=c;pending.forEach {it(c)}}}
                            }
                        }
                        bitmapCallbacks.remove(path)?.forEach {it(bitmap)}
                    }
                    schedule()
                }
            }
        }
    }

    fun close(){
        closed=true
        observer?.takeIf {it.isAlive}?.removeOnScrollChangedListener(listener)
        root?.removeOnLayoutChangeListener(layout)
        root=null;observer=null
        entries.clear();colors.clear();colorCallbacks.clear();bitmapCallbacks.clear();eagerColors.clear();failed.clear();retries.clear()
        memory.evictAll()
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val MAX_WIDTH=480
        const val MAX_HEIGHT=720
        const val MAX_CONCURRENT=6
        private const val MAX_RETRIES=1
        private const val RETRY_DELAY_MS=350L

        fun validDimensions(width:Int,height:Int):Boolean = width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT

        /** Fast subsampled dominant color (~3000 pixels instead of 194k) for identical tint at ~60x lower CPU cost. */
        internal fun sampleDominantColor(bitmap:Bitmap):Int? {
            if(bitmap.isRecycled || bitmap.width<=0 || bitmap.height<=0)return null
            val step=(minOf(bitmap.width,bitmap.height)/36).coerceAtLeast(1)
            val cols=(bitmap.width+step-1)/step
            val rows=(bitmap.height+step-1)/step
            val sampled=IntArray(cols*rows)
            var idx=0
            var y=0
            while(y<bitmap.height){
                var x=0
                while(x<bitmap.width){
                    if(idx<sampled.size) sampled[idx++]=bitmap.getPixel(x,y)
                    x+=step
                }
                y+=step
            }
            return br.com.centralcasa.books.CoverColors.dominant(if(idx==sampled.size) sampled else sampled.copyOf(idx))
        }
    }
}
