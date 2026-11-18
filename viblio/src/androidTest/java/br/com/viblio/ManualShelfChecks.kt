package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipDescription
import android.os.Bundle
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.R
import br.com.centralcasa.books.*
import br.com.centralcasa.ui.*
import java.util.UUID

/** Emulator-only fixtures: restore preferences and remove only our generated book IDs. */
object ManualShelfChecks {
    fun run(test:Instrumentation,activity:Activity) {
        val repo=LibraryRepository(activity)
        check(repo.books().isEmpty() && repo.collections().isEmpty()) { "Manual UI fixtures require an empty emulator library" }
        val prefs=activity.getSharedPreferences("panel_settings",0);val snapshot=prefs.all.toMap()
        val ids=List(4) { UUID.randomUUID().toString() }
        var panel:BooksPanel?=null
        var savedManualBeforeFilter=""
        lateinit var parent:LinearLayout
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun text(value:String)=views(parent).filterIsInstance<TextView>().first { it.text.toString()==value }
        fun order()=views(parent).mapNotNull { it.tag?.toString()?.takeIf { tag->tag.startsWith("shelf-cover:") }?.removePrefix("shelf-cover:") }
        fun render() { parent.removeAllViews();panel!!.render(parent) }
        fun drag(target:View,from:String,action:Int) {
            val clip=ClipData.newPlainText("Estante","")
            val obtain=DragEvent::class.java.getDeclaredMethod("obtain",Int::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Any::class.java,ClipDescription::class.java,ClipData::class.java,Class.forName("com.android.internal.view.IDragAndDropPermissions"),Boolean::class.javaPrimitiveType)
            val event=obtain.invoke(null,action,0f,0f,from,clip.description,clip,null,true) as DragEvent
            check(target.dispatchDragEvent(event))
        }
        try {
            ids.take(3).forEachIndexed { index,id->repo.save(LibraryBook(id=id,title="Fixture ${'A'+index}",totalPages=100)) }
            test.checkedMain {
                prefs.edit().putString("SHELF_TYPE","book").remove("SHELF_MANUAL_DEFAULT:book").remove("SHELF_MANUAL_ORDER:book").remove("BOOK_SHELF_FILTERS:book").commit()
                parent=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                val scroll=ReadingScrollView(activity).apply { id=R.id.content_scroll;addView(parent) };activity.setContentView(scroll)
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply { useManualShelfOrder=true;pinchStep=1.4;separateShelfStatistics=true }
                render();check(order()==ids.take(3))
                // A query narrows the screen; entering manual layout must clear it.
                views(parent).filterIsInstance<EditText>().single().setText("Fixture A")
                check(order()==listOf(ids[0]))
                views(parent).first { it.tag=="shelf-cover:${ids[0]}" }.performLongClick()
                check(order()==ids.take(3));check(text("Salvar como principal").isShown);check(!panel!!.canReplaceLibrary)
                check(views(parent).filterIsInstance<EditText>().single().visibility==View.GONE)
                val target=views(parent).first { it.tag=="shelf-cover:${ids[2]}" }
                drag(target,ids[0],DragEvent.ACTION_DRAG_STARTED);drag(target,ids[0],DragEvent.ACTION_DROP);drag(target,ids[0],DragEvent.ACTION_DRAG_ENDED)
            }
            test.waitForIdleSync()
            test.checkedMain {
                check(order()==listOf(ids[1],ids[2],ids[0]))
                order().forEach {id->check((views(parent).single {it.tag=="shelf-cover:$id"} as ViewGroup).getChildAt(1).visibility==View.GONE)}
                text("Salvar como principal").performClick();check(panel!!.canReplaceLibrary);check(prefs.getString("SHELF_MANUAL_DEFAULT:book","")=="true")
                check(views(parent).filterIsInstance<EditText>().single().text.toString().isEmpty())
                // A fresh panel reads the saved order rather than title sorting or the previous query.
                panel!!.onDestroy()
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply { useManualShelfOrder=true;pinchStep=1.4;separateShelfStatistics=true }
                render();check(order()==listOf(ids[1],ids[2],ids[0]))
                val state=Bundle();panel!!.saveState(state);panel!!.restoreState(state);render();check(order()==listOf(ids[1],ids[2],ids[0]))
                repo.save(LibraryBook(id=ids[3],title="A new title",totalPages=100));render()
                check(order()==listOf(ids[1],ids[2],ids[0],ids[3]))
                views(parent).first { it.tag=="shelf-cover:${ids[1]}" }.performLongClick()
                val target=views(parent).first { it.tag=="shelf-cover:${ids[0]}" }
                drag(target,ids[1],DragEvent.ACTION_DRAG_STARTED);drag(target,ids[1],DragEvent.ACTION_DROP);drag(target,ids[1],DragEvent.ACTION_DRAG_ENDED)
            }
            test.waitForIdleSync()
            test.checkedMain {
                text("Cancelar").performClick();check(order()==listOf(ids[1],ids[2],ids[0],ids[3]))
                // A manual default must not erase a later, explicitly applied filter/order.
                ids.slice(1..2).forEachIndexed {i,id->repo.save(requireNotNull(repo.book(id)).copy(genre="Fantasia",rating=4.5-i,currentPage=10+i))}
                savedManualBeforeFilter=prefs.getString("SHELF_MANUAL_ORDER:book","").orEmpty()
                val configured=Bundle();panel!!.saveState(configured)
                configured.putBoolean("books.manualView",false);configured.putString("books.order","TITLE:true")
                panel!!.restoreState(configured);render()
                val method=BooksPanel::class.java.getDeclaredMethod("filterDialog",List::class.java).apply {isAccessible=true}
                val dialog=method.invoke(panel,repo.books()) as android.app.AlertDialog
                fun select(name:String,label:String){
                    val spinner=views(dialog.window!!.decorView).filterIsInstance<Spinner>().single {it.contentDescription==name}
                    val index=(0 until spinner.adapter.count).first {spinner.adapter.getItem(it).toString()==label}
                    spinner.setSelection(index)
                }
                select("Gênero","Fantasia");select("Situação","Em leitura")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            // AlertDialog dispatches the positive listener through its main-thread Handler.
            test.waitForIdleSync()
            test.checkedMain {
                views(parent).filterIsInstance<EditText>().single().setText("Fixture")
                check(order()==listOf(ids[2],ids[1])) {"Applied filters returned ${order().map {ids.indexOf(it)}}; stored=${prefs.getString("BOOK_SHELF_FILTERS:book","")}"}
                val filtered=prefs.getString("BOOK_SHELF_FILTERS:book","")
                panel!!.onStop();panel!!.onStart();render();check(order()==listOf(ids[2],ids[1]))
                panel!!.onDestroy()
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply {useManualShelfOrder=true}
                render();check(order()==listOf(ids[2],ids[1]))
                check(views(parent).filterIsInstance<EditText>().single().text.toString()=="Fixture")
                val filteredState=Bundle();panel!!.saveState(filteredState);panel!!.restoreState(filteredState);render()
                check(order()==listOf(ids[2],ids[1]))
                // Loading a different type must leave the book selection untouched.
                panel!!.onDestroy();prefs.edit().putString("SHELF_TYPE","manga").commit()
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply {useManualShelfOrder=true};render();check(order().isEmpty())
                panel!!.onDestroy();prefs.edit().putString("SHELF_TYPE","book").commit()
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply {useManualShelfOrder=true};render()
                check(order()==listOf(ids[2],ids[1]));check(prefs.getString("BOOK_SHELF_FILTERS:book","")==filtered)
                // Cancelling a fresh organization restores the filter instead of forcing manual view.
                views(parent).first {it.tag=="shelf-cover:${ids[2]}"}.performLongClick()
                text("Cancelar").performClick();check(order()==listOf(ids[2],ids[1]))
                check(prefs.getString("SHELF_MANUAL_ORDER:book","")==savedManualBeforeFilter)
                check(prefs.getString("SHELF_MANUAL_DEFAULT:book","")=="true")

            }
        } finally {
            test.checkedMain { panel?.onDestroy() }
            ids.forEach { if(repo.book(it)!=null) repo.delete(it) };repo.close()
            val editor=prefs.edit().clear()
            snapshot.forEach { (key,value)->when(value) { is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value);is Long->editor.putLong(key,value);is Float->editor.putFloat(key,value);is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet()) } };editor.commit()
        }
    }
}
