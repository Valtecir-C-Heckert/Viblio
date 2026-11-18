package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.LinearLayout
import br.com.viblio.ui.FriendCoverLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Leaving during thumbnail/color work must neither call a detached screen nor recycle its UI. */
object FriendCoverLifecycleChecks {
    fun run(test:Instrumentation,activity:Activity){
        val started=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val callback=AtomicBoolean(false)
        val bytes=java.io.ByteArrayOutputStream().also {out->
            Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).let {bitmap->bitmap.eraseColor(android.graphics.Color.RED);bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);bitmap.recycle()}
        }.toByteArray()
        val loader=FriendCoverLoader {
            started.countDown()
            // Simulate work completing despite cancellation, as a decoder may do.
            try {release.await(5,TimeUnit.SECONDS)}catch(_:InterruptedException){}
            finished.countDown();bytes
        }
        lateinit var root:LinearLayout
        try {
            test.checkedMain {
                root=LinearLayout(activity);activity.setContentView(root)
                val image=ImageView(activity);root.addView(image,LinearLayout.LayoutParams(100,150))
                loader.attach(root);loader.bind(image,"fixture");loader.color("fixture",eager=true){callback.set(true)}
            }
            check(started.await(5,TimeUnit.SECONDS))
            test.checkedMain {loader.close();loader.close();root.removeAllViews();loader.attach(root);loader.bind(ImageView(activity),"after-close");loader.color("after-close",eager=true){callback.set(true)}}
            release.countDown();check(finished.await(5,TimeUnit.SECONDS));test.waitForIdleSync()
            test.checkedMain {check(!callback.get())}
        }finally {release.countDown();test.checkedMain {loader.close()}}
    }
}
