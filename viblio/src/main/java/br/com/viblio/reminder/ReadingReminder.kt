package br.com.viblio.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import br.com.centralcasa.R
import br.com.centralcasa.books.LibraryRepository
import br.com.centralcasa.books.ReadingHeatmap
import br.com.viblio.ViblioActivity
import java.util.Calendar
import java.util.Locale

/**
 * Optional local daily reading reminder.
 * Stays completely silent on days when the user already logged a session or ReadingDate.
 */
class ReadingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (ReadingReminder.isEnabled(appCtx)) {
                ReadingReminder.scheduleNext(appCtx)
            }
            return
        }
        if (!ReadingReminder.isEnabled(appCtx)) return
        // Re-schedule next day's reminder first.
        ReadingReminder.scheduleNext(appCtx)

        val pending = goAsync()
        reminderExecutor.execute {
            try {
                val today = br.com.centralcasa.books.ReadingDays.at(System.currentTimeMillis())
                val repo = LibraryRepository(appCtx)
                val (alreadyReadToday, currentTitle) = try {
                    repo.hasReadOnDay(today) to repo.current()?.title
                } finally {
                    runCatching { repo.close() }
                }
                if (!alreadyReadToday) {
                    ReadingReminder.notifyUser(appCtx, currentTitle)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val reminderExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}

object ReadingReminder {
    private const val PREFS = "viblio_reading_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val CHANNEL_ID = "viblio_reading_reminder"
    private const val NOTIF_ID = 6102
    private const val REQ_CODE = 6102

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun hour(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HOUR, 21).coerceIn(0, 23)

    fun minute(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MINUTE, 0).coerceIn(0, 59)

    fun label(context: Context): String =
        String.format(Locale.US, "%02d:%02d", hour(context), minute(context))

    fun enable(context: Context, hour: Int, minute: Int) {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_HOUR, h)
            .putInt(KEY_MINUTE, m)
            .apply()
        scheduleNext(context)
    }

    fun disable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(alarmPendingIntent(context))
    }

    fun scheduleNext(context: Context) {
        if (!isEnabled(context)) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(context))
            set(Calendar.MINUTE, minute(context))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 5_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val pi = alarmPendingIntent(context)
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReadingReminderReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQ_CODE, intent, flags)
    }

    @Suppress("DEPRECATION")
    internal fun notifyUser(context: Context, currentTitle: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Lembrete de leitura", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Aviso diário opcional quando você ainda não registrou leitura no dia."
            }
            nm.createNotificationChannel(ch)
        }
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse("viblio://current"), context, ViblioActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(context, NOTIF_ID, openIntent, flags)

        val body = if (!currentTitle.isNullOrBlank()) {
            "Continue \"$currentTitle\" e mantenha sua sequência de leitura."
        } else {
            "Separe alguns minutos hoje para sua leitura no Viblio."
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            android.app.Notification.Builder(context)
        }
        val notif = builder
            .setSmallIcon(R.drawable.viblio_launcher_fg)
            .setContentTitle("Hora da leitura")
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIF_ID, notif) }
    }
}
