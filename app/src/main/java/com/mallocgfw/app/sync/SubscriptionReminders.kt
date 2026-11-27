package com.mallocgfw.app.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mallocgfw.app.MainActivity
import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.model.AppTextLanguage
import com.mallocgfw.app.model.SubscriptionItem
import com.mallocgfw.app.model.tr
import com.mallocgfw.app.ui.formatDataSize
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Warns before a subscription runs out: when it expires within
 * [EXPIRY_WARNING_DAYS] days (or has expired) and when [TRAFFIC_WARNING_PERCENT]
 * of its traffic is used (or all of it), from what the provider last reported.
 * Each warning is shown once per plan: a renewal (new expiry or allowance)
 * makes the same warning due again.
 */
object SubscriptionReminders {
    const val EXPIRY_WARNING_DAYS = 3
    const val TRAFFIC_WARNING_PERCENT = 90

    /** One subscription's warnings; [keys] identify them so each is shown once. */
    data class Reminder(val subscriptionId: String, val name: String, val keys: Set<String>, val lines: List<String>)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** What is due now for [subscriptions]: at most one reminder each, its most urgent lines. */
    fun due(subscriptions: List<SubscriptionItem>, nowMs: Long): List<Reminder> = subscriptions.mapNotNull { sub ->
        val usage = sub.usage ?: return@mapNotNull null
        val keys = mutableSetOf<String>()
        val lines = mutableListOf<String>()
        usage.expiresAtSec?.let { expireSec ->
            val expireMs = expireSec * 1000L
            val left = expireMs - nowMs
            when {
                left <= 0 -> {
                    keys += "expired|${sub.id}|$expireSec"
                    lines += tr("已到期，节点可能已无法使用。", "Expired; its nodes may no longer work.", "Истекла, узлы могут больше не работать.")
                }
                left <= EXPIRY_WARNING_DAYS * DAY_MS -> {
                    keys += "expiring|${sub.id}|$expireSec"
                    // Counted like the subscription card: whole days left, 0 = today.
                    val days = (left / DAY_MS).toInt()
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expireMs))
                    lines += if (days < 1) {
                        tr("今天到期（$date）。", "Expires today ($date).", "Истекает сегодня ($date).")
                    } else {
                        tr("将于 $date 到期，剩 $days 天。", "Expires $date; ${if (days == 1) "1 day" else "$days days"} left.", "Истекает $date, осталось дней: $days.")
                    }
                }
            }
        }
        val limit = usage.limitBytes
        val used = usage.usedBytes
        if (limit != null && used != null) {
            val plan = "${sub.id}|$limit|${usage.expireAtSec ?: 0}"
            when {
                used >= limit -> {
                    keys += "usedup|$plan"
                    lines += tr("流量已用完（共 ${formatDataSize(limit)}）。", "All traffic used (${formatDataSize(limit)}).", "Весь трафик израсходован (${formatDataSize(limit)}).")
                }
                used * 100 >= limit * TRAFFIC_WARNING_PERCENT -> {
                    keys += "traffic|$plan"
                    val left = formatDataSize(limit - used)
                    lines += tr("流量已用 ${used * 100 / limit}%，剩余 $left。", "${used * 100 / limit}% of traffic used; $left left.", "Израсходовано ${used * 100 / limit}% трафика, осталось $left.")
                }
            }
        }
        if (keys.isEmpty()) null else Reminder(sub.id, sub.name, keys, lines)
    }

    /**
     * Posts the reminders that are due and not shown yet. Does nothing when
     * they're switched off or notifications aren't allowed (nothing is marked
     * shown then, so they come once notifications are allowed).
     */
    fun check(context: Context) {
        val state = AppController.current()
        if (!state.settings.subscriptionReminders || !canPostNotifications(context)) return
        AppTextLanguage.apply(state.settings.language)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty()
        val due = due(state.subscriptions, System.currentTimeMillis())
        val fresh = due.filter { reminder -> !shown.containsAll(reminder.keys) }
        fresh.forEach { post(context, it) }
        // Keep only keys still due: a warning that cleared (renewed) can come back later.
        val stillDue = due.flatMap { it.keys }.toSet()
        prefs.edit().putStringSet(KEY_SHOWN, (shown intersect stillDue) + fresh.flatMap { it.keys }).apply()
    }

    private fun post(context: Context, reminder: Reminder) {
        ensureChannel(context)
        val launchIntent = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = reminder.lines.joinToString(" ")
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(tr("订阅提醒：${reminder.name}", "Subscription: ${reminder.name}", "Подписка: ${reminder.name}"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setContentIntent(launchIntent)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_BASE_ID + (reminder.subscriptionId.hashCode() and 0xFFFF), notification)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                tr("订阅到期与流量提醒", "Subscription expiry and traffic", "Срок и трафик подписки"),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** A twice-daily check, so a reminder comes even when the app isn't opened. */
    fun schedule(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SubscriptionReminderWorker>(12, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private const val PREFS = "subscription_reminders"
    private const val KEY_SHOWN = "shown"
    private const val CHANNEL_ID = "mallocgfw_subscription_reminders"
    private const val NOTIFICATION_BASE_ID = 0x30000
    private const val UNIQUE_WORK_NAME = "mallocgfw_subscription_reminders"
}

class SubscriptionReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching {
            AppController.init(applicationContext)
            SubscriptionReminders.check(applicationContext)
        }
        return Result.success()
    }
}
