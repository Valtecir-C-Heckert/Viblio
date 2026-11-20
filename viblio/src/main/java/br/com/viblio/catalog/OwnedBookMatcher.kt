package br.com.viblio.catalog

import br.com.centralcasa.books.LibraryBook
import br.com.centralcasa.books.catalog.CatalogBook
import java.text.Normalizer

/**
 * Local client-side owned-book matcher mirroring the server's rules so recommendations
 * never display a book already on the user's shelf (even before cloud sync completes or
 * across subtitle/edition variants), while preserving distinct volumes (e.g. Vol. 1 vs Vol. 2).
 */
internal object OwnedBookMatcher {
    private val parens = Regex("""\s*[\(\[][^\)\]]*[\)\]]""")
    private val subtitleSplit = Regex("""\s*(?::|\s[-–—|]\s)\s*""")
    private val volumeMarker = Regex("""\b(?:vol(?:ume)?|livro|book|tomo|parte|pt|n[oº°]?)\s*#?\s*0*(\d{1,4})\b|#\s*0*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val trailingNum = Regex("""^(.*\S)\s+0*(\d{1,3})$""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")

    private data class Entry(
        val catalogId: String?,
        val fullTitle: String,
        val baseTitle: String,
        val authorTokens: Set<String>,
        val volume: Int?,
    )

    private fun norm(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val nfkd = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFKD)
        val noMarks = buildString(nfkd.length) {
            for (ch in nfkd) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
            }
        }
        return nonAlnum.replace(noMarks, " ").trim()
    }

    private fun authorTokens(raw: String?): Set<String> =
        norm(parens.replace(raw.orEmpty(), "")).split(' ').filter { it.isNotEmpty() }.toSet()

    private fun extractVolume(title: String?, sequence: Int?): Int? {
        if (sequence != null && sequence in 1..10000) return sequence
        val n = norm(title)
        if (n.isEmpty()) return null
        val m = volumeMarker.find(n)
        if (m != null) {
            val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
            if (v != null && v in 1..10000) return v
        }
        val m2 = trailingNum.matchEntire(n)
        if (m2 != null) {
            val prefix = m2.groupValues[1]
            val v = m2.groupValues[2].toIntOrNull()
            if (prefix.length >= 3 && v != null && v in 1..500) return v
        }
        return null
    }

    private fun baseTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val stripped = parens.replace(title, "").trim()
        val head = subtitleSplit.split(stripped, 2).firstOrNull()?.trim().orEmpty()
        val chosen = if (norm(head).length >= 4) head else stripped
        var n = norm(chosen)
        val noVol = volumeMarker.replace(n, "").replace(Regex("""\s+"""), " ").trim()
        if (noVol.length >= 3) n = noVol
        val m = trailingNum.matchEntire(n)
        if (m != null && m.groupValues[1].length >= 3 && (m.groupValues[2].toIntOrNull() ?: 0) in 1..500) {
            n = m.groupValues[1].trim()
        }
        return n
    }

    private fun authorsOverlap(a1: Set<String>, a2: Set<String>): Boolean {
        if (a1.isEmpty() || a2.isEmpty()) return false
        val s1 = a1.filterTo(HashSet()) { it.length >= 3 }
        val s2 = a2.filterTo(HashSet()) { it.length >= 3 }
        return if (s1.isNotEmpty() && s2.isNotEmpty()) s1.any { it in s2 } else a1.any { it in a2 }
    }

    fun filterUnowned(items: List<CatalogBook>, owned: List<LibraryBook>): List<CatalogBook> {
        if (items.isEmpty() || owned.isEmpty()) return items
        val ids = HashSet<String>(owned.size)
        val entries = ArrayList<Entry>(owned.size)
        for (b in owned) {
            if (b.archived) continue
            b.catalogId?.takeIf { it.isNotBlank() }?.let(ids::add)
            val ft = norm(b.title)
            if (ft.isNotEmpty()) {
                entries.add(Entry(b.catalogId, ft, baseTitle(b.title), authorTokens(b.author), extractVolume(b.title, b.sequenceNumber)))
            }
        }
        return items.filterNot { cand ->
            if (cand.id in ids) return@filterNot true
            val ft = norm(cand.title)
            if (ft.isEmpty()) return@filterNot false
            val bt = baseTitle(cand.title)
            val at = authorTokens(cand.author)
            val vol = extractVolume(cand.title, cand.sequence)
            entries.any { ow ->
                if (vol != null && ow.volume != null && vol != ow.volume) return@any false
                val bothAuthors = at.isNotEmpty() && ow.authorTokens.isNotEmpty()
                if (bothAuthors && !authorsOverlap(at, ow.authorTokens)) return@any false
                if (ft == ow.fullTitle) return@any true
                if (bt.isNotEmpty() && bt == ow.baseTitle) return@any bt.length >= if (bothAuthors) 3 else 5
                if (bothAuthors && bt.isNotEmpty() && ow.baseTitle.isNotEmpty()) {
                    val shorter = if (bt.length <= ow.baseTitle.length) bt else ow.baseTitle
                    val longer = if (bt.length <= ow.baseTitle.length) ow.fullTitle else ft
                    shorter.length >= 5 && longer.startsWith("$shorter ")
                } else false
            }
        }
    }
}
