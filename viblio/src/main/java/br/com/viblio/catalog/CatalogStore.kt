package br.com.viblio.catalog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingBook(val id:String,val metadata:String,val cover:String,val source:String)
/** Persistent outbox is independent of the personal library and survives process restarts. */
class CatalogStore(context:Context):SQLiteOpenHelper(context,"catalog-outbox.db",null,1) {
    override fun onCreate(db:SQLiteDatabase) { db.execSQL("CREATE TABLE pending(id TEXT PRIMARY KEY,metadata TEXT NOT NULL,cover TEXT NOT NULL,source TEXT NOT NULL)") }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {}
    fun queue(row:PendingBook) { writableDatabase.insertWithOnConflict("pending",null,ContentValues().apply { put("id",row.id);put("metadata",row.metadata);put("cover",row.cover);put("source",row.source) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun rows():List<PendingBook> = readableDatabase.rawQuery("SELECT id,metadata,cover,source FROM pending",null).use { c->buildList { while(c.moveToNext()) add(PendingBook(c.getString(0),c.getString(1),c.getString(2),c.getString(3))) } }
    fun complete(row:PendingBook) { writableDatabase.delete("pending","id=? AND metadata=?",arrayOf(row.id,row.metadata)) }
    fun count():Int=readableDatabase.rawQuery("SELECT count(*) FROM pending",null).use { it.moveToFirst();it.getInt(0) }
}
