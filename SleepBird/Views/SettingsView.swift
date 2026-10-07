import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var tracker: SleepTracker
    @EnvironmentObject private var notifications: NotificationManager
    @State private var draft = AppSettings()
    @State private var applying = false
    @State private var saved = false

    private func timeBinding(start: Bool) -> Binding<Date> {
        Binding {
            let minute = start ? draft.startMinute : draft.endMinute
            return Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60,
                                         second: 0, of: Date())!
        } set: { date in
            let components = Calendar.current.dateComponents([.hour, .minute], from: date)
            let minute = components.hour! * 60 + components.minute!
            if start { draft.startMinute = minute } else { draft.endMinute = minute }
            saved = false
        }
    }

    var body: some View {
        Form {
            Section("提醒窗口") {
                DatePicker("开始提醒时间", selection: timeBinding(start: true), displayedComponents: .hourAndMinute)
                DatePicker("最晚提醒时间", selection: timeBinding(start: false), displayedComponents: .hourAndMinute)
                Text("结束时间早于开始时间时，结束点在次日。相同时间不允许；整个窗口至少 30 分钟。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("催睡风格") {
                Picker("提醒强度", selection: $draft.intensity) {
                    ForEach(ReminderIntensity.allCases) { Text($0.title).tag($0) }
                }
                Picker("提醒频率", selection: $draft.frequency) {
                    ForEach(ReminderFrequency.allCases) { Text($0.title).tag($0) }
                }
                Text("低：约 65–90 分钟；正常：32–55 分钟；高：20–35 分钟。强度调整文案等级，频率调整间隔。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("声音与触感") {
                Toggle("通知声音", isOn: $draft.sound)
                Toggle("App 内按钮触感", isOn: $draft.haptics)
                Text("iOS 不提供本地通知的独立震动开关。通知震动由设备与系统通知设置决定；iPad 通常没有触感马达。此开关仅控制 App 内按钮反馈。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section {
                Button {
                    applying = true
                    tracker.updateSettings(draft)
                    Task {
                        await notifications.reconcile()
                        applying = false
                        saved = true
                    }
                } label: {
                    HStack { Text(saved && draft == tracker.settings ? "设置已保存 ✓" : "保存并更新提醒"); Spacer(); if applying { ProgressView() } }
                }
                .disabled(applying || duration < 30 || draft.startMinute == draft.endMinute || tracker.storageError != nil)
                if duration < 30 || draft.startMinute == draft.endMinute {
                    Text("请选择至少 30 分钟的有效窗口。") .foregroundStyle(.red)
                }
                Text("保存会重新生成未来计划。已完成的睡眠夜仍然保持完成。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("系统通知") {
                LabeledContent("授权状态", value: notifications.authorizationText)
                Button("请求通知权限") { Task { await notifications.requestPermission() } }
                    .disabled(notifications.authorization != .notDetermined)
                Button("打开系统设置") { notifications.openSystemSettings() }
            }
            Section("开发者") {
                NavigationLink("Developer / Debug Mode") { DebugView() }
            }
            Section("关于") {
                Text("SleepBird 1.1.0 · 本地 MVP").font(.headline)
                Text("无需服务器、账号或付费 API。打卡用于记录习惯，不判断你是否真正入睡。")
                Text("未来提醒按系统队列容量预排。长期不打开 App 时，预排耗尽后停止提醒；打开 App 或点击通知操作会补充计划。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("设置")
        .onAppear { draft = tracker.settings }
    }

    private var duration: Int { (draft.endMinute - draft.startMinute + 1440) % 1440 }
}
