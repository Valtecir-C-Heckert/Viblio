package br.com.centralcasa.books.catalog

import br.com.centralcasa.books.LibraryBook

data class CatalogBook(val id:String,val source:String,val title:String,val author:String="",val genre:String="",val subgenre:String="",val publisher:String="",val saga:String="",val pages:Int?=null,val sequence:Int?=null,val coverUrl:String="",val isbn:String="",val category:String="",val type:String="") {
    val kind get():String {
        val tags=listOf(category,type).map(br.com.centralcasa.books.ShelfFilters::key)
        return when { tags.any { it in listOf("manga","mangas") } -> "manga"; tags.any { it in listOf("quadrinho","quadrinhos","hq","comic","comics") } -> "comic"; else -> "book" }
    }
    fun fill(book:LibraryBook):LibraryBook = book.copy(title=title,author=author,publisher=publisher,genre=genre,
        subgenre=subgenre.split(';').firstOrNull().orEmpty().trim(),subgenres=subgenre.split(';').map(String::trim).filter(String::isNotBlank).distinct().take(3),saga=saga,sequenceNumber=sequence,
        totalPages=pages ?: 0,pagesKnown=pages!=null,catalogId=id,catalogSource=source)
}

data class CatalogCover(val id:String,val url:String,val primary:Boolean=false,val bookId:String="",val title:String="",val publisher:String="",val isbn:String="",val pages:Int?=null,val sequence:Int?=null)
data class CatalogCoverPage(val source:String,val items:List<CatalogCover>,val nextCursor:Int?=null,val truncated:Boolean=false)
data class RecommendationSection(val key:String,val title:String,val tag:String,val items:List<CatalogBook>)
data class CatalogRecommendations(val anchorTitle:String,val anchorKind:String,val sections:List<RecommendationSection>)

/** Optional catalogue capability. The Central leaves this null; the Viblio hosts its adapter. */
interface BookCatalog {
    val supportsIsbnScan:Boolean get()=false
    fun scanIsbn() {}
    val configured:Boolean
    fun configure()
    fun search(title:String,author:String="",isbn:String="",done:(Result<List<CatalogBook>>)->Unit)
    fun searchIsbn(isbn:String,done:(Result<List<CatalogBook>>)->Unit){
        val versions=br.com.centralcasa.books.Isbn.variants(isbn)
        search("",isbn=versions.first()){result->
            if(result.isSuccess && result.getOrNull().isNullOrEmpty() && versions.size==2)search("",isbn=versions[1],done=done) else done(result)
        }
    }
    fun loadCover(book:CatalogBook,done:(Result<String>)->Unit)
    fun chooseCover(id:String,source:String,done:(Result<CatalogCover>)->Unit) { done(Result.failure(IllegalStateException("Capas alternativas indisponíveis neste catálogo."))) }
    fun recommendations(kind:String="all",seed:Int=0,done:(Result<CatalogRecommendations>)->Unit) { done(Result.success(CatalogRecommendations("","none",emptyList()))) }
    fun libraryFingerprint():String = ""
    fun wishlist(done:(Result<List<CatalogBook>>)->Unit) { done(Result.success(emptyList())) }
    fun addWishlist(book:CatalogBook,done:(Result<CatalogBook>)->Unit) { done(Result.failure(IllegalStateException("Lista de desejos indisponível neste catálogo."))) }
    fun removeWishlist(catalogId:String,done:(Result<Unit>)->Unit) { done(Result.success(Unit)) }
    fun saved(book:LibraryBook)
}
