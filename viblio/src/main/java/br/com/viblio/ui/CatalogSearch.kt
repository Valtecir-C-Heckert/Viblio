package br.com.viblio.ui

import br.com.centralcasa.books.catalog.CatalogBook

/** Search routing and display filtering, independent of Android views. */
object CatalogSearch {
    fun isbn(query:String):String? = query.replace(Regex("[\\s-]"), "").uppercase().takeIf { it.matches(Regex("(?:[0-9]{9}[0-9X]|[0-9]{13})")) }
    fun merge(first:List<CatalogBook>,second:List<CatalogBook>) = (first+second).distinctBy { it.source to it.id }
    fun filter(items:List<CatalogBook>,kind:String) = if(kind=="all") items else items.filter { it.kind==kind }
    fun edition(book:CatalogBook):String = listOf(
        when(book.kind) { "manga"->"Mangá";"comic"->"Quadrinho";else->"Livro" },
        book.saga,book.sequence?.let { if(book.kind=="book") "Livro $it" else "Volume $it" }.orEmpty(),book.publisher
    ).filter(String::isNotBlank).joinToString(" · ")
}
