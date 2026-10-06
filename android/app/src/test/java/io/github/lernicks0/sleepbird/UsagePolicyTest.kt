package io.github.lernicks0.sleepbird

import org.junit.Assert.*
import org.junit.Test

class UsagePolicyTest {
    private val session = MonitorSession("2026-10-01", 100_000, 100_000, 500_000)
    private val ignored = setOf("sleepbird", "launcher", "systemui", "keyboard")
    private fun violation(events: List<AppActivity>, now: Long = 140_000, on: Boolean = true, locked: Boolean = false) =
        UsagePolicy.isViolation(events, session, now, on, locked, ignored)

    @Test fun givesThirtySecondsToLockScreen() {
        assertFalse(violation(listOf(AppActivity("other", 110_000, true)), 129_999))
        assertTrue(violation(listOf(AppActivity("other", 110_000, true)), 130_000))
    }
    @Test fun screenOffOrLockedNeverRevokes() {
        val events = listOf(AppActivity("other", 135_000, true))
        assertFalse(violation(events, on = false)); assertFalse(violation(events, locked = true))
    }
    @Test fun excludesOwnAppLauncherSystemAndKeyboard() {
        ignored.forEach { assertFalse(violation(listOf(AppActivity(it, 135_000, true)))) }
    }
    @Test fun closedBeforeGraceDoesNotCount() {
        assertFalse(violation(listOf(AppActivity("other", 110_000, true), AppActivity("other", 120_000, false))))
    }
    @Test fun launchAfterGraceCountsEvenWhenAlreadyClosed() {
        assertTrue(violation(listOf(AppActivity("other", 130_000, true), AppActivity("other", 132_000, false))))
    }
    @Test fun notificationCheckInCatchesAppStillForegroundAfterGrace() {
        assertTrue(violation(listOf(AppActivity("other", 99_000, true))))
    }
    @Test fun emptyAndBackgroundOnlyEventsDoNotCount() {
        assertFalse(violation(emptyList()))
        assertFalse(violation(listOf(AppActivity("music", 135_000, false))))
    }
    @Test fun stopsAtEndAndRejectsClockRollbackAndFutureEvents() {
        assertFalse(violation(listOf(AppActivity("other", 135_000, true)), 500_000))
        assertFalse(violation(listOf(AppActivity("other", 135_000, true)), 90_000))
        assertFalse(violation(listOf(AppActivity("other", 150_000, true))))
    }
    @Test fun revokingTodayRollsStreakBackToYesterday() {
        val records = listOf(SleepRecord("2026-09-30", 1), SleepRecord("2026-10-01", 2))
        val date = java.time.LocalDate.parse("2026-10-01")
        assertEquals(2, Streak.current(records, date))
        assertEquals(1, Streak.current(records.filter { it.nightId != session.nightId }, date))
    }
}
