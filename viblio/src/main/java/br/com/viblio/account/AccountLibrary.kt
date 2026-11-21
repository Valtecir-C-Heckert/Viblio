package br.com.viblio.account
import android.content.Context
import br.com.centralcasa.books.*
import com.google.gson.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
/** Every dependency is frozen to this profile, including jobs queued during a switch. */
class AccountLibrary(private val context:Context) {
    private val database=LibraryProfiles.database(context)
    private val settings=LibraryProfiles.settings(context)
    private val suffix=LibraryProfiles.suffix(context)
    private val images=BookImages(context,suffix)
    private fun allowed(k:String)=k!=LibrarySyncPolicy.LOCAL_ZOOM && (k.startsWith("SHELF_") || k=="BOOK_SHELF_FILTERS" || k.startsWith("BOOK_SHELF_FILTERS:") || k.startsWith("shelf_columns:") || k.startsWith("collection_columns:") || k.startsWith("genre_color:") || k.startsWith("subgenre_vocabulary:") || k in listOf("books_per_row","STATISTICS_TYPE","genre_parents_migrated_v2"))
    fun capture(useMediaRefs:Boolean=false):JsonObject {
        val repo=LibraryRepository(context,database)
        val snapshot:LibraryJson.Snapshot
        try {repo.readableDatabase.beginTransactionNonExclusive();try {snapshot=LibraryJson.Snapshot(repo.books(true),repo.sessions(),repo.currentId(),repo.dates(),repo.collections(true));repo.readableDatabase.setTransactionSuccessful()}finally {repo.readableDatabase.endTransaction()}}finally {repo.close()}
        val library=JsonParser.parseString(LibraryJson(images).write(snapshot.books,snapshot.sessions,snapshot.currentId,snapshot.dates,snapshot.collections,useMediaRefs=useMediaRefs)).asJsonObject
        val appearance=JsonObject();context.getSharedPreferences(settings,0).all.filterKeys(::allowed).forEach {(key,value)->when(value){is String->appearance.addProperty(key,value);is Int->appearance.addProperty(key,value);is Long->appearance.addProperty(key,value);is Boolean->appearance.addProperty(key,value);is Float->appearance.addProperty(key,value);is Set<*>->appearance.add(key,JsonArray().apply {value.filterIsInstance<String>().sorted().forEach(::add)})}}
        return JsonObject().apply {add("library",library);add("appearance",appearance)}
    }
    fun syncMediaUp(client:AccountApi,snapshot:JsonObject,onlyHashes:Collection<String>?=null) {
        val wanted=onlyHashes?.distinct() ?: collectMediaHashes(snapshot)
        if(wanted.isEmpty())return
        val missing=if(onlyHashes!=null)wanted else client.checkMedia(wanted)
        for(sha in missing){
            val file=requireNotNull(images.fileBySha256(sha)){"Imagem local ausente antes do envio. Reabra ou salve a imagem novamente."}
            client.uploadMedia(sha,file.readBytes())
        }
    }
    fun syncMediaDown(client:AccountApi,snapshot:JsonObject) {
        for(sha in collectMediaHashes(snapshot)){
            if(images.ensureCanonicalBySha256(sha)==null){
                images.saveVerifiedMedia(sha,client.downloadMedia(sha))
            }
        }
    }
    private fun canonical(value:JsonElement):JsonElement=when {
        value.isJsonObject->JsonObject().apply {value.asJsonObject.entrySet().sortedBy {it.key}.forEach {add(it.key,canonical(it.value))}}
        value.isJsonArray->JsonArray().apply {val all=value.asJsonArray.toList();val ordered=if(all.isNotEmpty() && all.all {it.isJsonObject && it.asJsonObject.has("id")})all.sortedBy {it.asJsonObject.get("id").asString} else all;ordered.forEach {add(canonical(it))}}
        else->value
    }
    fun hash(value:JsonObject)=MessageDigest.getInstance("SHA-256").digest(canonical(LibrarySyncPolicy.shared(value)).toString().toByteArray()).joinToString("") {"%02x".format(it)}
    fun safety(snapshot:JsonObject):File {
        val folder=File(context.filesDir,"account-safety"+suffix).apply {mkdirs()};val file=File(folder,"${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.json.gz")
        GZIPOutputStream(file.outputStream()).use {it.write(snapshot.toString().toByteArray())}
        folder.listFiles()?.sortedByDescending {it.name}?.drop(3)?.forEach {it.delete()};return file
    }
    fun apply(snapshot:JsonObject) {
        val parsed=LibraryJson(images,true).read(ByteArrayInputStream(snapshot.getAsJsonObject("library").toString().toByteArray()))
        val repo=LibraryRepository(context,database)
        try {
            check(repo.draft()==null){"Pause e salve a sessão antes de restaurar a Estante."};safety(capture(useMediaRefs=true))
            val db=repo.writableDatabase;db.beginTransaction()
            try {db.delete("books",null,null);db.delete("collections",null,null);db.delete("preferences",null,null);repo.importSnapshot(parsed.books,parsed.sessions,parsed.currentId,parsed.dates,parsed.collections);if(parsed.currentId==null)db.delete("preferences","key='current_book'",null);db.setTransactionSuccessful()}finally {db.endTransaction()}
            val prefs=context.getSharedPreferences(settings,0);val edit=prefs.edit();prefs.all.keys.filter(::allowed).forEach(edit::remove)
            snapshot.getAsJsonObject("appearance")?.entrySet()?.forEach {(key,value)->if(allowed(key)){when {
                value.isJsonArray->edit.putStringSet(key,value.asJsonArray.map {it.asString}.toSet())
                value.isJsonPrimitive->{
                    val p=value.asJsonPrimitive
                    when {
                        p.isBoolean->edit.putBoolean(key,p.asBoolean)
                        p.isNumber->{
                            val raw=p.asString
                            if(raw.contains('.') || raw.contains('e') || raw.contains('E')) edit.putFloat(key,p.asFloat)
                            else {
                                val lv=p.asLong
                                if(lv in Int.MIN_VALUE..Int.MAX_VALUE) edit.putInt(key,lv.toInt()) else edit.putLong(key,lv)
                            }
                        }
                        else->edit.putString(key,p.asString)
                    }
                }
            }}}
            check(edit.commit()) {"Não foi possível gravar as preferências. Um backup local foi preservado."}
        }finally {repo.close()}
    }
    companion object {
        fun collectMediaHashes(snapshot:JsonObject):List<String> {
            val library=snapshot.getAsJsonObject("library") ?: return emptyList()
            val found=LinkedHashSet<String>()
            fun scan(section:String,vararg fields:String) {
                val arr=library.getAsJsonArray(section) ?: return
                for(el in arr){
                    val obj=el.takeIf {it.isJsonObject}?.asJsonObject ?: continue
                    for(field in fields){
                        val raw=obj.get(field)?.takeIf {it.isJsonPrimitive}?.asString ?: continue
                        val match=LibraryJson.MEDIA_REF_RE.matchEntire(raw) ?: continue
                        found.add(match.groupValues[1])
                    }
                }
            }
            scan("books","coverData","spineData")
            scan("collections","coverData")
            return found.toList()
        }
    }
}
