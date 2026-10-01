import SwiftUI
import UIKit

struct HomeView: View {
    @EnvironmentObject private var tracker: SleepTracker
    @EnvironmentObject private var notifications: NotificationManager
    @State private var completing = false

    private var next: PendingNotificationInfo? {
        notifications.pending.first {
            $0.nightID == tracker.night.id && !$0.isTest && ($0.date ?? .distantPast) > tracker.now
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Image(systemName: "bird.fill")
                    .font(.system(size: 60)).foregroundStyle(.indigo)
                    .padding(24).background(.indigo.opacity(0.1), in: Circle())
                    .accessibilityHidden(true)
                VStack(spacing: 6) {
                    Text("🔥 \(tracker.currentStreak)")
                        .font(.system(size: 58, weight: .bold, design: .rounded))
                        .contentTransition(.numericText())
                    Text("连续早睡 \(tracker.currentStreak) 天")
                        .font(.headline).foregroundStyle(.secondary)
                }
                VStack(spacing: 18) {
                    Text("今晚状态").font(.subheadline).foregroundStyle(.secondary)
                    Text(tracker.isComplete ? "已睡，晚安 🌙" : "还没睡 👀")
                        .font(.title2.bold())
                    Button {
                        completing = true
                        if tracker.settings.haptics { UIImpactFeedbackGenerator(style: .soft).impactOccurred() }
                        Task {
                            await notifications.completeCurrentNight()
                            completing = false
                        }
                    } label: {
                        Text(tracker.isComplete ? "今晚任务完成 ✓" : "我睡了 💤")
                            .font(.title2.bold()).frame(maxWidth: .infinity).padding(.vertical, 22)
                    }
                    .buttonStyle(.borderedProminent)
                    .clipShape(RoundedRectangle(cornerRadius: 22))
                    .disabled(tracker.isComplete || completing || tracker.storageError != nil)
                    if let record = tracker.records.first(where: { $0.nightID == tracker.night.id }) {
                        Text("打卡于 \(record.completedAt.formatted(date: .omitted, time: .shortened))")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    HStack {
                        Label("下一次提醒", systemImage: "bell")
                        Spacer()
                        if tracker.isComplete { Text("今晚不再提醒") }
                        else if !notifications.canNotify { Text("等待通知授权") }
                        else if let date = next?.date { Text("大约 \(date.formatted(date: .omitted, time: .shortened))") }
                        else { Text(notifications.isScheduling ? "正在安排…" : "暂无待发提醒") }
                    }
                    .font(.subheadline)
                }
                .padding(24).background(Color(uiColor: .secondarySystemGroupedBackground),
                                          in: RoundedRectangle(cornerRadius: 28))

                if !notifications.canNotify {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("让 SleepBird 来提醒你", systemImage: "bell.badge")
                            .font(.headline)
                        Text("只发送本机催睡通知，不需要账号。你可以随时关闭声音或更改频率。")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Button(notifications.authorization == .denied ? "打开系统通知设置" : "开启通知") {
                            if notifications.authorization == .denied { notifications.openSystemSettings() }
                            else { Task { await notifications.requestPermission() } }
                        }.buttonStyle(.bordered)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }

                VStack(alignment: .leading, spacing: 14) {
                    Label("今晚已经提醒 \(notifications.deliveredTonight) 次（已观察）", systemImage: "bell.fill")
                    Label("最长连续记录：\(tracker.longestStreak) 天", systemImage: "flame.fill")
                    Text("睡眠夜：\(tracker.night.id) · \(time(tracker.settings.startMinute))–\(time(tracker.settings.endMinute))")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("通知计数仅含 App 实际观察到的送达，不把到点计划当作送达。")
                        .font(.caption).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                if let error = tracker.storageError {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
            .padding(24).frame(maxWidth: 620).frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("SleepBird")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { DebugView() } label: { Image(systemName: "ladybug") }
                    .accessibilityLabel("开发者调试")
            }
        }
    }

    private func time(_ minute: Int) -> String { String(format: "%02d:%02d", minute / 60, minute % 60) }
}
