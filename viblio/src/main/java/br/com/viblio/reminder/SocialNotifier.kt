package br.com.viblio.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import br.com.centralcasa.R
import br.com.centralcasa.data.HomeAssistantCredentials
import br.com.viblio.ViblioActivity
import br.com.viblio.account.AccountApi
import br.com.viblio.account.AccountConnection
import br.com.viblio.account.SocialRequests
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.Executors

data class SocialSummary(
    val friendRequests: Int = 0,
    val clubInvites: Int = 0,
    val openVotes: Int = 0,
    val unreadMessages: Int = 0,
) {
    val total: Int get() = friendRequests + clubInvites + openVotes + unreadMessages

    fun signature(): String = "$friendRequests:$clubInvites:$openVotes:$unreadMessages"

    fun notificationBody(): String {
        val parts = mutableListOf<String>()
        if (unreadMessages > 0) parts.add(if (unreadMessages == 1) "1 nova mensagem no clube" else "$unreadMessages novas mensagens nos clubes")
        if (friendRequests > 0) parts.add(if (friendRequests == 1) "1 pedido de amizade" else "$friendRequests pedidos de amizade")
        if (clubInvites > 0) parts.add(if (clubInvites == 1) "1 convite de clube" else "$clubInvites convites de clube")
        if (openVotes > 0) parts.add(if (openVotes == 1) "1 votação aberta" else "$openVotes votações abertas")
        return parts.joinToString(" · ")
    }

    companion object {
        fun fromJson(obj: JsonObject?): SocialSummary {
            if (obj == null) return SocialSummary()
            fun num(k: String) = obj.get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrDefault(0)?.coerceAtLeast(0) ?: 0
            return SocialSummary(
                friendRequests = num("friend_requests"),
                clubInvites = num("club_invites"),
                openVotes = num("open_votes"),
                unreadMessages = num("unread_messages"),
            )
        }
    }
}

/**
 * 100% self-hosted periodic social activity checker using native Android JobScheduler (no Firebase).
 */
class SocialCheckJobService : JobService() {
    @Volatile private var activeConnection: AccountConnection? = null
    @Volatile private var cancelled = false

    override fun onStartJob(params: JobParameters): Boolean {
        val appCtx = applicationContext
        if (!SocialNotifier.isEnabled(appCtx)) return false
        cancelled = false
        executor.execute {
            try {
                val summary = SocialNotifier.fetchSummaryBlocking(appCtx) { activeConnection = it }
                if (!cancelled && summary != null) {
                    SocialNotifier.maybeNotify(appCtx, summary)
                }
            } finally {
                activeConnection = null
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        val conn = activeConnection
        activeConnection = null
        if (conn != null) executor.execute { runCatching { conn.cancel() } }
        return false
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}

object SocialNotifier {
    private const val PREFS = "viblio_social_notifier"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SIG = "last_sig"
    private const val KEY_LAST_TOTAL = "last_total"
    private const val CHANNEL_ID = "viblio_social_activity"
    private const val NOTIF_ID = 6204
    private const val JOB_ID = 6204
    private const val INTERVAL_MS = 30L * 60L * 1000L

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) schedule(appCtx) else cancel(appCtx)
    }

    fun schedule(context: Context) {
        val appCtx = context.applicationContext
        if (!isEnabled(appCtx)) return
        val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val info = JobInfo.Builder(JOB_ID, ComponentName(appCtx, SocialCheckJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL_MS)
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(info) }
    }

    fun cancel(context: Context) {
        val appCtx = context.applicationContext
        val scheduler = appCtx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        runCatching { scheduler?.cancel(JOB_ID) }
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching { nm?.cancel(NOTIF_ID) }
    }

    fun clearState(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAST_SIG)
            .remove(KEY_LAST_TOTAL)
            .apply()
        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching { nm?.cancel(NOTIF_ID) }
    }

    fun markSeen(context: Context, summary: SocialSummary) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_SIG, summary.signature())
            .putInt(KEY_LAST_TOTAL, summary.total)
            .apply()
        if (summary.total == 0) {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            runCatching { nm?.cancel(NOTIF_ID) }
        }
    }

    internal fun shouldNotify(previousSig: String, previousTotal: Int, next: SocialSummary): Boolean {
        if (next.total <= 0) return false
        if (next.signature() == previousSig) return false
        return next.total > previousTotal || previousSig.isBlank()
    }

    internal fun fetchSummaryBlocking(
        context: Context,
        onConnection: (AccountConnection) -> Unit = {},
    ): SocialSummary? {
        val secret = HomeAssistantCredentials(context, "viblio_account_session", "viblio_account_session_key")
        val raw = runCatching { secret.load() }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        val session = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val address = session.get("address")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val token = session.get("token")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (address.isBlank() || token.isBlank()) return null
        val api = AccountApi(address, token)
        val conn = AccountConnection()
        onConnection(conn)
        return try {
            val res = SocialRequests.call(api, "/v1/social/summary", "GET", null, conn)
            SocialSummary.fromJson(res)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { api.close() }
        }
    }

    @Suppress("DEPRECATION")
    internal fun maybeNotify(context: Context, summary: SocialSummary) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prevSig = prefs.getString(KEY_LAST_SIG, "").orEmpty()
        val prevTotal = prefs.getInt(KEY_LAST_TOTAL, 0)
        prefs.edit().putString(KEY_LAST_SIG, summary.signature()).putInt(KEY_LAST_TOTAL, summary.total).apply()
        if (!shouldNotify(prevSig, prevTotal, summary)) return
        val body = summary.notificationBody()
        if (body.isBlank()) return

        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Atividade social", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisos de novas mensagens nos clubes, convites e votações."
            }
            nm.createNotificationChannel(ch)
        }
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse("viblio://friends"), appCtx, ViblioActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(appCtx, NOTIF_ID, openIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(appCtx, CHANNEL_ID)
        } else {
            android.app.Notification.Builder(appCtx)
        }
        val notif = builder
            .setSmallIcon(R.drawable.viblio_launcher_fg)
            .setContentTitle("Viblio · Amigos")
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
    }
}
