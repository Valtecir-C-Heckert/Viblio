package br.com.centralcasa.books

/** IDs, never titles: renames and reading progress cannot change the saved positions. */
object ManualShelfOrder {
    fun withHidden(saved:List<String>,activeOrder:List<String>):List<String> {
        val active=activeOrder.distinct();val visible=active.toSet();var index=0
        return saved.distinct().map {if(it in visible)active[index++] else it}+active.drop(index)
    }
    fun reconcile(saved:List<String>,available:List<String>):List<String> {
        val valid=available.toSet()
        val ordered=saved.filter { it in valid }.distinct()
        val existing=ordered.toSet()
        return ordered+available.filter { it !in existing }.distinct()
    }
    fun move(order:List<String>,from:String,to:String):List<String> {
        val origin=order.indexOf(from);val target=order.indexOf(to)
        if(origin<0 || target<0 || origin==target) return order
        return order.toMutableList().apply { removeAt(origin);add(target.coerceAtMost(size),from) }
    }
    fun select(items:List<LibraryBook>,order:List<String>):List<LibraryBook> {
        val byId=items.associateBy { it.id }
        return reconcile(order,items.map { it.id }).mapNotNull(byId::get)
    }
}
