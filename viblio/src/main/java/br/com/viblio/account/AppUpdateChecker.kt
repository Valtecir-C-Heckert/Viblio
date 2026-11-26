package br.com.viblio.account

import android.content.Context
import android.os.Build
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class AppVersionInfo(val versionName: String, val versionCode: Long)

data class AppUpdateRelease(
    val version: String,
    val tag: String,
    val apkUrl: String,
)

/**
 * Checks GitHub Releases for a newer signed Viblio APK strictly pinned to Valtecir-C-Heckert/Viblio.
 */
object AppUpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/Valtecir-C-Heckert/Viblio/releases?per_page=10"
    private const val ALLOWED_DOWNLOAD_PREFIX = "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/"
    private const val MAX_BODY_BYTES = 512 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Suppress("DEPRECATION")
    fun currentVersion(context: Context): AppVersionInfo = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        AppVersionInfo(info.versionName.orEmpty().ifBlank { "0.0.0" }, code)
    }.getOrDefault(AppVersionInfo("0.0.0", 0L))

    fun compareVersions(a: String, b: String): Int {
        val pa = parseParts(a)
        val pb = parseParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun parseParts(v: String): List<Int> =
        v.trim().removePrefix("viblio-v").removePrefix("v")
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun isAllowedApkUrl(url: String): Boolean =
        url.startsWith(ALLOWED_DOWNLOAD_PREFIX) &&
            url.endsWith(".apk") &&
            url.length <= 512 &&
            !url.contains("..") &&
            !url.any(Char::isWhitespace)

    fun parseLatestNewerRelease(json: String, currentVersion: String): AppUpdateRelease? {
        if (json.length > MAX_BODY_BYTES) return null
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!root.isJsonArray) return null
        var best: AppUpdateRelease? = null
        for (el in root.asJsonArray) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            if (obj.get("draft")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) continue
            if (obj.get("prerelease")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) continue
            val tag = obj.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (!tag.startsWith("viblio-v")) continue
            val version = tag.removePrefix("viblio-v")
            if (!version.matches(Regex("\\d+(?:\\.\\d+){1,3}"))) continue
            if (compareVersions(version, currentVersion) <= 0) continue
            val assets = obj.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val apkUrl = assets.mapNotNull { a ->
                val ao = a.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = ao.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val url = ao.get("browser_download_url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (name.startsWith("viblio-") && name.endsWith(".apk") && isAllowedApkUrl(url)) url else null
            }.firstOrNull() ?: continue
            if (best == null || compareVersions(version, best.version) > 0) {
                best = AppUpdateRelease(version = version, tag = tag, apkUrl = apkUrl)
            }
        }
        return best
    }

    /** Blocking network check; always call on a background thread. */
    fun fetchLatestNewer(currentVersion: String): AppUpdateRelease? {
        val req = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Viblio-Android/$currentVersion")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Não foi possível verificar atualizações (HTTP ${resp.code}).")
            val body = resp.body?.source()?.let { src ->
                src.request(MAX_BODY_BYTES.toLong() + 1)
                if (src.buffer.size > MAX_BODY_BYTES) error("Resposta de atualização grande demais.")
                src.readUtf8()
            }.orEmpty()
            return parseLatestNewerRelease(body, currentVersion)
        }
    }

    private const val PREFS_UPDATE = "viblio_app_update_cache"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_VERSION = "version"
    private const val KEY_TAG = "tag"
    private const val KEY_APK_URL = "apk_url"
    internal const val AUTO_CHECK_INTERVAL_MS = 30L * 60L * 1000L

    fun shouldAutoCheck(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = context.applicationContext.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
            .getLong(KEY_CHECKED_AT, 0L)
        return nowMs - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun cachedNewerRelease(context: Context, currentVersion: String): AppUpdateRelease? {
        val p = context.applicationContext.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        val version = p.getString(KEY_VERSION, null).orEmpty()
        val tag = p.getString(KEY_TAG, null).orEmpty()
        val url = p.getString(KEY_APK_URL, null).orEmpty()
        if (version.isBlank() || tag.isBlank() || !isAllowedApkUrl(url)) return null
        return if (compareVersions(version, currentVersion) > 0) AppUpdateRelease(version, tag, url) else null
    }

    fun recordCheckResult(context: Context, release: AppUpdateRelease?, nowMs: Long = System.currentTimeMillis()) {
        val edit = context.applicationContext.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CHECKED_AT, nowMs)
        if (release != null && isAllowedApkUrl(release.apkUrl)) {
            edit.putString(KEY_VERSION, release.version)
                .putString(KEY_TAG, release.tag)
                .putString(KEY_APK_URL, release.apkUrl)
        } else {
            edit.remove(KEY_VERSION).remove(KEY_TAG).remove(KEY_APK_URL)
        }
        edit.apply()
    }
}
