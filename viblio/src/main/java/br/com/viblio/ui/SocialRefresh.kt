package br.com.viblio.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.*
import br.com.centralcasa.ui.DisplayUi

/** Keep the refresh button's place while its request is in flight. No loading paragraph. */
internal object SocialRefresh {
    fun button(activity:Activity,ui:DisplayUi,parent:LinearLayout,loading:Boolean,label:String,onClick:()->Unit):ImageView {
        val slot=FrameLayout(activity)
        parent.addView(slot,LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)))
        val temporary=ui.row()
        val button=ui.icon(temporary,"refresh",label,44,onClick=onClick)
        temporary.removeView(button)
        slot.addView(button,FrameLayout.LayoutParams(-1,-1))
        button.isEnabled=!loading;button.background=ui.outlined(ui.surface,ui.lineColor,0)
        button.setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10))
        if(loading){
            button.setImageDrawable(null)
            val progress=ProgressBar(activity,null,android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate=true;indeterminateTintList=ColorStateList.valueOf(ui.accent)
                contentDescription="Atualizando";tag="social-loading"
            }
            slot.addView(progress,FrameLayout.LayoutParams(ui.dp(22),ui.dp(22),Gravity.CENTER))
        }
        return button
    }
}
