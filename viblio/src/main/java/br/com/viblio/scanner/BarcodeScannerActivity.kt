@file:Suppress("DEPRECATION")
package br.com.viblio.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import br.com.centralcasa.ui.DisplayUi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Internal ISBN reader. Camera lifetime is strictly limited to this foreground screen. */
class BarcodeScannerActivity:Activity(),SurfaceHolder.Callback {
    private lateinit var ui:DisplayUi
    private lateinit var preview:SurfaceView
    private lateinit var stage:FrameLayout
    private lateinit var status:TextView
    private lateinit var light:TextView
    private var camera:Camera?=null
    private var resumed=false
    private var ready=false
    private var epoch=0
    private var lastFrame=0L
    private var torch=false
    private val busy=AtomicBoolean(false)
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val decoder=BarcodeDecoder()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);ui=DisplayUi(this)
        val root=ui.column().apply {setBackgroundColor(0xff0d1713.toInt());setPadding(ui.dp(16),ui.dp(20),ui.dp(16),ui.dp(20))}
        val header=ui.row();root.addView(header)
        ui.icon(header,"back","Voltar à busca",48){finish()}
        ui.label(header,"Ler ISBN",25).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        light=ui.action(header,"Lanterna"){toggleTorch()}.apply {layoutParams=LinearLayout.LayoutParams(-2,ui.dp(48));visibility=View.GONE}
        stage=FrameLayout(this).apply {setBackgroundColor(android.graphics.Color.BLACK)}
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        preview=SurfaceView(this);stage.addView(preview,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));preview.holder.addCallback(this)
        val guide=View(this).apply {background=ui.outlined(android.graphics.Color.TRANSPARENT,0xff9bd7bf.toInt());importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        stage.addView(guide,FrameLayout.LayoutParams(-1,ui.dp(130),Gravity.CENTER).apply {leftMargin=ui.dp(22);rightMargin=ui.dp(22)})
        status=ui.label(root,"Aponte para o código de barras ISBN do livro.",14,ui.muted).apply {setPadding(0,ui.dp(16),0,ui.dp(8));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        ui.action(root,"Voltar e digitar ISBN"){finish()}
        setContentView(root)
        root.setOnApplyWindowInsetsListener {v,i->v.setPadding(ui.dp(16)+i.systemWindowInsetLeft,ui.dp(12)+i.systemWindowInsetTop,ui.dp(16)+i.systemWindowInsetRight,ui.dp(12)+i.systemWindowInsetBottom);i.consumeSystemWindowInsets()}
        root.requestApplyInsets()
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.CAMERA),1)
    }
    override fun onResume(){super.onResume();resumed=true;open()}
    override fun onPause(){resumed=false;release();super.onPause()}
    override fun onDestroy(){release();worker.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy()}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==1){if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED)open() else status.text="Permita a câmera para ler o código, ou volte e digite o ISBN."}}
    override fun surfaceCreated(holder:SurfaceHolder){ready=true;open()}
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){}
    override fun surfaceDestroyed(holder:SurfaceHolder){ready=false;release()}
    private fun open(){
        if(!resumed || !ready || camera!=null || Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return
        try {
            val id=(0 until Camera.getNumberOfCameras()).firstOrNull {Camera.CameraInfo().let {info->Camera.getCameraInfo(it,info);info.facing==Camera.CameraInfo.CAMERA_FACING_BACK}} ?: 0
            val info=Camera.CameraInfo().also {Camera.getCameraInfo(id,it)}
            val device=Camera.open(id);camera=device
            val params=device.parameters
            val size=params.supportedPreviewSizes.filter {it.width.toLong()*it.height<=1280L*960}.minByOrNull {kotlin.math.abs(it.width*it.height-640*480)} ?: error("Preview unavailable")
            params.setPreviewSize(size.width,size.height);params.previewFormat=android.graphics.ImageFormat.NV21
            if(params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)==true)params.focusMode=Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            device.parameters=params
            val degrees=when(windowManager.defaultDisplay.rotation){Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}
            val orientation=if(info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT)(360-(info.orientation+degrees)%360)%360 else (info.orientation-degrees+360)%360
            device.setDisplayOrientation(orientation)
            stage.post {
                if(camera===device && stage.width>0 && stage.height>0){
                    val w=if(orientation%180==0)size.width else size.height;val h=if(orientation%180==0)size.height else size.width
                    val scale=minOf(stage.width.toFloat()/w,stage.height.toFloat()/h)
                    preview.layoutParams=FrameLayout.LayoutParams((w*scale).toInt(),(h*scale).toInt(),Gravity.CENTER)
                }
            }
            light.visibility=if(params.supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH)==true)View.VISIBLE else View.GONE
            device.setPreviewDisplay(preview.holder)
            val generation=++epoch
            device.setPreviewCallbackWithBuffer {data,owner->
                val now=android.os.SystemClock.elapsedRealtime()
                if(data!=null && resumed && camera===owner && now-lastFrame>=250 && busy.compareAndSet(false,true)){
                    lastFrame=now;val frame=data.copyOf(size.width*size.height)
                    worker.execute {
                        val isbn=runCatching {decoder.decode(frame,size.width,size.height)}.getOrNull()
                        busy.set(false)
                        if(isbn!=null)handler.post {if(resumed && generation==epoch && !isFinishing){setResult(RESULT_OK,Intent().putExtra("isbn",isbn));finish()}}
                    }
                }
                if(camera===owner)runCatching {owner.addCallbackBuffer(data)}
            }
            repeat(2){device.addCallbackBuffer(ByteArray(size.width*size.height*3/2))}
            device.startPreview()
            if(params.focusMode!=Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)focus()
        }catch(_:Exception){release();status.text="Não foi possível abrir a câmera. Volte para digitar o ISBN ou tente novamente."}
    }
    private val autofocus=Runnable {focus()}
    private fun focus(){val device=camera ?: return;runCatching {device.autoFocus {_,_->if(resumed && camera===device)handler.postDelayed(autofocus,1800)}}}
    private fun toggleTorch(){val device=camera ?: return;runCatching {val p=device.parameters;torch=!torch;p.flashMode=if(torch)Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF;device.parameters=p;light.text=if(torch)"Apagar" else "Lanterna"}}
    private fun release(){epoch++;handler.removeCallbacks(autofocus);val device=camera;camera=null;torch=false;runCatching {device?.setPreviewCallbackWithBuffer(null);device?.stopPreview()};runCatching {device?.release()}}
}
