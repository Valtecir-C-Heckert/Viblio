package br.com.centralcasa.books

/** Accent-insensitive name completion, including surnames and small typing mistakes. */
object BookMetadata {
    fun suggestions(values: List<String>, query: String = ""): List<String> {
        val needle = ShelfFilters.key(query)
        val terms = needle.split(Regex("\\s+")).filter(String::isNotBlank)
        fun score(value: String): Int {
            val key = ShelfFilters.key(value)
            if (needle.isEmpty() || key == needle) return 0
            if (key.startsWith(needle)) return 1
            if (terms.all(key::contains)) return 2
            val words = key.split(Regex("\\s+"))
            if (terms.all { term -> words.any { word ->
                word.startsWith(term) || (term.length in 3..64 && word.length <= 64 && distance(term, word) <= if (term.length >= 5) 2 else 1)
            } }) return 3
            return 4
        }
        return BookGenres.suggestions(values).map { it to score(it) }.filter { it.second < 4 }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { ShelfFilters.key(it.first) }).take(12).map { it.first }
    }
    private fun distance(a: String, b: String): Int {
        val rows = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) rows[i][0] = i
        for (j in 0..b.length) rows[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            rows[i][j] = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1, rows[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) rows[i][j] = minOf(rows[i][j], rows[i - 2][j - 2] + 1)
        }
        return rows[a.length][b.length]
    }
}
