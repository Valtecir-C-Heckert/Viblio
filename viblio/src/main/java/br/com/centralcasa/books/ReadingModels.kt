package br.com.centralcasa.books

import java.util.UUID

data class LibraryBook(
    val id: String = UUID.randomUUID().toString(),
    val title: String, val author: String = "", val genre: String = "",
    val totalPages: Int, val currentPage: Int = 0,
    val cover: String = "", val background: String = "black",
    val updatedAt: Long = System.currentTimeMillis(),
    val sequenceNumber: Int? = null,
    val saga: String = "",
    val subgenre: String = "",
    val subgenres: List<String> = emptyList(),
    val kind: String = "book", val collectionId: String = "", val publisher: String = "",
    val rating: Double? = null, val pagesKnown: Boolean = true, val readFinished: Boolean = false,
    val catalogId:String?=null,val catalogSource:String="", val archived:Boolean=false
) {
    val selectedSubgenres get() = subgenres.ifEmpty { listOf(subgenre).filter(String::isNotBlank) }
    val percent get() = if (!pagesKnown) { if (readFinished) 100 else 0 } else (currentPage.toLong() * 100 / totalPages.coerceAtLeast(1)).toInt()
    val complete get() = if (pagesKnown) currentPage == totalPages else readFinished
    fun validated(): LibraryBook {
        require(catalogId==null || catalogId.toLongOrNull()!=null && catalogSource.isNotBlank() && catalogSource.length<=200) { "Vínculo do catálogo inválido." }
        validateRating(rating)
        require(kind in CollectionKinds.labels.keys && kind != "all") { "Tipo inválido." }
        require(pagesKnown || collectionId.isNotBlank() && totalPages == 1 && currentPage == 0) { "Páginas desconhecidas só são aceitas em volumes de uma coleção." }
        require(sequenceNumber == null || sequenceNumber in 1..10000) { "Número na saga: use um número entre 1 e 10.000 ou deixe vazio." }
        require(selectedSubgenres.size <= 3) { "Escolha até três subgêneros por livro." }
        require(selectedSubgenres.all(String::isNotBlank)) { "Subgênero vazio." }
        require(selectedSubgenres.map(ShelfFilters::key).distinct().size == selectedSubgenres.size) { "Subgêneros repetidos." }
        require(selectedSubgenres.isEmpty() || genre.isNotBlank()) { "Informe o gênero principal para usar um subgênero." }
        require(title.isNotBlank()) { "Informe o título." }
        require(totalPages in 1..100000) { "Total de páginas: use um número entre 1 e 100.000." }
        require(currentPage in 0..totalPages) { "A página atual precisa estar entre 0 e $totalPages." }
        require(background in backgrounds) { "Fundo inválido." }
        require(id.isNotBlank() && id.length <= 200) { "Identificador inválido." }
        return this
    }
    companion object { val backgrounds = setOf("black", "graphite", "slate", "warm", "blue", "wine", "cover") }
}
data class ReadingSession(
    val id: String = UUID.randomUUID().toString(), val bookId: String,
    val startedAt: Long, val finishedAt: Long, val durationMs: Long,
    val startPage: Int, val endPage: Int,
    val readingDay: String? = null
) {
    val pages get() = endPage - startPage
    val millisPerPage get() = if (pages > 0) durationMs / pages else null
    fun validated(totalPages: Int): ReadingSession {
        readingDay?.let(ReadingDays::validate)
        require(id.isNotBlank() && id.length <= 200 && bookId.isNotBlank()) { "Identificador de sessão inválido." }
        require(durationMs >= 0) { "Tempo inválido." }
        require(startPage in 0..totalPages && endPage in startPage..totalPages) { "Página final inválida." }
        require(finishedAt >= startedAt && startedAt >= 0) { "Datas inválidas." }
        return this
    }
}
data class ReadingDraft(
    val bookId: String, val startPage: Int, val startedAt: Long,
    val elapsedMs: Long = 0, val runningSince: Long? = null, val boot: Int = -1
) {
    fun duration(nowElapsed: Long, currentBoot: Int): Long = elapsedMs +
        if (runningSince != null && currentBoot == boot && nowElapsed >= runningSince) nowElapsed - runningSince else 0
    fun checkpoint(nowElapsed: Long, currentBoot: Int): ReadingDraft {
        val running = runningSince != null && currentBoot == boot && nowElapsed >= runningSince
        return copy(elapsedMs = duration(nowElapsed, currentBoot), runningSince = if (running) nowElapsed else null, boot = currentBoot)
    }
}
data class ReadingSummary(val durationMs: Long, val timedPages: Int) {
    val millisPerPage get() = if (timedPages > 0) durationMs / timedPages else null
    companion object {
        fun from(sessions: List<ReadingSession>) = ReadingSummary(sessions.sumOf { it.durationMs }, sessions.sumOf { it.pages })
    }
}
fun readingTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
fun readingPace(ms: Long?): String = if (ms == null) "Sem páginas cronometradas" else {
    val seconds = ms / 1000
    "${seconds / 60} min ${seconds % 60} s / página"
}

/** An active timer counts forward from its original page; manual corrections may move both ways above that floor. */
fun readingProgress(book: LibraryBook, page: Int, draft: ReadingDraft?): LibraryBook {
    require(page in 0..book.totalPages) { "Use uma página entre 0 e ${book.totalPages}." }
    if (draft?.bookId == book.id) require(page >= draft.startPage) {
        "Esta leitura começou na página ${draft.startPage}. Pause e descarte o tempo para corrigir uma página anterior."
    }
    return book.copy(currentPage = page)
}
