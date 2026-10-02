package io.github.lernicks0.sleepbird

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** One private JSON snapshot; main-thread activity/receivers serialize mutations. */
class SleepStore(context: Context) {
    private val prefs = context.getSharedPreferences("sleepbird", Context.MODE_PRIVATE)
    var settings = AppSettings()
    var records = mutableListOf<SleepRecord>()
    var plans = mutableListOf<NightPlan>()
    var tests = mutableListOf<TestReminder>()
    var delivered = mutableMapOf<String, Int>()
    var longest = 0
    var lastDelivered = 0L
    var lastMessage: String? = null
    var nextAlarm: PlannedReminder? = null
    var timeZone = ZoneId.systemDefault().id
    var error: String? = null
        private set

    init {
        try {
            prefs.getString("state.v1", null)?.let { raw ->
                val state = JSONObject(raw)
                val s = state.getJSONObject("settings")
                val loaded = AppSettings(s.getInt("start"), s.getInt("end"), s.getInt("intensity"),
                    s.getInt("frequency"), s.getBoolean("sound"), s.getBoolean("vibration"))
                require(loaded.isValid)
                val r = state.getJSONArray("records").objects().map {
                    val id = it.getString("night")
                    LocalDate.parse(id)
                    SleepRecord(id, it.getLong("at"))
                }
                val p = state.getJSONArray("plans").objects().map { plan ->
                    NightPlan(plan.getString("night"), plan.getJSONArray("items").objects().map(::readReminder))
                }
                settings = loaded
                records = r.distinctBy { it.nightId }.toMutableList()
                plans = p.toMutableList()
                tests = state.optJSONArray("tests")?.objects()?.map {
                    TestReminder(it.getString("id"), it.getString("night"), it.getLong("time"))
                }?.toMutableList() ?: mutableListOf()
                val counts = state.optJSONObject("delivered") ?: JSONObject()
                counts.keys().forEach { delivered[it] = counts.getInt(it) }
                longest = maxOf(state.optInt("longest"), Streak.longest(records))
                timeZone = state.optString("zone", timeZone)
                lastDelivered = state.optLong("lastDelivered")
                lastMessage = if (state.isNull("lastMessage")) null else state.optString("lastMessage")
                nextAlarm = state.optJSONObject("next")?.let(::readReminder)
            }
        } catch (_: Exception) {
            error = "本机数据无法读取，原始数据已保留。请在调试页重置本机数据。"
        }
    }

    fun complete(target: String, now: Long): Boolean {
        if (error != null || !Streak.canComplete(target, now, settings, records)) return false
        records += SleepRecord(target, now)
        longest = maxOf(longest, Streak.longest(records))
        return save()
    }

    fun refresh(now: Long) {
        if (error != null) return
        val zone = ZoneId.systemDefault().id
        if (timeZone != zone) { plans.clear(); timeZone = zone; lastDelivered = 0 }
        val night = SleepNight.current(now, settings)
        plans.removeAll { LocalDate.parse(it.nightId) < night.date.minusDays(2) }
        delivered.keys.removeAll { LocalDate.parse(it) < night.date.minusDays(2) }
        tests.removeAll { it.time < now - 60 * 60_000L }
        for (offset in 0L..7L) {
            val candidate = SleepNight.make(night.date.plusDays(offset), settings)
            if (plans.none { it.nightId == candidate.id }) {
                val previousNight = SleepNight.make(candidate.date.minusDays(1), settings)
                val previous = plans.firstOrNull { it.nightId == previousNight.id }?.reminders?.firstOrNull()
                val previousOffset = previous?.let { ((it.time - previousNight.start) / 60_000L).toInt() }
                plans += ReminderScheduler.generate(candidate, settings, previousOffset)
            }
        }
    }

    fun save(): Boolean {
        if (error != null) return false
        val s = settings
        val value = JSONObject().put("settings", JSONObject().put("start", s.startMinute).put("end", s.endMinute)
            .put("intensity", s.intensity).put("frequency", s.frequency).put("sound", s.sound).put("vibration", s.vibration))
            .put("records", JSONArray(records.map { JSONObject().put("night", it.nightId).put("at", it.completedAt) }))
            .put("plans", JSONArray(plans.map { JSONObject().put("night", it.nightId).put("items", JSONArray(it.reminders.map(::writeReminder))) }))
            .put("tests", JSONArray(tests.map { JSONObject().put("id", it.id).put("night", it.nightId).put("time", it.time) }))
            .put("delivered", JSONObject(delivered.toMap())).put("longest", longest).put("zone", timeZone)
            .put("lastDelivered", lastDelivered).put("lastMessage", lastMessage ?: JSONObject.NULL)
            .put("next", nextAlarm?.let(::writeReminder) ?: JSONObject.NULL)
        if (!prefs.edit().putString("state.v1", value.toString()).commit()) {
            error = "本机数据保存失败。请检查设备存储空间后重试。"
            return false
        }
        return true
    }

    fun reset() { prefs.edit().clear().commit() }

    private fun readReminder(o: JSONObject) = PlannedReminder(o.getString("id"), o.getString("night"),
        o.getLong("time"), o.getInt("level"), o.getString("message"))
    private fun writeReminder(r: PlannedReminder) = JSONObject().put("id", r.id).put("night", r.nightId)
        .put("time", r.time).put("level", r.level).put("message", r.message)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
