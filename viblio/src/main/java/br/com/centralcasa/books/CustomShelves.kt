package br.com.centralcasa.books

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

data class CustomShelf(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bookIds: Set<String> = emptySet(),
) {
    fun validated(): CustomShelf {
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        require(UUID.fromString(id).toString() == id) { "Identificador da prateleira inválido." }
        require(cleanName.length in 1..40) { "Use um nome de 1 a 40 caracteres." }
        require(bookIds.size <= CustomShelves.MAX_BOOKS_PER_SHELF) { "Limite de livros na prateleira atingido." }
        require(bookIds.all { it.isNotBlank() && it.length <= 200 }) { "Livro inválido na prateleira." }
        return copy(name = cleanName)
    }
}

/**
 * Pure operations + codec for user-defined custom shelves.
 * Stored under `SHELF_CUSTOM_SHELVES` in profile settings so account backup/sync preserves them automatically.
 */
object CustomShelves {
    const val PREF_KEY = "SHELF_CUSTOM_SHELVES"
    const val MAX_SHELVES = 50
    const val MAX_BOOKS_PER_SHELF = 5000
    const val MAX_JSON = 256 * 1024

    fun encode(shelves: List<CustomShelf>): String {
        val clean = normalize(shelves)
        val arr = JsonArray()
        clean.forEach { s ->
            val books = JsonArray().apply { s.bookIds.sorted().forEach(::add) }
            arr.add(JsonObject().apply {
                addProperty("id", s.id)
                addProperty("name", s.name)
                add("books", books)
            })
        }
        return JsonObject().apply {
            addProperty("v", 1)
            add("shelves", arr)
        }.toString()
    }

    fun decode(raw: String): List<CustomShelf> {
        require(raw.length <= MAX_JSON && raw.toByteArray().size <= MAX_JSON) { "Prateleiras grandes demais." }
        val o = JsonParser.parseString(raw).asJsonObject
        val v = o.get("v")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        require(v == 1) { "Versão de prateleiras não suportada." }
        val arr = o.getAsJsonArray("shelves") ?: JsonArray()
        require(arr.size() <= MAX_SHELVES) { "Limite de $MAX_SHELVES prateleiras atingido." }
        val list = (0 until arr.size()).mapNotNull { i ->
            val item = arr.get(i)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = item.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val name = item.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val booksArr = item.getAsJsonArray("books") ?: JsonArray()
            val ids = LinkedHashSet<String>()
            for (j in 0 until booksArr.size()) {
                val bId = booksArr.get(j)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (bId.isNotBlank()) ids.add(bId)
            }
            runCatching { CustomShelf(id, name, ids).validated() }.getOrNull()
        }
        return normalize(list)
    }

    fun normalize(shelves: List<CustomShelf>): List<CustomShelf> {
        val seenIds = HashSet<String>()
        val seenNames = HashSet<String>()
        val out = mutableListOf<CustomShelf>()
        for (raw in shelves) {
            val s = raw.validated()
            val nameKey = ShelfFilters.key(s.name)
            if (seenIds.add(s.id) && seenNames.add(nameKey)) {
                out.add(s)
                if (out.size >= MAX_SHELVES) break
            }
        }
        return out
    }

    fun create(shelves: List<CustomShelf>, name: String, initialBookId: String? = null): Pair<List<CustomShelf>, CustomShelf> {
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        require(cleanName.length in 1..40) { "Use um nome de 1 a 40 caracteres." }
        require(shelves.size < MAX_SHELVES) { "Limite de $MAX_SHELVES prateleiras atingido." }
        val key = ShelfFilters.key(cleanName)
        require(shelves.none { ShelfFilters.key(it.name) == key }) { "Já existe uma prateleira com esse nome." }
        val created = CustomShelf(
            name = cleanName,
            bookIds = initialBookId?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet(),
        ).validated()
        return (shelves + created) to created
    }

    fun rename(shelves: List<CustomShelf>, shelfId: String, newName: String): List<CustomShelf> {
        val cleanName = newName.trim().replace(Regex("\\s+"), " ")
        require(cleanName.length in 1..40) { "Use um nome de 1 a 40 caracteres." }
        val key = ShelfFilters.key(cleanName)
        require(shelves.none { it.id != shelfId && ShelfFilters.key(it.name) == key }) { "Já existe uma prateleira com esse nome." }
        return shelves.map { if (it.id == shelfId) it.copy(name = cleanName).validated() else it }
    }

    fun delete(shelves: List<CustomShelf>, shelfId: String): List<CustomShelf> =
        shelves.filterNot { it.id == shelfId }

    fun setBookShelves(shelves: List<CustomShelf>, bookId: String, selectedShelfIds: Set<String>): List<CustomShelf> {
        require(bookId.isNotBlank() && bookId.length <= 200) { "Livro inválido." }
        return shelves.map { s ->
            val next = if (s.id in selectedShelfIds) s.bookIds + bookId else s.bookIds - bookId
            if (next == s.bookIds) s else s.copy(bookIds = next).validated()
        }
    }

    fun shelvesForBook(shelves: List<CustomShelf>, bookId: String): List<CustomShelf> =
        if (bookId.isBlank()) emptyList() else shelves.filter { bookId in it.bookIds }
}

class CustomShelfStore(context: Context) {
    private val appContext = context.applicationContext

    private fun prefs() = appContext.getSharedPreferences(LibraryProfiles.settings(appContext), 0)

    companion object {
        private val lock = Any()
    }

    fun load(): List<CustomShelf> {
        val raw = prefs().getString(CustomShelves.PREF_KEY, null) ?: return emptyList()
        return runCatching { CustomShelves.decode(raw) }.getOrDefault(emptyList())
    }

    fun save(shelves: List<CustomShelf>) {
        synchronized(lock) {
            val p = prefs()
            if (shelves.isEmpty()) {
                p.edit().remove(CustomShelves.PREF_KEY).commit()
                return
            }
            val raw = CustomShelves.encode(shelves)
            check(raw.length <= CustomShelves.MAX_JSON && raw.toByteArray().size <= CustomShelves.MAX_JSON) {
                "Limite de armazenamento das prateleiras atingido."
            }
            check(p.edit().putString(CustomShelves.PREF_KEY, raw).commit()) {
                "Não foi possível salvar as prateleiras."
            }
        }
    }
}
