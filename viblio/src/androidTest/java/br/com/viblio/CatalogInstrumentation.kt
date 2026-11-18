package br.com.viblio

import android.app.Instrumentation
import android.os.Bundle
import br.com.centralcasa.books.*
import br.com.viblio.catalog.*
import java.io.ByteArrayInputStream

/** Isolated native SQLite/JSON checks; does not modify either application's actual library. */
class CatalogInstrumentation:Instrumentation() {
    private var iconOnly=false
    private var readingOnly=false
    private var syncOnly=false
    private var goalsOnly=false
    private var themeOnly=false
    private var socialOnly=false
    private var friendsOnly=false
    private var apiAddress=""
    private var apiToken=""
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);iconOnly=arguments?.getString("iconOnly")=="true";socialOnly=arguments?.getString("socialOnly")=="true";readingOnly=arguments?.getString("readingOnly")=="true";syncOnly=arguments?.getString("syncOnly")=="true";goalsOnly=arguments?.getString("goalsOnly")=="true";themeOnly=arguments?.getString("themeOnly")=="true";friendsOnly=arguments?.getString("friendsOnly")=="true";apiAddress=arguments?.getString("apiAddress").orEmpty();apiToken=arguments?.getString("apiToken").orEmpty();start() }
    override fun onStart() {
        val report=Bundle()
        try {
            if(iconOnly){LauncherIconChecks.run(this);report.putString("stream","PASS: launcher book scales proportionally; background unchanged\n");finish(-1,report);return}
            if(socialOnly){SocialNetworkChecks.run(this);SocialChecks.run(this);CatalogEntryChecks.run(this);report.putString("stream","PASS: social feed, sharing, directory requests, clubs, discussion and lifecycle\n");finish(-1,report);return}
            if(readingOnly){ManualReadingChecks.run(this);report.putString("stream","PASS: reading total from saved sessions, corrections and completion\n");finish(-1,report);return}
            if(syncOnly){QueuedSyncChecks.run(this);report.putString("stream","PASS: queued sync during identity/upload and foreground return\n");finish(-1,report);return}
            if(goalsOnly){GoalsChecks.run(this);report.putString("stream","PASS: private reading goals, native forms, counters and purchase cycles\n");finish(-1,report);return}
            if(themeOnly){ReadingThemeChecks.run(this);report.putString("stream","PASS: five reading palettes and system bars\n");finish(-1,report);return}
            if(friendsOnly){
                val activity=startActivitySync(android.content.Intent(targetContext,ViblioActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                try {FriendsChecks.run(this,activity)}finally {checkedMain {activity.finish()}}
                report.putString("stream","PASS: isolated Friends lifecycle checks\n");finish(-1,report);return
            }
            val legacyName="catalog-legacy-validation.db";targetContext.deleteDatabase(legacyName)
            val old=targetContext.openOrCreateDatabase(legacyName,0,null)
            old.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,genre TEXT NOT NULL,total_pages INTEGER NOT NULL,current_page INTEGER NOT NULL,cover TEXT NOT NULL,background TEXT NOT NULL,updated_at INTEGER NOT NULL,sequence_number INTEGER,saga TEXT NOT NULL DEFAULT '',subgenre TEXT NOT NULL DEFAULT '',subgenres TEXT NOT NULL DEFAULT '[]')")
            old.execSQL("CREATE TABLE preferences(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            old.execSQL("INSERT INTO books(id,title,author,genre,total_pages,current_page,cover,background,updated_at) VALUES ('legacy','Antigo','Autor','Fantasia Dark',100,10,'','black',0)")
            old.version=6;old.close()
            val migrated=LibraryRepository(targetContext,legacyName)
            check(migrated.books().single().id=="legacy" && migrated.book("legacy")!!.currentPage==10)
            migrated.close();targetContext.deleteDatabase(legacyName)
            val name="catalog-validation.db";targetContext.deleteDatabase(name)
            var repo=LibraryRepository(targetContext,name)
            val book=LibraryBook(id="validation",title="Livro",totalPages=100,currentPage=0,rating=4.5)
            repo.save(book);repo.start(book.id);repo.pause();repo.finish(12)
            repo.setCatalogBinding(book.id,"9007199254740993","source")
            val sessions=repo.sessions();repo.save(book.copy(currentPage=12,title="Nome pessoal"))
            check(repo.book(book.id)?.catalogId=="9007199254740993")
            check(repo.sessions()==sessions && repo.book(book.id)?.currentPage==12)
            val images=BookImages(targetContext)
            val json=LibraryJson(images);val backup=json.write(repo.books(),repo.sessions(),repo.currentId(),repo.dates(),repo.collections())
            check(json.read(ByteArrayInputStream(backup.toByteArray())).books.single().catalogSource=="source")
            repo.close();repo=LibraryRepository(targetContext,name)
            check(repo.book(book.id)?.catalogId=="9007199254740993" && repo.book(book.id)?.rating==4.5)
            repo.delete(book.id);check(repo.book(book.id)==null);repo.close();targetContext.deleteDatabase(name)
            // Viblio-only queue records are preserved by a second connection and cleared conditionally.
            val store=CatalogStore(targetContext)
            val id="instrumentation-only";val pending=PendingBook(id,"{\"request_id\":\"sample\"}","manual.jpg","source")
            store.queue(pending);check(store.rows().any { it==pending })
            store.complete(pending.copy(metadata="stale"));check(store.rows().any { it==pending })
            store.complete(pending);check(store.rows().none { it.id==id });store.close()
            if(apiAddress.isNotBlank()) {
                val api=br.com.centralcasa.books.catalog.CatalogApi(apiAddress,apiToken)
                check(api.health().isNotBlank())
                val found=api.search("sabio").single { it.id=="9007199254740993" }
                check(found.pages==992 && found.author=="Patrick Rothfuss")
                val filled=found.fill(book);check(filled.id==book.id && filled.totalPages==992)
                val bitmap=android.graphics.Bitmap.createBitmap(32,48,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
                val output=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,output);bitmap.recycle()
                val manual=images.save(ByteArrayInputStream(output.toByteArray()))
                val metadata=com.google.gson.JsonObject().apply { addProperty("request_id",java.util.UUID.randomUUID().toString());addProperty("title","Native smoke "+java.util.UUID.randomUUID());addProperty("pages",100) }.toString()
                val response=api.write(metadata,images.file(manual));check(response.first.id.toLongOrNull()!=null)
                val received=api.image(response.first.coverUrl,images::save)
                check(images.thumbnail(received)!=null && images.file(received)!!.length()>0)
                images.file(manual)?.delete();images.file(received)?.delete()
            }
            val missing=LibraryRepository(targetContext,"viblio_test_missing_covers.db")
            try {
                missing.save(LibraryBook(id="missing",title="Sem capa",totalPages=100,currentPage=12,rating=4.5,catalogId="123",catalogSource="test-source"))
                check(!missing.fillMissingCover("missing","123","wrong-source","auto.jpg"))
                check(missing.fillMissingCover("missing","123","test-source","auto.jpg"))
                missing.save(requireNotNull(missing.book("missing")).copy(cover="personal.jpg"))
                check(!missing.fillMissingCover("missing","123","test-source","other.jpg"))
                check(missing.book("missing")!!.let {it.cover=="personal.jpg" && it.currentPage==12 && it.rating==4.5})
            }finally {missing.close();targetContext.deleteDatabase("viblio_test_missing_covers.db")}
            QueuedSyncChecks.run(this)
            NavigationChecks.run(this)
            SocialChecks.run(this)
            CatalogEntryChecks.run(this)
            ManualReadingChecks.run(this)
            ReadingThemeChecks.run(this)
            ScannerLifecycleChecks.run(this)
            DeepLinkChecks.run(this)
            GoalsChecks.run(this)
            AccountProfileChecks.run(this)
            if(apiAddress.isNotBlank())AccountFlowChecks.run(this,apiAddress)
            report.putString("stream","PASS: search thumbnail HTTP download/Android decode/display/cache, native account forms, invitation registration/sync/logout/login, isolated profiles, archive/restore and lossless JPEG snapshots, SQLite catalogue binding, IDs above 2^53, personal edits, reading sessions/progress/rating, JSON backup, persistence, durable outbox conditional completion. ${if(apiAddress.isNotBlank()) "Android HTTP search/fill/manual JPEG upload/automatic cover load also passed against isolated PostgreSQL API." else "Network validation was not requested."}\n")
            finish(-1,report)
        } catch(error:Throwable) { report.putString("stream","FAIL: ${error.message}\n${error.stackTraceToString()}\n");finish(0,report) }
    }
}
