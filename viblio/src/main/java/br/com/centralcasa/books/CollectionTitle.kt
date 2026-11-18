package br.com.centralcasa.books

import java.util.UUID

object CollectionKinds {
    val labels = linkedMapOf("book" to "Livros", "manga" to "Mangás", "comic" to "Quadrinhos", "all" to "Todos")
}
data class CollectionTitle(
    val id: String = UUID.randomUUID().toString(), val kind: String = "manga", val title: String,
    val author: String = "", val genre: String = "", val publisher: String = "",
    val missingVolumes: Int? = null, val sourceReadCount: Int = 0,
    val sourceRating: Double? = null, val rating: Double? = null, val cover: String = "",
    val updatedAt: Long = System.currentTimeMillis(), val subgenres: List<String> = emptyList(), val archived: Boolean = false, val expectedVolumes: Int? = null
) {
    fun validated(): CollectionTitle {
        require(id.isNotBlank() && id.length <= 200 && title.isNotBlank()) { "Informe o título da coleção." }
        require(kind in CollectionKinds.labels.keys && kind != "all") { "Tipo inválido." }
        require(missingVolumes == null || missingVolumes in 0..10000) { "Quantidade faltante inválida." }
        require(expectedVolumes == null || expectedVolumes in 1..10000) { "Total de volumes inválido." }
        require(sourceReadCount in 0..10000) { "Leitura inválida." }
        require(sourceRating == null || sourceRating.isFinite() && sourceRating in 0.0..10.0) { "Nota original inválida." }
        require(subgenres.size <= 3 && subgenres.all { it.isNotBlank() } && subgenres.map(ShelfFilters::key).distinct().size == subgenres.size) { "Escolha até três subgêneros diferentes." }
        validateRating(rating)
        return this
    }
    /** Keep the existing book ID so sessions, dates and the current reading stay attached. */
    fun lightNovelVolumeOne(book: LibraryBook): LibraryBook {
        require(kind == "book" && book.kind == "book" && book.collectionId.isBlank()) { "Escolha um livro sem coleção." }
        return book.copy(collectionId = id, sequenceNumber = 1, saga = title,
            title = title, author = author, publisher = publisher, genre = genre,
            subgenres = subgenres, subgenre = subgenres.firstOrNull().orEmpty(), cover = cover,
            totalPages = if (book.totalPages > 0) book.totalPages else 1,
            pagesKnown = book.pagesKnown && book.totalPages > 0).validated()
    }
    fun shelfBook(volumes: List<LibraryBook>): LibraryBook {
        val owned = volumes.filter { it.collectionId == id }
        val read = owned.count { it.complete }
        return LibraryBook(id = id, title = title, author = author, genre = genre, totalPages = owned.size.coerceAtLeast(1), currentPage = read,
            cover = owned.firstOrNull { it.sequenceNumber == 1 }?.cover.orEmpty(), updatedAt = updatedAt,
            kind = kind, publisher = publisher, rating = rating, subgenres = subgenres, subgenre = subgenres.firstOrNull().orEmpty())
    }
}
fun validateRating(value: Double?) {
    require(value == null || value.isFinite() && value in 0.0..5.0) { "Use uma nota entre 0 e 5 estrelas." }
}

/** Map a star-slider fraction to 0..5 in half-point steps. */
fun halfStarRating(fraction: Double): Double {
    require(fraction.isFinite()) { "Nota inválida." }
    return Math.round(fraction.coerceIn(0.0, 1.0) * 10) / 2.0
}
