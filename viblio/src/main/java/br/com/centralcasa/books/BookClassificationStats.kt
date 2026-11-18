package br.com.centralcasa.books

/** Each classification counts books, including all selected tags, once per book. */
object BookClassificationStats {
    data class Count(val name: String, val books: Int)
    fun genres(books: List<LibraryBook>) = count(books.map { listOf(it.genre.ifBlank { "Sem gênero" }) })
    fun subgenres(books: List<LibraryBook>) = count(books.map { it.selectedSubgenres })
    private fun count(classifications: List<List<String>>): List<Count> {
        val names = linkedMapOf<String, String>()
        val totals = linkedMapOf<String, Int>()
        classifications.forEach { tags ->
            tags.map(String::trim).filter(String::isNotBlank).distinctBy(ShelfFilters::key).forEach { name ->
                val key = ShelfFilters.key(name)
                names.getOrPut(key) { name }
                totals[key] = (totals[key] ?: 0) + 1
            }
        }
        return totals.map { (key, total) -> Count(names.getValue(key), total) }
            .sortedWith(compareByDescending<Count> { it.books }.thenBy { ShelfFilters.key(it.name) })
    }
}
