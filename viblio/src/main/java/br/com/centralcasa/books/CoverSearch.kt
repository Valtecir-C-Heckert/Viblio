package br.com.centralcasa.books

/** Search the precise medium and owned edition; never invent an unknown sequence number. */
object CoverSearch {
    fun query(title: String, author: String, kind: String, number: Int?, publisher: String = ""): String {
        val type = when (kind) { "manga" -> "mangá"; "comic" -> "quadrinho"; else -> "livro" }
        val sequence = number?.takeIf { it > 0 }?.let { "${if (kind == "book") "livro" else "volume"} $it" }.orEmpty()
        return listOf(title.trim(), author.trim(), publisher.trim(), "capa", type, sequence).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
