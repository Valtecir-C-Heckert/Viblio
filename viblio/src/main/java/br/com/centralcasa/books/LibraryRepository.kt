package br.com.centralcasa.books

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/** Local source of truth. A future server adapter can exchange snapshots without coupling UI to transport. */
class LibraryRepository(private val context: Context, private val databaseName: String = LibraryProfiles.database(context)) : SQLiteOpenHelper(context.applicationContext ?: context, databaseName, null, 11) {
    private val closedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lease: SharedLease = acquire(context.applicationContext ?: context, databaseName)

    override fun getReadableDatabase(): SQLiteDatabase = lease.helper.readableDatabase
    override fun getWritableDatabase(): SQLiteDatabase = lease.helper.writableDatabase
    override fun close() {
        if (closedFlag.compareAndSet(false, true)) {
            release(databaseName, lease)
        }
    }
    override fun onCreate(db: SQLiteDatabase) = lease.helper.onCreate(db)
    override fun onConfigure(db: SQLiteDatabase) = lease.helper.onConfigure(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = lease.helper.onUpgrade(db, oldVersion, newVersion)

    fun hasReadOnDay(day: String): Boolean {
        ReadingDays.validate(day)
        val hasDate = readableDatabase.rawQuery(
            "SELECT 1 FROM reading_dates d JOIN books b ON b.id=d.book_id WHERE b.archived=0 AND d.day=? LIMIT 1",
            arrayOf(day),
        ).use { it.moveToFirst() }
        if (hasDate) return true
        val hasTaggedSession = readableDatabase.rawQuery(
            "SELECT 1 FROM sessions s JOIN books b ON b.id=s.book_id WHERE b.archived=0 AND s.reading_day=? LIMIT 1",
            arrayOf(day),
        ).use { it.moveToFirst() }
        if (hasTaggedSession) return true
        return readableDatabase.rawQuery(
            "SELECT s.started_at FROM sessions s JOIN books b ON b.id=s.book_id WHERE b.archived=0 AND s.reading_day IS NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                if (ReadingDays.at(c.getLong(0)) == day) return@use true
            }
            false
        }
    }

    fun collections(includeArchived:Boolean=false): List<CollectionTitle> = readableDatabase.rawQuery("SELECT * FROM collections"+(if(includeArchived) "" else " WHERE archived=0")+" ORDER BY title COLLATE NOCASE", null).use { c -> buildList {
        while (c.moveToNext()) add(CollectionTitle(c.string("id"), c.string("kind"), c.string("title"), c.string("author"), c.string("genre"), c.string("publisher"), if (c.isNull(c.getColumnIndexOrThrow("missing_volumes"))) null else c.number("missing_volumes").toInt(), c.number("source_read_count").toInt(), c.optionalDouble("source_rating"), c.optionalDouble("rating"), c.string("cover"), c.number("updated_at"), org.json.JSONArray(c.string("subgenres")).let { a -> (0 until a.length()).map { a.getString(it) } }, c.number("archived")!=0L, c.getColumnIndex("expected_volumes").takeIf {it>=0 && !c.isNull(it)}?.let(c::getInt) ))
    } }.let { titles -> val owned=books(true);titles.map { title->
        title.copy(missingVolumes=if(title.expectedVolumes==null)null else CollectionInventory.of(title,owned).missing)
    } }
    fun saveCollection(input: CollectionTitle) {
        transaction {
            val c = input.copy(expectedVolumes=CollectionInventory.expected(input,books(true))).validated()
            val values = ContentValues().apply {
                put("archived", if(c.archived)1 else 0); put("id", c.id); put("kind", c.kind); put("title", c.title); put("author", c.author); put("genre", c.genre); put("publisher", c.publisher)
                if (c.missingVolumes == null) putNull("missing_volumes") else put("missing_volumes", c.missingVolumes)
                if(c.expectedVolumes==null)putNull("expected_volumes") else put("expected_volumes",c.expectedVolumes)
                put("source_read_count", c.sourceReadCount); if (c.sourceRating == null) putNull("source_rating") else put("source_rating", c.sourceRating)
                if (c.rating == null) putNull("rating") else put("rating", c.rating)
                put("cover", c.cover); put("updated_at", c.updatedAt); put("subgenres", org.json.JSONArray(c.subgenres).toString())
            }
            if (writableDatabase.update("collections", values, "id=?", arrayOf(c.id)) == 0) writableDatabase.insertOrThrow("collections", null, values)
        }
    }
    fun saveTitleCover(input: CollectionTitle) {
        transaction {
            saveCollection(input)
            books().firstOrNull { it.collectionId == input.id && it.sequenceNumber == 1 }?.let { first -> save(first.copy(cover = input.cover)) }
        }
    }
    fun deleteCollection(id: String) {
        val volumes = books(true).filter { it.collectionId == id }
        require(draft()?.bookId !in volumes.map { it.id }) { "Finalize ou descarte a leitura antes de excluir o título." }
        transaction { volumes.forEach { delete(it.id) }; writableDatabase.delete("collections", "id=?", arrayOf(id)) }
    }
    fun setRating(id: String, rating: Double?, collection: Boolean = false) {
        validateRating(rating)
        require(if (collection) collections().any { it.id == id } else book(id) != null)
        writableDatabase.update(if (collection) "collections" else "books", ContentValues().apply {
            if (rating == null) putNull("rating") else put("rating", rating)
        }, "id=?", arrayOf(id))
    }
    fun markVolumeRead(id: String, value: Boolean) {
        val b = requireNotNull(book(id)); require(!b.pagesKnown)
        writableDatabase.update("books", ContentValues().apply { put("read_finished", if (value) 1 else 0);put("updated_at",System.currentTimeMillis()) }, "id=?", arrayOf(id))
    }
    fun dates(bookId: String? = null): List<ReadingDate> = readableDatabase.rawQuery(
        "SELECT * FROM reading_dates" + (if (bookId == null) "" else " WHERE book_id=?") + " ORDER BY day DESC", bookId?.let { arrayOf(it) }
    ).use { c -> buildList { while (c.moveToNext()) add(ReadingDate(c.string("id"), c.string("book_id"), c.string("day"))) } }
    fun addDates(bookId: String, days: List<String>) {
        require(book(bookId) != null); days.forEach(ReadingDays::validate)
        transaction { days.distinct().forEach { addDate(ReadingDate(bookId = bookId, day = it)) } }
    }
    private fun addDate(date: ReadingDate) {
        date.validated(); require(book(date.bookId) != null)
        writableDatabase.insertWithOnConflict("reading_dates", null, ContentValues().apply {
            put("id", date.id); put("book_id", date.bookId); put("day", date.day)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun deleteDate(id: String) { writableDatabase.delete("reading_dates", "id=?", arrayOf(id)) }
    fun changeDate(date: ReadingDate, day: String) {
        ReadingDays.validate(day)
        transaction { deleteDate(date.id); addDate(date.copy(day = day)) }
    }
    fun changeSessionDay(id: String, day: String) {
        ReadingDays.validate(day)
        writableDatabase.update("sessions", ContentValues().apply { put("reading_day", day) }, "id=?", arrayOf(id))
    }
    /** Corrections affect recorded history only; never rewind the book or an active timer. */
    fun editReadingSession(expected:ReadingSession,day:String,durationMs:Long){
        ReadingDays.validate(day)
        require(durationMs==expected.durationMs || durationMs in 0..3_599_999_000L){"Informe um tempo de até 999h59min59s."}
        transaction {
            val live=sessions(expected.bookId).firstOrNull {it.id==expected.id}
            require(live==expected){"O registro mudou. Abra a edição novamente."}
            expected.copy(readingDay=day,durationMs=durationMs).validated(requireNotNull(book(expected.bookId)).totalPages)
            writableDatabase.update("sessions",ContentValues().apply {put("reading_day",day);put("duration_ms",durationMs)},"id=?",arrayOf(expected.id))
        }
    }
    fun deleteReadingSession(expected:ReadingSession){
        transaction {
            require(sessions(expected.bookId).firstOrNull {it.id==expected.id}==expected){"O registro mudou. Abra o histórico novamente."}
            writableDatabase.delete("sessions","id=?",arrayOf(expected.id))
        }
    }
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.number(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.optionalDouble(name: String): Double? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getDouble(it) }
    private fun Cursor.book():LibraryBook {
        val column=getColumnIndex("catalog_binding")
        val link=if(column<0 || isNull(column)) null else runCatching {
            org.json.JSONObject(getString(column)).let { it.getString("id") to it.getString("source") }
        }.getOrNull()
        return LibraryBook(string("id"), string("title"), string("author"), string("genre"), number("total_pages").toInt(), number("current_page").toInt(), string("cover"), string("background"), number("updated_at"), if (isNull(getColumnIndexOrThrow("sequence_number"))) null else number("sequence_number").toInt(), string("saga"), string("subgenre"), org.json.JSONArray(string("subgenres")).let { array -> (0 until array.length()).map { array.getString(it) } }, string("kind"), string("collection_id"), string("publisher"), optionalDouble("rating"), number("pages_known") == 1L, number("read_finished") == 1L).copy(catalogId=link?.first,catalogSource=link?.second.orEmpty(),archived=getColumnIndex("archived").let {it>=0 && getLong(it)!=0L})
    }
    fun books(includeArchived:Boolean=false): List<LibraryBook> = readableDatabase.rawQuery("SELECT books.*,preferences.value AS catalog_binding FROM books LEFT JOIN preferences ON preferences.key='catalog:'||books.id"+(if(includeArchived) "" else " WHERE books.archived=0")+" ORDER BY books.title COLLATE NOCASE", null).use { c -> buildList { while (c.moveToNext()) add(c.book()) } }
    fun book(id: String): LibraryBook? = readableDatabase.rawQuery("SELECT books.*,preferences.value AS catalog_binding FROM books LEFT JOIN preferences ON preferences.key='catalog:'||books.id WHERE books.id=?", arrayOf(id)).use { if (it.moveToFirst()) it.book() else null }
    fun currentId(): String? = readableDatabase.rawQuery("SELECT value FROM preferences WHERE key='current_book'", null).use { if (it.moveToFirst()) it.getString(0) else null }
    fun current(): LibraryBook? = currentId()?.let(::book)?.takeUnless {it.archived}
    fun setCurrent(id: String) {
        require(book(id) != null)
        writableDatabase.insertWithOnConflict("preferences", null, ContentValues().apply { put("key", "current_book"); put("value", id) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun setCatalogBinding(id:String,catalogId:String?,source:String) {
        if(book(id)==null) return
        if(catalogId==null) writableDatabase.delete("preferences","key=?",arrayOf("catalog:$id"))
        else writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply {
            put("key","catalog:$id");put("value",org.json.JSONObject().put("id",catalogId).put("source",source).toString())
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun fillMissingCover(id:String,catalogId:String,source:String,cover:String):Boolean {
        require(cover.matches(Regex("[a-zA-Z0-9-]+\\.jpg")))
        val binding=org.json.JSONObject().put("id",catalogId).put("source",source).toString()
        return writableDatabase.update("books",ContentValues().apply {put("cover",cover);put("updated_at",System.currentTimeMillis())},
            "id=? AND cover='' AND archived=0 AND EXISTS(SELECT 1 FROM preferences WHERE key=? AND value=?)",arrayOf(id,"catalog:$id",binding))==1
    }
    fun save(input: LibraryBook) {
        transaction {
            val book = BookGenres.normalize(input)
            book.validated()
            require(sessions(book.id).all { it.endPage <= book.totalPages }) { "O total de páginas não pode ser menor que as páginas das sessões salvas." }
            val active = draft()
            require(active?.bookId != book.id || (book(id = book.id)!!.currentPage == book.currentPage && book.totalPages >= active.startPage)) { "Finalize ou descarte a sessão antes de alterar as páginas deste livro." }
            val values = ContentValues().apply {
                put("archived",if(book.archived)1 else 0); put("id", book.id); put("title", book.title.trim()); put("author", book.author.trim()); put("genre", book.genre.trim()); put("saga", book.saga.trim()); put("subgenre", book.subgenre); put("subgenres", org.json.JSONArray(book.selectedSubgenres).toString())
                put("kind", book.kind); put("collection_id", book.collectionId); put("publisher", book.publisher)
                if (book.rating == null) putNull("rating") else put("rating", book.rating)
                put("pages_known", if (book.pagesKnown) 1 else 0); put("read_finished", if (book.readFinished) 1 else 0)
                put("total_pages", book.totalPages); put("current_page", book.currentPage); put("cover", book.cover); put("background", book.background); put("updated_at", book.updatedAt); if (book.sequenceNumber == null) putNull("sequence_number") else put("sequence_number", book.sequenceNumber)
            }
            // UPDATE, never REPLACE: REPLACE would delete child sessions through the foreign key.
            if (writableDatabase.update("books", values, "id=?", arrayOf(book.id)) == 0) writableDatabase.insertOrThrow("books", null, values)
            if(book.catalogId!=null) setCatalogBinding(book.id,book.catalogId,book.catalogSource)
            if (current() == null && book.pagesKnown && !book.archived) setCurrent(book.id)
        }
    }
    /** Progress changes never close or reset the persisted reading timer. */
    fun updateProgress(id: String, page: Int) {
        val b = requireNotNull(book(id))
        val d = draft()?.takeIf { it.bookId == id }
        readingProgress(b, page, d)
        writableDatabase.update("books", ContentValues().apply {
            put("current_page", page); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }
    /** Manual reading and its page update are committed together; concurrent progress is never overwritten. */
    fun recordReading(id:String,expectedPage:Int,endPage:Int,durationMs:Long):ReadingSession {
        require(durationMs in 1..3_599_940_000L) {"Informe um tempo de leitura válido."}
        var result:ReadingSession?=null
        transaction {
            val b=requireNotNull(book(id));require(b.currentPage==expectedPage) {"A página mudou. Confira o progresso antes de registrar."}
            val d=draft()?.takeIf {it.bookId==id}
            require(d?.runningSince==null) {"Pause a leitura antes de registrar."}
            val start=d?.startPage ?: expectedPage
            require(endPage>start && endPage in 0..b.totalPages) {"Confira a página final."}
            val now=System.currentTimeMillis();val session=ReadingSession(bookId=id,startedAt=(now-durationMs).coerceAtLeast(0),finishedAt=now,durationMs=durationMs,startPage=start,endPage=endPage,readingDay=ReadingDays.at(now)).validated(b.totalPages)
            addSession(session);if(d!=null)discard();save(b.copy(currentPage=endPage,updatedAt=now));result=session
        }
        return requireNotNull(result)
    }
    fun finishBook(id: String): ReadingSession {
        require(draft()?.bookId == id) { "Este livro não tem leitura em andamento." }
        return finish(requireNotNull(book(id)).totalPages)
    }
    fun delete(id: String) {
        transaction {
            writableDatabase.delete("preferences","key=?",arrayOf("catalog:$id"))
            writableDatabase.delete("books", "id=?", arrayOf(id))
            if (currentId() == id) writableDatabase.delete("preferences", "key='current_book'", null)
        }
    }
    fun archive(id:String,collection:Boolean=false,removed:Boolean=true) {
        val ids=if(collection)books(true).filter {it.collectionId==id}.map {it.id} else listOf(id)
        require(draft()?.bookId !in ids) {"Pause e salve a sessão antes de remover esta leitura."}
        transaction {
            if(collection)writableDatabase.update("collections",ContentValues().apply {put("archived",if(removed)1 else 0)},"id=?",arrayOf(id))
            ids.forEach {key->
                writableDatabase.update("books",ContentValues().apply {put("archived",if(removed)1 else 0);put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(key))
                if(removed && currentId()==key)writableDatabase.delete("preferences","key='current_book'",null)
                if(!removed)book(key)?.collectionId?.takeIf(String::isNotBlank)?.let {parent->writableDatabase.update("collections",ContentValues().apply {put("archived",0)},"id=?",arrayOf(parent))}
            }
        }
    }
    fun sessions(bookId: String? = null): List<ReadingSession> {
        val where = if (bookId == null) "" else " WHERE book_id=?"
        return readableDatabase.rawQuery("SELECT * FROM sessions$where ORDER BY finished_at DESC", bookId?.let { arrayOf(it) }).use { c -> buildList {
            while (c.moveToNext()) add(ReadingSession(c.string("id"), c.string("book_id"), c.number("started_at"), c.number("finished_at"), c.number("duration_ms"), c.number("start_page").toInt(), c.number("end_page").toInt(), if (c.isNull(c.getColumnIndexOrThrow("reading_day"))) null else c.string("reading_day")))
        } }
    }
    private fun addSession(session: ReadingSession) {
        session.validated(requireNotNull(book(session.bookId)).totalPages)
        writableDatabase.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("id", session.id); put("book_id", session.bookId); put("started_at", session.startedAt); put("finished_at", session.finishedAt)
            put("duration_ms", session.durationMs); put("start_page", session.startPage); put("end_page", session.endPage); put("reading_day", session.readingDay)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun boot() = if (Build.VERSION.SDK_INT >= 24) Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) else 0
    fun draft(): ReadingDraft? = readableDatabase.rawQuery("SELECT * FROM draft WHERE singleton=1", null).use { c ->
        if (!c.moveToFirst()) null else ReadingDraft(c.string("book_id"), c.number("start_page").toInt(), c.number("started_at"), c.number("elapsed_ms"), if (c.isNull(c.getColumnIndexOrThrow("running_since"))) null else c.number("running_since"), c.number("boot").toInt())
    }
    private fun saveDraft(d: ReadingDraft) {
        writableDatabase.insertWithOnConflict("draft", null, ContentValues().apply {
            put("singleton", 1); put("book_id", d.bookId); put("start_page", d.startPage); put("started_at", d.startedAt); put("elapsed_ms", d.elapsedMs)
            if (d.runningSince == null) putNull("running_since") else put("running_since", d.runningSince); put("boot", d.boot)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun recover() { draft()?.let { d ->
        // On old Android without boot counter, pause on a new Activity; same-process checkpoints still use monotonic time.
        val safe = if (Build.VERSION.SDK_INT < 24) d.copy(runningSince = null) else d.checkpoint(SystemClock.elapsedRealtime(), boot())
        saveDraft(safe)
    } }
    fun elapsed(d: ReadingDraft) = d.duration(SystemClock.elapsedRealtime(), boot())
    fun checkpoint() { draft()?.let { saveDraft(it.checkpoint(SystemClock.elapsedRealtime(), boot())) } }
    fun start(id: String) {
        val existing = draft()
        require(existing == null || existing.bookId == id) { "Finalize ou descarte a sessão do outro livro primeiro." }
        val b = requireNotNull(book(id))
        require(b.pagesKnown) { "Informe o total de páginas para cronometrar este volume." }
        require(!b.complete || existing != null) { "Este livro já está concluído." }
        setCurrent(id)
        val d = existing ?: ReadingDraft(id, b.currentPage, System.currentTimeMillis())
        if (d.runningSince == null) saveDraft(d.copy(runningSince = SystemClock.elapsedRealtime(), boot = boot()))
    }
    fun pause() { draft()?.let { saveDraft(it.checkpoint(SystemClock.elapsedRealtime(), boot()).copy(runningSince = null)) } }
    fun discard() { writableDatabase.delete("draft", null, null) }
    fun finish(endPage: Int): ReadingSession {
        val d = requireNotNull(draft()) { "Nenhuma sessão em andamento." }
        val b = requireNotNull(book(d.bookId))
        val session = ReadingSession(bookId = b.id, startedAt = d.startedAt, finishedAt = System.currentTimeMillis().coerceAtLeast(d.startedAt), durationMs = elapsed(d), startPage = d.startPage, endPage = endPage, readingDay = ReadingDays.at(d.startedAt)).validated(b.totalPages)
        transaction { addSession(session); discard(); save(b.copy(currentPage = endPage, updatedAt = System.currentTimeMillis())) }
        return session
    }
    fun createCollection(input: CollectionTitle, ownedVolumes: Int) {
        input.validated()
        require(ownedVolumes in 0..5000) { "Use uma quantidade de 0 a 5000 volumes." }
        require(collections().none { it.id == input.id }) { "Este título já está cadastrado." }
        transaction {
            saveCollection(input.copy(expectedVolumes=input.expectedVolumes ?: input.missingVolumes?.let {(it+ownedVolumes).takeIf {n->n>0}}))
            repeat(ownedVolumes) { index -> save(LibraryBook(title = input.title, author = input.author, genre = input.genre, subgenres = input.subgenres, subgenre = input.subgenres.firstOrNull().orEmpty(),
                kind = input.kind, collectionId = input.id, publisher = input.publisher, saga = input.title, sequenceNumber = index + 1,
                totalPages = 1, pagesKnown = false, cover = if (index == 0) input.cover else "")) }
        }
    }
    fun createLightNovel(input: CollectionTitle, first: LibraryBook, ownedVolumes: Int) {
        require(ownedVolumes in 1..5000) { "Inclua pelo menos o volume 1 deste livro." }
        val volumeOne = input.validated().lightNovelVolumeOne(first)
        transaction {
            createCollection(input.copy(expectedVolumes=input.expectedVolumes ?: input.missingVolumes?.let {(it+ownedVolumes).takeIf {n->n>0}}), 0)
            save(volumeOne)
            for (number in 2..ownedVolumes) save(LibraryBook(title = input.title, author = input.author,
                publisher = input.publisher, genre = input.genre, subgenres = input.subgenres,
                kind = "book", collectionId = input.id, saga = input.title, sequenceNumber = number,
                totalPages = 1, pagesKnown = false))
        }
    }
    fun importSnapshot(books: List<LibraryBook>, sessions: List<ReadingSession>, currentId: String?, dates: List<ReadingDate> = emptyList(), collections: List<CollectionTitle> = emptyList()): Int {
        val active = draft()
        require(active?.runningSince == null) { "Pause a leitura antes de importar." }
        val imported = books.map { preserveReadingOnImport(it, if (it.id == active?.bookId) book(it.id) else null, active) }
        require(imported.map { it.id }.distinct().size == imported.size) { "Identificadores de livros duplicados no JSON." }
        require(collections.map { it.id }.distinct().size == collections.size) { "Títulos de coleção duplicados." }
        collections.forEach { it.validated() }
        require(collections.none { c -> imported.any { it.id == c.id } || book(c.id) != null }) { "ID do título também usado por um livro." }
        val knownCollections = (collections + this.collections()).associateBy { it.id }
        imported.forEach { it.validated(); require(it.collectionId.isBlank() || knownCollections[it.collectionId]?.kind == it.kind) { "Volume sem título de coleção compatível." } }
        sessions.forEach { s -> s.validated(requireNotNull(imported.find { it.id == s.bookId } ?: book(s.bookId)) { "Sessão sem livro." }.totalPages) }
        dates.forEach { it.validated(); require(imported.any { b -> b.id == it.bookId } || book(it.bookId) != null) { "Data sem livro." } }
        transaction {
            collections.forEach { saveCollection(it.copy(expectedVolumes=CollectionInventory.expected(it,imported))) }; imported.forEach { save(it) }; sessions.forEach(::addSession); dates.forEach(::addDate)
            if (active != null) setCurrent(active.bookId) else if (currentId != null && book(currentId) != null) setCurrent(currentId)
        }
        return books.size
    }
    private fun transaction(action: () -> Unit) {
        val db = writableDatabase; db.beginTransaction()
        try { action(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private class SharedLease(val helper: SharedHelper, var refs: Int)

    private class SharedHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 11) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            runCatching { db.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() } }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,genre TEXT NOT NULL,total_pages INTEGER NOT NULL,current_page INTEGER NOT NULL,cover TEXT NOT NULL,background TEXT NOT NULL,updated_at INTEGER NOT NULL,sequence_number INTEGER,saga TEXT NOT NULL DEFAULT '',subgenre TEXT NOT NULL DEFAULT '',subgenres TEXT NOT NULL DEFAULT '[]',kind TEXT NOT NULL DEFAULT 'book',collection_id TEXT NOT NULL DEFAULT '',publisher TEXT NOT NULL DEFAULT '',rating REAL,pages_known INTEGER NOT NULL DEFAULT 1,read_finished INTEGER NOT NULL DEFAULT 0,archived INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,started_at INTEGER NOT NULL,finished_at INTEGER NOT NULL,duration_ms INTEGER NOT NULL,start_page INTEGER NOT NULL,end_page INTEGER NOT NULL,reading_day TEXT)")
            createCollections(db)
            createDates(db)
            db.execSQL("CREATE INDEX sessions_book ON sessions(book_id,finished_at)")
            db.execSQL("CREATE TABLE preferences(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE draft(singleton INTEGER PRIMARY KEY CHECK(singleton=1),book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,start_page INTEGER NOT NULL,started_at INTEGER NOT NULL,elapsed_ms INTEGER NOT NULL,running_since INTEGER,boot INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE books ADD COLUMN sequence_number INTEGER")
                val updates = db.rawQuery("SELECT id,title,author FROM books", null).use { c -> buildList {
                    while (c.moveToNext()) add(c.getString(0) to LegacyBookMetadata.read(c.getString(1), c.getString(2)))
                } }
                updates.forEach { (id, metadata) -> db.update("books", ContentValues().apply {
                    put("title", metadata.title); metadata.sequenceNumber?.let { put("sequence_number", it) }
                }, "id=?", arrayOf(id)) }
            }
            if (oldVersion < 3) db.execSQL("ALTER TABLE books ADD COLUMN saga TEXT NOT NULL DEFAULT ''")
            if (oldVersion < 4) { db.execSQL("ALTER TABLE sessions ADD COLUMN reading_day TEXT"); createDates(db) }
            if (oldVersion < 5) upgradeGenres(db)
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE books ADD COLUMN subgenres TEXT NOT NULL DEFAULT '[]'")
                val rows = db.rawQuery("SELECT id,subgenre FROM books", null).use { c -> buildList {
                    while (c.moveToNext()) add(c.getString(0) to c.getString(1))
                } }
                rows.forEach { (id, tag) -> db.update("books", ContentValues().apply {
                    put("subgenres", org.json.JSONArray(listOf(tag).filter(String::isNotBlank)).toString())
                }, "id=?", arrayOf(id)) }
            }
            if (oldVersion < 8) {
                db.execSQL("ALTER TABLE books ADD COLUMN kind TEXT NOT NULL DEFAULT 'book'")
                db.execSQL("ALTER TABLE books ADD COLUMN collection_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN publisher TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN rating REAL")
                db.execSQL("ALTER TABLE books ADD COLUMN pages_known INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE books ADD COLUMN read_finished INTEGER NOT NULL DEFAULT 0")
                createCollections(db)
            }
            if (oldVersion == 8) db.execSQL("ALTER TABLE collections ADD COLUMN subgenres TEXT NOT NULL DEFAULT '[]'")
            if (oldVersion < 10) {
                db.execSQL("ALTER TABLE books ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                if(oldVersion>=8) db.execSQL("ALTER TABLE collections ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 11) {
                // Collections created by older upgrade steps already include this column.
                if(oldVersion>=8) db.execSQL("ALTER TABLE collections ADD COLUMN expected_volumes INTEGER")
                db.execSQL("UPDATE collections SET expected_volumes=missing_volumes+(SELECT COUNT(DISTINCT CASE WHEN sequence_number IS NULL THEN 'id:'||id ELSE 'number:'||sequence_number END) FROM books WHERE collection_id=collections.id) WHERE missing_volumes IS NOT NULL")
                db.execSQL("UPDATE collections SET expected_volumes=NULL WHERE expected_volumes=0")
            }
            if (oldVersion < 7) {
                // Update classification in place: never replace books or their reading records.
                val rows = db.rawQuery("SELECT * FROM books", null).use { c -> buildList {
                    while (c.moveToNext()) add(readLegacyBook(c))
                } }
                rows.forEach { original ->
                    val book = BookGenres.normalize(original)
                    if (book.selectedSubgenres.size <= 3) db.update("books", ContentValues().apply {
                        put("genre", book.genre); put("subgenre", book.subgenre)
                        put("subgenres", org.json.JSONArray(book.selectedSubgenres).toString())
                    }, "id=?", arrayOf(book.id))
                }
            }
        }
        private fun createCollections(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE collections(id TEXT PRIMARY KEY,kind TEXT NOT NULL,title TEXT NOT NULL,author TEXT NOT NULL,genre TEXT NOT NULL,publisher TEXT NOT NULL,missing_volumes INTEGER,expected_volumes INTEGER,source_read_count INTEGER NOT NULL,source_rating REAL,rating REAL,cover TEXT NOT NULL,updated_at INTEGER NOT NULL,subgenres TEXT NOT NULL DEFAULT '[]',archived INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX books_collection ON books(collection_id)")
        }
        private fun createDates(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE reading_dates(id TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,day TEXT NOT NULL,UNIQUE(book_id,day))")
        }
        private fun upgradeGenres(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN subgenre TEXT NOT NULL DEFAULT ''")
            val rows = db.rawQuery("SELECT id,genre FROM books", null).use { c -> buildList {
                while (c.moveToNext()) add(c.getString(0) to BookGenres.split(c.getString(1)))
            } }
            rows.forEach { (id, category) -> db.update("books", ContentValues().apply {
                put("genre", category.genre); put("subgenre", category.subgenre)
            }, "id=?", arrayOf(id)) }
        }
        private fun readLegacyBook(c: Cursor): LibraryBook {
            fun str(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            fun num(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
            fun optDouble(name: String): Double? = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getDouble(it) }
            return LibraryBook(
                str("id"), str("title"), str("author"), str("genre"),
                num("total_pages").toInt(), num("current_page").toInt(), str("cover"), str("background"), num("updated_at"),
                if (c.isNull(c.getColumnIndexOrThrow("sequence_number"))) null else num("sequence_number").toInt(),
                str("saga"), str("subgenre"),
                org.json.JSONArray(str("subgenres")).let { array -> (0 until array.length()).map { array.getString(it) } },
                str("kind"), str("collection_id"), str("publisher"), optDouble("rating"),
                num("pages_known") == 1L, num("read_finished") == 1L,
            )
        }
    }

    companion object {
        private val lock = Any()
        private val leases = HashMap<String, SharedLease>()

        private fun acquire(appContext: Context, name: String): SharedLease = synchronized(lock) {
            val existing = leases[name]
            if (existing != null) {
                existing.refs++
                existing
            } else {
                SharedLease(SharedHelper(appContext, name), 1).also { leases[name] = it }
            }
        }

        private fun release(name: String, lease: SharedLease) {
            val toClose = synchronized(lock) {
                if (leases[name] !== lease) return
                lease.refs--
                if (lease.refs <= 0) {
                    leases.remove(name)
                    lease.helper
                } else null
            }
            toClose?.let { runCatching { it.close() } }
        }
    }
}
