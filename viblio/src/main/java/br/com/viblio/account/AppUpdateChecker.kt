package br.com.viblio.account

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppVersionInfo(val versionName: String, val versionCode: Long)

data class AppUpdateRelease(
    val version: String,
    val tag: String,
    val apkUrl: String,
    val apkName: String = "",
    val sumsUrl: String? = null,
)

/**
 * Checks GitHub Releases for a newer signed Viblio APK strictly pinned to Valtecir-C-Heckert/Viblio,
 * verifies its SHA-256 checksum before invoking Android's native PackageInstaller.
 */
object AppUpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/Valtecir-C-Heckert/Viblio/releases?per_page=10"
    private const val ALLOWED_DOWNLOAD_PREFIX = "https://github.com/Valtecir-C-Heckert/Viblio/releases/download/"
    private const val MAX_BODY_BYTES = 512 * 1024
    private const val MAX_SUMS_BYTES = 64 * 1024
    internal const val MAX_APK_BYTES = 40L * 1024L * 1024L
    internal const val ACTION_INSTALL_RESULT = "br.com.viblio.action.APP_UPDATE_INSTALL_RESULT"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                if (!req.isHttps) throw IOException("Redirecionamento inseguro bloqueado.")
                chain.proceed(req)
            }
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

    private fun isAllowedAssetUrl(url: String): Boolean =
        url.startsWith(ALLOWED_DOWNLOAD_PREFIX) &&
            url.length <= 512 &&
            !url.contains("..") &&
            !url.any(Char::isWhitespace)

    fun isAllowedApkUrl(url: String): Boolean =
        isAllowedAssetUrl(url) && url.endsWith(".apk")

    fun isAllowedSumsUrl(url: String): Boolean =
        isAllowedAssetUrl(url) && (url.endsWith("SHA256SUMS.txt") || url.endsWith("SHA256SUMS"))

    fun parseExpectedSha256(sumsText: String, fileName: String): String? {
        if (sumsText.length > MAX_SUMS_BYTES || fileName.isBlank()) return null
        val target = fileName.trim().substringAfterLast('/')
        val lineRegex = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")
        for (rawLine in sumsText.lineSequence()) {
            val match = lineRegex.matchEntire(rawLine.trim()) ?: continue
            val candidate = match.groupValues[2].trim().substringAfterLast('/')
            if (candidate == target) {
                return match.groupValues[1].lowercase()
            }
        }
        return null
    }

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
            var apkName = ""
            var apkUrl: String? = null
            var sumsUrl: String? = null
            for (a in assets) {
                val ao = a.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val name = ao.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val url = ao.get("browser_download_url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (apkUrl == null && name.startsWith("viblio-") && name.endsWith(".apk") && isAllowedApkUrl(url)) {
                    apkName = name
                    apkUrl = url
                } else if (sumsUrl == null && (name.endsWith("SHA256SUMS.txt") || name == "SHA256SUMS") && isAllowedSumsUrl(url)) {
                    sumsUrl = url
                }
            }
            val resolvedApk = apkUrl ?: continue
            if (best == null || compareVersions(version, best.version) > 0) {
                best = AppUpdateRelease(
                    version = version,
                    tag = tag,
                    apkUrl = resolvedApk,
                    apkName = apkName,
                    sumsUrl = sumsUrl,
                )
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

    private fun safeApkFileName(release: AppUpdateRelease): String {
        val candidate = release.apkName.ifBlank { release.apkUrl.substringAfterLast('/') }
        val clean = candidate.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (clean.endsWith(".apk")) clean else "viblio-${release.version}.apk"
    }

    /**
     * Downloads the release checksums and APK over HTTPS, enforces a 40 MB size bound,
     * validates ZIP/APK magic bytes and exact SHA-256 digest before returning the cached file.
     * Always call on a background thread.
     */
    fun downloadAndVerifyApk(
        context: Context,
        release: AppUpdateRelease,
        onProgress: (percent: Int) -> Unit = {},
    ): File {
        require(isAllowedApkUrl(release.apkUrl)) { "Endereço de atualização inválido." }
        val sumsUrl = release.sumsUrl?.takeIf(::isAllowedSumsUrl)
            ?: error("Arquivo SHA-256 ausente nesta versão.")
        val apkFileName = safeApkFileName(release)

        // 1) Fetch and parse expected SHA-256 checksum
        val sumsReq = Request.Builder()
            .url(sumsUrl)
            .header("User-Agent", "Viblio-Android/${currentVersion(context).versionName}")
            .build()
        val expectedHash = downloadClient.newCall(sumsReq).execute().use { resp ->
            if (!resp.isSuccessful) error("Não foi possível obter o checksum (HTTP ${resp.code}).")
            val text = resp.body?.source()?.let { src ->
                src.request(MAX_SUMS_BYTES.toLong() + 1)
                if (src.buffer.size > MAX_SUMS_BYTES) error("Arquivo de checksum grande demais.")
                src.readUtf8()
            }.orEmpty()
            parseExpectedSha256(text, apkFileName)
                ?: error("Checksum não encontrado para $apkFileName.")
        }

        // 2) Stream APK to cacheDir/updates while hashing and bounding size
        val updatesDir = File(context.applicationContext.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "$apkFileName.part")
        val finalFile = File(updatesDir, apkFileName)
        if (partFile.exists()) partFile.delete()

        val apkReq = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "Viblio-Android/${currentVersion(context).versionName}")
            .build()

        var success = false
        try {
            downloadClient.newCall(apkReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Falha ao baixar atualização (HTTP ${resp.code}).")
                val body = resp.body ?: error("Resposta de download vazia.")
                val contentLength = body.contentLength()
                if (contentLength > MAX_APK_BYTES) error("Arquivo de atualização maior que o limite permitido.")
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(16 * 1024)
                val header = ByteArray(4)
                var headerRead = 0
                var totalRead = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            totalRead += n
                            if (totalRead > MAX_APK_BYTES) error("Arquivo de atualização excedeu o limite de 40 MB.")
                            if (headerRead < 4) {
                                val take = minOf(n, 4 - headerRead)
                                System.arraycopy(buf, 0, header, headerRead, take)
                                headerRead += take
                            }
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                            if (contentLength > 0) {
                                val pct = ((totalRead * 99L) / contentLength).toInt().coerceIn(0, 99)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                if (headerRead < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte() ||
                    header[2] != 0x03.toByte() || header[3] != 0x04.toByte()
                ) {
                    error("Arquivo baixado não é um APK válido.")
                }
                val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actualHex.toByteArray(Charsets.US_ASCII), expectedHash.toByteArray(Charsets.US_ASCII))) {
                    error("Assinatura SHA-256 do APK não confere.")
                }
            }
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                error("Não foi possível preparar o arquivo de atualização.")
            }
            onProgress(100)
            success = true
            return finalFile
        } finally {
            if (!success) {
                partFile.delete()
            }
        }
    }

    /** Commits a verified APK file into Android's native PackageInstaller. */
    fun installApk(context: Context, apkFile: File) {
        val appCtx = context.applicationContext
        require(apkFile.isFile && apkFile.length() in 1L..MAX_APK_BYTES) { "Arquivo APK inválido." }
        val installer = appCtx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appCtx.packageName)
            setSize(apkFile.length())
        }
        val sessionId = installer.createSession(params)
        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            session.openWrite("viblio-update.apk", 0L, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
                session.fsync(out)
            }
            val callbackIntent = Intent(appCtx, AppUpdateInstallReceiver::class.java).apply {
                action = ACTION_INSTALL_RESULT
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(appCtx, sessionId, callbackIntent, pendingFlags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            throw e
        } finally {
            runCatching { session?.close() }
        }
    }

    fun cleanupUpdates(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "updates")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private const val PREFS_UPDATE = "viblio_app_update_cache"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_VERSION = "version"
    private const val KEY_TAG = "tag"
    private const val KEY_APK_URL = "apk_url"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_SUMS_URL = "sums_url"
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
        val name = p.getString(KEY_APK_NAME, null).orEmpty()
        val sums = p.getString(KEY_SUMS_URL, null)?.takeIf(::isAllowedSumsUrl)
        if (version.isBlank() || tag.isBlank() || !isAllowedApkUrl(url)) return null
        return if (compareVersions(version, currentVersion) > 0) {
            AppUpdateRelease(version, tag, url, name, sums)
        } else null
    }

    fun recordCheckResult(context: Context, release: AppUpdateRelease?, nowMs: Long = System.currentTimeMillis()) {
        val edit = context.applicationContext.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CHECKED_AT, nowMs)
        if (release != null && isAllowedApkUrl(release.apkUrl)) {
            edit.putString(KEY_VERSION, release.version)
                .putString(KEY_TAG, release.tag)
                .putString(KEY_APK_URL, release.apkUrl)
                .putString(KEY_APK_NAME, release.apkName)
            val validSums = release.sumsUrl?.takeIf(::isAllowedSumsUrl)
            if (validSums != null) edit.putString(KEY_SUMS_URL, validSums) else edit.remove(KEY_SUMS_URL)
        } else {
            edit.remove(KEY_VERSION).remove(KEY_TAG).remove(KEY_APK_URL)
                .remove(KEY_APK_NAME).remove(KEY_SUMS_URL)
        }
        edit.apply()
    }
}

class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdateChecker.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure {
                            Toast.makeText(context, "Não foi possível abrir o instalador do Android.", Toast.LENGTH_LONG).show()
                        }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdateChecker.cleanupUpdates(context)
                Toast.makeText(context, "Viblio atualizado!", Toast.LENGTH_SHORT).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Toast.makeText(context, "Assinatura incompatível com a versão instalada.", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Toast.makeText(context, "Sem espaço livre para instalar a atualização.", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(context, "Não foi possível concluir a instalação.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
