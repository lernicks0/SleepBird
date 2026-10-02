package io.github.lernicks0.sleepbird

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Small native Android UI, with no server, analytics, ad SDK or network permission. */
class MainActivity : Activity() {
    private var page = "home"
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (page == "home") { NotificationEngine.reconcile(this@MainActivity); render() }
            handler.postDelayed(this, 30_000)
        }
    }
    private val dark: Boolean get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink: Int get() = color(if (dark) "#F5F2FF" else "#29243E")
    private val muted: Int get() = color(if (dark) "#B9B3CE" else "#706A84")
    private val accent: Int get() = color(if (dark) "#BEAFFF" else "#6B52CF")
    private val surface: Int get() = color(if (dark) "#26223A" else "#FFFFFF")
    private val backdrop: Int get() = color(if (dark) "#171423" else "#F6F3FC")
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("page") ?: "home"
    }
    override fun onResume() {
        super.onResume()
        NotificationEngine.reconcile(this)
        render()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 30_000)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }

    private fun render() {
        val store = SleepStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backdrop) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val frame = FrameLayout(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
        }
        val width = minOf(resources.displayMetrics.widthPixels, dp(640))
        frame.addView(column, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        scroll.addView(frame)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (store.error != null) {
            text("本机数据需要检查", 26f, bold = true)
            text(store.error!!, 16f, muted)
            button("重置本机数据", danger = true) { confirm("清除所有本机数据？", "打卡记录和设置都会删除。") {
                NotificationEngine.cancelAll(this, store); store.reset(); NotificationEngine.reconcile(this); render()
            } }
        } else when (page) {
            "history" -> history(store)
            "settings" -> settings(store)
            "debug" -> debug(store)
            else -> home(store)
        }
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(surface)
        }
        listOf("home" to "今晚", "history" to "记录", "settings" to "设置").forEach { (key, label) ->
            val active = page == key || (key == "settings" && page == "debug")
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 15f; setTextColor(if (active) accent else muted)
                background = shape(if (active) color(if (dark) "#382E58" else "#EEE8FF") else surface, 18)
                minHeight = dp(48)
                setOnClickListener { page = key; render() }
            }
            nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.addView(nav)
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun home(store: SleepStore) {
        val now = System.currentTimeMillis()
        val night = SleepNight.current(now, store.settings)
        val done = store.records.any { it.nightId == night.id }
        text("SleepBird", 28f, bold = true)
        text("把今天的精彩，留一点给梦里。", 14f, muted)
        val bird = ImageView(this).apply {
            setImageResource(R.drawable.ic_bird)
            contentDescription = "准备睡觉的小鸟"
            background = shape(color("#28213F"), 44)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        column.addView(bird, LinearLayout.LayoutParams(dp(130), dp(130)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24) })
        text("🔥 ${Streak.current(store.records, night.date)}", 50f, bold = true, center = true)
        text("连续早睡", 15f, muted, center = true)
        space(16)
        card {
            addText(this, "今晚状态", 13f, muted)
            addText(this, if (done) "已完成，晚安 💤" else "还没睡 👀", 24f, ink, true)
            addText(this, "${night.id} 的睡眠夜 · ${clockMinute(store.settings.startMinute)} — ${clockMinute(store.settings.endMinute)}", 13f, muted)
        }
        button(if (done) "今晚已打卡 ✓" else "我睡了 💤", primary = true, enabled = !done) { view ->
            if (store.settings.vibration) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val ok = NotificationEngine.complete(this, night.id)
            if (ok) toast("晚安，明天见 🌙") else toast("状态已变化，请查看今晚记录")
            render()
        }
        card {
            addText(this, "下一次提醒", 13f, muted)
            val next = store.nextAlarm?.takeIf { it.nightId == night.id }
            addText(this, when { done -> "今晚不再催你啦"; !NotificationEngine.allowed(this@MainActivity) -> "请先开启通知";
                next != null -> "大约 ${time(next.time)}"; else -> "今晚没有剩余提醒" }, 22f, ink, true)
            addText(this, "今晚已经提醒 ${store.delivered[night.id] ?: 0} 次", 14f, muted)
        }
        text("最长连续记录：${store.longest} 天", 14f, muted, center = true)
        if (!NotificationEngine.allowed(this)) {
            button("开启通知") { requestNotifications() }
        } else if (!NotificationEngine.exactAllowed(this)) {
            text("当前使用大约提醒；开启准时提醒后，计划时间会更准确。", 13f, muted)
            button("开启准时提醒") { openSettings(NotificationEngine.exactSettingsIntent(this)) }
        }
        text("睡眠记录由你主动打卡，不检测真实入睡。", 12f, muted, center = true)
    }

    private fun history(store: SleepStore) {
        text("晚安记录", 28f, bold = true)
        text("每一次收工，都值得记住。", 14f, muted)
        card { addText(this, "🔥 最长连续 ${store.longest} 天", 24f, ink, true); addText(this, "累计完成 ${store.records.size} 个睡眠夜", 15f, muted) }
        if (store.records.isEmpty()) {
            space(36); text("第一声晚安，还在等你 🌙", 22f, bold = true, center = true)
            text("回到今晚，点一下「我睡了」。", 15f, muted, center = true)
        }
        store.records.sortedByDescending { it.nightId }.forEach { record ->
            card {
                addText(this, "${record.nightId}  ✓", 19f, ink, true)
                addText(this, "打卡于 ${dateTime(record.completedAt)}", 14f, muted)
            }
        }
    }

    private fun settings(store: SleepStore) {
        text("设置", 28f, bold = true)
        text("让小鸟按照你的节奏值班。", 14f, muted)
        text("提醒窗口", 17f, bold = true)
        button("开始提醒时间     ${clockMinute(store.settings.startMinute)}") {
            timePicker(store, true)
        }
        button("最晚提醒时间     ${clockMinute(store.settings.endMinute)}") {
            timePicker(store, false)
        }
        text("结束早于开始时，自动跨到次日。", 13f, muted)
        button("提醒强度     ${listOf("温和", "正常", "疯狂")[store.settings.intensity + 1]}") {
            AlertDialog.Builder(this).setTitle("提醒强度").setSingleChoiceItems(arrayOf("温和", "正常", "疯狂"), store.settings.intensity + 1) { dialog, which ->
                updateSettings(store.settings.copy(intensity = which - 1)); dialog.dismiss()
            }.setNegativeButton("取消", null).show()
        }
        button("提醒频率     ${listOf("低", "正常", "高")[store.settings.frequency]}") {
            AlertDialog.Builder(this).setTitle("提醒频率").setSingleChoiceItems(arrayOf("低 · 间隔 65–90 分钟", "正常 · 间隔 32–55 分钟", "高 · 间隔 20–35 分钟"), store.settings.frequency) { dialog, which ->
                updateSettings(store.settings.copy(frequency = which)); dialog.dismiss()
            }.setNegativeButton("取消", null).show()
        }
        toggle("通知声音", store.settings.sound) { updateSettings(store.settings.copy(sound = it)) }
        toggle("通知振动", store.settings.vibration) { updateSettings(store.settings.copy(vibration = it)) }
        text("声音和振动还会受系统通知设置、静音和勿扰模式影响。", 13f, muted)
        space(12)
        text("系统权限", 17f, bold = true)
        button(if (NotificationEngine.allowed(this)) "通知已开启 · 管理通知" else "开启通知") {
            if (NotificationEngine.allowed(this)) openNotificationSettings() else requestNotifications()
        }
        button("准时提醒：${if (NotificationEngine.exactAllowed(this)) "已开启" else "未开启"}") {
            openSettings(NotificationEngine.exactSettingsIntent(this))
        }
        button("系统通知设置") { openNotificationSettings() }
        text("若厂商省电策略阻止提醒，可在系统中允许 SleepBird 后台运行。强行停止后，需要重新打开 App。", 13f, muted)
        space(12)
        button("Developer / Debug Mode") { page = "debug"; render() }
        card {
            addText(this, "SleepBird 1.0.0 · Android", 16f, ink, true)
            addText(this, "完全本地 · 无账号 · 无广告 · MIT 开源", 13f, muted)
            addText(this, "把 APK 发给朋友，一起早点收工。", 14f, muted)
        }
        button("查看开源项目") { openSettings(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lernicks0/SleepBird"))) }
    }

    private fun debug(store: SleepStore) {
        val night = SleepNight.current(System.currentTimeMillis(), store.settings)
        text("Developer / Debug Mode", 23f, bold = true)
        text("测试通知可在白天发送；已打卡时请先清除今晚完成状态。", 14f, muted)
        listOf(10, 30).forEach { seconds ->
            button("$seconds 秒后发送测试通知") {
                if (!NotificationEngine.allowed(this)) requestNotifications()
                else if (NotificationEngine.scheduleTest(this, seconds)) {
                    toast(if (NotificationEngine.exactAllowed(this)) "已安排，返回桌面等待通知" else "已安排；未开启准时提醒，系统可能延迟")
                    render()
                } else toast("请先清除今晚完成状态，或检查本机存储")
            }
        }
        if (!NotificationEngine.exactAllowed(this)) button("开启准时提醒以测试 10 / 30 秒通知") { openSettings(NotificationEngine.exactSettingsIntent(this)) }
        button("清除今晚完成状态", danger = true) {
            confirm("清除今晚打卡？", "今晚会恢复提醒。最长连续纪录保留。") {
                val fresh = SleepStore(this); fresh.records.removeAll { it.nightId == night.id }
                NotificationEngine.reconcile(this, fresh); render()
            }
        }
        button("清除 streak 和历史记录", danger = true) {
            confirm("清除所有打卡记录？", "当前连续、最长连续和全部历史记录都会清除。") {
                val fresh = SleepStore(this); fresh.records.clear(); fresh.longest = 0
                NotificationEngine.reconcile(this, fresh); render()
            }
        }
        button("重新生成今晚通知") {
            val fresh = SleepStore(this); fresh.plans.removeAll { it.nightId == night.id }
            NotificationEngine.reconcile(this, fresh); render(); toast("已生成新的今晚计划")
        }
        button("刷新计划与已登记通知") { NotificationEngine.reconcile(this); render() }
        text("今晚计划 · ${night.id}", 18f, bold = true)
        store.plans.firstOrNull { it.nightId == night.id }?.reminders?.forEach { item ->
            card {
                addText(this, "${dateTime(item.time)}  · LEVEL ${item.level}", 15f, ink, true)
                addText(this, item.message, 14f, muted)
            }
        }
        text("已登记的系统闹钟", 18f, bold = true)
        text("Android 不提供枚举全部 AlarmManager 队列的公开接口。以下是 App 保存的登记信息；普通提醒每次只登记下一条。", 13f, muted)
        store.nextAlarm?.let { text("催睡：${dateTime(it.time)} · ${it.nightId}", 14f) } ?: text("没有已登记的普通提醒", 14f, muted)
        store.tests.forEach { text("${it.id}：${dateTime(it.time)}", 14f) }
        text("准时提醒：${NotificationEngine.exactAllowed(this)}\n通知权限：${NotificationEngine.allowed(this)}", 13f, muted)
    }

    private fun updateSettings(value: AppSettings) {
        if (!value.isValid) { toast("提醒窗口至少需要 30 分钟，开始和结束不能相同"); return }
        val fresh = SleepStore(this)
        NotificationEngine.cancelAll(this, fresh)
        fresh.settings = value; fresh.plans.clear(); fresh.tests.clear()
        NotificationEngine.reconcile(this, fresh)
        render()
    }
    private fun timePicker(store: SleepStore, start: Boolean) {
        val minute = if (start) store.settings.startMinute else store.settings.endMinute
        TimePickerDialog(this, { _, hour, min ->
            updateSettings(if (start) store.settings.copy(startMinute = hour * 60 + min) else store.settings.copy(endMinute = hour * 60 + min))
        }, minute / 60, minute % 60, true).show()
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        } else openNotificationSettings()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        NotificationEngine.reconcile(this); render()
    }
    private fun openNotificationSettings() {
        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { toast("此设备没有对应的设置页面") }
    }
    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> action() }.show()
    }
    private fun toggle(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
        val control = Switch(this).apply {
            text = label; textSize = 16f; setTextColor(ink); isChecked = checked
            minHeight = dp(56); setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(surface, 18)
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        column.addView(control, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    private fun text(value: String, size: Float, tint: Int = ink, bold: Boolean = false, center: Boolean = false) {
        addText(column, value, size, tint, bold, center)
    }
    private fun addText(parent: LinearLayout, value: String, size: Float, tint: Int, bold: Boolean = false, center: Boolean = false) {
        val view = TextView(this).apply {
            text = value; textSize = size; setTextColor(tint); setLineSpacing(dp(3).toFloat(), 1f)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (center) gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
    }
    private fun card(content: LinearLayout.() -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(surface, 24)
            setPadding(dp(18), dp(14), dp(18), dp(14)); content()
        }
        column.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
    }
    private fun button(label: String, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, action: (View) -> Unit) {
        val view = Button(this).apply {
            text = label; isAllCaps = false; textSize = if (primary) 24f else 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.WHITE else if (danger) color(if (dark) "#FFB4B4" else "#AE3636") else accent)
            background = shape(if (primary) color(if (enabled) "#7257D9" else "#756C93") else surface, if (primary) 24 else 18)
            minHeight = dp(if (primary) 76 else 54); setPadding(dp(14), dp(12), dp(14), dp(12))
            isEnabled = enabled; setOnClickListener(action)
        }
        column.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(2) })
    }
    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat() }
    private fun space(height: Int) { column.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun color(value: String) = Color.parseColor(value)
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun time(millis: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    private fun dateTime(millis: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    private fun clockMinute(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
}
