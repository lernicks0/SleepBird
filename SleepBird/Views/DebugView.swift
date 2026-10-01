import SwiftUI

struct DebugView: View {
    @EnvironmentObject private var tracker: SleepTracker
    @EnvironmentObject private var notifications: NotificationManager
    @State private var confirmClearStreak = false
    @State private var confirmResetStorage = false
    @State private var busy = false

    private var tonight: [PlannedReminder] {
        tracker.plans.first { $0.nightID == tracker.night.id }?.reminders ?? []
    }

    var body: some View {
        List {
            Section("当前上下文") {
                LabeledContent("睡眠夜", value: tracker.night.id)
                LabeledContent("通知权限", value: notifications.authorizationText)
                LabeledContent("系统待发送", value: "\(notifications.pending.count) 条")
                LabeledContent("计划生成", value: "\(tracker.plans.count) 晚")
                LabeledContent("打卡状态", value: tracker.isComplete ? "已完成" : "未完成")
                if notifications.isScheduling { ProgressView("正在同步系统通知…") }
                if let last = notifications.pending.filter({ !$0.isTest }).compactMap(\.date).max() {
                    Text("当前队列覆盖至 \(last.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("立即测试") {
                Button("10 秒后发送测试通知") { run { await notifications.scheduleTest(after: 10) } }
                Button("30 秒后发送测试通知") { run { await notifications.scheduleTest(after: 30) } }
                Button("取消全部测试通知") { run { await notifications.cancelTests() } }
                Text("测试通知可以在窗口外发送，标记为测试，不计入催睡次数。快捷打卡仍归属当前睡眠夜。长按通知查看操作。")
                    .font(.caption).foregroundStyle(.secondary)
            }.disabled(busy)
            Section("任务与计划") {
                Button("清除今晚完成状态") {
                    tracker.now = Date()
                    tracker.clearTonight()
                    run { await notifications.reconcile() }
                }
                Button("重新生成今晚通知") {
                    tracker.now = Date()
                    tracker.regenerate()
                    run { await notifications.reconcile() }
                }
                Button("刷新 pending notifications") { run { await notifications.reconcile() } }
                Button("清除 streak 与全部打卡历史", role: .destructive) { confirmClearStreak = true }
            }.disabled(busy)
            Section("今晚计划 · \(tonight.count) 条") {
                if tracker.isComplete { Text("今晚已完成，下列计划不会再入队。") .foregroundStyle(.secondary) }
                ForEach(tonight) { reminder in
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text(reminder.fireDate.formatted(date: .abbreviated, time: .shortened)).bold()
                            Spacer()
                            Text("L\(reminder.level.rawValue)").foregroundStyle(.secondary)
                        }
                        Text(reminder.message).font(.subheadline)
                        Text(planStatus(reminder)).font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 3)
                }
            }
            Section("系统 pending notifications · \(notifications.pending.count) 条") {
                ForEach(notifications.pending) { request in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(request.date?.formatted(date: .abbreviated, time: .shortened) ?? "时间未知").bold()
                        Text(request.body).font(.subheadline)
                        Text(request.id).font(.caption2).foregroundStyle(.secondary).textSelection(.enabled)
                    }.padding(.vertical, 3)
                }
            }
            if let error = tracker.storageError {
                Section("数据恢复") {
                    Text(error).foregroundStyle(.red)
                    Button("放弃损坏数据并重新开始", role: .destructive) { confirmResetStorage = true }
                }
            }
        }
        .navigationTitle("开发者调试")
        .task { await notifications.inspect() }
        .confirmationDialog("清除全部打卡历史与连续记录？此操作无法撤销。", isPresented: $confirmClearStreak,
                            titleVisibility: .visible) {
            Button("清除历史与 streak", role: .destructive) {
                tracker.clearStreak()
                run { await notifications.reconcile() }
            }
        }
        .confirmationDialog("放弃损坏的本机数据？此操作无法撤销。", isPresented: $confirmResetStorage,
                            titleVisibility: .visible) {
            Button("重置本机数据", role: .destructive) {
                tracker.resetCorruptStorage()
                run { await notifications.reconcile() }
            }
        }
    }

    private func run(_ action: @escaping @MainActor () async -> Void) {
        busy = true
        Task { await action(); busy = false }
    }
    private func planStatus(_ reminder: PlannedReminder) -> String {
        if tracker.isComplete { return "已取消" }
        if notifications.pending.contains(where: { $0.id == reminder.id }) { return "已加入系统队列" }
        return reminder.fireDate <= tracker.now ? "计划时间已过（不等于实际送达）" : "尚未入队"
    }
}
