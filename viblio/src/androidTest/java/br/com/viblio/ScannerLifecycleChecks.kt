package br.com.viblio

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import br.com.viblio.scanner.BarcodeScannerActivity

object ScannerLifecycleChecks {
    fun run(test:Instrumentation){
        val context=test.targetContext
        val info=context.packageManager.getActivityInfo(ComponentName(context,BarcodeScannerActivity::class.java),0)
        check(!info.exported)
        // Emulator-only grant: exercise preview opening/closing without automating the system dialog.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("pm grant br.com.viblio android.permission.CAMERA")).use {it.readBytes()}
        check(context.checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
        val activity=test.startActivitySync(Intent(context,BarcodeScannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        try {
            test.waitForIdleSync()
            test.checkedMain {
                check(views(activity.window.decorView).filterIsInstance<TextView>().any {it.text.toString()=="Ler ISBN"})
                views(activity.window.decorView).filterIsInstance<TextView>().single {it.text.toString()=="Voltar e digitar ISBN"}.performClick()
                check(activity.isFinishing)
            }
        }finally {test.checkedMain {activity.finish()}}
    }
}
