package br.com.centralcasa.books

/** Only recognized legacy labels are split; unknown/custom classifications remain intact. */
object BookGenres {
    data class Classification(val genre: String, val subgenre: String = "")
    private val fantasy = mapOf(
        "dark" to "Dark", "sombria" to "Sombria", "epica" to "Épica", "urbana" to "Urbana",
        "alta" to "Alta fantasia", "baixa" to "Baixa fantasia", "heroica" to "Heroica",
        "historica" to "Histórica", "romantica" to "Romântica"
    )
    fun split(value: String): Classification {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        val key = ShelfFilters.key(clean)
        // The user's two other parent categories accept any suffix as a subgenre.
        Regex("(?iu)^n[ãa]o[- ]fic[çc][ãa]o(?=$|[ :–—-])").find(clean)?.let {
            return Classification("Não-Ficção", clean.substring(it.range.last + 1).trim(' ', '-', ':', '–', '—'))
        }
        Regex("(?iu)^terror(?=$|[ :–—-])").find(clean)?.let {
            return Classification("Terror", clean.substring(it.range.last + 1).trim(' ', '-', ':', '–', '—'))
        }
        if (key == "fantasia") return Classification("Fantasia")
        if (key.startsWith("fantasia ")) fantasy[key.removePrefix("fantasia ")]?.let { return Classification("Fantasia", it) }
        return Classification(clean)
    }
    fun normalize(book: LibraryBook): LibraryBook {
        val selected = book.selectedSubgenres.map(String::trim).distinctBy(ShelfFilters::key)
        val category = split(book.genre)
        val tags = (listOf(category.subgenre).filter(String::isNotBlank) + selected).distinctBy(ShelfFilters::key)
        return book.copy(genre = category.genre, subgenre = tags.firstOrNull().orEmpty(), subgenres = tags)
    }
    fun suggestions(values: List<String>) = values.map(String::trim).filter(String::isNotBlank)
        .distinctBy(ShelfFilters::key).sortedBy(ShelfFilters::key)
    fun subgenres(books: List<LibraryBook>, genre: String) = suggestions(books.filter {
        ShelfFilters.key(split(it.genre).genre) == ShelfFilters.key(split(genre).genre)
    }.flatMap { normalize(it).selectedSubgenres })
}
