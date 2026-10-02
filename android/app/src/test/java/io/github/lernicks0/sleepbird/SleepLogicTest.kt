package io.github.lernicks0.sleepbird

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class SleepLogicTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val settings = AppSettings()
    private fun at(value: String, z: ZoneId = zone) = LocalDateTime.parse(value).atZone(z).toInstant().toEpochMilli()
    private val night get() = SleepNight.make(LocalDate.parse("2026-10-01"), settings, zone)

    @Test fun midnightBelongsToPreviousNight() {
        assertEquals("2026-10-01", SleepNight.current(at("2026-10-02T00:30"), settings, zone).id)
        assertEquals("2026-10-01", SleepNight.current(at("2026-10-02T03:59:59"), settings, zone).id)
    }
    @Test fun exactEndStartsNewLogicalDate() {
        assertEquals("2026-10-02", SleepNight.current(at("2026-10-02T04:00"), settings, zone).id)
        assertFalse(night.contains(at("2026-10-02T04:00")))
    }
    @Test fun daytimeBelongsToUpcomingNight() {
        assertEquals("2026-10-01", SleepNight.current(at("2026-10-01T20:29"), settings, zone).id)
        assertFalse(night.contains(at("2026-10-01T20:29")))
        assertTrue(night.contains(at("2026-10-01T20:30")))
    }
    @Test fun sameDayWindowNeverAssignsYesterday() {
        val s = settings.copy(startMinute = 540, endMinute = 1020)
        assertEquals("2026-10-02", SleepNight.current(at("2026-10-02T01:00"), s, zone).id)
        val n = SleepNight.make(LocalDate.parse("2026-10-02"), s, zone)
        assertEquals(8 * 60 * 60_000L, n.end - n.start)
    }
    @Test fun daylightSavingUsesCalendarDays() {
        val z = ZoneId.of("America/New_York")
        val spring = SleepNight.make(LocalDate.parse("2026-03-07"), settings, z)
        val fall = SleepNight.make(LocalDate.parse("2026-10-31"), settings, z)
        assertEquals(390 * 60_000L, spring.end - spring.start)
        assertEquals(510 * 60_000L, fall.end - fall.start)
        assertEquals("2026-03-07", SleepNight.current(at("2026-03-08T03:30", z), settings, z).id)
    }
    @Test fun fiveLevelBoundaries() {
        listOf("2026-10-01T20:30" to 1, "2026-10-01T21:30" to 2, "2026-10-01T22:30" to 3,
            "2026-10-01T23:30" to 4, "2026-10-02T01:00" to 5).forEach { (date, level) ->
            assertEquals(level, ReminderScheduler.levelAt(at(date), night, settings, zone))
        }
    }
    @Test fun intensityClampsAtOneAndFive() {
        assertEquals(1, ReminderScheduler.levelAt(night.start, night, settings.copy(intensity = -1), zone))
        assertEquals(5, ReminderScheduler.levelAt(night.end - 1, night, settings.copy(intensity = 1), zone))
    }
    @Test fun customWindowLevelsOnlyIncrease() {
        val s = settings.copy(startMinute = 540, endMinute = 1020)
        val n = SleepNight.make(LocalDate.parse("2026-10-01"), s, zone)
        val levels = (0..479).map { ReminderScheduler.levelAt(n.start + it * 60_000L, n, s, zone) }
        assertEquals(1, levels.first()); assertEquals(5, levels.last())
        assertTrue(levels.zipWithNext().all { it.first <= it.second })
    }
    @Test fun plansStayInsideWindowAndRespectGaps() {
        for (frequency in 0..2) for (seed in 0..49) {
            val s = settings.copy(frequency = frequency)
            val plan = ReminderScheduler.generate(night, s, random = Random(seed), zone = zone)
            assertTrue(plan.reminders.isNotEmpty())
            assertTrue(plan.reminders.all { night.contains(it.time) })
            assertTrue(plan.reminders.zipWithNext().all { (a, b) -> (b.time - a.time) / 60_000L in s.gap })
            assertTrue(plan.reminders.zipWithNext().all { (a, b) -> a.message != b.message })
            assertEquals(plan.reminders.size, plan.reminders.map { it.id }.toSet().size)
        }
    }
    @Test fun nextNightFirstOffsetIsAlwaysDifferent() {
        for (previous in 5..18) for (seed in 0..19) {
            val plan = ReminderScheduler.generate(night, settings, previous, Random(seed), zone)
            assertNotEquals(previous.toLong(), (plan.reminders.first().time - night.start) / 60_000L)
        }
    }
    @Test fun settingsRejectShortAndEqualWindows() {
        assertFalse(settings.copy(endMinute = settings.startMinute).isValid)
        assertFalse(settings.copy(startMinute = 100, endMinute = 129).isValid)
        assertTrue(settings.copy(startMinute = 100, endMinute = 130).isValid)
        assertFalse(settings.copy(startMinute = -1).isValid)
    }
    @Test fun everyLevelHasFifteenUniqueMessagesAndExcludesPrevious() {
        NotificationMessageProvider.messages.forEachIndexed { index, messages ->
            assertEquals(15, messages.toSet().size)
            messages.forEach { assertNotEquals(it, NotificationMessageProvider.pick(index + 1, it)) }
        }
    }
    @Test fun streakPreservesPreviousWhileTonightStillOpen() {
        val records = listOf(SleepRecord("2026-09-29", 1), SleepRecord("2026-09-30", 2))
        assertEquals(2, Streak.current(records, LocalDate.parse("2026-10-01")))
        assertEquals(0, Streak.current(records, LocalDate.parse("2026-10-02")))
        assertEquals(2, Streak.longest(records))
    }
    @Test fun streakWorksAcrossYearAndLeapDay() {
        val records = listOf("2023-12-31", "2024-01-01", "2024-02-28", "2024-02-29", "2024-03-01").map { SleepRecord(it, 1) }
        assertEquals(2, Streak.current(records, LocalDate.parse("2024-01-01")))
        assertEquals(3, Streak.longest(records))
    }
    @Test fun completionIsIdempotentAndRejectsOldNotification() {
        val now = at("2026-10-02T00:30")
        assertTrue(Streak.canComplete("2026-10-01", now, settings, emptyList(), zone))
        assertFalse(Streak.canComplete("2026-10-01", now, settings, listOf(SleepRecord("2026-10-01", now)), zone))
        assertFalse(Streak.canComplete("2026-09-30", now, settings, emptyList(), zone))
        assertFalse(Streak.canComplete("2026-10-01", at("2026-10-02T04:00"), settings, emptyList(), zone))
    }
}
