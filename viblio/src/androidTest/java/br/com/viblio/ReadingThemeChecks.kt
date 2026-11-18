package br.com.viblio

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.com.centralcasa.books.*
import br.com.centralcasa.ui.DisplayUi

/** Current-book changes recolour chrome across tabs without modifying library contents. */
object ReadingThemeChecks {
    fun run(test:Instrumentation){
        val context=test.targetContext;val repo=LibraryRepository(context);check(repo.books().isEmpty())
        val images=BookImages(context)
        fun cover(color:Int):String {val bitmap=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply {eraseColor(color)};val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);bitmap.recycle();return images.save(java.io.ByteArrayInputStream(out.toByteArray()))}
        val red=LibraryBook(id="theme-fixture-red",title="Livro vermelho",totalPages=100,currentPage=12,cover=cover(0xffba2e45.toInt()))
        val blue=LibraryBook(id="theme-fixture-blue",title="Livro azul",totalPages=100,currentPage=23,cover=cover(0xff173fbb.toInt()))
        repo.save(red);repo.save(blue);repo.setCurrent(red.id)
        val fallback=DisplayUi(context).apply {readingColor(null)}.accent
        val activity=test.startActivitySync(Intent(context,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun all()=views(activity.window.decorView)
        fun nav(label:String)=all().single {it is LinearLayout && it.contentDescription?.toString()==label}
        val field=ViblioActivity::class.java.getDeclaredField("ui").apply {isAccessible=true}
        val ui=field.get(activity) as DisplayUi
        fun await(label:String,predicate:()->Boolean){val deadline=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<deadline){test.waitForIdleSync();var result=false;test.checkedMain {result=predicate()};if(result)return;Thread.sleep(50)};error("Reading theme timeout: $label")}
        try {
            test.checkedMain {all().filterIsInstance<TextView>().single {it.text.toString()=="Usar Estante offline"}.performClick()}
            await("red accent"){ui.accent!=fallback && Color.red(ui.accent)>Color.blue(ui.accent)}
            val redAccent=ui.accent
            val redBackground=ui.background
            test.checkedMain {
                check(Color.red(ui.background)>Color.blue(ui.background))
                check(activity.window.statusBarColor==ui.background && activity.window.navigationBarColor==ui.background)
                check(CoverColors.contrast(ui.text,ui.activeSurface)>=7 && CoverColors.contrast(ui.muted,ui.activeSurface)>=4.6)
                check(ui.shape(ui.surface,20).cornerRadius<=ui.dp(1))
                // A blue book opened from the shelf must not inherit the red current-book backdrop.
                nav("Estante").performClick()
                (all().singleOrNull {it.tag=="shelf-cover:${blue.id}"} ?: error("Blue fixture absent from shelf")).performClick()
                check(repo.currentId()==red.id && ui.accent==redAccent)
                val readingCard=all().singleOrNull {it.tag=="reading-history-card"} ?: error("History card absent from opened blue book")
                val cardFill=(readingCard.background as GradientDrawable).colors!!.first()
                check(Color.blue(cardFill)>Color.red(cardFill)){"Reading card inherited the current book color"}
                check(Color.blue(activity.window.statusBarColor)>Color.red(activity.window.statusBarColor))
                val root=ViblioActivity::class.java.getDeclaredField("root").apply {isAccessible=true}.get(activity) as View
                val backdrop=requireNotNull(root.background);val oldBounds=android.graphics.Rect(backdrop.bounds)
                val sample=Bitmap.createBitmap(64,96,Bitmap.Config.ARGB_8888)
                try {
                    backdrop.setBounds(0,0,64,96);backdrop.draw(android.graphics.Canvas(sample))
                    val pixels=IntArray(64*96);sample.getPixels(pixels,0,64,0,0,64,96)
                    check(pixels.sumOf {Color.blue(it).toLong()}>pixels.sumOf {Color.red(it).toLong()}) {"Opened book backdrop followed the current book"}
                }finally {backdrop.bounds=oldBounds;sample.recycle()}
                nav("Livro atual").performClick()
                check(repo.currentId()==red.id && Color.red(activity.window.statusBarColor)>Color.blue(activity.window.statusBarColor))
                nav("Estatísticas").performClick()
                check((all().single {it.tag=="statistics-pages"} as TextView).currentTextColor==redAccent)
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"statistics-red")
                repo.setCurrent(blue.id);nav("Livro atual").performClick()
            }
            await("blue accent"){ui.accent!=redAccent && Color.blue(ui.accent)>Color.red(ui.accent)}
            test.checkedMain {
                check(ui.background!=redBackground && Color.blue(ui.background)>Color.red(ui.background))
                check(Color.blue(ui.surface)>Color.red(ui.surface))
                check(activity.window.statusBarColor==ui.background && activity.window.navigationBarColor==ui.background)
                check(CoverColors.contrast(ui.text,ui.activeSurface)>=7 && CoverColors.contrast(ui.muted,ui.activeSurface)>=4.6)
                val card=all().single {it.tag=="reading-history-card"}
                check((card.background as GradientDrawable).cornerRadius<=ui.dp(1))
                check(all().filterIsInstance<TextView>().single {it.text.toString()=="00:00:00"}.currentTextColor==ui.accent)
                val start=all().filterIsInstance<TextView>().single {it.text.toString()=="Iniciar leitura"}
                check(((start.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as GradientDrawable).color?.defaultColor==ui.accent)
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"current-square")
                nav("Estatísticas").performClick()
                check((all().single {it.tag=="statistics-pages"} as TextView).currentTextColor==ui.accent)
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"statistics-blue")
                nav("Pesquisa").performClick()
                val query=all().filterIsInstance<android.widget.EditText>().single()
                check((query.background as GradientDrawable).cornerRadius<=ui.dp(1))
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"search-square")
                repo.delete(blue.id);nav("Estante").performClick()
            }
            await("neutral fallback"){ui.accent==fallback}
            test.checkedMain {
                check(Color.red(ui.background)==Color.blue(ui.background))
                val scoped=DisplayUi(context)
                for((label,color) in listOf("green" to 0xff238c55.toInt(),"warm" to 0xff916544.toInt(),"purple" to 0xff8056b8.toInt())){
                    scoped.scopedReadingColor(color)
                    check(CoverColors.contrast(scoped.accent,scoped.activeSurface)>=4.6)
                    check(CoverColors.contrast(scoped.muted,scoped.activeSurface)>=4.6)
                    if(label=="green")check(Color.green(scoped.background)>Color.red(scoped.background))
                    else if(label=="warm")check(Color.red(scoped.background)>Color.blue(scoped.background))
                    else check(Color.blue(scoped.background)>Color.green(scoped.background))
                    // Render the real Statistics screen with the global current-book palette.
                    ui.readingColor(color);nav("Estatísticas").performClick();nav("Pesquisa").performClick();nav("Estatísticas").performClick()
                    captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"statistics-$label")
                }
                ui.readingColor(null)
            }
            check(repo.book(red.id)?.currentPage==12)
        }finally {test.checkedMain {activity.finish();repo.delete(red.id);repo.delete(blue.id);repo.close();images.file(red.cover)?.delete();images.file(blue.cover)?.delete();ui.readingColor(null)}}
    }
}
