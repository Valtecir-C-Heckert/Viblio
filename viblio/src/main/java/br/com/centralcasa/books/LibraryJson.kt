package br.com.centralcasa.books

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Portable snapshot; simple legacy arrays/objects accept common Portuguese/English field names. */
class LibraryJson(private val images: BookImages, private val preserveStoredCovers:Boolean=false) {
    private fun loadImage(bytes:ByteArray, createdCovers: MutableList<String>):String {
        val name=if(preserveStoredCovers)images.saveStoredJpeg(bytes) else images.save(ByteArrayInputStream(bytes))
        if(!preserveStoredCovers) createdCovers.add(name)
        return name
    }
    private fun decodeArt(raw: String, label: String, createdCovers: MutableList<String>): String {
        if (raw.isBlank()) return ""
        val media = MEDIA_REF_RE.matchEntire(raw)
        if (media != null) {
            val sha = media.groupValues[1]
            return requireNotNull(images.ensureCanonicalBySha256(sha)) { "$label não encontrada localmente. Tente sincronizar novamente." }
        }
        if (raw.startsWith("data:image/")) {
            val body = raw.substringAfter(",", "")
            require(raw.substringBefore(",").endsWith(";base64")) { "$label precisa usar data:image/...;base64." }
            return loadImage(Base64.decode(body, Base64.DEFAULT), createdCovers)
        }
        throw IllegalArgumentException("$label: use imagem embutida base64; caminhos locais e URLs não são portáveis.")
    }
    private fun encodeArt(name: String, useMediaRefs: Boolean): String {
        if (name.isBlank()) return ""
        if (useMediaRefs) {
            val sha = images.sha256(name) ?: return ""
            return "media:$sha"
        }
        val file = images.file(name) ?: return ""
        return "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }
    data class Snapshot(val books: List<LibraryBook>, val sessions: List<ReadingSession>, val currentId: String?, val dates: List<ReadingDate> = emptyList(), val collections: List<CollectionTitle> = emptyList())
    fun read(input: InputStream): Snapshot {
        val bytes = input.use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count: Int
            while (stream.read(buffer).also { count = it } != -1) {
                require(out.size() + count <= 32 * 1024 * 1024) { "O JSON precisa ter até 32 MB. Divida bibliotecas maiores." }; out.write(buffer, 0, count)
            }; out.toByteArray()
        }
        val raw = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        require(withinMaxDepth(raw, MAX_JSON_DEPTH)) { "Estrutura JSON muito profunda." }
        val root = if (raw.startsWith("[")) JSONObject().put("books", JSONArray(raw)) else JSONObject(raw)
        require(!root.has("schemaVersion") || root.optInt("schemaVersion") in 1..6) { "Versão de JSON não suportada." }
        val array = root.optJSONArray("books") ?: root.optJSONArray("livros") ?: throw IllegalArgumentException("O JSON precisa conter uma lista 'books' ou 'livros'.")
        require(array.length() <= 5000) { "Importe até 5.000 livros por arquivo." }
        val createdCovers = mutableListOf<String>()
        try {
            val books = (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                fun string(vararg keys: String) = keys.firstOrNull { o.has(it) && !o.isNull(it) }?.let { o.optString(it) }.orEmpty()
                fun integer(vararg keys: String): Int? = keys.firstOrNull { o.has(it) && !o.isNull(it) }?.let { key ->
                    val v = o.get(key); require(v.toString().matches(Regex("[0-9]+"))) { "Número inválido no livro ${index + 1}: $key." }; v.toString().toInt()
                }
                val title = string("title", "titulo", "título").trim()
                val author = string("author", "autor")
                val number = integer("sequenceNumber", "numeroSequencia", "numeroSaga")
                val metadata = if (root.has("schemaVersion") && o.has("sequenceNumber")) LegacyBookMetadata.Metadata(title, number) else LegacyBookMetadata.read(title, author, number)
                val pagesKnown = o.optBoolean("pagesKnown", true)
                val total = integer("totalPages", "total_pages", "totalPaginas", "total_paginas", "paginas", "pages") ?: if (!pagesKnown) 1 else throw IllegalArgumentException("Total de páginas ausente no livro ${index + 1}.")
                val page = integer("currentPage", "current_page", "paginaAtual", "pagina_atual", "paginasLidas", "paginas_lidas", "readPages", "read") ?: 0
                val id = string("id").ifBlank { "legacy-" + MessageDigest.getInstance("SHA-256").digest("$title\u0000$author".toByteArray()).take(16).joinToString("") { "%02x".format(it) } }
                val coverValue = string("coverData", "cover", "capa", "coverImage")
                val cover = decodeArt(coverValue, "Capa do livro ${index + 1}", createdCovers)
                val tagsKey = listOf("subgenres", "subgeneros", "subgêneros").firstOrNull { o.has(it) && !o.isNull(it) }
                val tagsArray = tagsKey?.let { requireNotNull(o.optJSONArray(it)) { "Livro ${index + 1}: subgêneros precisam ser uma lista." } }
                val tags = tagsArray?.let { list -> (0 until list.length()).map { list.getString(it) } }
                require(tags == null || tags.size <= 3) { "Livro ${index + 1}: escolha até três subgêneros." }
                LibraryBook(id, metadata.title, author, string("genre", "genero", "gênero"), total, page, cover, string("background").ifBlank { "black" }, o.optLong("updatedAt", System.currentTimeMillis()), metadata.sequenceNumber, string("saga", "series", "serie", "série").trim(), (if (tags != null) tags.firstOrNull().orEmpty() else string("subgenre", "subgenero", "subgênero")), tags.orEmpty(), kind = string("kind").ifBlank { "book" }, collectionId = string("collectionId"), publisher = string("publisher", "editora"), rating = if (o.isNull("rating")) null else o.getDouble("rating"), pagesKnown = pagesKnown, readFinished = o.optBoolean("readFinished", false), catalogId = o.optString("catalogId").takeIf { it.isNotBlank() && it!="null" }, catalogSource=o.optString("catalogSource"),archived=o.optBoolean("archived",false)).let(BookGenres::normalize).validated()
            }
            val collectionArray = root.optJSONArray("collections") ?: JSONArray()
            require(collectionArray.length() <= 5000) { "Importe até 5.000 títulos." }
            val collections = (0 until collectionArray.length()).map { index ->
                val o = collectionArray.getJSONObject(index)
                val image = o.optString("coverData")
                val cover = decodeArt(image, "Capa do título", createdCovers)
                CollectionTitle(o.getString("id"), o.getString("kind"), o.getString("title"), o.optString("author"), o.optString("genre"), o.optString("publisher"),
                    if (o.isNull("missingVolumes")) null else o.getInt("missingVolumes"), o.optInt("sourceReadCount", 0),
                    if (o.isNull("sourceRating")) null else o.getDouble("sourceRating"), if (o.isNull("rating")) null else o.getDouble("rating"), cover, o.optLong("updatedAt", System.currentTimeMillis()), o.optJSONArray("subgenres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(o.optString("subgenre")).filter(String::isNotBlank), o.optBoolean("archived",false), if(o.has("expectedVolumes")) {if(o.isNull("expectedVolumes"))null else o.getInt("expectedVolumes")} else if(!o.isNull("totalVolumes")) o.getInt("totalVolumes").takeIf {it>0} else null).let {c->c.copy(expectedVolumes=CollectionInventory.expected(c,books))}.validated()
            }
            val sessions = root.optJSONArray("sessions") ?: JSONArray()
            require(sessions.length() <= 20000) { "Importe até 20.000 sessões por arquivo." }
            val records = (0 until sessions.length()).map { i -> val o = sessions.getJSONObject(i)
                ReadingSession(o.getString("id"), o.getString("bookId"), o.getLong("startedAt"), o.getLong("finishedAt"), o.getLong("durationMs"), o.getInt("startPage"), o.getInt("endPage"), if (o.isNull("readingDay")) null else o.optString("readingDay").takeIf { it.isNotBlank() })
            }
            val current = listOf("currentBookId", "selectedId").firstNotNullOfOrNull { key ->
                root.optString(key).takeIf { it.isNotBlank() && it != "null" }
            }
            val dates = root.optJSONArray("readingDates") ?: JSONArray()
            require(dates.length() <= 100000) { "Importe até 100.000 datas por arquivo." }
            return Snapshot(books, records, current, (0 until dates.length()).map { i ->
                val o = dates.getJSONObject(i); ReadingDate(o.getString("id"), o.getString("bookId"), o.getString("day")).validated()
            }, collections)
        } catch (err: Throwable) {
            createdCovers.forEach { name -> runCatching { images.file(name)?.delete() } }
            throw err
        }
    }
    companion object {
        internal const val MAX_JSON_DEPTH = 12
        internal val MEDIA_REF_RE = Regex("media:([a-f0-9]{64})")
        internal fun withinMaxDepth(raw: String, maxDepth: Int = MAX_JSON_DEPTH): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (ch in raw) {
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '{', '[' -> {
                            depth++
                            if (depth > maxDepth) return false
                        }
                        '}', ']' -> {
                            depth--
                            if (depth < 0) return false
                        }
                    }
                }
            }
            return true
        }
    }
    fun write(books: List<LibraryBook>, sessions: List<ReadingSession>, currentId: String?, dates: List<ReadingDate> = emptyList(), collections: List<CollectionTitle> = emptyList(), useMediaRefs: Boolean = false): String {
        val root = JSONObject().put("schemaVersion", 6).put("currentBookId", currentId ?: JSONObject.NULL)
        root.put("books", JSONArray().apply { books.forEach { b -> put(JSONObject().apply {
            put("subgenres", JSONArray(b.selectedSubgenres)); put("subgenre", b.selectedSubgenres.firstOrNull().orEmpty()); put("saga", b.saga); put("sequenceNumber", b.sequenceNumber ?: JSONObject.NULL); put("id", b.id); put("title", b.title); put("author", b.author); put("genre", b.genre); put("totalPages", if (b.pagesKnown) b.totalPages else JSONObject.NULL); put("currentPage", b.currentPage)
            put("coverData", encodeArt(b.cover, useMediaRefs))
            put("archived",b.archived); put("kind", b.kind); put("collectionId", b.collectionId); put("publisher", b.publisher); put("rating", b.rating ?: JSONObject.NULL)
            put("pagesKnown", b.pagesKnown); put("readFinished", b.readFinished)
            put("background", b.background); put("updatedAt", b.updatedAt)
            if(b.catalogId!=null) { put("catalogId",b.catalogId);put("catalogSource",b.catalogSource) }
        }) } })
        root.put("collections", JSONArray().apply { collections.forEach { c -> put(JSONObject().apply {
            put("subgenres", JSONArray(c.subgenres)); put("subgenre", c.subgenres.firstOrNull().orEmpty()); put("id", c.id); put("kind", c.kind); put("title", c.title); put("author", c.author); put("genre", c.genre); put("publisher", c.publisher)
            put("expectedVolumes", CollectionInventory.expected(c,books) ?: JSONObject.NULL); put("obtainedVolumes", books.count { it.collectionId == c.id && !it.archived }); put("totalVolumes", CollectionInventory.expected(c,books) ?: JSONObject.NULL); put("readVolumes", books.count { it.collectionId == c.id && !it.archived && it.complete }); put("mangaka", if (c.kind == "manga") c.author else JSONObject.NULL)
            put("missingVolumes", CollectionInventory.of(c,books).missing ?: JSONObject.NULL); put("sourceReadCount", c.sourceReadCount)
            put("archived",c.archived); put("sourceRating", c.sourceRating ?: JSONObject.NULL); put("rating", c.rating ?: JSONObject.NULL); put("updatedAt", c.updatedAt)
            put("coverData", encodeArt(c.cover, useMediaRefs))
        }) } })
        root.put("sessions", JSONArray().apply { sessions.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("bookId", s.bookId); put("startedAt", s.startedAt); put("finishedAt", s.finishedAt); put("durationMs", s.durationMs); put("startPage", s.startPage); put("endPage", s.endPage); put("readingDay", s.readingDay ?: JSONObject.NULL)
        }) } })
        root.put("readingDates", JSONArray().apply { dates.forEach { d -> put(JSONObject().put("id", d.id).put("bookId", d.bookId).put("day", d.day)) } })
        return root.toString(2)
    }
}
