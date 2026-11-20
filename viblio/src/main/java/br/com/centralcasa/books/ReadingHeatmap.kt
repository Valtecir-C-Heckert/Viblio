package br.com.centralcasa.books

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class DayActivity(
    val day: String,
    val pages: Int,
    val durationMs: Long,
    val bookTitles: List<String>,
    val level: Int, // 0..3
)

data class YearHeatmapSummary(
    val year: Int,
    val daysByDate: Map<String, DayActivity>,
    val totalActiveDays: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    val weeks: List<List<String?>>, // Each inner list has 7 entries (Sun..Sat); null outside [year]
)

data class RetrospectiveBook(
    val id: String,
    val title: String,
    val author: String,
    val cover: String,
    val rating: Double?,
    val pages: Int,
)

data class RetrospectiveData(
    val title: String,
    val periodLabel: String,
    val completedCount: Int,
    val totalPagesRead: Long,
    val totalDurationMs: Long,
    val activeDays: Int,
    val bestStreakInPeriod: Int,
    val topRatedBook: RetrospectiveBook?,
    val topGenres: List<String>,
    val showcaseBooks: List<RetrospectiveBook>,
)

/**
 * Pure domain calculators for the Reading Heatmap (yearly active days + streaks)
 * and the shareable Reading Retrospective (Viblio Wrapped).
 * Strictly uses recorded sessions and manual [ReadingDate]s — never guesses from [LibraryBook.updatedAt].
 */
object ReadingHeatmap {
    private fun utcDayFormatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseUtc(day: String): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        time = utcDayFormatter().parse(ReadingDays.validate(day))!!
    }

    fun intensityLevel(pages: Int, hasActivity: Boolean): Int = when {
        !hasActivity -> 0
        pages >= 61 -> 3
        pages >= 21 -> 2
        else -> 1
    }

    fun collectActivityByDay(
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate>,
    ): Map<String, DayActivity> {
        val activeById = books.filterNot { it.archived }.associateBy { it.id }
        val pagesMap = HashMap<String, Int>()
        val durationMap = HashMap<String, Long>()
        val titlesMap = HashMap<String, LinkedHashSet<String>>()

        sessions.forEach { s ->
            val b = activeById[s.bookId] ?: return@forEach
            val day = s.readingDay ?: ReadingDays.at(s.startedAt)
            if (b.pagesKnown && s.pages > 0) {
                pagesMap[day] = (pagesMap[day] ?: 0) + s.pages
            }
            durationMap[day] = (durationMap[day] ?: 0L) + s.durationMs.coerceAtLeast(0L)
            titlesMap.getOrPut(day) { LinkedHashSet() }.add(b.title)
        }
        dates.forEach { d ->
            val b = activeById[d.bookId] ?: return@forEach
            titlesMap.getOrPut(d.day) { LinkedHashSet() }.add(b.title)
        }

        val allDays = LinkedHashSet<String>().apply {
            addAll(titlesMap.keys)
            addAll(pagesMap.keys)
        }
        val out = LinkedHashMap<String, DayActivity>(allDays.size)
        allDays.sorted().forEach { day ->
            val p = pagesMap[day] ?: 0
            val dur = durationMap[day] ?: 0L
            val titles = titlesMap[day]?.toList().orEmpty()
            out[day] = DayActivity(
                day = day,
                pages = p,
                durationMs = dur,
                bookTitles = titles,
                level = intensityLevel(p, true),
            )
        }
        return out
    }

    fun bestStreakIn(activeDaysSorted: List<String>): Int {
        if (activeDaysSorted.isEmpty()) return 0
        val fmt = utcDayFormatter()
        var best = 1
        var run = 1
        var prevCal = parseUtc(activeDaysSorted[0])
        for (i in 1 until activeDaysSorted.size) {
            val expectedNext = (prevCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val nextStr = fmt.format(expectedNext.time)
            if (activeDaysSorted[i] == nextStr) {
                run++
                if (run > best) best = run
            } else if (activeDaysSorted[i] != fmt.format(prevCal.time)) {
                run = 1
            }
            prevCal = parseUtc(activeDaysSorted[i])
        }
        return best
    }

    /** Current streak counts consecutive active days ending on [todayDay] or yesterday (grace window). */
    fun currentStreak(activeDays: Set<String>, todayDay: String): Int {
        if (activeDays.isEmpty()) return 0
        val fmt = utcDayFormatter()
        val cal = parseUtc(todayDay)
        val todayStr = fmt.format(cal.time)
        if (todayStr !in activeDays) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
            if (fmt.format(cal.time) !in activeDays) return 0
        }
        var count = 0
        while (fmt.format(cal.time) in activeDays) {
            count++
            cal.add(Calendar.DAY_OF_YEAR, -1)
            if (count >= 4000) break
        }
        return count
    }

    fun forYear(
        year: Int,
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate>,
        todayDay: String = ReadingDays.at(System.currentTimeMillis()),
    ): YearHeatmapSummary {
        val allActivity = collectActivityByDay(books, sessions, dates)
        val prefix = String.format(Locale.US, "%04d-", year)
        val yearActivity = allActivity.filterKeys { it.startsWith(prefix) }

        val fmt = utcDayFormatter()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, Calendar.JANUARY, 1)
        }
        val weeks = mutableListOf<MutableList<String?>>()
        var currentWeek = MutableList<String?>(7) { null }
        while (cal.get(Calendar.YEAR) == year) {
            val dow = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY // 0=Sun..6=Sat
            currentWeek[dow] = fmt.format(cal.time)
            if (dow == 6) {
                weeks.add(currentWeek)
                currentWeek = MutableList(7) { null }
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (currentWeek.any { it != null }) {
            weeks.add(currentWeek)
        }

        val sortedYearDays = yearActivity.keys.sorted()
        val activeSetAll = allActivity.keys
        val streakToday = if (todayDay.startsWith(prefix)) {
            currentStreak(activeSetAll, todayDay)
        } else {
            // For past/future years, show streak ending on Dec 31 of that year (or 0 if none).
            val dec31 = String.format(Locale.US, "%04d-12-31", year)
            currentStreak(yearActivity.keys, dec31)
        }

        return YearHeatmapSummary(
            year = year,
            daysByDate = yearActivity,
            totalActiveDays = sortedYearDays.size,
            currentStreak = streakToday,
            bestStreak = bestStreakIn(sortedYearDays),
            weeks = weeks,
        )
    }

    fun hasReadToday(
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate>,
        todayDay: String = ReadingDays.at(System.currentTimeMillis()),
    ): Boolean {
        val activeIds = books.filterNot { it.archived }.map { it.id }.toSet()
        if (dates.any { it.bookId in activeIds && it.day == todayDay }) return true
        return sessions.any { it.bookId in activeIds && (it.readingDay ?: ReadingDays.at(it.startedAt)) == todayDay }
    }
}

object RetrospectiveBuilder {
    fun build(
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        dates: List<ReadingDate>,
        range: ReadingPeriodRange,
        title: String,
        periodLabel: String,
    ): RetrospectiveData {
        val activeBooks = books.filterNot { it.archived }
        val byId = activeBooks.associateBy { it.id }

        val period = PeriodReading.from(activeBooks, sessions, dates, range)
        val completedIds = PeriodCompletions.completedBookIds(activeBooks, sessions, dates, range)

        // Pages read: recorded session pages + totalPages of completed books in range that had no sessions covering them.
        val sessionPagesByBook = period.sessions
            .groupBy { it.bookId }
            .mapValues { (bookId, list) ->
                if (byId[bookId]?.pagesKnown == true) list.sumOf { it.pages.coerceAtLeast(0).toLong() } else 0L
            }
        var totalPages = period.pages
        completedIds.forEach { id ->
            val b = byId[id] ?: return@forEach
            if (b.pagesKnown) {
                val sPages = sessionPagesByBook[id] ?: 0L
                if (sPages == 0L) totalPages += b.totalPages.toLong()
            }
        }

        // Active days & best streak inside [range]
        val allActivity = ReadingHeatmap.collectActivityByDay(activeBooks, sessions, dates)
        val daysInRange = allActivity.keys.filter { range.contains(it) }.sorted()

        val completedBooks = completedIds.mapNotNull { byId[it] }
        val touchedBooks = (completedIds + period.bookIds).mapNotNull { byId[it] }
        val pool = if (completedBooks.isNotEmpty()) completedBooks else touchedBooks

        val topRated = pool
            .filter { it.rating != null }
            .sortedWith(compareByDescending<LibraryBook> { it.rating ?: 0.0 }.thenBy { it.title.lowercase(Locale("pt", "BR")) })
            .firstOrNull()
            ?.let { RetrospectiveBook(it.id, it.title, it.author, it.cover, it.rating, if (it.pagesKnown) it.totalPages else 0) }

        val topGenres = pool
            .map { it.genre.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(2)
            .map { it.key }

        // Showcase covers prioritize completed books (with covers first), then touched books.
        val showcase = pool
            .sortedWith(
                compareByDescending<LibraryBook> { it.id in completedIds }
                    .thenByDescending { it.cover.isNotBlank() }
                    .thenByDescending { it.rating ?: -1.0 }
                    .thenBy { it.title.lowercase(Locale("pt", "BR")) },
            )
            .map { RetrospectiveBook(it.id, it.title, it.author, it.cover, it.rating, if (it.pagesKnown) it.totalPages else 0) }

        return RetrospectiveData(
            title = title,
            periodLabel = periodLabel,
            completedCount = completedIds.size,
            totalPagesRead = totalPages,
            totalDurationMs = period.durationMs,
            activeDays = daysInRange.size,
            bestStreakInPeriod = ReadingHeatmap.bestStreakIn(daysInRange),
            topRatedBook = topRated,
            topGenres = topGenres,
            showcaseBooks = showcase,
        )
    }
}
