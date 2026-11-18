package br.com.viblio
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import br.com.centralcasa.books.LibraryRepository
import br.com.centralcasa.books.LibraryBook

object DeepLinkChecks {
    fun run(test:Instrumentation) {
        val repo=LibraryRepository(test.targetContext)
        check(repo.books().isEmpty()) { "Deep link fixtures require an empty emulator library." }
        val book=LibraryBook(id="viblio-deeplink-fixture",title="Leitura atual pelo dashboard",totalPages=100,currentPage=20)
        var activity:ViblioActivity?=null
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        try {
            repo.save(book);repo.setCurrent(book.id)
            val incoming=Intent(Intent.ACTION_VIEW,Uri.parse("viblio://current")).setPackage(test.targetContext.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            check(test.targetContext.packageManager.resolveActivity(incoming,0)?.activityInfo?.name==ViblioActivity::class.java.name)
            activity=test.startActivitySync(incoming) as ViblioActivity
            test.checkedMain { views(activity.window.decorView).filterIsInstance<TextView>().firstOrNull {it.text.toString()=="Usar Estante offline"}?.performClick() }
            test.checkedMain { check(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString()==book.title }) }
            test.checkedMain { activity.onNewIntent(incoming);check(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString()==book.title }) }
        } finally {
            activity?.let { test.checkedMain { it.finish() } }
            val deadline=System.currentTimeMillis()+30000
            while(activity?.isDestroyed==false && System.currentTimeMillis()<deadline){Thread.sleep(50);test.waitForIdleSync()}
            check(activity==null || activity.isDestroyed) {"Deep link Activity must be destroyed before profile fixtures."}
            repo.delete(book.id);repo.close()
        }
    }
}
