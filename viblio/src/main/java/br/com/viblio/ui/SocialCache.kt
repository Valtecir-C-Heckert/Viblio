package br.com.viblio.ui

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * Lightweight profile-scoped offline cache for the first page of Feed and Clubs list.
 * Never stores tokens or credentials; strictly bounded and cleared on logout/profile switch.
 */
class SocialCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "viblio_social_cache"
        const val MAX_JSON_BYTES = 256 * 1024

        internal fun scopeKey(identity: String): String {
            if (identity.isBlank()) return ""
            return MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
                .take(24)
        }

        internal fun parseBounded(raw: String?): JsonObject? {
            if (raw.isNullOrBlank()) return null
            if (raw.length > MAX_JSON_BYTES || raw.toByteArray().size > MAX_JSON_BYTES) return null
            return runCatching { JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
        }
    }

    private fun key(identity: String, slot: String): String? {
        val scope = scopeKey(identity)
        return if (scope.isBlank()) null else "${scope}_$slot"
    }

    fun saveFeed(identity: String, payload: JsonObject) = save(identity, "feed", payload)
    fun loadFeed(identity: String): JsonObject? = load(identity, "feed")

    fun saveClubs(identity: String, payload: JsonObject) = save(identity, "clubs", payload)
    fun loadClubs(identity: String): JsonObject? = load(identity, "clubs")

    fun clear(identity: String) {
        val scope = scopeKey(identity)
        if (scope.isBlank()) return
        prefs.edit().remove("${scope}_feed").remove("${scope}_clubs").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun save(identity: String, slot: String, payload: JsonObject) {
        val k = key(identity, slot) ?: return
        val raw = payload.toString()
        if (raw.length > MAX_JSON_BYTES || raw.toByteArray().size > MAX_JSON_BYTES) return
        prefs.edit().putString(k, raw).apply()
    }

    private fun load(identity: String, slot: String): JsonObject? {
        val k = key(identity, slot) ?: return null
        return parseBounded(prefs.getString(k, null))
    }
}
