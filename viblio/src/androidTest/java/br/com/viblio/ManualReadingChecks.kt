package br.com.viblio

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import br.com.centralcasa.books.*

/** Page-only correction, timed manual registration and persisted statuses on actual native views. */
object ManualReadingChecks {
    fun run(test:Instrumentation){
        val context=test.targetContext;val repo=LibraryRepository(context);check(repo.books().isEmpty())
        val book=LibraryBook(id="manual-reading-fixture",title="Registro de leitura",totalPages=100)
        repo.save(book);repo.setCurrent(book.id)
        val activity=test.startActivitySync(Intent(context,ViblioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
        fun all()=views(activity.window.decorView)
        fun roots():List<View>{val type=Class.forName("android.view.WindowManagerGlobal");val instance=type.getDeclaredMethod("getInstance").invoke(null);return (type.getDeclaredField("mViews").apply {isAccessible=true}.get(instance) as List<*>).filterIsInstance<View>().flatMap(::views)}
        fun total()=(all().single {it.tag=="book-reading-total"} as TextView).text.toString()
        fun totalLabel()=(all().single {it.tag=="book-reading-total-label"} as TextView).text.toString()
        fun status()=all().filterIsInstance<TextView>().single {it.tag=="reading-status"}.text.toString()
        fun destination()=roots().single {it.tag=="reading-page"} as EditText
        fun page(value:Int){val slider=all().filterIsInstance<SeekBar>().single();val listener=SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener").apply {isAccessible=true}.get(slider) as SeekBar.OnSeekBarChangeListener;listener.onStartTrackingTouch(slider);slider.progress=value;listener.onStopTrackingTouch(slider)}
        try {
            test.checkedMain {all().filterIsInstance<TextView>().single {it.text.toString()=="Usar Estante offline"}.performClick();all().single {it is LinearLayout && it.contentDescription?.toString()=="Livro atual"}.performClick();check(status()=="Não iniciado");check(total()=="—" && totalLabel()=="Tempo total de leitura");page(23)}
            test.waitForIdleSync()
            test.checkedMain {
                check(destination().text.toString()=="23")
                destination().setText("");roots().filterIsInstance<Button>().single {it.id==android.R.id.button1}.performClick()
                check(destination().error!=null && repo.book(book.id)?.currentPage==0 && repo.sessions(book.id).isEmpty())
                destination().setText("101");roots().filterIsInstance<Button>().single {it.id==android.R.id.button3}.performClick()
                check(destination().error!=null && repo.book(book.id)?.currentPage==0 && repo.sessions(book.id).isEmpty())
                destination().setText("20");(roots().single {it.tag=="reading-minutes"} as EditText).setText("25")
                roots().filterIsInstance<Button>().single {it.id==android.R.id.button1}.performClick()
            }
            test.waitForIdleSync()
            test.checkedMain {val session=repo.sessions(book.id).single();check(session.startPage==0 && session.endPage==20 && session.durationMs==1_500_000L);check(repo.book(book.id)?.currentPage==20);check(status()=="Pausado");check(total()=="25 min");page(35)}
            test.waitForIdleSync()
            test.checkedMain {destination().setText("30");roots().filterIsInstance<Button>().single {it.id==android.R.id.button3}.performClick()}
            test.waitForIdleSync()
            test.checkedMain {check(repo.book(book.id)?.currentPage==30 && repo.sessions(book.id).size==1);page(12)}
            test.waitForIdleSync()
            test.checkedMain {
                check(destination().text.toString()=="12");destination().setText("10")
                roots().filterIsInstance<Button>().single {it.id==android.R.id.button1}.performClick()
                check(repo.book(book.id)?.currentPage==10 && repo.sessions(book.id).size==1);check(total()=="25 min")
                page(40)
            }
            test.waitForIdleSync()
            test.checkedMain {destination().setText("33");roots().filterIsInstance<Button>().single {it.id==android.R.id.button2}.performClick();check(repo.book(book.id)?.currentPage==10 && repo.sessions(book.id).size==1)}
            check(runCatching {repo.recordReading(book.id,9,40,60000)}.isFailure)
            check(repo.book(book.id)?.currentPage==10 && repo.sessions(book.id).size==1)
            test.checkedMain {page(100)}
            test.waitForIdleSync()
            test.checkedMain {(roots().single {it.tag=="reading-minutes"} as EditText).setText("50");roots().filterIsInstance<Button>().single {it.id==android.R.id.button1}.performClick()}
            test.waitForIdleSync()
            test.checkedMain {
                check(total()=="1h 15min" && totalLabel()=="Tempo para concluir")
                check(repo.sessions(book.id).sumOf {it.durationMs}==4_500_000L && status()=="Concluído")
                captureFixture(activity,all().single {it.tag=="book-reading-rhythm"},"book-reading-total")
                all().single {it.contentDescription?.toString()?.startsWith("Histórico de leitura ·")==true}.performClick()
                all().first {it.contentDescription=="Editar leitura · 90 páginas"}.performClick()
                (roots().single {it.tag=="session-edit-minutes"} as EditText).setText("30")
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button2}.performClick()
                check(total()=="1h 15min")
            }
            val beforeEdit=repo.sessions(book.id).single {it.pages==90}
            test.checkedMain {
                all().first {it.contentDescription=="Editar leitura · 90 páginas"}.performClick()
                (roots().single {it.tag=="session-edit-minutes"} as EditText).setText("60")
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button1}.performClick()
                check(repo.sessions(book.id).single {it.pages==90}==beforeEdit)
                (roots().single {it.tag=="session-edit-minutes"} as EditText).setText("30")
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button1}.performClick()
                check(total()=="55 min" && repo.book(book.id)?.currentPage==100)
                check(runCatching {repo.editReadingSession(beforeEdit,"2026-11-10",60000)}.isFailure)
                all().first {it.contentDescription=="Editar leitura · 20 páginas"}.performClick()
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button3}.performClick()
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button2}.performClick()
            }
            test.waitForIdleSync()
            test.checkedMain {
                check(repo.sessions(book.id).size==2)
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button3}.performClick()
            }
            test.waitForIdleSync()
            test.checkedMain {roots().filterIsInstance<Button>().last {it.id==android.R.id.button1}.performClick()}
            test.waitForIdleSync()
            test.checkedMain {
                check(repo.sessions(book.id).size==1 && total()=="30 min")
                check(repo.book(book.id)?.currentPage==100 && status()=="Concluído")
                val remaining=repo.sessions(book.id).single()
                repo.editReadingSession(remaining,"2026-11-10",remaining.durationMs)
                check(repo.sessions(book.id).single().let {it.readingDay=="2026-11-10" && it.startedAt==remaining.startedAt && it.finishedAt==remaining.finishedAt})
                check(runCatching {repo.deleteReadingSession(remaining)}.isFailure)
            }
            val saved=repo.sessions(book.id)
            test.checkedMain {repo.updateProgress(book.id,60);repo.start(book.id);page(70)}
            test.waitForIdleSync()
            test.checkedMain {
                check(repo.draft()?.runningSince==null);destination().setText("65")
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button2}.performClick()
            }
            test.waitForIdleSync()
            test.checkedMain {
                check(repo.book(book.id)?.currentPage==60 && repo.draft()?.runningSince!=null && repo.draft()?.startPage==60){"After cancel: page=${repo.book(book.id)?.currentPage}, draft=${repo.draft()}"}
                check(repo.sessions(book.id)==saved);repo.pause();page(75)
            }
            test.waitForIdleSync();val paused=repo.draft()
            test.checkedMain {
                destination().setText("0");roots().filterIsInstance<Button>().last {it.id==android.R.id.button3}.performClick()
                check(destination().error!=null && repo.book(book.id)?.currentPage==60 && repo.draft()==paused)
                destination().setText("80");roots().filterIsInstance<Button>().last {it.id==android.R.id.button3}.performClick()
                check(repo.book(book.id)?.currentPage==80 && repo.draft()==paused && repo.sessions(book.id)==saved)
                page(85)
            }
            test.waitForIdleSync()
            test.checkedMain {
                repo.updateProgress(book.id,82)
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button3}.performClick()
                check(destination().error!=null && repo.book(book.id)?.currentPage==82 && repo.draft()==paused)
                roots().filterIsInstance<Button>().last {it.id==android.R.id.button2}.performClick()
                check(repo.sessions(book.id)==saved)
            }
        }finally {test.checkedMain {if(repo.draft()?.bookId==book.id)repo.discard();activity.finish();repo.delete(book.id);repo.close()}}
    }
}
