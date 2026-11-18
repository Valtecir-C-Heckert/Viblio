package br.com.viblio
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import br.com.centralcasa.books.*
import br.com.viblio.account.AccountLibrary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
object AccountProfileChecks {
    fun run(test:Instrumentation) {
        test.waitForIdleSync();val c=test.targetContext;val profiles=c.getSharedPreferences("viblio_profiles",0);check(profiles.all.isEmpty())
        val prefs=c.getSharedPreferences("panel_settings",0);val previous=prefs.all.toMap();val repo=LibraryRepository(c)
        check(repo.books(true).isEmpty() && repo.collections(true).isEmpty()) {"Requires empty emulator library"}
        val folder=File(c.filesDir,"book-covers");val before=folder.listFiles()?.map {it.name}?.toSet().orEmpty()
        val safety=File(c.filesDir,"account-safety");check(safety.listFiles().isNullOrEmpty())
        val bitmap=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
        val output=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,95,output);bitmap.recycle()
        val cover=BookImages(c).save(ByteArrayInputStream(output.toByteArray()));val book=LibraryBook(id="account-fixture",title="Meu acervo",totalPages=100,cover=cover,rating=4.5)
        val a=LibraryProfiles.key("fixture","a");val b=LibraryProfiles.key("fixture","b")
        try {
            repo.save(book);repo.start(book.id);repo.pause();repo.finish(12);repo.addDates(book.id,listOf("2026-10-17"))
            prefs.edit().putString("SHELF_MANUAL_ORDER:book","[\"${book.id}\"]").putString("SERVER_TOKEN","must-not-export").putString("SHELF_SPINE_ZOOM","0.75").commit()
            LibraryProfiles.select(c,a,true);check(LibraryProfiles.database(c)=="library.db")
            repo.archive(book.id);check(repo.books().isEmpty() && repo.books(true).single().archived && repo.sessions(book.id).size==1)
            repo.archive(book.id,removed=false);repo.setCurrent(book.id)
            val io=AccountLibrary(c);val snapshot=io.capture();val original=BookImages(c).file(cover)!!.readBytes();check(!snapshot.getAsJsonObject("appearance").has("SERVER_TOKEN"))
            check(!snapshot.getAsJsonObject("appearance").has("SHELF_SPINE_ZOOM"))
            val hash=io.hash(snapshot);snapshot.getAsJsonObject("appearance").addProperty("SHELF_SPINE_ZOOM","1.15")
            check(io.hash(snapshot)==hash)
            LibraryProfiles.select(c,b,false);val other=LibraryRepository(c)
            try {
                check(other.books(true).isEmpty() && BookImages(c).file(cover)==null)
                check(c.getSharedPreferences(LibraryProfiles.settings(c),0).getString("SHELF_MANUAL_ORDER:book","")=="")
                val otherPrefs=c.getSharedPreferences(LibraryProfiles.settings(c),0);otherPrefs.edit().putString("SHELF_SPINE_ZOOM","1.25").commit()
                other.save(book.copy(cover="",rating=1.0));io.apply(snapshot)
                check(otherPrefs.getString("SHELF_SPINE_ZOOM",null)=="1.25")
                val otherIo=AccountLibrary(c);val legacy=otherIo.capture();legacy.getAsJsonObject("appearance").addProperty("SHELF_SPINE_ZOOM","1.15");otherIo.apply(legacy)
                check(otherPrefs.getString("SHELF_SPINE_ZOOM",null)=="1.25")
                check(other.book(book.id)?.rating==1.0 && other.book(book.id)?.currentPage==0)
            }finally {other.close()}
            LibraryProfiles.select(c,a,false);io.apply(snapshot)
            check(prefs.getString("SHELF_SPINE_ZOOM",null)=="0.75")
            val restored=repo.book(book.id)!!;check(restored.currentPage==12 && restored.rating==4.5)
            check(BookImages(c).file(restored.cover)!!.readBytes().contentEquals(original));check(repo.sessions(book.id).size==1 && repo.dates(book.id).size==1)
            check(io.hash(io.capture())==io.hash(snapshot))
            LibraryProfiles.anonymous(c);val guest=LibraryRepository(c);try {check(guest.books(true).isEmpty())}finally {guest.close()}
            LibraryProfiles.select(c,a,false);check(repo.book(book.id)?.rating==4.5)
        }finally {
            LibraryProfiles.select(c,a,false);repo.delete(book.id);repo.close();c.deleteDatabase("account-$b.db");c.deleteDatabase("guest-library.db");profiles.edit().clear().commit()
            c.getSharedPreferences("panel_settings_account-$b",0).edit().clear().commit()
            val edit=prefs.edit().clear();previous.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Boolean->edit.putBoolean(k,v);is Float->edit.putFloat(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}};edit.commit()
            File(c.filesDir,"account-safety_account-$b").deleteRecursively()
            folder.listFiles()?.filter {it.name !in before}?.forEach {it.delete()};safety.deleteRecursively()
        }
    }
}
