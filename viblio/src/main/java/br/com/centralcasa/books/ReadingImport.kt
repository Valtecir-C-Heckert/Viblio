package br.com.centralcasa.books

/** Metadata can be imported during a paused session without rewinding its page range. */
fun preserveReadingOnImport(incoming: LibraryBook, current: LibraryBook?, draft: ReadingDraft?): LibraryBook {
    require(draft?.runningSince == null) { "Pause a leitura antes de importar." }
    if (draft == null || incoming.id != draft.bookId) return incoming
    val existing = requireNotNull(current) { "Livro da sessão não encontrado." }
    require(existing.id == incoming.id) { "Livro da sessão incompatível." }
    return incoming.copy(totalPages = existing.totalPages, currentPage = existing.currentPage,
        pagesKnown = existing.pagesKnown, readFinished = existing.readFinished)
}
