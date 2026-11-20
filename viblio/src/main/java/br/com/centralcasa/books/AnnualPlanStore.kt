package br.com.centralcasa.books

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private profile-scoped storage for unified yearly Goals & Reading Plans.
 * Uses the `SHELF_` prefix so cloud backup/sync (`personal_library.py`) preserves plans losslessly.
 */
class AnnualPlanStore(context: Context) {
    private val profile = LibraryProfiles.settings(context)
    private val prefs = context.getSharedPreferences(profile, 0)

    companion object {
        const val PREFIX = "SHELF_ANNUAL_PLAN:"
        const val MAX_JSON = 512 * 1024
        const val MAX_YEARS = 10
        private val lock = Any()

        internal fun encode(plan: AnnualReadingPlan): String {
            val valid = plan.validated()
            val arr = JSONArray()
            valid.entries.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("month", e.month)
                        .put("position", e.position)
                        .put("kind", e.kind.name)
                        .put("bookId", e.bookId)
                        .put("wishlistCatalogId", e.wishlistCatalogId)
                        .put("title", e.title)
                        .put("author", e.author)
                        .put("saga", e.saga)
                        .put("coverUrl", e.coverUrl)
                        .put("reservedPages", e.reservedPages)
                        .put("tag", e.tag)
                        .put("completed", e.completed)
                        .put("reread", e.reread),
                )
            }
            val exMonth = JSONArray().apply { valid.excludedMonthBooks.sorted().forEach(::put) }
            val exYear = JSONArray().apply { valid.excludedYearBooks.sorted().forEach(::put) }
            return JSONObject()
                .put("v", 2)
                .put("year", valid.year)
                .put("booksGoalType", valid.booksGoalType.name)
                .put("booksGoalValue", valid.booksGoalValue)
                .put("pagesGoalType", valid.pagesGoalType.name)
                .put("pagesGoalValue", valid.pagesGoalValue)
                .put("goalConfigured", valid.goalConfigured)
                .put("excludedMonthBooks", exMonth)
                .put("excludedYearBooks", exYear)
                .put("goalMode", valid.goalMode.name)
                .put("dailyPagesTarget", valid.dailyPagesTarget)
                .put("monthlyBooksTarget", valid.monthlyBooksTarget)
                .put("showPurchaseGoal", valid.showPurchaseGoal)
                .put("entries", arr)
                .toString()
        }

        internal fun decode(raw: String): AnnualReadingPlan {
            require(raw.length <= MAX_JSON && raw.toByteArray().size <= MAX_JSON) { "Meta anual grande demais." }
            var depth = 0
            var quoted = false
            var escape = false
            for (c in raw) {
                if (quoted) {
                    if (escape) escape = false
                    else if (c == '\\') escape = true
                    else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '[', '{' -> {
                        depth++
                        require(depth <= 10) { "JSON aninhado demais." }
                    }
                    ']', '}' -> depth--
                }
            }
            val o = JSONObject(raw)
            val version = o.getInt("v")
            require(version in 1..2) { "Versão de meta não suportada." }
            val arr = o.optJSONArray("entries") ?: JSONArray()
            require(arr.length() <= AnnualReadingPlan.MAX_ENTRIES) { "Muitos itens no ano." }
            val entries = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                PlanEntry(
                    id = e.getString("id"),
                    month = e.getInt("month"),
                    position = e.optInt("position", i),
                    kind = PlanEntryKind.valueOf(e.getString("kind")),
                    bookId = e.optString("bookId"),
                    wishlistCatalogId = e.optString("wishlistCatalogId"),
                    title = e.optString("title"),
                    author = e.optString("author"),
                    saga = e.optString("saga"),
                    coverUrl = e.optString("coverUrl"),
                    reservedPages = e.optInt("reservedPages", 200),
                    tag = e.optString("tag"),
                    completed = e.optBoolean("completed", false),
                    reread = e.optBoolean("reread", false),
                )
            }
            val legacyMode = runCatching { PlanGoalMode.valueOf(o.optString("goalMode", PlanGoalMode.NONE.name)) }.getOrDefault(PlanGoalMode.NONE)
            val legacyDaily = o.optInt("dailyPagesTarget", 30).coerceIn(1, 2000)
            val legacyMonthly = o.optInt("monthlyBooksTarget", 2).coerceIn(1, 100)

            val booksGoalType = if (o.has("booksGoalType")) {
                BooksGoalType.valueOf(o.getString("booksGoalType"))
            } else if (legacyMode == PlanGoalMode.BOOKS_PER_MONTH) {
                BooksGoalType.PER_MONTH
            } else {
                BooksGoalType.NONE
            }
            val booksGoalValue = if (o.has("booksGoalValue")) o.getInt("booksGoalValue") else legacyMonthly

            val pagesGoalType = if (o.has("pagesGoalType")) {
                PagesGoalType.valueOf(o.getString("pagesGoalType"))
            } else if (legacyMode == PlanGoalMode.PAGES_PER_DAY) {
                PagesGoalType.PER_DAY
            } else {
                PagesGoalType.NONE
            }
            val pagesGoalValue = if (o.has("pagesGoalValue")) o.getInt("pagesGoalValue") else legacyDaily

            val goalConfigured = o.optBoolean("goalConfigured", version >= 2)

            fun readStringSet(key: String): Set<String> {
                val a = o.optJSONArray(key) ?: return emptySet()
                val out = LinkedHashSet<String>(a.length())
                for (i in 0 until a.length()) {
                    val s = a.optString(i)
                    if (s.isNotBlank()) out.add(s)
                }
                return out
            }

            return AnnualReadingPlan(
                year = o.getInt("year"),
                booksGoalType = booksGoalType,
                booksGoalValue = booksGoalValue,
                pagesGoalType = pagesGoalType,
                pagesGoalValue = pagesGoalValue,
                goalConfigured = goalConfigured,
                excludedMonthBooks = readStringSet("excludedMonthBooks"),
                excludedYearBooks = readStringSet("excludedYearBooks"),
                entries = entries,
                goalMode = legacyMode,
                dailyPagesTarget = legacyDaily,
                monthlyBooksTarget = legacyMonthly,
                showPurchaseGoal = o.optBoolean("showPurchaseGoal", true),
            ).validated()
        }
    }

    fun years(): List<Int> = prefs.all.keys
        .filter { it.startsWith(PREFIX) }
        .mapNotNull { it.removePrefix(PREFIX).toIntOrNull() }
        .sortedDescending()

    fun load(year: Int): AnnualReadingPlan? {
        val raw = prefs.getString(PREFIX + year, null) ?: return null
        return runCatching { decode(raw).takeIf { it.year == year } }.getOrNull()
    }

    fun save(plan: AnnualReadingPlan) {
        synchronized(lock) {
            val valid = plan.validated()
            val key = PREFIX + valid.year
            if (!prefs.contains(key)) {
                check(years().size < MAX_YEARS) { "Limite de $MAX_YEARS anos atingido." }
            }
            val raw = encode(valid)
            check(raw.length <= MAX_JSON && raw.toByteArray().size <= MAX_JSON) { "Meta anual grande demais." }
            check(prefs.edit().putString(key, raw).commit()) { "Não foi possível salvar a meta anual." }
        }
    }

    fun delete(year: Int) {
        synchronized(lock) {
            prefs.edit().remove(PREFIX + year).commit()
        }
    }
}
