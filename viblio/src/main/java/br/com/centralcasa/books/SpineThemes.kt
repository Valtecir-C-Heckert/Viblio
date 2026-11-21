package br.com.centralcasa.books

import java.security.MessageDigest

/** Selectable bookshelf furniture & atmosphere styles (Editorial Moderna vs Colecionador Iluminada). */
enum class ShelfWoodStyle(val key: String, val label: String) {
    EDITORIAL("editorial", "Editorial Moderna"),
    COLLECTOR("collector", "Colecionador Iluminada");
    companion object {
        fun of(raw: String?): ShelfWoodStyle = entries.firstOrNull { it.key == raw } ?: COLLECTOR
    }
}

/** Selectable book spine decoration mode (uniform standard binding vs thematic per genre). */
enum class SpineDesignMode(val key: String, val label: String) {
    STANDARD("standard", "Lombada padrão"),
    BY_GENRE("genre", "Lombadas por gênero");
    companion object {
        fun of(raw: String?): SpineDesignMode = entries.firstOrNull { it.key == raw } ?: BY_GENRE
    }
}

enum class SpineMaterial { LEATHER, LINEN, VELVET, CONCRETE }

/**
 * The 10 thematic spine families (+ DEFAULT fallback). Each family ships 3 engraved WebP
 * relief stamps (a, b, c) that always preserve the book's dominant cover color while applying
 * hot-stamped metallic/ink detailing with automatic readability contrast.
 */
enum class SpineGenreTheme(
    val assetPrefix: String?,
    val material: SpineMaterial,
    val sansSerif: Boolean,
    val darkFoil: Int,
    val lightFoil: Int
) {
    FANTASIA("fantasia", SpineMaterial.LEATHER, false, 0xFFEBC86E.toInt(), 0xFF3E290F.toInt()),
    TERROR("terror", SpineMaterial.CONCRETE, false, 0xFFE3D9D4.toInt(), 0xFF2B1216.toInt()),
    SCIFI("scifi", SpineMaterial.CONCRETE, true, 0xFF8BE2FA.toInt(), 0xFF10324A.toInt()),
    ROMANCE("romance", SpineMaterial.LINEN, false, 0xFFF5D8B8.toInt(), 0xFF4F1D2E.toInt()),
    SUSPENSE("suspense", SpineMaterial.LINEN, false, 0xFFE5C174.toInt(), 0xFF1E2630.toInt()),
    NAO_FICCAO("naoficcao", SpineMaterial.LINEN, false, 0xFFF0DFB4.toInt(), 0xFF1C2D3D.toInt()),
    AVENTURA("aventura", SpineMaterial.LEATHER, false, 0xFFF2CC84.toInt(), 0xFF3D2410.toInt()),
    DISTOPIA("distopia", SpineMaterial.CONCRETE, true, 0xFFF2B544.toInt(), 0xFF282018.toInt()),
    CLASSICOS("classicos", SpineMaterial.VELVET, false, 0xFFF0CD78.toInt(), 0xFF3F1A26.toInt()),
    MANGA("manga", SpineMaterial.LINEN, true, 0xFFFFE57A.toInt(), 0xFF3D1418.toInt()),
    DEFAULT(null, SpineMaterial.LEATHER, false, 0xFFE8C87A.toInt(), 0xFF3A2816.toInt());
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
            // Compound labels like "Suspense / Mistério" or "Ação e Aventura"
            for (part in clean.split('/', '|', '·', ',', ';', '-')) {
                val k = ShelfFilters.key(part)
                if (k.isNotEmpty()) aliases[k]?.let { return it }
            }
        }
        if (kind == "manga" || kind == "comic") return SpineGenreTheme.MANGA
        return SpineGenreTheme.DEFAULT
    }

    /**
     * Stable deterministic variant index (0 = A, 1 = B, 2 = C) derived from SHA-256 of bookId.
     * Gives natural variety across books of the same genre while never changing between runs or devices.
     */
    fun deterministicVariant(bookId: String): Int {
        val bytes = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray(Charsets.UTF_8))
        val value = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        return ((value.toLong() and 0xFFFFFFFFL) % 3L).toInt()
    }

    /** Asset path under assets/ for the book's deterministic thematic stamp, or null for standard fallback. */
    fun assetName(theme: SpineGenreTheme, bookId: String): String? {
        val prefix = theme.assetPrefix ?: return null
        val letter = ('a'.code + deterministicVariant(bookId)).toChar()
        return "spines/${prefix}_$letter.webp"
    }

    /** Subtle organic height variation (0..12 dp) for Editorial Moderna shelves; deterministic per book. */
    fun organicHeightOffsetDp(bookId: String, pages: Int): Int {
        val bytes = MessageDigest.getInstance("SHA-256").digest(("h:$bookId").toByteArray(Charsets.UTF_8))
        val hashJitter = (bytes[0].toInt() and 0xFF) % 7 // 0..6 dp
        val pageBias = when {
            pages >= 550 -> 0
            pages >= 350 -> 3
            else -> 6
        }
        return (hashJitter + pageBias).coerceIn(0, 12)
    }

    /** Chooses a high-contrast metallic/ink foil color harmonized with the book's real cover color. */
    fun foilColor(faceColor: Int, theme: SpineGenreTheme): Int {
        val ink = ShelfSpines.materialInk(faceColor)
        val preferred = if (ink == -1) theme.darkFoil else theme.lightFoil
        if (CoverColors.contrast(faceColor, preferred) >= 3.0) return preferred
        return ink
    }
}
