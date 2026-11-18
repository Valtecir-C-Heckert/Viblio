package br.com.viblio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import br.com.centralcasa.R
import br.com.centralcasa.books.*
import br.com.centralcasa.ui.*

/** Same public books as the approved mockup, drawn by the actual release views. */
object SpineMaterialChecks {
    fun run(test:Instrumentation,activity:Activity){
        val repo=LibraryRepository(activity);check(repo.books().isEmpty())
        val prefs=activity.getSharedPreferences(LibraryProfiles.settings(activity),0);val before=prefs.all.toMap()
        val fixtures=listOf(
            LibraryBook(id="material-1984",title="1984",author="George Orwell",totalPages=400),
            LibraryBook(id="material-casmurro",title="Dom Casmurro",author="Machado de Assis",totalPages=700),
            LibraryBook(id="material-tolkien",title="O Senhor dos Anéis",author="J. R. R. Tolkien",totalPages=1300),
            LibraryBook(id="material-clarice",title="A paixão segundo G.H.",author="Clarice Lispector",totalPages=800))
        val colors=listOf(0xff7d2732.toInt(),0xffb34d23.toInt(),0xffe6bc64.toInt(),0xff24415f.toInt())
        var panel:BooksPanel?=null
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        try {
            test.checkedMain {
                for(width in listOf(24,32,48,72)){
                    val sample=ShelfSpineView(activity,"Sacramentadora",author="Brandon Sanderson")
                    sample.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(220,View.MeasureSpec.EXACTLY));sample.layout(0,0,width,220)
                    val title=ShelfSpineView::class.java.getDeclaredField("titleLayout").apply {isAccessible=true}.get(sample) as android.text.StaticLayout
                    check(title.lineCount==1){"Sacramentadora split at width $width"}
                }
                val materials=checkNotNull(ShelfMaterials.load(activity));check(materials.leather.width==120 && materials.wood.width==973)
                fixtures.forEach(repo::save)
                prefs.edit().putString("SHELF_TYPE","book").putString("SHELF_SPINE_VIEW:book","true").putString("SHELF_SPINE_ZOOM","1.35")
                    .putString("SHELF_MANUAL_DEFAULT:book","true").putString("SHELF_MANUAL_ORDER:book",org.json.JSONArray(fixtures.map {it.id}).toString()).remove("BOOK_SHELF_FILTERS:book").commit()
                val ui=DisplayUi(activity)
                val parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(24))}
                activity.setContentView(ReadingScrollView(activity).apply {id=R.id.content_scroll;addView(parent)})
                panel=BooksPanel(activity,ui){}.apply {useSpineLayout=true;useManualShelfOrder=true;separateShelfStatistics=true}
                panel!!.render(parent)
                val spines=views(parent).filterIsInstance<ShelfSpineView>();check(spines.size==4)
                spines.forEachIndexed {index,spine->val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply {eraseColor(colors[index])};spine.setImageBitmap(bitmap);bitmap.recycle()}
                captureFixture(activity,parent,"shelf-materials")
                check(views(parent).filter {it.tag=="spine-row"}.size==1)
                check(views(parent).filter {it.tag=="shelf-wood"}.size==1)
                spines.forEachIndexed {index,spine->
                    val author=ShelfSpineView::class.java.getDeclaredField("authorLayout").apply {isAccessible=true}.get(spine) as android.text.StaticLayout
                    check(author.text.toString().replace('\n',' ')==fixtures[index].author)
                    check(author.lineCount==2){"Author ${fixtures[index].author}: ${author.lineCount} lines"}
                    check(author.text.substring(author.getLineStart(0),author.getLineEnd(0)).trim()==ShelfSpines.authorLines(fixtures[index].author).substringBefore('\n'))
                    val title=ShelfSpineView::class.java.getDeclaredField("titleLayout").apply {isAccessible=true}.get(spine) as android.text.StaticLayout
                    check(title.text.toString()==fixtures[index].title)
                }
            }
        }finally {
            test.checkedMain {panel?.onDestroy();fixtures.forEach {repo.delete(it.id)};repo.close();val edit=prefs.edit().clear();before.forEach {(key,value)->when(value){is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Boolean->edit.putBoolean(key,value);is Float->edit.putFloat(key,value)}};edit.commit()}
        }
    }
}
