package br.com.centralcasa.books

/** Ownership is independent from reading progress; duplicate copies never fill a missing number. */
enum class CollectionCompletion(val label:String) {
    ALL("Todas"), COMPLETE("Completas"), INCOMPLETE("Incompletas"), UNKNOWN("Total não informado")
}
data class CollectionInventory(val expected:Int?,val missing:Int?) {
    val status get()=when { expected==null -> CollectionCompletion.UNKNOWN;missing==0 -> CollectionCompletion.COMPLETE;else -> CollectionCompletion.INCOMPLETE }
    companion object {
        fun expected(title:CollectionTitle,books:List<LibraryBook>):Int? = title.expectedVolumes ?: title.missingVolumes?.let { missing ->
            // Legacy snapshots store a remaining count. Freeze its implied total once.
            (missing+books.filter {it.collectionId==title.id}.distinctBy {it.sequenceNumber?.let {n->"number:$n"} ?: "id:${it.id}"}.size).takeIf {it>0}
        }
        fun of(title:CollectionTitle,books:List<LibraryBook>):CollectionInventory {
            val total=expected(title,books) ?: return CollectionInventory(null,null)
            val numbers=books.filter {it.collectionId==title.id && !it.archived}.mapNotNull {it.sequenceNumber}.filter {it in 1..total}.toSet()
            return CollectionInventory(total,(total-numbers.size).coerceAtLeast(0))
        }
    }
}
