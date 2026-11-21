package br.com.viblio.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Switches the launcher app icon among 16 pre-declared <activity-alias> entries so the
 * Viblio icon matches the cover color of the last viewed/current book when leaving the app.
 */
object DynamicAppIcon {
    private const val PREFS = "viblio_app_icon"
    private const val KEY_ENABLED = "dynamic_icon_enabled"
    private const val KEY_CURRENT = "active_alias_key"
    private val executor = Executors.newSingleThreadExecutor()

    data class AliasSpec(
        val key: String,
        val className: String,
        val r: Int,
        val g: Int,
        val b: Int,
    )

    val aliases: List<AliasSpec> = listOf(
        AliasSpec("default", "br.com.viblio.LauncherDefault", 0xF0, 0xEC, 0xE4),
        AliasSpec("red", "br.com.viblio.LauncherRed", 0xD9, 0x4B, 0x4B),
        AliasSpec("crimson", "br.com.viblio.LauncherCrimson", 0xA8, 0x32, 0x48),
        AliasSpec("orange", "br.com.viblio.LauncherOrange", 0xE0, 0x7A, 0x3A),
        AliasSpec("amber", "br.com.viblio.LauncherAmber", 0xD9, 0xA4, 0x41),
        AliasSpec("yellow", "br.com.viblio.LauncherYellow", 0xE3, 0xC5, 0x4A),
        AliasSpec("olive", "br.com.viblio.LauncherOlive", 0x8E, 0x9B, 0x42),
        AliasSpec("green", "br.com.viblio.LauncherGreen", 0x3F, 0xA3, 0x6C),
        AliasSpec("emerald", "br.com.viblio.LauncherEmerald", 0x2B, 0x8A, 0x6E),
        AliasSpec("teal", "br.com.viblio.LauncherTeal", 0x2F, 0xA8, 0xA0),
        AliasSpec("cyan", "br.com.viblio.LauncherCyan", 0x38, 0xAE, 0xE6),
        AliasSpec("blue", "br.com.viblio.LauncherBlue", 0x3F, 0x7D, 0xE0),
        AliasSpec("indigo", "br.com.viblio.LauncherIndigo", 0x5D, 0x65, 0xE0),
        AliasSpec("purple", "br.com.viblio.LauncherPurple", 0x9B, 0x5D, 0xE5),
        AliasSpec("magenta", "br.com.viblio.LauncherMagenta", 0xD9, 0x52, 0x9B),
        AliasSpec("brown", "br.com.viblio.LauncherBrown", 0x9C, 0x6B, 0x4E),
    )

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (!enabled) applyKeyAsync(appCtx, "default")
    }

    /** Pure JVM-testable perceptual color matching (no Android framework dependency). */
    fun nearestKey(color: Int?): String {
        if (color == null) return "default"
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        val (h, s, v) = rgbToHsv(r, g, b)
        // Very desaturated or very dark/white covers map cleanly to the classic default icon.
        if (s < 0.14f || (v > 0.88f && s < 0.22f) || v < 0.08f) return "default"
        var best = "default"
        var bestScore = Float.MAX_VALUE
        for (spec in aliases) {
            if (spec.key == "default") continue
            val (sh, ss, sv) = rgbToHsv(spec.r, spec.g, spec.b)
            val dh = min(abs(h - sh), 360f - abs(h - sh)) / 180f
            val ds = abs(s - ss)
            val dv = abs(v - sv)
            // Hue dominates so red/blue/green/purple covers never drift to an unrelated family.
            val score = dh * dh * 6.5f + ds * ds * 1.4f + dv * dv * 0.9f
            if (score < bestScore) {
                bestScore = score
                best = spec.key
            }
        }
        return best
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / d) % 6f)
            max == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (max == 0f) 0f else d / max
        return Triple(h, s, max)
    }

    fun applyForColorAsync(context: Context, color: Int?) {
        val appCtx = context.applicationContext
        val key = if (isEnabled(appCtx)) nearestKey(color) else "default"
        applyKeyAsync(appCtx, key)
    }

    fun applyForBookAsync(context: Context, colorProvider: () -> Int?) {
        val appCtx = context.applicationContext
        executor.execute {
            runCatching {
                val key = if (isEnabled(appCtx)) nearestKey(runCatching(colorProvider).getOrNull()) else "default"
                applyKeySync(appCtx, key)
            }
        }
    }

    private fun applyKeyAsync(appCtx: Context, key: String) {
        executor.execute { runCatching { applyKeySync(appCtx, key) } }
    }

    @Synchronized
    private fun applyKeySync(appCtx: Context, targetKey: String) {
        val target = aliases.firstOrNull { it.key == targetKey } ?: aliases.first()
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pm = appCtx.packageManager
        val pkg = appCtx.packageName
        val currentState = pm.getComponentEnabledSetting(ComponentName(pkg, target.className))
        val targetAlreadyActive = currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (target.key == "default" && currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        if (targetAlreadyActive && prefs.getString(KEY_CURRENT, "default") == target.key) return

        // Always enable the target alias FIRST so the launcher never sees zero active aliases.
        pm.setComponentEnabledSetting(
            ComponentName(pkg, target.className),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for (other in aliases) {
            if (other.key == target.key) continue
            val cn = ComponentName(pkg, other.className)
            val state = pm.getComponentEnabledSetting(cn)
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        prefs.edit().putString(KEY_CURRENT, target.key).apply()
    }
}
