package br.com.centralcasa.books

import java.util.UUID

enum class GoalMetric(val label:String){BOOKS("Livros"),PAGES("Páginas"),PURCHASE("Próxima compra")}
enum class GoalPeriod(val label:String){DAY("Dia"),WEEK("Semana"),MONTH("Mês"),YEAR("Ano"),CONTINUOUS("Ciclo contínuo")}
data class GoalCredit(val bookId:String,val title:String,val author:String,val cover:String,val at:Long,val pages:Long=0,val sessionIds:List<String> = emptyList())
data class GoalCycle(val id:String=UUID.randomUUID().toString(),val at:Long,val reward:Int,val books:List<GoalCredit>,val purchased:List<String> = emptyList())
data class ReadingGoal(val id:String=UUID.randomUUID().toString(),val name:String,val metric:GoalMetric,val target:Int,val reward:Int=1,val period:GoalPeriod=GoalPeriod.YEAR,val kind:String="book",val createdAt:Long,val fromAt:Long=createdAt,val baseline:Map<String,Int>,val excluded:Set<String>,val cycles:List<GoalCycle> = emptyList()) {
    fun validated():ReadingGoal {
        require(UUID.fromString(id).toString()==id && name.isNotBlank() && name.length<=80)
        require(target in 1..if(metric==GoalMetric.PAGES)10_000_000 else if(metric==GoalMetric.PURCHASE)100 else 5000)
        require(reward in 1..100 && kind in listOf("book","manga","comic","all") && createdAt>=0 && fromAt in 0..createdAt && (metric!=GoalMetric.PURCHASE || fromAt==createdAt))
        require(baseline.size<=5000 && excluded.size<=5000 && baseline.all {it.key.length in 1..200 && it.value in 0..100000} && excluded.all {it.length in 1..200})
        require(cycles.size<=256 && cycles.map {it.id}.distinct().size==cycles.size)
        val used=mutableSetOf<String>()
        cycles.forEach {cycle->
            require(cycle.at>=createdAt && cycle.reward in 1..100 && cycle.books.size in 1..100)
            require(cycle.purchased.size<=100 && cycle.purchased.all {it.isNotBlank() && it.length<=200})
            require(UUID.fromString(cycle.id).toString()==cycle.id)
            cycle.books.forEach {require(it.bookId.length in 1..200 && it.title.length<=500 && it.author.length<=200 && it.cover.length<=200 && it.at>=createdAt && used.add(it.bookId))}
        }
        return this
    }
}
data class GoalProgress(val credits:List<GoalCredit>,val value:Long,val label:String){fun remaining(goal:ReadingGoal)=(goal.target-value).coerceAtLeast(0)}
object ReadingGoals {
    fun progress(goal:ReadingGoal,books:List<LibraryBook>,sessions:List<ReadingSession>,now:Long):GoalProgress {
        goal.validated()
        val range=if(goal.period==GoalPeriod.CONTINUOUS)null else ReadingPeriods.range(ReadingDays.at(now),ReadingPeriodUnit.valueOf(goal.period.name))
        fun inPeriod(day:String)=range?.contains(day) ?: true
        val byId=books.filter {goal.kind=="all" || it.kind==goal.kind}.associateBy {it.id}
        val label=range?.label ?: "Ciclo contínuo"
        val records=sessions.filter {it.bookId in byId && it.finishedAt in goal.fromAt..now}
        if(goal.metric==GoalMetric.PAGES){
            val timed=records.filter {byId[it.bookId]?.pagesKnown==true && inPeriod(it.readingDay ?: ReadingDays.at(it.startedAt))}
            val credits=timed.groupBy {it.bookId}.mapNotNull {(id,rows)->
                val b=byId.getValue(id)
                val pages=rows.sumOf {r->(r.endPage-maxOf(r.startPage,if(r.startedAt<goal.fromAt)goal.baseline[id] ?: 0 else r.startPage)).coerceAtLeast(0).toLong()}
                if(pages==0L)null else GoalCredit(id,b.title,b.author,b.cover,rows.maxOf {it.finishedAt},pages,rows.map {it.id})
            }.sortedByDescending {it.pages}
            return GoalProgress(credits,credits.sumOf {it.pages},label)
        }
        val used=if(goal.metric==GoalMetric.PURCHASE)goal.cycles.flatMap {it.books}.map {it.bookId}.toSet() else emptySet()
        val completions=records.filter {r->val b=byId.getValue(r.bookId);b.pagesKnown && r.endPage>=b.totalPages && inPeriod(ReadingDays.at(r.finishedAt))}.groupBy {it.bookId}
        val credits=byId.values.mapNotNull {b->
            if(b.id in used || goal.metric==GoalMetric.PURCHASE && b.id in goal.excluded)return@mapNotNull null
            val at=if(b.pagesKnown)completions[b.id]?.minOfOrNull {it.finishedAt} else b.updatedAt.takeIf {b.complete && b.id !in goal.excluded}
            if(at==null || at !in goal.fromAt..now || !inPeriod(ReadingDays.at(at)))null else GoalCredit(b.id,b.title,b.author,b.cover,at)
        }.sortedWith(compareBy<GoalCredit> {it.at}.thenBy {it.bookId})
        return GoalProgress(credits,credits.size.toLong(),label)
    }
    /** Number of full purchase cycles currently unlocked and stacked (waiting to be redeemed). */
    fun unlockedCycles(goal:ReadingGoal,progress:GoalProgress):Int =
        if(goal.metric!=GoalMetric.PURCHASE || goal.target<=0) 0 else (progress.value/goal.target).toInt().coerceAtLeast(0)
    /** Total reward books stacked across all unlocked purchase cycles. */
    fun stackedRewards(goal:ReadingGoal,progress:GoalProgress):Int = unlockedCycles(goal,progress)*goal.reward
    /** Books read toward the next (not-yet-unlocked) cycle beyond any stacked cycles. */
    fun currentCycleProgress(goal:ReadingGoal,progress:GoalProgress):Int =
        if(goal.metric!=GoalMetric.PURCHASE || goal.target<=0) 0 else (progress.value%goal.target).toInt().coerceAtLeast(0)
    fun purchase(goal:ReadingGoal,progress:GoalProgress,now:Long,purchased:List<String> = emptyList()):ReadingGoal {
        require(goal.metric==GoalMetric.PURCHASE && progress.value>=goal.target && progress.credits.size>=goal.target && progress.value==progress.credits.size.toLong()){"A compra ainda não foi liberada."}
        require(goal.cycles.size<256){"Histórico cheio. Crie uma nova meta para continuar."}
        val clean=purchased.map {it.trim().take(200)}.filter {it.isNotEmpty()}.take(100)
        return goal.copy(cycles=goal.cycles+GoalCycle(at=now,reward=goal.reward,books=progress.credits.take(goal.target),purchased=clean)).validated()
    }
    fun updateCyclePurchased(goal:ReadingGoal,cycleId:String,purchased:List<String>):ReadingGoal {
        val clean=purchased.map {it.trim().take(200)}.filter {it.isNotEmpty()}.take(100)
        val idx=goal.cycles.indexOfFirst {it.id==cycleId}
        require(idx>=0){"Ciclo não encontrado."}
        val next=goal.cycles.toMutableList()
        next[idx]=next[idx].copy(purchased=clean)
        return goal.copy(cycles=next).validated()
    }
}
