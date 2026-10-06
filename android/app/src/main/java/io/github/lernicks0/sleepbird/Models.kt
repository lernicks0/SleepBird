package io.github.lernicks0.sleepbird

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

data class AppSettings(
    val startMinute: Int = 1230,
    val endMinute: Int = 240,
    val intensity: Int = 0,
    val frequency: Int = 1,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val monitorUsage: Boolean = false
) {
    val durationMinutes: Int get() = (endMinute - startMinute + 1440) % 1440
    val isValid: Boolean get() = startMinute in 0..1439 && endMinute in 0..1439 &&
        durationMinutes >= 30 && intensity in -1..1 && frequency in 0..2
    val gap: IntRange get() = when (frequency) { 0 -> 65..90; 2 -> 20..35; else -> 32..55 }
}

data class SleepNight(val date: LocalDate, val start: Long, val end: Long) {
    val id: String get() = date.toString()
    fun contains(time: Long): Boolean = time >= start && time < end

    companion object {
        fun make(day: LocalDate, settings: AppSettings, zone: ZoneId = ZoneId.systemDefault()): SleepNight {
            val endDay = if (settings.endMinute <= settings.startMinute) day.plusDays(1) else day
            val start = day.atTime(settings.startMinute / 60, settings.startMinute % 60).atZone(zone).toInstant().toEpochMilli()
            val end = endDay.atTime(settings.endMinute / 60, settings.endMinute % 60).atZone(zone).toInstant().toEpochMilli()
            return SleepNight(day, start, end)
        }

        fun current(now: Long, settings: AppSettings, zone: ZoneId = ZoneId.systemDefault()): SleepNight {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            if (settings.endMinute <= settings.startMinute) {
                val previous = make(today.minusDays(1), settings, zone)
                if (now < previous.end) return previous
            }
            return make(today, settings, zone)
        }
    }
}

data class SleepRecord(val nightId: String, val completedAt: Long)
data class PlannedReminder(val id: String, val nightId: String, val time: Long, val level: Int, val message: String)
data class NightPlan(val nightId: String, val reminders: List<PlannedReminder>)
data class TestReminder(val id: String, val nightId: String, val time: Long)
data class MonitorSession(val nightId: String, val completedAt: Long, val armedAt: Long, val endsAt: Long)
data class AppActivity(val packageName: String, val time: Long, val resumed: Boolean)

/** Screen/lock state and excluded packages are provided by the Android adapter. */
object UsagePolicy {
    const val GRACE_MS = 30_000L
    fun isViolation(events: List<AppActivity>, session: MonitorSession, now: Long,
                    interactive: Boolean, locked: Boolean, ignored: Set<String>): Boolean {
        if (!interactive || locked || now < session.armedAt + GRACE_MS || now >= session.endsAt || now < session.armedAt) return false
        val gate = session.armedAt + GRACE_MS
        val ordered = events.filter { it.time <= now }.sortedBy { it.time }
        // A real foreground launch after the grace period counts even if already closed.
        if (ordered.any { it.resumed && it.time >= gate && it.packageName !in ignored }) return true
        // Also catch staying in another app after a notification-action check-in.
        val last = ordered.lastOrNull() ?: return false
        return last.resumed && last.packageName !in ignored
    }
}

object ReminderScheduler {
    fun levelAt(time: Long, night: SleepNight, settings: AppSettings, zone: ZoneId = ZoneId.systemDefault()): Int {
        val minute = if (settings.startMinute == 1230 && settings.endMinute == 240) {
            val local = Instant.ofEpochMilli(time).atZone(zone)
            val wall = local.hour * 60 + local.minute
            if (wall < 720) wall + 1440 else wall
        } else {
            1230 + ((time - night.start).coerceAtLeast(0).toDouble() / (night.end - night.start).coerceAtLeast(1) * 450).toInt()
        }
        val base = when { minute < 1290 -> 1; minute < 1350 -> 2; minute < 1410 -> 3; minute < 1500 -> 4; else -> 5 }
        return (base + settings.intensity).coerceIn(1, 5)
    }

    fun generate(night: SleepNight, settings: AppSettings, previousOffset: Int? = null,
                 random: Random = Random.Default, zone: ZoneId = ZoneId.systemDefault()): NightPlan {
        val offsets = (5..18).filter { it != previousOffset }
        var time = night.start + offsets.random(random) * 60_000L
        var previous: String? = null
        val result = mutableListOf<PlannedReminder>()
        while (time < night.end) {
            val level = levelAt(time, night, settings, zone)
            val message = NotificationMessageProvider.pick(level, previous, random)
            result += PlannedReminder("${night.id}:$time", night.id, time, level, message)
            previous = message
            time += random.nextInt(settings.gap.first, settings.gap.last + 1) * 60_000L
        }
        return NightPlan(night.id, result)
    }
}

object Streak {
    fun current(records: List<SleepRecord>, nightDate: LocalDate): Int {
        val dates = records.map { it.nightId }.toSet()
        var cursor = if (nightDate.toString() in dates) nightDate else nightDate.minusDays(1)
        var count = 0
        while (cursor.toString() in dates) { count++; cursor = cursor.minusDays(1) }
        return count
    }
    fun longest(records: List<SleepRecord>): Int {
        var previous: LocalDate? = null
        var run = 0
        var best = 0
        records.map { LocalDate.parse(it.nightId) }.distinct().sorted().forEach { day ->
            run = if (previous?.plusDays(1) == day) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }
    fun canComplete(target: String, now: Long, settings: AppSettings, records: List<SleepRecord>,
                    zone: ZoneId = ZoneId.systemDefault()): Boolean =
        target == SleepNight.current(now, settings, zone).id && records.none { it.nightId == target }
}
