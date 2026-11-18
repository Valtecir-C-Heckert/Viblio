package br.com.viblio

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.*
import br.com.centralcasa.ui.*
import br.com.viblio.account.AccountLibrary

/** Real preferences, private sync roundtrip, native forms and purchase lifecycle. */
object GoalsChecks {
    fun run(test:Instrumentation){
        val c=test.targetContext;val prefs=c.getSharedPreferences(LibraryProfiles.settings(c),0)
        val store=ReadingGoalStore(c);check(store.entries().isEmpty())
        val safety=java.io.File(c.filesDir,"account-safety"+LibraryProfiles.suffix(c));val beforeSafety=safety.listFiles()?.map {it.name}?.toSet().orEmpty()
        val repo=LibraryRepository(c,"viblio_test_goals.db")
        val fixtureCovers=mutableListOf<String>()
        val activity=test.startActivitySync(Intent(c,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var panel:ReadingGoalsPanel;lateinit var host:LinearLayout
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun roots():List<View>{val type=Class.forName("android.view.WindowManagerGlobal");val instance=type.getDeclaredMethod("getInstance").invoke(null);return (type.getDeclaredField("mViews").apply {isAccessible=true}.get(instance) as List<*>).filterIsInstance<View>().flatMap(::views)}
        fun tag(value:String)=roots().single {it.tag==value}
        fun button(id:Int)=roots().filterIsInstance<Button>().single {it.id==id}
        fun render(){host.removeAllViews();panel.render(host)}
        try {
            test.checkedMain {
                host=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(DisplayUi(activity).background)}
                panel=ReadingGoalsPanel(activity,DisplayUi(activity),repo,::render)
                activity.setContentView(ScrollView(activity).apply {addView(host)})
                render();check(views(host).none {it.tag?.toString()?.startsWith("goal:")==true})
                tag("reading-goals-toggle").performClick();check(store.expanded())
                tag("goal-create").performClick();check(panel.isEditing)
                (tag("goal-name") as EditText).setText("Minha leitura");(tag("goal-target") as EditText).setText("5")
                button(android.R.id.button1).performClick()
            }
            test.waitForIdleSync()
            val g=store.entries().single().goal!!;check(g.target==5 && !panel.isEditing)
            test.checkedMain {
                tag("goal-edit:${g.id}").performClick();(tag("goal-target") as EditText).setText("6");button(android.R.id.button1).performClick()
                check(store.entries().single().goal!!.target==6)
                tag("reading-goals-toggle").performClick();check(!store.expanded());check(views(host).none {it.tag=="goal:${g.id}"})
            }
            val original=store.entries().single();check(ReadingGoalStore(c).entries().single().raw==original.raw)
            val io=AccountLibrary(c);val snapshot=io.capture()
            check(snapshot.getAsJsonObject("appearance").get(ReadingGoalStore.PREFIX+g.id).asString==original.raw)
            check(!snapshot.getAsJsonObject("appearance").has("reading_goals_expanded"))
            prefs.edit().remove(ReadingGoalStore.PREFIX+g.id).commit();io.apply(snapshot);check(store.entries().single().raw==original.raw)
            val profiles=c.getSharedPreferences("viblio_profiles",0);val previous=profiles.all.toMap();val other=LibraryProfiles.key("goals-fixture","other")
            LibraryProfiles.select(c,other,false)
            try {check(ReadingGoalStore(c).entries().isEmpty())}finally {
                profiles.edit().clear().apply {previous.forEach {(k,v)->if(v is String)putString(k,v)}}.commit()
                c.getSharedPreferences("panel_settings_account-$other",0).edit().clear().commit()
            }
            val since=System.currentTimeMillis()-1000
            val purchase=ReadingGoal(name="Próxima compra",metric=GoalMetric.PURCHASE,target=3,period=GoalPeriod.CONTINUOUS,createdAt=since,baseline=emptyMap(),excluded=emptySet())
            val pages=ReadingGoal(name="Páginas",metric=GoalMetric.PAGES,target=500,createdAt=since,baseline=emptyMap(),excluded=emptySet())
            store.save(purchase,null);store.save(pages,null)
            test.checkedMain {store.expanded(true);render();check(roots().none {it.tag=="goal-purchase:${purchase.id}"})}
            val titles=listOf("Maré","O jardim","Constelações","Travessia")
            val colors=listOf(0xff7b3b24.toInt(),0xff263e45.toInt(),0xff46523e.toInt(),0xff443447.toInt())
            (1..4).forEach {i->
                val art=android.graphics.Bitmap.createBitmap(96,144,android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(art);canvas.drawColor(colors[i-1])
                val ink=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=0xffe5cf9b.toInt();style=android.graphics.Paint.Style.STROKE;strokeWidth=1f}
                canvas.drawRect(7f,7f,89f,137f,ink);canvas.drawCircle(48f,78f,22f,ink);canvas.drawLine(20f,111f,76f,111f,ink)
                ink.style=android.graphics.Paint.Style.FILL;ink.textSize=12f;ink.textAlign=android.graphics.Paint.Align.CENTER
                titles[i-1].split(' ').forEachIndexed {n,word->canvas.drawText(word,48f,27f+n*15,ink)}
                val output=java.io.ByteArrayOutputStream();art.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,output);art.recycle()
                val cover=BookImages(c).save(java.io.ByteArrayInputStream(output.toByteArray()));fixtureCovers.add(cover)
                val b=LibraryBook(id="goal-book-$i",title=titles[i-1],cover=cover,totalPages=100);repo.save(b);repo.recordReading(b.id,0,100,60000)
            }
            test.checkedMain {
                render();check((tag("goal-count:${g.id}") as TextView).text.toString()=="4")
                check(tag("goal-count:${pages.id}").contentDescription=="400 de 500 páginas")
                check(tag("goal-purchase-reward:${purchase.id}").contentDescription.toString().contains("compra liberada"))
                captureFixture(activity,host,"goals-expanded")
                tag("reading-goals-toggle").performClick();captureFixture(activity,host,"goals-collapsed")
                tag("reading-goals-toggle").performClick()
                tag("goal-details:${purchase.id}").performClick();check(roots().count {it.tag?.toString()?.startsWith("goal-credit:")==true}==3)
                tag("goal-purchase:${purchase.id}").performClick();button(android.R.id.button1).performClick()
            }
            test.waitForIdleSync()
            val used=store.entries().single {it.id==purchase.id};check(used.goal!!.cycles.single().books.size==3)
            check(ReadingGoals.progress(used.goal!!,repo.books(true),repo.sessions(),System.currentTimeMillis()).value==1L)
            check(runCatching {store.save(purchase,"")}.isFailure)
            test.checkedMain {
                check((tag("goal-purchase-status:${purchase.id}") as TextView).text.toString().startsWith("Faltam 2"))
                check(tag("goal-purchase-reward:${purchase.id}").contentDescription.toString().contains("compra bloqueada"))
                roots().filterIsInstance<TextView>().single {it.text.toString().startsWith("Ciclo 1 ·")}.performClick()
                check(roots().count {it.tag?.toString()?.startsWith("goal-credit:")==true}==4)
                button(android.R.id.button2).performClick()
            }
            val invalid=java.util.UUID.randomUUID().toString();val key=ReadingGoalStore.PREFIX+invalid
            prefs.edit().putString(key,"[".repeat(1000)+"]".repeat(1000)).commit()
            val entry=store.entries().single {it.id==invalid};check(entry.goal==null);store.remove(entry);check(!prefs.contains(key))
            test.checkedMain {tag("goal-edit:${g.id}").performClick();button(android.R.id.button3).performClick()}
            test.waitForIdleSync()
            test.checkedMain {button(android.R.id.button1).performClick()}
            test.waitForIdleSync()
            check(store.entries().none {it.id==g.id})
            store.save(g.copy(target=6),null)
            test.checkedMain {panel.close();activity.finish()}
            test.waitForIdleSync()
            captureStatistics(test,repo)
        }finally {
            test.checkedMain {runCatching {panel.close()};activity.finish()}
            prefs.edit().apply {prefs.all.keys.filter {it.startsWith(ReadingGoalStore.PREFIX)||it=="reading_goals_expanded"}.forEach(::remove)}.commit()
            repo.close();c.deleteDatabase("viblio_test_goals.db");fixtureCovers.forEach {BookImages(c).file(it)?.delete()}
            safety.listFiles()?.filter {it.name !in beforeSafety}?.forEach {it.delete()}
        }
    }
    /** Capture the real Statistics screen, including adjacent sections and the whole-section toggle. */
    private fun captureStatistics(test:Instrumentation,fixture:LibraryRepository){
        val c=test.targetContext;val main=LibraryRepository(c);check(main.books(true).isEmpty())
        val images=BookImages(c)
        val bitmap=android.graphics.Bitmap.createBitmap(32,48,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(0xffa76c50.toInt())}
        val bytes=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,bytes);bitmap.recycle()
        val cover=images.save(java.io.ByteArrayInputStream(bytes.toByteArray()))
        var screen:android.app.Activity?=null
        try {
            main.importSnapshot(fixture.books(true),fixture.sessions(),null)
            val current=requireNotNull(main.book("goal-book-1"));main.save(current.copy(cover=current.cover.ifBlank {cover}));main.setCurrent(current.id)
            screen=test.startActivitySync(Intent(c,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity=requireNotNull(screen)
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
            test.checkedMain {
                views(activity.window.decorView).filterIsInstance<TextView>().single {it.text.toString()=="Usar Estante offline"}.performClick()
            }
            val deadline=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<deadline){
                test.waitForIdleSync();var ready=false
                test.checkedMain {val color=DisplayUi(activity).accent;ready=android.graphics.Color.red(color)>android.graphics.Color.blue(color)}
                if(ready)break
                Thread.sleep(30)
            }
            test.checkedMain {
                views(activity.window.decorView).single {it is LinearLayout && it.contentDescription=="Estatísticas"}.performClick()
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"goals-statistics-expanded")
                views(activity.window.decorView).single {it.tag=="reading-goals-toggle"}.performClick()
                check(views(activity.window.decorView).none {it.tag?.toString()?.startsWith("goal:")==true})
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"goals-statistics-collapsed")
            }
        }finally {
            test.checkedMain {screen?.finish()}
            fixture.books(true).forEach {main.delete(it.id)};main.close();images.file(cover)?.delete()
        }
    }

}
