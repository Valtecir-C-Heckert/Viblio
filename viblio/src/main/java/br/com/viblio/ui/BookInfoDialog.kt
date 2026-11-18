package br.com.viblio.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import br.com.centralcasa.ui.DisplayUi

/** A consultation view only: no repository, timer, credentials or implicit library writes. */
object BookInfoDialog {
    data class Info(val title:String,val author:String="",val publisher:String="",val genre:String="",val pages:Int?=null,val isbn:String="",val page:Int?=null,val owner:String="")
    fun show(activity:Activity,ui:DisplayUi,info:Info,bind:(ImageView,View)->Unit,onClose:()->Unit,add:(()->Unit)?=null):AlertDialog {
        val body=ui.column().apply {tag="book-info";setPadding(ui.dp(20),ui.dp(16),ui.dp(20),ui.dp(20))}
        if(info.owner.isNotBlank())ui.label(body,"@${info.owner.take(32)}",13,ui.accent).gravity=Gravity.CENTER_HORIZONTAL
        val image=ImageView(activity).apply {setImageDrawable(ui.symbol("books",ui.muted,48));scaleType=ImageView.ScaleType.FIT_CENTER;background=ui.shape(ui.activeSurface,0);contentDescription="Capa de ${info.title.take(500)}"}
        body.addView(image,LinearLayout.LayoutParams(ui.dp(140),ui.dp(210)).apply {gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=ui.dp(16)})
        ui.label(body,info.title.take(500),26).apply {typeface=Typeface.create("serif",Typeface.NORMAL);gravity=Gravity.CENTER_HORIZONTAL}
        if(info.author.isNotBlank())ui.label(body,info.author.take(200),14,ui.muted).gravity=Gravity.CENTER_HORIZONTAL
        val facts=ui.panel(body,12)
        if(info.page!=null){
            val total=info.pages?.takeIf {it>0};val page=info.page.coerceAtLeast(0)
            val label=if(total!=null)"$page / $total páginas · ${(page.toLong()*100/total).coerceIn(0,100)}%" else "$page páginas"
            ui.label(facts,label,18,ui.accent,true).tag="book-info-progress"
            if(total!=null)ui.progress(facts,(page.toLong()*100/total).coerceIn(0,100).toInt())
        }else info.pages?.let {ui.label(facts,"$it páginas",14)}
        if(info.publisher.isNotBlank())ui.label(facts,info.publisher.take(200),13,ui.muted)
        if(info.genre.isNotBlank())ui.label(facts,info.genre.take(200),13,ui.muted)
        if(info.isbn.isNotBlank())ui.label(facts,"ISBN ${info.isbn.take(32)}",13,ui.muted)
        val builder=AlertDialog.Builder(activity).setView(ScrollView(activity).apply {addView(body)}).setNegativeButton("Fechar",null)
        if(add!=null)builder.setPositiveButton("Adicionar à estante"){_,_->add()}
        val dialog=builder.create();dialog.setOnDismissListener {onClose()};dialog.show();dialog.window?.setBackgroundDrawable(ui.shape(ui.surface,0));bind(image,body)
        return dialog
    }
}
