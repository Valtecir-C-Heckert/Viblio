package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.R
import br.com.centralcasa.books.*
import br.com.centralcasa.books.catalog.*
import br.com.centralcasa.ui.*

object SpineIsbnChecks {
    fun run(test:Instrumentation,activity:Activity){
        val repo=LibraryRepository(activity)
        check(repo.books().isEmpty())
        val prefs=activity.getSharedPreferences("panel_settings",0);val before=prefs.all.toMap()
        val longTitle="ISBN fixture 0 — Uma história muito longa sobre livros, leitores e todas as aventuras que precisam caber na lombada"
        val ids=listOf("spine-fixture-a","spine-fixture-b","spine-fixture-c")
        lateinit var panel:BooksPanel;lateinit var parent:LinearLayout
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun render(){parent.removeAllViews();panel.render(parent)}
        var scans=0;var requestIsbn="";var requestTitle="";var reply:((Result<List<CatalogBook>>)->Unit)?=null
        val gateway=object:BookCatalog {
            override val configured=true
            override val supportsIsbnScan=true
            override fun scanIsbn(){scans++}
            override fun configure(){}
            override fun saved(book:LibraryBook){}
            override fun loadCover(book:CatalogBook,done:(Result<String>)->Unit){error("Fixture has no cover")}
            override fun search(title:String,author:String,isbn:String,done:(Result<List<CatalogBook>>)->Unit){requestIsbn=isbn;requestTitle=title;reply=done}
        }
        try {
            test.checkedMain {
                ids.forEachIndexed {i,id->repo.save(LibraryBook(id=id,title=if(i==0)longTitle else "ISBN fixture $i",totalPages=if(i==0)100 else if(i==1)1000 else 2000,author="Machado de Assis"))}
                prefs.edit().putString("SHELF_TYPE","book").putString("SHELF_SPINE_VIEW:book","true").remove("BOOK_SHELF_FILTERS:book").remove("SHELF_MANUAL_DEFAULT:book").commit()
                parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                activity.setContentView(ReadingScrollView(activity).apply {id=R.id.content_scroll;addView(parent)})
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply {useSpineLayout=true;useAsyncShelfImages=true;useManualShelfOrder=true;catalog=gateway}
                render()
                val width=DisplayUi(activity).dp(320)
                parent.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));parent.layout(0,0,width,parent.measuredHeight)
                val row=views(parent).filterIsInstance<LinearLayout>().first {it.tag=="spine-row"}
                check(row.childCount==3)
                for(i in 1 until row.childCount)check(row.getChildAt(i).left==row.getChildAt(i-1).right)
                check(views(parent).filterIsInstance<ShelfSpineView>().size==3)
                val spine=views(parent).filterIsInstance<ShelfSpineView>().first()
                val bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(0xffa52c31.toInt())}
                spine.setImageBitmap(bitmap);check(spine.spineColor==0xffa52c31.toInt());check(spine.drawable==null);bitmap.recycle();captureFixture(activity,parent,"spines")
                val titleLayout=ShelfSpineView::class.java.getDeclaredField("titleLayout").apply {isAccessible=true}.get(spine) as android.text.StaticLayout
                check(titleLayout.text.toString()==longTitle && titleLayout.getLineEnd(titleLayout.lineCount-1)==longTitle.length)
                check(titleLayout.height<=spine.width-DisplayUi(activity).dp(10))
                check(titleLayout.paint.textSize<13*activity.resources.displayMetrics.density)
                val authorLayout=ShelfSpineView::class.java.getDeclaredField("authorLayout").apply {isAccessible=true}.get(spine) as android.text.StaticLayout
                check(authorLayout.text.toString()=="Machado\nde Assis")
                check(authorLayout.height<=DisplayUi(activity).dp(36))
                check(authorLayout.width<=spine.width-DisplayUi(activity).dp(10))
                check(views(parent).any {it.tag=="shelf-wood"})
                check(row.getChildAt(0).width<row.getChildAt(1).width && row.getChildAt(1).width<row.getChildAt(2).width)

                views(parent).first {it.tag=="shelf-cover:${ids[0]}"}.performClick();check(views(parent).filterIsInstance<TextView>().any {it.text.toString()==longTitle})
                panel.openShelf();views(parent).filterIsInstance<EditText>().single().setText("fixture 1")
                check(views(parent).count {it is ShelfSpineView}==1)
                val toggle=views(parent).single {it.tag=="shelf-view-toggle"}
                check(toggle.isSelected && toggle.contentDescription=="Mostrar capas")
                toggle.performClick()
                check(views(parent).none {it is ShelfSpineView})
                check(views(parent).filterIsInstance<EditText>().single().text.toString()=="fixture 1")
                check(prefs.getString("SHELF_SPINE_VIEW:book","")=="false")
                views(parent).single {it.tag=="shelf-view-toggle"}.apply {check(contentDescription=="Mostrar lombadas");performClick()}
                check(views(parent).count {it is ShelfSpineView}==1)
                check(prefs.getString("SHELF_SPINE_VIEW:book","")=="true")
                panel.addFromCatalog()
                views(parent).single {it.tag=="editor-scan-isbn"}.performClick();check(scans==1)
                views(parent).filterIsInstance<EditText>().single {it.hint.toString()=="Título *"}.setText("Título provisório")
                panel.acceptScannedIsbn("9780134685991")
            }
            test.waitForIdleSync()
            test.checkedMain {
                check(requestIsbn=="9780134685991" && requestTitle.isEmpty())
                reply!!(Result.success(listOf(CatalogBook("42","fixture","Livro encontrado","Autora",pages=345,isbn=requestIsbn))))
                check(views(parent).filterIsInstance<EditText>().single {it.hint.toString()=="Título *"}.text.toString()=="Livro encontrado")
                check(views(parent).filterIsInstance<EditText>().single {it.hint.toString()=="Total de páginas *"}.text.toString()=="345")
                // Filling a match does not save a book or change the existing shelf.
                check(repo.books().map {it.id}.toSet()==ids.toSet())
            }
        }finally {
            test.checkedMain {panel.onDestroy();ids.forEach(repo::delete);repo.close();val edit=prefs.edit().clear();before.forEach {(key,value)->when(value){is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Boolean->edit.putBoolean(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)}};edit.commit()}
        }
    }
}
