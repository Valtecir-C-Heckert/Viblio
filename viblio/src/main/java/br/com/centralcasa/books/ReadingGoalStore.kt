package br.com.centralcasa.books

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Opaque private shelf preferences are already synchronized losslessly by older clients/APIs. */
class ReadingGoalStore(context:Context) {
    private val profile=LibraryProfiles.settings(context)
    private val prefs=context.getSharedPreferences(profile,0)
    companion object {const val PREFIX="SHELF_READING_GOAL:";private val lock=Any();const val MAX_JSON=1_048_576}
    data class Entry(val id:String,val raw:String,val goal:ReadingGoal?)
    fun entries()=prefs.all.entries.filter {it.key.startsWith(PREFIX)}.sortedBy {it.key}.map {row->val raw=row.value as? String ?: "";Entry(row.key.removePrefix(PREFIX),raw,runCatching {decode(raw).also {require(it.id==row.key.removePrefix(PREFIX))}}.getOrNull())}.sortedWith(compareBy<Entry> {it.goal?.createdAt ?: Long.MAX_VALUE}.thenBy {it.id})
    fun expanded()=prefs.getBoolean("reading_goals_expanded",false)
    fun expanded(value:Boolean){prefs.edit().putBoolean("reading_goals_expanded",value).apply()}
    fun count()=prefs.all.keys.count {it.startsWith(PREFIX)}
    fun save(goal:ReadingGoal,expected:String?){synchronized(lock){
        val key=PREFIX+goal.id;check(prefs.all[key]==expected){"A meta mudou. Reabra para conferir."}
        check(expected!=null || count()<24){"Limite de metas atingido. Remova uma meta para criar outra."}
        val raw=encode(goal.validated());check(raw.length<=MAX_JSON && raw.toByteArray().size<=MAX_JSON){"Histórico grande demais. Crie outra meta para continuar."}
        check(prefs.edit().putString(key,raw).commit()){"Não foi possível salvar a meta."}
    }}
    fun remove(entry:Entry){synchronized(lock){check(prefs.all[PREFIX+entry.id].let {it==entry.raw || it !is String && entry.raw.isEmpty()}){"A meta mudou. Reabra para conferir."};check(prefs.edit().remove(PREFIX+entry.id).commit())}}
    private fun credit(c:GoalCredit)=JSONObject().put("bookId",c.bookId).put("title",c.title).put("author",c.author).put("cover",c.cover).put("at",c.at)
    private fun encode(g:ReadingGoal)=JSONObject().put("v",1).put("id",g.id).put("name",g.name).put("metric",g.metric.name).put("target",g.target).put("reward",g.reward).put("period",g.period.name).put("kind",g.kind).put("createdAt",g.createdAt).put("fromAt",g.fromAt)
        .put("baseline",JSONObject().also {o->g.baseline.toSortedMap().forEach {(k,v)->o.put(k,v)}}).put("excluded",JSONArray(g.excluded.sorted()))
        .put("cycles",JSONArray().also {a->g.cycles.forEach {c->a.put(JSONObject().put("id",c.id).put("at",c.at).put("reward",c.reward).put("books",JSONArray().also {b->c.books.forEach {b.put(credit(it))}}))}}).toString()
    private fun decode(raw:String):ReadingGoal {
        require(raw.length<=MAX_JSON && raw.toByteArray().size<=MAX_JSON)
        var depth=0;var quoted=false;var escape=false
        raw.forEach {c->if(quoted){if(escape)escape=false else if(c=='\\')escape=true else if(c=='"')quoted=false}else when(c){'"'->quoted=true;'[','{'->{depth++;require(depth<=12)};']','}'->depth--}}
        val o=JSONObject(raw);require(o.getInt("v")==1)
        val base=o.getJSONObject("baseline");require(base.length()<=5000)
        val excluded=o.getJSONArray("excluded");require(excluded.length()<=5000)
        val cycles=o.getJSONArray("cycles");require(cycles.length()<=256)
        return ReadingGoal(id=o.getString("id"),name=o.getString("name"),metric=GoalMetric.valueOf(o.getString("metric")),target=o.getInt("target"),reward=o.getInt("reward"),period=GoalPeriod.valueOf(o.getString("period")),kind=o.getString("kind"),createdAt=o.getLong("createdAt"),fromAt=o.optLong("fromAt",o.getLong("createdAt")),
            baseline=base.keys().asSequence().associateWith {base.getInt(it)},excluded=(0 until excluded.length()).map {excluded.getString(it)}.toSet(),cycles=(0 until cycles.length()).map {i->val c=cycles.getJSONObject(i);val b=c.getJSONArray("books");require(b.length() in 1..100);GoalCycle(c.getString("id"),c.getLong("at"),c.getInt("reward"),(0 until b.length()).map {n->val v=b.getJSONObject(n);GoalCredit(v.getString("bookId"),v.getString("title"),v.getString("author"),v.getString("cover"),v.getLong("at"))})}).validated()
    }
}
