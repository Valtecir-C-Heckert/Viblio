package br.com.viblio

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.catalog.*
import br.com.centralcasa.ui.DisplayUi
import br.com.viblio.ui.SearchPanel

/** Real native views and callbacks; no personal database writes or external requests. */
object NavigationChecks {
    fun run(test:Instrumentation) {
        val activity=test.startActivitySync(Intent(test.targetContext,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun all()=views(activity.window.decorView)
        fun text(value:String)=all().filterIsInstance<TextView>().first { it.text.toString()==value }
        fun tab(label:String)=all().first { it.contentDescription?.toString()==label && it is LinearLayout }
        try {
            test.checkedMain { check(text("Criar conta por convite").isShown);check(all().filterIsInstance<TextView>().last { it.text.toString()=="Entrar" }.isEnabled)
                check(all().filterIsInstance<EditText>().all { it.isEnabled });check(all().none {it.contentDescription?.toString()=="Estante"})
                if(ViblioServer.PUBLIC_API_URL.isNotBlank()){
                    check(all().filterIsInstance<EditText>().none {it.hint.toString()=="Endereço do servidor"})
                    text("Criar conta por convite").performClick()
                    check(all().filterIsInstance<EditText>().map {it.hint.toString()}.containsAll(listOf("Nome de usuário","E-mail","Convite")))
                    check(all().filterIsInstance<EditText>().none {it.hint.toString()=="Endereço do servidor" || it.hint.toString().startsWith("Chave da instalação")})
                    check(all().filterIsInstance<TextView>().none {it.text.toString().startsWith("Configurar primeira conta")})
                    text("Já tenho conta · Entrar").performClick()
                }
                text("Usar Estante offline").performClick();check(tab("Estante").isSelected)
                val nav=tab("Livro atual").parent as LinearLayout
                check(nav.getChildAt(0).contentDescription=="Livro atual" && nav.childCount==5)
                check(nav.getChildAt(4).contentDescription=="Amigos")
                check(all().none {it is LinearLayout && it.contentDescription?.toString()=="Conta"})
                val currentRepo=br.com.centralcasa.books.LibraryRepository(activity)
                val currentBook=br.com.centralcasa.books.LibraryBook(id="navigation-current-check",title="Leitura do menu",totalPages=100)
                currentRepo.save(currentBook);currentRepo.setCurrent(currentBook.id)
                val historySessions=(1..5).map {n->br.com.centralcasa.books.ReadingSession(id="navigation-session-$n",bookId=currentBook.id,startedAt=n*10000L,finishedAt=n*10000L+1000,durationMs=1000,startPage=n-1,endPage=n,readingDay="2026-10-0$n")}
                currentRepo.importSnapshot(listOf(currentBook),historySessions,currentBook.id)
                tab("Livro atual").performClick();check(text("Leitura do menu").isShown);check(tab("Livro atual").isSelected)
                all().single {it.contentDescription?.toString()?.startsWith("Histórico de leitura ·")==true}.performClick()
                var historyCard=all().single {it.tag=="reading-history-card"}
                check(views(historyCard).count {it.tag=="reading-history-day"}==4)
                check(views(historyCard).filter {it.tag=="reading-history-day"}.all {it.background==null})
                check(views(historyCard).count {it.tag=="reading-history-session"}==4)
                val phoneWidth=DisplayUi(activity).dp(320)
                historyCard.measure(View.MeasureSpec.makeMeasureSpec(phoneWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));historyCard.layout(0,0,phoneWidth,historyCard.measuredHeight)
                views(historyCard).filter {it.tag=="reading-history-session"}.forEach {session->
                    val clock=views(session).single {it.tag=="session-clock"};val duration=views(session).single {it.tag=="session-duration"}
                    check(clock.width>0 && duration.width>0 && clock.right<=duration.left)
                }
                check(views(historyCard).filterIsInstance<TextView>().any {it.text.toString()=="Páginas 4 → 5 · 1 página"})
                check(views(historyCard).filterIsInstance<TextView>().none {it.text.toString().contains("sessão(ões)")})
                val dayToggle=views(historyCard).first {it.contentDescription?.toString()?.startsWith("Leituras de ")==true}.contentDescription.toString()
                all().first {it.contentDescription?.toString()==dayToggle}.performClick()
                check(views(all().single {it.tag=="reading-history-card"}).count {it.tag=="reading-history-session"}==3)
                all().first {it.contentDescription?.toString()==dayToggle}.performClick()

                text("Ver mais dias").performClick()
                historyCard=all().single {it.tag=="reading-history-card"}
                check(views(historyCard).count {it.tag=="reading-history-day"}==5)
                check(currentRepo.sessions(currentBook.id)==historySessions.sortedByDescending {it.finishedAt})
                tab("Estante").performClick();check(text("Estante").isShown);check(tab("Estante").isSelected)
                check(!tab("Livro atual").isSelected);check(currentRepo.currentId()==currentBook.id)
                tab("Livro atual").performClick();check(text("Leitura do menu").isShown)
                openAccountMenu(test,activity);tab("Estante").performClick()
                check(text("Estante").isShown && tab("Estante").isSelected)
                check(currentRepo.currentId()==currentBook.id)

                val statsBook=br.com.centralcasa.books.LibraryBook(id="statistics-book",title="Livro de teste",author="Autora",genre="Fantasia",subgenres=listOf("Épica"),totalPages=100,currentPage=40)
                val statsComic=br.com.centralcasa.books.LibraryBook(id="statistics-comic",title="HQ de teste",kind="comic",totalPages=100,currentPage=17)
                currentRepo.saveCollection(br.com.centralcasa.books.CollectionTitle(id="statistics-manga-title",title="Mangá sem páginas",kind="manga"))
                val statsManga=br.com.centralcasa.books.LibraryBook(id="statistics-manga",title="Mangá sem páginas",kind="manga",collectionId="statistics-manga-title",totalPages=1,pagesKnown=false,readFinished=true)
                listOf(statsBook,statsComic,statsManga).forEach(currentRepo::save)
                tab("Estatísticas").performClick()
                fun pageMetric()=(all().single {it.tag=="statistics-pages"} as TextView).text.toString()
                check(pageMetric()=="57")
                check(all().filterIsInstance<TextView>().none {it.text.toString().contains("Sua leitura em números")})
                all().single {it.tag=="statistics-filter:manga"}.performClick();check(pageMetric()=="0")
                check(all().filterIsInstance<TextView>().none {it.text.toString().contains("sem páginas informadas.") || it.text.toString().contains("Ritmo calculado")})
                all().single {it.tag=="statistics-filter:comic"}.performClick();check(pageMetric()=="17")
                all().single {it.tag=="statistics-filter:book"}.performClick();check(pageMetric()=="40")
                all().single {it.tag=="statistics-filter:all"}.performClick();check(pageMetric()=="57")
                check(all().single {it.tag=="statistics-filter:all"}.isSelected)
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"statistics")
                check(all().single {it.tag=="statistics-distribution:stats"}.visibility==View.GONE)
                all().single {it.tag=="statistics-toggle:stats"}.performClick()
                all().single {it.tag=="statistics-toggle:classification-tag"}.performClick()
                check(text("Fantasia").isShown && text("Épica").isShown)
                // Measure real native distribution cards at phone width: trailing counts must not consume weighted labels/bars.
                all().filter {it.tag?.toString()?.startsWith("statistics-distribution:")==true}.forEach {card->
                    val width=DisplayUi(activity).dp(320)
                    card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    card.layout(0,0,width,card.measuredHeight)
                    val names=views(card).filter {it.tag=="statistics-classification-name"}
                    check(names.isNotEmpty() && names.all {it.width>0})
                    check(views(card).filterIsInstance<ProgressBar>().all {it.width>0})
                    check(views(card).filter {it.tag=="statistics-classification-count"}.all {it.width in 1 until width/3})
                }

                val periodSession=br.com.centralcasa.books.ReadingSession(id="period-session",bookId=statsBook.id,startedAt=1000,finishedAt=61000,durationMs=60000,startPage=0,endPage=20,readingDay="2026-11-01")
                currentRepo.importSnapshot(listOf(statsBook),listOf(periodSession),currentBook.id)
                currentRepo.addDates(statsManga.id,listOf("2026-11-01"))
                val statisticsField=ViblioActivity::class.java.getDeclaredField("statistics").apply {isAccessible=true}
                val statistics=statisticsField.get(activity) as br.com.centralcasa.ui.BooksPanel
                br.com.centralcasa.ui.BooksPanel::class.java.getDeclaredField("readingPeriodAnchor").apply {isAccessible=true}.set(statistics,"2026-11-01")
                all().single {it.tag=="statistics-periods"}.performClick()
                fun periodPages()=(all().single {it.tag=="period-metric:1"} as TextView).text.toString()
                listOf("DAY","WEEK","MONTH").forEach {unit->all().single {it.tag=="period-unit:$unit"}.performClick();check(periodPages()=="20")}
                check((all().single {it.tag=="period-metric:0"} as TextView).text.toString()=="2")
                all().single {it.tag=="period-unit:YEAR"}.performClick();check(periodPages()=="25")
                check(all().single {it.tag=="period-unit:YEAR"}.background==null)
                captureFixture(activity,activity.findViewById<ScrollView>(br.com.centralcasa.R.id.content_scroll).getChildAt(0),"periods")
                all().single {it.tag=="statistics-summary"}.performClick()
                listOf(statsBook,statsComic,statsManga,currentBook).forEach {currentRepo.delete(it.id)}
                currentRepo.deleteCollection("statistics-manga-title")
                currentRepo.close()
                tab("Estante").performClick()

                openAccountMenu(test,activity);check(all().none {it is EditText});check(all().filterIsInstance<TextView>().none {it.text.toString() in listOf("Entrar","Home Assistant")})
                text("Continuar na Estante").performClick();check(tab("Estante").isSelected)
                tab("Estatísticas").performClick();check(text("Seu ritmo").isShown);check(all().any {it.tag=="statistics-pages"})
                all().first { it.contentDescription?.toString()=="Voltar à estante" }.performClick();check(tab("Estante").isSelected)
                check(all().count { it.contentDescription?.toString()=="Estatísticas" }==1)
                tab("Pesquisa").performClick();check(text("+ Cadastrar").isShown);check(text("Conectar catálogo").isShown);check(all().filterIsInstance<EditText>().single().hint.toString()=="Título, autor ou ISBN")
                tab("Estante").performClick();check(text("Estante").isShown)
            }
            test.checkedMain {
                val variants=listOf(CatalogCover("primary","https://example.test/primary.jpg",true),CatalogCover("second","https://example.test/second.jpg",false))
                var chosen:CatalogCover?=null
                val dialog=br.com.viblio.ui.CoverGalleryDialog.show(activity,variants) { chosen=it }
                val buttons=views(dialog.window!!.decorView).filter { it.tag?.toString()?.startsWith("catalog-cover:")==true }
                check(views(dialog.window!!.decorView).filter {it.tag=="catalog-cover-row"}.all {(it as LinearLayout).childCount==3});check(buttons.size==2);buttons[1].performClick();check(chosen==variants[1]);check(!dialog.isShowing)
            }
            test.checkedMain {
                val first=CatalogCover("one","https://example.test/one.jpg",true)
                val later=CatalogCover("later","https://example.test/later.jpg",false,publisher="Editora teste",isbn="9780000000001",pages=300)
                var selectedCover:CatalogCover?=null
                val dialog=br.com.viblio.ui.CoverGalleryDialog.show(activity,listOf(first,first.copy(id="duplicate")),more={cursor,done->
                    check(cursor==30);done(Result.success(CatalogCoverPage("fixture",listOf(later))))
                },nextCursor=30){selectedCover=it}
                fun buttons()=views(dialog.window!!.decorView).filter {it.tag?.toString()?.startsWith("catalog-cover:")==true}
                check(buttons().size==1)
                views(dialog.window!!.decorView).filterIsInstance<TextView>().single {it.text.toString()=="Carregar mais capas"}.performClick()
                check(buttons().size==2)
                check(views(dialog.window!!.decorView).filterIsInstance<TextView>().any {it.text.toString().contains("Editora teste")})
                buttons().last().performClick();check(selectedCover==later)
            }
            val requests=mutableListOf<Triple<String,String,(Result<List<CatalogBook>>)->Unit>>()
            val isbnRequests=mutableListOf<String>();var scanLaunches=0
            val fake=object:BookCatalog {
                override val configured=true
                override fun configure() {}
                override fun saved(book:br.com.centralcasa.books.LibraryBook) {}
                override fun loadCover(book:CatalogBook,done:(Result<String>)->Unit) { error("No covers requested by fake rows") }
                override fun search(title:String,author:String,isbn:String,done:(Result<List<CatalogBook>>)->Unit) { requests+=Triple(title,author,done);isbnRequests+=isbn }
            }
            lateinit var panel:SearchPanel;lateinit var parent:LinearLayout
            var chosen:CatalogBook?=null
            test.checkedMain {
                parent=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                activity.setContentView(parent)
                panel=SearchPanel(activity,DisplayUi(activity),fake,scan={scanLaunches++}) { chosen=it }
                panel.render(parent);views(parent).single {it.tag=="search-scan-isbn"}.performClick();check(scanLaunches==1);views(parent).filterIsInstance<EditText>().single().setText("Mistborn")
            }
            Thread.sleep(650);test.waitForIdleSync()
            val row=CatalogBook("9007199254740993","test","O Império Final",saga="Mistborn",sequence=1,publisher="Trama")
            test.checkedMain {
                check(requests.size==2 && requests[0].first=="Mistborn" && requests[1].second=="Mistborn")
                requests[0].third(Result.success(listOf(row)))
                // A delayed author request must not delay the title result or its cover.
                check(text("O Império Final").isShown)
                check(requests.size==2 && requests[1].second=="Mistborn")
                requests[1].third(Result.success(listOf(row)))
                check(views(parent).filterIsInstance<TextView>().count { it.text.toString()=="Adicionar à Estante" }==1)
                check(views(parent).filterIsInstance<TextView>().any { it.text.toString().contains("Livro 1") })
                views(parent).single {it.tag=="search-book:${row.id}"}.performClick()
                val windows=Class.forName("android.view.WindowManagerGlobal");val instance=windows.getDeclaredMethod("getInstance").invoke(null)
                val roots=(windows.getDeclaredField("mViews").apply {isAccessible=true}.get(instance) as List<*>).filterIsInstance<View>().flatMap(::views)
                check(roots.any {it.tag=="book-info"})
                roots.filterIsInstance<Button>().single {it.id==android.R.id.button1}.performClick()
            }
            test.waitForIdleSync()
            test.checkedMain {
                check(chosen==row)
                text("Adicionar à Estante").performClick();check(chosen==row)
                text("Mangás").performClick();check(text("Nenhum resultado neste filtro. Você pode cadastrar manualmente.").isShown)
                views(parent).filterIsInstance<EditText>().single().setText("Outro")
            }
            Thread.sleep(650);test.waitForIdleSync()
            test.checkedMain {
                check(requests.size==4)
                panel.hide();requests[2].third(Result.success(listOf(row)))
                requests[3].third(Result.success(listOf(row)))
                check(requests.size==4) // Hidden screen must not issue the second request or draw stale results.
                panel.acceptIsbn("9780134685991");parent.removeAllViews();panel.render(parent)
            }
            Thread.sleep(650);test.waitForIdleSync()
            test.checkedMain {
                check(requests.size==5 && isbnRequests.last()=="9780134685991")
                check(requests.last().first.isEmpty() && requests.last().second.isEmpty())
                requests.last().third(Result.success(emptyList()))
                check(requests.size==6 && isbnRequests.last()=="0134685997")
                requests.last().third(Result.success(emptyList()))
                check(views(parent).filterIsInstance<TextView>().any {it.text.toString().contains("cadastrar manualmente")})
                panel.close()
            }
            SearchCoverChecks.run(test,activity)
            ManualShelfChecks.run(test,activity)
            CollectionInventoryChecks.run(test,activity)
            SpineIsbnChecks.run(test,activity)
            SpineMaterialChecks.run(test,activity)
            FriendsChecks.run(test,activity)
        } finally { test.checkedMain { activity.finish() } }
    }
}

/** Synthetic emulator fixtures only. Captures native drawing for visual inspection. */
internal fun captureFixture(activity:android.app.Activity,view:View,name:String){
    val width=DisplayUi(activity).dp(360)
    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,width,view.measuredHeight)
    val bitmap=android.graphics.Bitmap.createBitmap(width,view.measuredHeight,android.graphics.Bitmap.Config.ARGB_8888)
    val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(DisplayUi(activity).background);view.draw(canvas)
    val directory=activity.getExternalFilesDir("validation")!!;directory.mkdirs()
    java.io.File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
}
