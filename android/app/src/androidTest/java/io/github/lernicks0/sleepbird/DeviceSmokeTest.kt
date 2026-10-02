package io.github.lernicks0.sleepbird

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("cmd appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        instrumentation.runOnMainSync {
            NotificationEngine.cancelAll(context, SleepStore(context))
            SleepStore(context).reset()
            NotificationEngine.reconcile(context)
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { NotificationEngine.cancelAll(context, SleepStore(context)); SleepStore(context).reset() }
    }

    @Test fun appLaunchesAndKeepsSamePersistedPlan() {
        val before = SleepStore(context).plans
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
            assertEquals(before, SleepStore(context).plans)
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (screenshot != null) {
                val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                File(directory, "home.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        }
    }

    @Test fun notificationActionCompletesWithoutOpeningAppAndCancelsTonight() {
        val now = System.currentTimeMillis()
        val night = SleepNight.current(now, SleepStore(context).settings)
        instrumentation.runOnMainSync {
            val store = SleepStore(context)
            store.tests += TestReminder("test10", night.id, now - 10)
            store.tests += TestReminder("test30", night.id, now + 30_000)
            assertTrue(store.save())
            NotificationEngine.receive(context, Intent(context, ReminderReceiver::class.java).putExtra("testId", "test10"))
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        await { manager.activeNotifications.any { it.tag == night.id } }
        val notification = manager.activeNotifications.first { it.tag == night.id }.notification
        assertEquals("我睡了 💤", notification.actions[0].title.toString())
        notification.actions[0].actionIntent.send()
        await { SleepStore(context).records.any { it.nightId == night.id } }
        await { manager.activeNotifications.none { it.tag == night.id } }
        val saved = SleepStore(context)
        assertTrue(saved.tests.none { it.nightId == night.id })
        assertNotEquals(night.id, saved.nextAlarm?.nightId)
        assertEquals(1, Streak.current(saved.records, night.date))
        instrumentation.runOnMainSync { assertFalse(NotificationEngine.complete(context, night.id)) }
        assertEquals(1, SleepStore(context).records.size)
    }

    @Test fun staleActionDoesNotCompleteToday() {
        val night = SleepNight.current(System.currentTimeMillis(), SleepStore(context).settings)
        instrumentation.runOnMainSync { assertFalse(NotificationEngine.complete(context, night.date.minusDays(1).toString())) }
        assertTrue(SleepStore(context).records.isEmpty())
        assertEquals(night.id, SleepNight.current(System.currentTimeMillis(), SleepStore(context).settings).id)
    }

    @Test fun rebootReschedulingPreservesPlanAndRecords() {
        val before = SleepStore(context)
        instrumentation.runOnMainSync { RestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED)) }
        assertEquals(before.plans, SleepStore(context).plans)
        assertNotNull(SleepStore(context).nextAlarm)
    }

    @Test fun completedNightSuppressesDelayedOrdinaryAlarm() {
        val now = System.currentTimeMillis()
        val night = SleepNight.current(now, SleepStore(context).settings)
        instrumentation.runOnMainSync {
            NotificationEngine.complete(context, night.id)
            val store = SleepStore(context)
            store.nextAlarm = PlannedReminder("late", night.id, now - 1000, 1, "迟到的提醒")
            store.save()
            NotificationEngine.receive(context, Intent().putExtra("reminderId", "late"))
        }
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        assertEquals(1, SleepStore(context).records.size)
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Device state did not settle within 5 seconds", predicate())
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
