package br.com.viblio

import android.app.Instrumentation

/** Propagate assertions to the test runner so fixture cleanup still executes. */
fun Instrumentation.checkedMain(action:()->Unit) {
    var failure:Throwable?=null
    runOnMainSync { try { action() } catch(error:Throwable) { failure=error } }
    failure?.let { throw it }
}

/** Emulator-only inspection: assert Conta is the last real menu item, then activate it. */
internal fun openAccountMenu(test:Instrumentation,activity:android.app.Activity){
    fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
    fun all()=views(activity.window.decorView)
    if(all().filterIsInstance<android.widget.TextView>().any {it.text.toString().equals("Conta",true)})return
    var menu=all().firstOrNull {it.contentDescription?.toString() in listOf("Biblioteca e backup","Opções do livro")}
    if(menu==null){all().first {it is android.widget.LinearLayout && it.contentDescription?.toString()=="Estante"}.performClick();menu=all().first {it.contentDescription?.toString() in listOf("Biblioteca e backup","Opções do livro")}}
    menu.performClick()
    val clazz=Class.forName("android.view.WindowManagerGlobal")
    val global=clazz.getDeclaredMethod("getInstance").invoke(null)
    val roots=clazz.getDeclaredField("mViews").apply {isAccessible=true}.get(global) as List<*>
    val list=roots.filterIsInstance<android.view.View>().flatMap(::views).filterIsInstance<android.widget.ListView>().last {it.adapter!=null && it.adapter.count>0 && it.adapter.getItem(it.adapter.count-1).toString()=="Conta"}
    val position=list.adapter.count-1
    check(list.adapter.getItem(position).toString()=="Conta")
    list.performItemClick(android.view.View(activity),position,list.adapter.getItemId(position))
}
