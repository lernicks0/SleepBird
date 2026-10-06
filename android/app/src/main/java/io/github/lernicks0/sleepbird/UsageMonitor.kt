package io.github.lernicks0.sleepbird

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.util.concurrent.Executors

object UsageMonitor {
    const val NOTIFICATION_ID = 1001
    const val STOP = "sleepbird.stopUsageMonitor"
    @Volatile var running = false
        internal set

    fun authorized(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return try {
            val mode = if (Build.VERSION.SDK_INT >= 29)
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: SecurityException) { false }
    }

    fun ignoredPackages(context: Context): Set<String> {
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        return setOfNotNull(context.packageName, "android", "com.android.systemui", home, ime)
    }

    fun readEvents(context: Context, session: MonitorSession, now: Long): List<AppActivity> {
        if (!authorized(context)) return emptyList()
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val events = manager.queryEvents((session.armedAt - 120_000L).coerceAtLeast(0), now + 1) ?: return emptyList()
        val event = UsageEvents.Event()
        val result = mutableListOf<AppActivity>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // 1/2 are ACTIVITY_RESUMED/PAUSED (MOVE_TO_FOREGROUND/BACKGROUND on API 26–28).
            if (event.eventType == 1 || event.eventType == 2) {
                event.packageName?.let { result += AppActivity(it, event.timeStamp, event.eventType == 1) }
            }
        }
        return result
    }

    fun screenInteractive(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isInteractive
    fun screenLocked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Called on the main thread, like all other persistent state mutations. */
    fun sync(context: Context, allowStart: Boolean = false, now: Long = System.currentTimeMillis()) {
        val store = SleepStore(context)
        val night = SleepNight.current(now, store.settings)
        val record = store.records.firstOrNull { it.nightId == night.id }
        if (store.error != null || !store.settings.monitorUsage || !authorized(context) ||
            !NotificationEngine.allowed(context) || record == null || now >= night.end) {
            if (store.monitorSession != null) { store.monitorSession = null; store.save() }
            stop(context)
            return
        }
        val existing = store.monitorSession
        if (existing == null || existing.nightId != night.id || existing.completedAt != record.completedAt || existing.endsAt != night.end) {
            store.monitorSession = MonitorSession(night.id, record.completedAt, now, night.end)
            store.monitorIssue = null
            if (!store.save()) { stop(context); return }
        }
        if (allowStart) {
            try {
                context.startForegroundService(Intent(context, UsageMonitorService::class.java))
            } catch (_: IllegalStateException) { reportUnavailable(context) }
            catch (_: SecurityException) { reportUnavailable(context) }
        }
    }

    fun reportUnavailable(context: Context) {
        val store = SleepStore(context)
        store.monitorIssue = "使用追踪暂未运行；请打开 SleepBird 恢复，并检查系统后台限制。"
        store.save()
    }

    fun stop(context: Context) {
        cancelEnd(context)
        context.stopService(Intent(context, UsageMonitorService::class.java))
    }

    private fun endIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("sleepbird://monitor/end")).putExtra("monitorEnd", true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun armEnd(context: Context, end: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = endIntent(context)
        if (NotificationEngine.exactAllowed(context)) {
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending); return }
            catch (_: SecurityException) { /* A revoked exact alarm permission uses the inexact fallback. */ }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
    }
    fun cancelEnd(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(endIntent(context)) }

    fun applyEvents(context: Context, expected: MonitorSession, events: List<AppActivity>, now: Long = System.currentTimeMillis()): Boolean {
        val store = SleepStore(context)
        if (!store.settings.monitorUsage || !authorized(context) || store.monitorSession != expected) return false
        if (!UsagePolicy.isViolation(events, expected, now, screenInteractive(context), screenLocked(context), ignoredPackages(context))) return false
        if (!store.revokeSleep(expected, now)) return false
        stop(context)
        store.lastDelivered = now
        NotificationEngine.reconcile(context, store, now)
        NotificationEngine.postRelapse(context, store.settings, expected.nightId)
        return true
    }
}

/** Visible and user-stoppable. No wake lock; queries only while screen is on/unlocked. */
class UsageMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var querying = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!querying) { handler.removeCallbacks(tick); handler.post(tick) }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed || querying) return
            val store = SleepStore(this@UsageMonitorService)
            val session = store.monitorSession
            val now = System.currentTimeMillis()
            if (store.error != null || !store.settings.monitorUsage || !UsageMonitor.authorized(this@UsageMonitorService) ||
                !NotificationEngine.allowed(this@UsageMonitorService) || session == null || now >= session.endsAt ||
                now < session.armedAt || SleepNight.current(now, store.settings).id != session.nightId ||
                store.records.none { it.nightId == session.nightId && it.completedAt == session.completedAt }) {
                UsageMonitor.sync(this@UsageMonitorService)
                stopSelf()
                return
            }
            if (!UsageMonitor.screenInteractive(this@UsageMonitorService) || UsageMonitor.screenLocked(this@UsageMonitorService)) {
                handler.postDelayed(this, 30_000)
                return
            }
            querying = true
            worker.execute {
                val events = try { UsageMonitor.readEvents(this@UsageMonitorService, session, now) }
                    catch (_: RuntimeException) { emptyList() }
                handler.post {
                    querying = false
                    if (!destroyed) {
                        if (!UsageMonitor.applyEvents(this@UsageMonitorService, session, events)) {
                            handler.removeCallbacks(this)
                            handler.postDelayed(this, 5_000)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == UsageMonitor.STOP) {
            val store = SleepStore(this)
            store.settings = store.settings.copy(monitorUsage = false)
            store.monitorSession = null; store.monitorIssue = null; store.save()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val store = SleepStore(this)
        val session = store.monitorSession
        if (store.error != null || session == null || !store.settings.monitorUsage || !UsageMonitor.authorized(this) ||
            !NotificationEngine.allowed(this) || System.currentTimeMillis() >= session.endsAt) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val notification = trackingNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(UsageMonitor.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(UsageMonitor.NOTIFICATION_ID, notification)
        } catch (_: RuntimeException) {
            UsageMonitor.reportUnavailable(this); stopSelf(); return START_NOT_STICKY
        }
        UsageMonitor.running = true
        store.monitorIssue = null; store.save()
        UsageMonitor.armEnd(this, session.endsAt)
        handler.removeCallbacks(tick); handler.post(tick)
        return START_STICKY
    }
    private fun trackingNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel("sleepbird.monitor", "入睡后使用追踪", NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null); channel.enableVibration(false)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 77, Intent(this, UsageMonitorService::class.java).setAction(UsageMonitor.STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel.id).setSmallIcon(R.drawable.ic_bird)
            .setContentTitle("SleepBird · 入睡后使用追踪开启")
            .setContentText("30 秒后若前台使用其他 App，将撤销今晚打卡。仅在本机判断。")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "关闭追踪", stop).build()).build()
    }
    override fun onDestroy() {
        destroyed = true
        UsageMonitor.running = false
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        unregisterReceiver(screenReceiver)
        UsageMonitor.cancelEnd(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
