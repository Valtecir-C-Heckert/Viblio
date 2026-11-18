package br.com.centralcasa.books

import java.text.Normalizer
import java.util.Locale

enum class ShelfStatus(val label: String) { ALL("Todos"), UNREAD("Não iniciados"), READING("Em leitura"), COMPLETED("Concluídos"), CURRENT("Livro atual") }
enum class ShelfRatings { ALL, RATED, UNRATED }
enum class ShelfCovers(val label: String) { ALL("Todas"), WITH("Com capa"), WITHOUT("Sem capa") }
enum class ShelfSort(val label: String) { TITLE("Título A–Z"), TITLE_REVERSE("Título Z–A"), AUTHOR("Autor A–Z"), PROGRESS_HIGH("Maior progresso"), PROGRESS_LOW("Menor progresso"), RECENT("Atualizados recentemente"), SEQUENCE("Número na saga"), GENRE("Gênero A–Z"), SAGA("Saga A–Z"), RATING_HIGH("Maior nota"), RATING_LOW("Menor nota") }

enum class ShelfField(val label: String, val numeric: Boolean = false) {
    GENRE("Gênero"), SUBGENRE("Subgênero"), SAGA("Saga"), SEQUENCE("Número na saga", true), TITLE("Título"), AUTHOR("Autor"), PROGRESS("Progresso", true), UPDATED("Atualização", true), RATING("Nota", true)
}
data class ShelfOrder(val field: ShelfField, val descending: Boolean = false) {
    val direction get() = if (field.numeric) { if (descending) "Decrescente" else "Crescente" } else { if (descending) "Z–A" else "A–Z" }
    val label get() = "${field.label} $direction"
    companion object {
        val defaults = listOf(ShelfOrder(ShelfField.GENRE), ShelfOrder(ShelfField.SAGA), ShelfOrder(ShelfField.SEQUENCE), ShelfOrder(ShelfField.TITLE))
        fun encode(order: List<ShelfOrder>) = order.joinToString(",") { "${it.field.name}:${it.descending}" }
        fun decode(value: String): List<ShelfOrder> = value.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2 || parts[1] !in listOf("true", "false")) null
            else runCatching { ShelfOrder(ShelfField.valueOf(parts[0]), parts[1].toBoolean()) }.getOrNull()
        }.distinctBy { it.field }
    }
}

data class ShelfFilters(
    val query: String = "", val status: ShelfStatus = ShelfStatus.ALL,
    val genre: String? = null, val author: String? = null,
    val covers: ShelfCovers = ShelfCovers.ALL, val sort: ShelfSort = ShelfSort.TITLE,
    val saga: String? = null, val order: List<ShelfOrder> = emptyList(), val subgenre: String? = null, val ratings: ShelfRatings = ShelfRatings.ALL, val minimumRating: Double? = null, val collectionCompletion:CollectionCompletion = CollectionCompletion.ALL
) {
    init { validateRating(minimumRating) }
    val activeCount get() = listOf(status != ShelfStatus.ALL, genre != null, subgenre != null, saga != null, author != null, covers != ShelfCovers.ALL, ratings != ShelfRatings.ALL || minimumRating != null, collectionCompletion != CollectionCompletion.ALL).count { it }
    fun select(books: List<LibraryBook>, currentId: String?): List<LibraryBook> {
        val terms = key(query).split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = books.filter { book ->
            val text = key("${book.title} ${book.author} ${book.genre} ${book.selectedSubgenres.joinToString(" ")} ${book.saga} ${book.sequenceNumber?.let { "Livro $it" }.orEmpty()}")
            terms.all(text::contains) &&
                (genre == null || key(book.genre.ifBlank { "Sem gênero" }) == key(genre)) &&
                (subgenre == null || book.selectedSubgenres.ifEmpty { listOf("Sem subgênero") }.any { key(it) == key(subgenre) }) &&
                (saga == null || key(book.saga.ifBlank { "Sem saga" }) == key(saga)) &&
                (author == null || key(book.author.ifBlank { "Sem autor" }) == key(author)) &&
                (when (ratings) { ShelfRatings.ALL -> true; ShelfRatings.RATED -> book.rating != null; ShelfRatings.UNRATED -> book.rating == null }) &&
                (minimumRating == null || book.rating?.let { it >= minimumRating } == true) &&
                when (status) {
                    ShelfStatus.ALL -> true
                    ShelfStatus.UNREAD -> book.currentPage == 0
                    ShelfStatus.READING -> book.currentPage > 0 && !book.complete
                    ShelfStatus.COMPLETED -> book.complete
                    ShelfStatus.CURRENT -> book.id == currentId
                } && when (covers) {
                    ShelfCovers.ALL -> true
                    ShelfCovers.WITH -> book.cover.isNotBlank()
                    ShelfCovers.WITHOUT -> book.cover.isBlank()
                }
        }
        val title = compareBy<LibraryBook> { key(it.title) }.thenBy { it.id }
        val order = when (sort) {
            ShelfSort.TITLE -> title
            ShelfSort.TITLE_REVERSE -> Comparator { a, b -> title.compare(b, a) }
            ShelfSort.AUTHOR -> compareBy<LibraryBook> { it.author.isBlank() }.thenBy { key(it.author) }.then(title)
            ShelfSort.PROGRESS_HIGH -> compareByDescending<LibraryBook> { it.currentPage.toDouble() / it.totalPages }.then(title)
            ShelfSort.PROGRESS_LOW -> compareBy<LibraryBook> { it.currentPage.toDouble() / it.totalPages }.then(title)
            ShelfSort.RECENT -> compareByDescending<LibraryBook> { it.updatedAt }.then(title)
            ShelfSort.GENRE -> compareBy<LibraryBook> { it.genre.isBlank() }.thenBy { key(it.genre) }.then(title)
            ShelfSort.SAGA -> compareBy<LibraryBook> { it.saga.isBlank() }.thenBy { key(it.saga) }.thenBy { it.sequenceNumber ?: Int.MAX_VALUE }.then(title)
            ShelfSort.RATING_HIGH -> compareBy<LibraryBook> { it.rating == null }.thenByDescending { it.rating ?: 0.0 }.then(title)
            ShelfSort.RATING_LOW -> compareBy<LibraryBook> { it.rating == null }.thenBy { it.rating ?: 0.0 }.then(title)
            ShelfSort.SEQUENCE -> compareBy<LibraryBook> { it.sequenceNumber ?: Int.MAX_VALUE }.then(title)
        }
        val priorities = this.order.distinctBy { it.field }
        if (priorities.isEmpty()) return matches.sortedWith(order)
        val dynamic = Comparator<LibraryBook> { a, b ->
            var result = 0
            for (criterion in priorities) {
                val field = criterion.field
                fun missing(book: LibraryBook) = when (field) {
                    ShelfField.SUBGENRE -> book.selectedSubgenres.isEmpty(); ShelfField.GENRE -> book.genre.isBlank(); ShelfField.SAGA -> book.saga.isBlank()
                    ShelfField.RATING -> book.rating == null; ShelfField.AUTHOR -> book.author.isBlank(); ShelfField.SEQUENCE -> book.sequenceNumber == null
                    else -> false
                }
                result = missing(a).compareTo(missing(b))
                if (result != 0) break // Missing metadata always goes last, including descending order.
                result = when (field) {
                    ShelfField.SUBGENRE -> a.selectedSubgenres.map(::key).sorted().joinToString("|").compareTo(b.selectedSubgenres.map(::key).sorted().joinToString("|"))
                    ShelfField.GENRE -> key(a.genre).compareTo(key(b.genre))
                    ShelfField.SAGA -> key(a.saga).compareTo(key(b.saga))
                    ShelfField.TITLE -> key(a.title).compareTo(key(b.title))
                    ShelfField.AUTHOR -> key(a.author).compareTo(key(b.author))
                    ShelfField.SEQUENCE -> (a.sequenceNumber ?: 0).compareTo(b.sequenceNumber ?: 0)
                    ShelfField.PROGRESS -> (a.currentPage.toDouble() / a.totalPages.coerceAtLeast(1)).compareTo(b.currentPage.toDouble() / b.totalPages.coerceAtLeast(1))
                    ShelfField.RATING -> (a.rating ?: 0.0).compareTo(b.rating ?: 0.0)
                    ShelfField.UPDATED -> a.updatedAt.compareTo(b.updatedAt)
                }
                if (criterion.descending) result = -result
                if (result != 0) break
            }
            if (result == 0) title.compare(a, b) else result
        }
        return matches.sortedWith(dynamic)
    }
    companion object {
        private val accents = Regex("\\p{M}+")
        fun key(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replace(accents, "").lowercase(Locale.ROOT)
    }
}
