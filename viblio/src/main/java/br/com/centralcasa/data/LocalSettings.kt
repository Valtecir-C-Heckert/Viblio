package br.com.centralcasa.data

import android.content.Context
class LocalSettings(context: Context) {
    private val prefs = context.getSharedPreferences(br.com.centralcasa.books.LibraryProfiles.settings(context), Context.MODE_PRIVATE)
    fun get(key: String) = prefs.getString(key, "") ?: ""
    fun set(key: String, value: String) { prefs.edit().putString(key, value.trim()).apply() }
    fun appLaunchCount(packageName: String) = prefs.getLong("app_launch_count:$packageName", 0L)
    fun recordAppLaunch(packageName: String) {
        val count = appLaunchCount(packageName)
        prefs.edit().putLong("app_launch_count:$packageName", if (count == Long.MAX_VALUE) count else count + 1).apply()
    }
    var cameraKeepConnected: Boolean
        get() = prefs.getBoolean("camera_keep_connected", true)
        set(value) { prefs.edit().putBoolean("camera_keep_connected", value).apply() }
    var cameraCompatible: Boolean
        get() = prefs.getBoolean("camera_compatible", true)
        set(value) { prefs.edit().putBoolean("camera_compatible", value).apply() }
    var restEnabled: Boolean
        get() = prefs.getBoolean("rest_enabled", true)
        set(value) { prefs.edit().putBoolean("rest_enabled", value).apply() }
    var proximityWake: Boolean
        get() = prefs.getBoolean("proximity_wake", false)
        set(value) { prefs.edit().putBoolean("proximity_wake", value).apply() }
    var proximityIdleSeconds: Int
        get() = prefs.getInt("proximity_idle_seconds", 30).coerceIn(15, 120)
        set(value) { prefs.edit().putInt("proximity_idle_seconds", value.coerceIn(15, 120)).apply() }
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) { prefs.edit().putBoolean("keep_screen_on", value).apply() }
    var booksPerRow: Int
        get() = prefs.getInt("books_per_row", 3).coerceIn(1, 6)
        set(value) { prefs.edit().putInt("books_per_row", value.coerceIn(1, 6)).apply() }
    private fun subgenreVocabularyKey(genre: String) = "subgenre_vocabulary:" + br.com.centralcasa.books.ShelfFilters.key(br.com.centralcasa.books.BookGenres.split(genre).genre)
    fun savedSubgenres(genre: String) = br.com.centralcasa.books.BookGenres.suggestions(prefs.getStringSet(subgenreVocabularyKey(genre), emptySet()).orEmpty().toList())
    fun rememberSubgenres(genre: String, values: List<String>) {
        if (genre.isBlank()) return
        val names = br.com.centralcasa.books.BookGenres.suggestions(savedSubgenres(genre) + values.map { it.trim().replace(Regex("\\s+"), " ") })
        prefs.edit().putStringSet(subgenreVocabularyKey(genre), names.toSet()).apply()
    }
    fun shelfColumns(type: String) = if (type == "book") booksPerRow else prefs.getInt("shelf_columns:$type", 3).coerceIn(1, 6)
    fun setShelfColumns(type: String, columns: Int) {
        if (type == "book") booksPerRow = columns else prefs.edit().putInt("shelf_columns:$type", columns.coerceIn(1, 6)).apply()
    }
    fun collectionVolumesPerRow(collectionId: String, type: String = "book") = prefs.getInt("collection_columns:$collectionId", shelfColumns(type)).coerceIn(1, 6)
    fun setCollectionVolumesPerRow(collectionId: String, columns: Int) {
        prefs.edit().putInt("collection_columns:$collectionId", columns.coerceIn(1, 6)).apply()
    }
    fun shelfWoodStyle(): br.com.centralcasa.books.ShelfWoodStyle =
        br.com.centralcasa.books.ShelfWoodStyle.of(prefs.getString("SHELF_WOOD_STYLE", null))
    fun setShelfWoodStyle(style: br.com.centralcasa.books.ShelfWoodStyle) {
        prefs.edit().putString("SHELF_WOOD_STYLE", style.key).apply()
    }
    fun shelfSpineDesign(): br.com.centralcasa.books.SpineDesignMode =
        br.com.centralcasa.books.SpineDesignMode.of(prefs.getString("SHELF_SPINE_DESIGN", null))
    fun setShelfSpineDesign(mode: br.com.centralcasa.books.SpineDesignMode) {
        prefs.edit().putString("SHELF_SPINE_DESIGN", mode.key).apply()
    }
    fun galleryUiStyle(): Boolean = prefs.getString("SHELF_UI_STYLE", "classic") == "gallery"
    fun setGalleryUiStyle(enabled: Boolean) {
        prefs.edit().putString("SHELF_UI_STYLE", if (enabled) "gallery" else "classic").apply()
    }
    fun lightFavorite(entityId: String, slot: Int): br.com.centralcasa.services.Rgb {
        require(slot in 0..5)
        val fallback = lightColorDefaults[slot].second
        val packed = prefs.getInt("light_favorite:$entityId:$slot", android.graphics.Color.rgb(fallback.red, fallback.green, fallback.blue))
        return br.com.centralcasa.services.Rgb(android.graphics.Color.red(packed), android.graphics.Color.green(packed), android.graphics.Color.blue(packed))
    }
    fun setLightFavorite(entityId: String, slot: Int, color: br.com.centralcasa.services.Rgb?) {
        require(slot in 0..5)
        prefs.edit().apply { if (color == null) remove("light_favorite:$entityId:$slot") else putInt("light_favorite:$entityId:$slot", android.graphics.Color.rgb(color.red, color.green, color.blue)) }.apply()
    }
    private fun genreKey(genre: String) = "genre_color:" + genre.trim().ifBlank { "Sem gênero" }.lowercase(java.util.Locale.ROOT)
    fun hasGenreColor(genre: String) = prefs.contains(genreKey(genre))
    fun genreColor(genre: String) = prefs.getInt(genreKey(genre), android.graphics.Color.rgb(21, 21, 21))
    fun setGenreColor(genre: String, color: Int?) {
        prefs.edit().apply { if (color == null) remove(genreKey(genre)) else putInt(genreKey(genre), color) }.apply()
    }
    /** Preserve a main genre's choice; otherwise reuse one old subgenre color deterministically. */
    fun migrateGenreColors() {
        if (prefs.getBoolean("genre_parents_migrated_v2", false)) return
        val edit = prefs.edit(); val chosen = mutableSetOf<String>()
        prefs.all.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (key.startsWith("genre_color:") && value is Int) {
                val old = key.removePrefix("genre_color:")
                val parent = br.com.centralcasa.books.BookGenres.split(old).genre
                val target = genreKey(parent)
                if (target != key && !prefs.contains(target) && chosen.add(target)) edit.putInt(target, value)
            }
        }
        edit.putBoolean("genre_parents_migrated_v2", true).apply()
    }
    fun genreCardColor(color: Int) = (color and 0x00ffffff) or 0xD9000000.toInt()
    fun genreCardTextColor(color: Int) = colorTextColor(android.graphics.Color.rgb(android.graphics.Color.red(color) * 102 / 255, android.graphics.Color.green(color) * 102 / 255, android.graphics.Color.blue(color) * 102 / 255))
    fun genreTextColor(genre: String): Int = colorTextColor(genreColor(genre))
    fun colorTextColor(color: Int): Int {
        fun channel(value: Int): Double { val c = value / 255.0; return if (c <= .04045) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4) }
        val luminance = .2126 * channel(android.graphics.Color.red(color)) + .7152 * channel(android.graphics.Color.green(color)) + .0722 * channel(android.graphics.Color.blue(color))
        return if (luminance > .179) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    val spotifyClientId get() = get("SPOTIFY_CLIENT_ID").ifBlank { "7225f2bde9c74535ba5c8a196dbfb449" }
    companion object {
        val lightColorDefaults = listOf("Verde" to br.com.centralcasa.services.Rgb(0, 255, 0), "Vermelho" to br.com.centralcasa.services.Rgb(255, 0, 0), "Azul" to br.com.centralcasa.services.Rgb(0, 0, 255), "Laranja" to br.com.centralcasa.services.Rgb(255, 128, 0), "Rosa" to br.com.centralcasa.services.Rgb(255, 0, 128), "Amarelo" to br.com.centralcasa.services.Rgb(255, 255, 0))
        val keys = listOf("SERVER_URL", "HOME_ASSISTANT_URL", "AI_URL", "BOOKS_APP_PACKAGE", "SPOTIFY_CLIENT_ID") }
}
