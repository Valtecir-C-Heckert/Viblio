package br.com.centralcasa.books

import java.security.MessageDigest
import kotlin.math.roundToInt

/** Selectable continuous wooden bookcase furniture styles (never alters book spines). */
enum class ShelfWoodStyle(val key: String, val label: String) {
    COLLECTOR("collector", "Colecionador Iluminada"),
    EDITORIAL("editorial", "Editorial Moderna");
    companion object {
        fun of(raw: String?): ShelfWoodStyle = entries.firstOrNull { it.key == raw } ?: COLLECTOR
    }
}

/** Selectable book spine styles (100% independent of bookcase furniture; uniform height always). */
enum class SpineDesignMode(val key: String, val label: String) {
    ORIGINAL("original", "Original (todas iguais)"),
    ANTIQUE_BROWN("antique", "Couro Antigo (marrom único)"),
    EDITORIAL("editorial", "Editorial (topo colorido)"),
    BY_GENRE("genre", "Por gênero");
    companion object {
        fun of(raw: String?): SpineDesignMode = when (raw) {
            "original" -> ORIGINAL
            "antique", "standard" -> ANTIQUE_BROWN
            "editorial" -> EDITORIAL
            "genre" -> BY_GENRE
            else -> BY_GENRE
        }
    }
}

enum class SpineMaterial { LEATHER, CLOTH }

/**
 * The 10 thematic spine families (+ DEFAULT fallback). Each family ships 3 flush-framed
 * engraved WebP hot-stamps (a, b, c) that always preserve the book's dominant cover color.
 */
enum class SpineGenreTheme(
    val assetPrefix: String?,
    val material: SpineMaterial,
    val sansSerif: Boolean,
    val darkFoil: Int,
    val lightFoil: Int
) {
    FANTASIA("fantasia", SpineMaterial.LEATHER, false, 0xFFDEBA66.toInt(), 0xFF3E290F.toInt()),
    TERROR("terror", SpineMaterial.LEATHER, false, 0xFFDACEC6.toInt(), 0xFF2B1216.toInt()),
    SCIFI("scifi", SpineMaterial.LEATHER, true, 0xFF84D4EB.toInt(), 0xFF10324A.toInt()),
    ROMANCE("romance", SpineMaterial.CLOTH, false, 0xFFECCCAA.toInt(), 0xFF4F1D2E.toInt()),
    SUSPENSE("suspense", SpineMaterial.CLOTH, false, 0xFFDAB66C.toInt(), 0xFF1E2630.toInt()),
    NAO_FICCAO("naoficcao", SpineMaterial.CLOTH, false, 0xFFE6D4A8.toInt(), 0xFF1C2D3D.toInt()),
    AVENTURA("aventura", SpineMaterial.LEATHER, false, 0xFFE8C27C.toInt(), 0xFF3D2410.toInt()),
    DISTOPIA("distopia", SpineMaterial.LEATHER, true, 0xFFE8AC40.toInt(), 0xFF282018.toInt()),
    CLASSICOS("classicos", SpineMaterial.LEATHER, false, 0xFFE0BA68.toInt(), 0xFF3F1A26.toInt()),
    MANGA("manga", SpineMaterial.CLOTH, true, 0xFFF4D870.toInt(), 0xFF3D1418.toInt()),
    DEFAULT(null, SpineMaterial.LEATHER, false, 0xFFDCBA6C.toInt(), 0xFF3A2816.toInt());
}

object SpineThemes {
    private val aliases: Map<String, SpineGenreTheme> = buildMap {
        fun add(theme: SpineGenreTheme, vararg names: String) {
            names.forEach { put(ShelfFilters.key(it), theme) }
        }
        add(SpineGenreTheme.FANTASIA, "fantasia", "fantasy", "alta fantasia", "baixa fantasia", "fantasia sombria", "fantasia epica", "fantasia urbana", "romantasia", "mitologia")
        add(SpineGenreTheme.TERROR, "terror", "horror", "gotico", "gotica", "sobrenatural", "horror cosmico", "terror psicologico")
        add(SpineGenreTheme.SCIFI, "ficcao cientifica", "ficcao-cientifica", "sci-fi", "scifi", "cyberpunk", "space opera", "ciencia ficcao", "ficcao especulativa")
        add(SpineGenreTheme.ROMANCE, "romance", "romantico", "romantica", "drama romantico", "romance historico", "romance contemporaneo", "chick-lit")
        add(SpineGenreTheme.SUSPENSE, "suspense", "misterio", "thriller", "policial", "crime", "investigacao", "detetive", "noir", "suspense psicologico")
        add(SpineGenreTheme.NAO_FICCAO, "nao-ficcao", "nao ficcao", "non-fiction", "nonfiction", "historia", "biografia", "autobiografia", "memorias", "ciencia", "filosofia", "psicologia", "negocios", "economia", "politica", "sociologia", "autoajuda", "desenvolvimento pessoal", "ensaios", "ensaio", "tecnologia", "religiao", "espiritualidade", "educacao", "jornalismo")
        add(SpineGenreTheme.AVENTURA, "aventura", "acao", "acao e aventura", "viagem", "viagens", "expedicao", "piratas", "faroeste", "western")
        add(SpineGenreTheme.DISTOPIA, "distopia", "distopico", "pos-apocaliptico", "pos apocaliptico", "utopia", "apocaliptico")
        add(SpineGenreTheme.CLASSICOS, "classicos", "classico", "literatura", "literatura classica", "literatura brasileira", "literatura estrangeira", "literatura universal", "poesia", "contos", "cronicas", "drama", "teatro", "ficcao literaria", "ficcao historica")
        add(SpineGenreTheme.MANGA, "manga", "mangas", "quadrinho", "quadrinhos", "hq", "hqs", "graphic novel", "gibi", "gibis", "comics", "comic", "manhwa", "manhua", "webtoon")
    }

    /** Maps a book's genre/kind to one of the 10 thematic spine families or DEFAULT fallback. */
    fun resolveGenre(genre: String, kind: String = "book"): SpineGenreTheme {
        val clean = genre.trim()
        if (clean.isNotEmpty()) {
            val parent = ShelfFilters.key(BookGenres.split(clean).genre)
            aliases[parent]?.let { return it }
            val full = ShelfFilters.key(clean)
            aliases[full]?.let { return it }
            for (part in clean.split('/', '|', '·', ',', ';', '-')) {
                val k = ShelfFilters.key(part)
                if (k.isNotEmpty()) aliases[k]?.let { return it }
            }
        }
        if (kind == "manga" || kind == "comic") return SpineGenreTheme.MANGA
        return SpineGenreTheme.DEFAULT
    }

    /**
     * Grouping key for deterministic A/B/C selection:
     * - Books sharing the same saga always receive the exact same spine art (varying only by cover color).
     * - Books in the same collection share the same spine art.
     * - Standalone books hash their own bookId.
     */
    fun variantSeed(bookId: String, saga: String = "", collectionId: String = ""): String {
        val cleanSaga = ShelfFilters.key(saga)
        if (cleanSaga.isNotEmpty()) return "saga:$cleanSaga"
        if (collectionId.isNotBlank()) return "col:${collectionId.trim()}"
        return bookId
    }

    /** Stable deterministic variant index (0 = A, 1 = B, 2 = C) derived from SHA-256. */
    fun deterministicVariant(seed: String): Int {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val value = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        return ((value.toLong() and 0xFFFFFFFFL) % 3L).toInt()
    }

    /** Asset path under assets/ for the book's thematic stamp, or null for fallback. */
    fun assetName(
        theme: SpineGenreTheme,
        bookId: String,
        saga: String = "",
        collectionId: String = "",
        overrideVariant: Int? = null
    ): String? {
        val prefix = theme.assetPrefix ?: return null
        val variant = overrideVariant?.coerceIn(0, 2) ?: deterministicVariant(variantSeed(bookId, saga, collectionId))
        val letter = ('a'.code + variant).toChar()
        return "spines/${prefix}_$letter.webp"
    }

    /**
     * Assigns deterministic A/B/C variant indices (0..2) across an ordered shelf list:
     * - Books sharing the same saga/collection ALWAYS receive the exact same variant.
     * - Two adjacent different series/standalone books of the SAME genre never repeat the same variant
     *   (even when filters or sort order change and bring two same-genre books side by side).
     */
    fun assignVariants(books: List<LibraryBook>): Map<String, Int> {
        if (books.isEmpty()) return emptyMap()
        data class Run(val seed: String, val theme: SpineGenreTheme, val bookIds: MutableList<String>)
        val runs = mutableListOf<Run>()
        for (book in books) {
            val seed = variantSeed(book.id, book.saga, book.collectionId)
            val theme = resolveGenre(book.genre, book.kind)
            val last = runs.lastOrNull()
            if (last != null && last.seed == seed && last.theme == theme) {
                last.bookIds.add(book.id)
            } else {
                runs.add(Run(seed, theme, mutableListOf(book.id)))
            }
        }
        val seedChoice = mutableMapOf<String, Int>()
        val runChoice = IntArray(runs.size)
        for (i in runs.indices) {
            val run = runs[i]
            val prevRun = runs.getOrNull(i - 1)
            val nextRun = runs.getOrNull(i + 1)
            val prevForbidden = if (prevRun != null && run.theme != SpineGenreTheme.DEFAULT && prevRun.theme == run.theme && prevRun.seed != run.seed) {
                runChoice[i - 1]
            } else -1
            val nextSameGenre = nextRun != null && run.theme != SpineGenreTheme.DEFAULT && nextRun.theme == run.theme && nextRun.seed != run.seed
            val nextFixed = if (nextSameGenre) seedChoice[nextRun!!.seed] ?: -1 else -1
            val existing = seedChoice[run.seed]
            val chosen = if (existing != null) {
                existing
            } else {
                val base = deterministicVariant(run.seed)
                val candidates = listOf(base, (base + 1) % 3, (base + 2) % 3)
                val nextAvoid = if (nextSameGenre) (seedChoice[nextRun!!.seed] ?: deterministicVariant(nextRun.seed)) else -1
                (candidates.firstOrNull { it != prevForbidden && it != nextFixed && it != nextAvoid }
                    ?: candidates.firstOrNull { it != prevForbidden && it != nextFixed }
                    ?: candidates.first { it != prevForbidden })
                    .also { seedChoice[run.seed] = it }
            }
            runChoice[i] = chosen
        }
        val result = HashMap<String, Int>(books.size)
        for (i in runs.indices) {
            val v = runChoice[i]
            for (id in runs[i].bookIds) result[id] = v
        }
        return result
    }

    /** Single antique saddle-brown leather color with subtle ±6 organic hide patina per book. */
    fun antiqueBrownColor(bookId: String): Int {
        val bytes = MessageDigest.getInstance("SHA-256").digest(("brown:$bookId").toByteArray(Charsets.UTF_8))
        val patina = ((bytes[0].toInt() and 0xFF) % 13) - 6
        val r = (84 + patina).coerceIn(45, 115)
        val g = (51 + (patina * 0.7f).roundToInt()).coerceIn(28, 72)
        val b = (31 + (patina * 0.5f).roundToInt()).coerceIn(16, 46)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Deterministic texture UV offset so adjacent books never display identical repeating leather grain. */
    fun grainOffset(bookId: String, tileW: Int, tileH: Int): Pair<Float, Float> {
        if (tileW <= 1 || tileH <= 1) return 0f to 0f
        val bytes = MessageDigest.getInstance("SHA-256").digest(("uv:$bookId").toByteArray(Charsets.UTF_8))
        val ox = -((bytes[0].toInt() and 0xFF) % tileW).toFloat()
        val oy = -((bytes[1].toInt() and 0xFF) % tileH).toFloat()
        return ox to oy
    }

    /** High-contrast metallic/ink foil color harmonized with the book's real cover color. */
    fun foilColor(faceColor: Int, theme: SpineGenreTheme): Int {
        val ink = ShelfSpines.materialInk(faceColor)
        val preferred = if (ink == -1) theme.darkFoil else theme.lightFoil
        if (CoverColors.contrast(faceColor, preferred) >= 3.0) return preferred
        return ink
    }
}

/**
 * Packs books into wall-to-wall closed shelf rows:
 * Every full row distributes the exact remaining pixels proportionally across ALL books in that row
 * so sum(widths) == innerWidthPx (0px gap on left or right wooden wall) without squishing the next row's book.
 */
object ShelfRowPacker {
    data class Slot(val index: Int, val widthPx: Int)

    fun pack(naturalWidthsPx: List<Int>, innerWidthPx: Int): List<List<Slot>> {
        if (naturalWidthsPx.isEmpty() || innerWidthPx <= 0) return emptyList()
        val rawRows = mutableListOf<MutableList<Slot>>()
        var current = mutableListOf<Slot>()
        var used = 0

        for (i in naturalWidthsPx.indices) {
            val w = naturalWidthsPx[i].coerceIn(1, innerWidthPx)
            if (current.isNotEmpty() && used + w > innerWidthPx) {
                rawRows.add(current)
                current = mutableListOf()
                used = 0
            }
            current.add(Slot(i, w))
            used += w
        }
        if (current.isNotEmpty()) rawRows.add(current)

        return rawRows.mapIndexed { rowIdx, row ->
            val sum = row.sumOf { it.widthPx }
            val isLastRow = rowIdx == rawRows.lastIndex
            // Close wall-to-wall on every full row, and on the last row when it already fills >= 82% of the shelf.
            val shouldClose = (!isLastRow && sum < innerWidthPx) || (isLastRow && sum < innerWidthPx && sum >= (innerWidthPx * 0.82f).roundToInt())
            if (!shouldClose || sum <= 0) {
                row
            } else {
                val scale = innerWidthPx.toDouble() / sum.toDouble()
                val adjusted = row.map { slot ->
                    Slot(slot.index, (slot.widthPx * scale).roundToInt().coerceAtLeast(1))
                }.toMutableList()
                var diff = innerWidthPx - adjusted.sumOf { it.widthPx }
                // Distribute any ±1px rounding remainder cleanly across books in the row so total == innerWidthPx exactly
                var cursor = 0
                while (diff != 0 && adjusted.isNotEmpty()) {
                    val pos = cursor % adjusted.size
                    val step = if (diff > 0) 1 else -1
                    val candidate = adjusted[pos].widthPx + step
                    if (candidate >= 1) {
                        adjusted[pos] = adjusted[pos].copy(widthPx = candidate)
                        diff -= step
                    }
                    cursor++
                    if (cursor > adjusted.size * 4) break
                }
                adjusted
            }
        }
    }
}
