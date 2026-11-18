package br.com.viblio
import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.ui.*
import java.io.ByteArrayInputStream
import java.util.UUID

object CollectionInventoryChecks {
    fun run(test:Instrumentation,activity:Activity){
        val context=test.targetContext
        val name="inventory-upgrade-validation.db";context.deleteDatabase(name)
        var repo=LibraryRepository(context,name)
        try {
            repo.createCollection(CollectionTitle(id="legacy",kind="comic",title="Legado",missingVolumes=2),1)
            // Reproduce a v10 database without the new column, preserving real rows.
            repo.writableDatabase.execSQL("ALTER TABLE collections RENAME TO old_collections")
            repo.writableDatabase.execSQL("CREATE TABLE collections AS SELECT id,kind,title,author,genre,publisher,missing_volumes,source_read_count,source_rating,rating,cover,updated_at,subgenres,archived FROM old_collections")
            repo.writableDatabase.execSQL("DROP TABLE old_collections")
            repo.writableDatabase.execSQL("UPDATE collections SET missing_volumes=2")
            repo.writableDatabase.version=10;repo.close();repo=LibraryRepository(context,name)
            check(repo.collections().single().expectedVolumes==3 && repo.collections().single().missingVolumes==2)
            val first=repo.books().single()
            repo.save(first.copy(id="two",sequenceNumber=2));check(repo.collections().single().missingVolumes==1)
            repo.save(requireNotNull(repo.book("two")).copy(title="Nome editado"));check(repo.collections().single().missingVolumes==1)
            repo.save(first.copy(id="copy-two",sequenceNumber=2));check(repo.collections().single().missingVolumes==1)
            repo.save(first.copy(id="three",sequenceNumber=3));check(repo.collections().single().missingVolumes==0)
            repo.archive("two");check(repo.collections().single().missingVolumes==0)
            repo.archive("copy-two");check(repo.collections().single().missingVolumes==1)
            repo.archive("two",removed=false);check(repo.collections().single().missingVolumes==0)
            val json=LibraryJson(BookImages(context))
            val snapshot=json.read(ByteArrayInputStream(json.write(repo.books(true),repo.sessions(),null,repo.dates(),repo.collections(true)).toByteArray()))
            check(snapshot.collections.single().expectedVolumes==3 && snapshot.collections.single().missingVolumes==0)
            repo.createCollection(CollectionTitle(id="unknown",kind="comic",title="Sem total",missingVolumes=0),0)
            repo.save(first.copy(id="unknown-one",collectionId="unknown"))
            check(repo.collections().first {it.id=="unknown"}.expectedVolumes==null)
            check(repo.collections().first {it.id=="unknown"}.missingVolumes==null)
        }finally {repo.close();context.deleteDatabase(name)}
        val prefs=activity.getSharedPreferences(LibraryProfiles.settings(activity),0);val original=prefs.all.toMap()
        val local=LibraryRepository(activity);val prefix="inventory-"+UUID.randomUUID();val ids=(0..2).map {"$prefix-$it"}
        var panel:BooksPanel?=null
        lateinit var parent:LinearLayout
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun visible()=views(parent).mapNotNull {it.tag?.toString()?.takeIf {t->t.startsWith("shelf-cover:")}?.removePrefix("shelf-cover:")}.toSet()
        fun render(){parent.removeAllViews();panel!!.render(parent)}
        try {
            ids.forEachIndexed {i,id->local.createCollection(CollectionTitle(id=id,title="Teste $i",expectedVolumes=if(i==2)null else 3),if(i==0)3 else 1)}
            test.checkedMain {
                prefs.edit().putString("SHELF_TYPE","manga").putString("SHELF_MANUAL_DEFAULT:manga","false").remove("BOOK_SHELF_FILTERS:manga").commit()
                parent=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};activity.setContentView(ScrollView(activity).apply {id=br.com.centralcasa.R.id.content_scroll;addView(parent)})
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply {useCollectionInventory=true};render()
            }
            fun filter(value:CollectionCompletion){test.checkedMain {
                val method=BooksPanel::class.java.getDeclaredMethod("filterDialog",List::class.java).apply {isAccessible=true}
                val dialog=method.invoke(panel,local.collections().map {it.shelfBook(local.books())}) as AlertDialog
                val spinner=views(dialog.window!!.decorView).filterIsInstance<Spinner>().single {it.contentDescription=="Coleção"}
                spinner.setSelection(value.ordinal);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }}
            filter(CollectionCompletion.COMPLETE);test.checkedMain {check(visible()==setOf(ids[0]))}
            filter(CollectionCompletion.INCOMPLETE);test.checkedMain {check(visible()==setOf(ids[1]))}
            filter(CollectionCompletion.UNKNOWN);test.checkedMain {
                check(visible()==setOf(ids[2]))
                val state=Bundle();panel!!.saveState(state);panel!!.onDestroy()
                panel=BooksPanel(activity,DisplayUi(activity),::render).apply {useCollectionInventory=true};panel!!.restoreState(state);render()
                check(visible()==setOf(ids[2]))
            }
        }finally {
            test.checkedMain {panel?.onDestroy()}
            ids.forEach {local.deleteCollection(it)};local.close()
            val edit=prefs.edit().clear();original.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Boolean->edit.putBoolean(k,v);is Float->edit.putFloat(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}};edit.commit()
        }
    }
}
