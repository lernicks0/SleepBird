package io.github.lernicks0.sleepbird

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Only one ordinary alarm is armed. Its receiver advances the rolling random plan. */
object NotificationEngine {
    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)
    fun allowed(context: Context): Boolean = notifications(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || alarms(context).canScheduleExactAlarms()

    private fun alarmIntent(context: Context, testId: String? = null, reminder: PlannedReminder? = null): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setData(Uri.parse("sleepbird://alarm/${testId ?: "regular"}"))
        if (testId != null) intent.putExtra("testId", testId)
        if (reminder != null) intent.putExtra("reminderId", reminder.id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(context: Context, time: Long, pending: PendingIntent) {
        if (exactAllowed(context)) {
            try {
                alarms(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
                return
            } catch (_: SecurityException) { /* Permission can be revoked between check and call. */ }
        }
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
    }

    fun cancelAll(context: Context, store: SleepStore) {
        UsageMonitor.stop(context)
        alarms(context).cancel(alarmIntent(context))
        store.tests.forEach { alarms(context).cancel(alarmIntent(context, it.id)) }
        notifications(context).cancelAll()
    }

    fun reconcile(context: Context, store: SleepStore = SleepStore(context), now: Long = System.currentTimeMillis()) {
        alarms(context).cancel(alarmIntent(context))
        if (store.error != null) { cancelAll(context, store); return }
        store.refresh(now)
        val current = SleepNight.current(now, store.settings)
        val obsolete = store.tests.filter { it.nightId != current.id || store.records.any { r -> r.nightId == it.nightId } || it.time < now - 3_600_000L }
        obsolete.forEach { alarms(context).cancel(alarmIntent(context, it.id)) }
        store.tests.removeAll(obsolete.toSet())
        val next = if (allowed(context)) store.plans.flatMap { it.reminders }
            .filter { it.time > now && store.records.none { r -> r.nightId == it.nightId } }
            .minByOrNull { it.time } else null
        store.nextAlarm = next
        if (!store.save()) return
        next?.let { arm(context, it.time, alarmIntent(context, reminder = it)) }
        if (allowed(context)) store.tests.filter { it.time > now }.forEach { arm(context, it.time, alarmIntent(context, it.id)) }
        UsageMonitor.sync(context, now = now)
    }

    fun complete(context: Context, target: String, now: Long = System.currentTimeMillis()): Boolean {
        val store = SleepStore(context)
        val completed = store.complete(target, now)
        // Also clears old notifications; stale actions never complete a different night.
        notifications(context).cancel(target, 100)
        notifications(context).cancel(target, 200)
        store.tests.filter { it.nightId == target }.forEach { alarms(context).cancel(alarmIntent(context, it.id)) }
        store.tests.removeAll { it.nightId == target }
        reconcile(context, store, now)
        UsageMonitor.sync(context, allowStart = true, now = now)
        return completed
    }

    fun scheduleTest(context: Context, seconds: Int): Boolean {
        if (!allowed(context)) return false
        val now = System.currentTimeMillis()
        val store = SleepStore(context)
        if (store.error != null) return false
        val night = SleepNight.current(now, store.settings)
        if (store.records.any { it.nightId == night.id }) return false
        val id = "test$seconds"
        store.tests.removeAll { it.id == id }
        store.tests += TestReminder(id, night.id, now + seconds * 1000L)
        if (!store.save()) return false
        arm(context, now + seconds * 1000L, alarmIntent(context, id))
        return true
    }

    fun receive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val store = SleepStore(context)
        if (store.error != null) { cancelAll(context, store); return }
        val night = SleepNight.current(now, store.settings)
        val testId = intent.getStringExtra("testId")
        if (testId != null) {
            val test = store.tests.firstOrNull { it.id == testId }
            store.tests.removeAll { it.id == testId }
            if (test != null && test.nightId == night.id && now >= test.time && now < test.time + 3_600_000L &&
                store.records.none { it.nightId == night.id }) {
                post(context, store.settings, night.id, "测试通知 · 晚安任务已上线 🐦", true)
            }
        } else {
            val reminder = store.nextAlarm
            if (reminder != null && reminder.id == intent.getStringExtra("reminderId") &&
                reminder.nightId == night.id && night.contains(now) && now >= reminder.time &&
                store.records.none { it.nightId == night.id } &&
                (store.lastDelivered == 0L || now - store.lastDelivered >= store.settings.gap.first * 60_000L)) {
                val level = ReminderScheduler.levelAt(now, night, store.settings)
                val message = if (level == reminder.level && reminder.message != store.lastMessage) reminder.message
                    else NotificationMessageProvider.pick(level, store.lastMessage)
                if (post(context, store.settings, night.id, message, false)) {
                    store.delivered[night.id] = (store.delivered[night.id] ?: 0) + 1
                    store.lastDelivered = now
                    store.lastMessage = message
                }
            }
        }
        reconcile(context, store, now)
    }

    fun postRelapse(context: Context, settings: AppSettings, night: String) {
        post(context, settings, night, NotificationMessageProvider.relapse.random(), false)
    }

    private fun post(context: Context, settings: AppSettings, night: String, message: String, test: Boolean): Boolean {
        if (!allowed(context)) return false
        val manager = notifications(context)
        val channelId = "sleepbird.sound${settings.sound}.vibration${settings.vibration}"
        val channel = NotificationChannel(channelId, "催睡 · ${if (settings.sound) "有声" else "静音"} · ${if (settings.vibration) "振动" else "无振动"}", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "半随机的本地晚安提醒"
        channel.enableVibration(settings.vibration)
        if (settings.vibration) channel.vibrationPattern = longArrayOf(0, 180, 100, 180)
        if (!settings.sound) channel.setSound(null, null)
        manager.createNotificationChannel(channel)
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val done = PendingIntent.getBroadcast(context, 0, Intent(context, SleepActionReceiver::class.java)
            .setData(Uri.parse("sleepbird://complete/$night")).putExtra("night", night),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_bird).setContentTitle(if (test) "SleepBird · 测试" else "SleepBird · 晚安冲刺！🐦")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "我睡了 💤", done).build())
            .build()
        return try { manager.notify(night, if (test) 200 else 100, notification); true }
            catch (_: SecurityException) { false }
    }

    fun exactSettingsIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 31)
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
