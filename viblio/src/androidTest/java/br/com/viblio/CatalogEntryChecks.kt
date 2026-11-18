package br.com.viblio

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.books.catalog.*
import br.com.centralcasa.ui.*
import br.com.viblio.ui.CoverGalleryDialog

/** Exercise the user's unbound-book entry point, not only the gallery in isolation. */
object CatalogEntryChecks {
    fun run(test:Instrumentation){
        val activity=test.startActivitySync(Intent(test.targetContext,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val repository=LibraryRepository(activity);val b=LibraryBook(id="catalog-entry-check",title="1984",author="George Orwell",totalPages=100,currentPage=25)
        val previous=repository.currentId();repository.save(b);repository.setCurrent(b.id)
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
        fun roots():List<View>{val type=Class.forName("android.view.WindowManagerGlobal");val instance=type.getDeclaredMethod("getInstance").invoke(null);return (type.getDeclaredField("mViews").apply {isAccessible=true}.get(instance) as List<*>).filterIsInstance<View>().flatMap(::views)}
        var requested=0;lateinit var panel:BooksPanel;lateinit var parent:LinearLayout
        val gateway=object:BookCatalog {
            override val configured=true
            override fun configure(){}
            override fun saved(book:LibraryBook){}
            override fun search(title:String,author:String,isbn:String,done:(Result<List<CatalogBook>>)->Unit){done(Result.success((1..2).map {CatalogBook("$it","source","1984","George Orwell")}))}
            override fun loadCover(book:CatalogBook,done:(Result<String>)->Unit){done(Result.success("catalog-entry.jpg"))}
            override fun chooseCover(id:String,source:String,done:(Result<CatalogCover>)->Unit){
                requested++;CoverGalleryDialog.show(activity,(1..6).map {CatalogCover("$it","https://fixture.invalid/edition-$it.jpg",it==1)}){done(Result.success(it))}
            }
        }
        try {
            test.checkedMain {
                parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};activity.setContentView(ReadingScrollView(activity).apply {id=br.com.centralcasa.R.id.content_scroll;addView(parent)})
                panel=BooksPanel(activity,DisplayUi(activity)){parent.removeAllViews();panel.render(parent)}.apply {catalog=gateway}
                panel.openCurrent()
                views(parent).first {it.contentDescription?.toString()?.startsWith("Capa de 1984 ·")==true}.performLongClick()
                val menu=roots().filterIsInstance<ListView>().last();menu.performItemClick(View(activity),0,menu.adapter.getItemId(0))
                check(requested==1){"Catalog covers must open without a textual edition picker"}
                val grid=roots().first {it.tag=="catalog-cover-grid"} as LinearLayout
                check(grid.childCount==2 && (0 until 2).all {(grid.getChildAt(it) as LinearLayout).childCount==3})
                captureFixture(activity,grid,"catalog-entry-grid")
                views(grid).first {it.tag=="catalog-cover:https://fixture.invalid/edition-6.jpg"}.performClick()
                check(repository.book(b.id)?.let {it.currentPage==25 && it.totalPages==100 && it.cover=="catalog-entry.jpg" && it.title=="1984"}==true)
            }
        }finally {test.checkedMain {runCatching {panel.onDestroy()};repository.delete(b.id);if(previous!=null)repository.setCurrent(previous);repository.close();activity.finish()}}
    }
}
