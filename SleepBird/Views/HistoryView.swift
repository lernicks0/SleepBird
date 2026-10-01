import SwiftUI

struct HistoryView: View {
    @EnvironmentObject private var tracker: SleepTracker

    var body: some View {
        List {
            Section {
                LabeledContent("当前连续", value: "\(tracker.currentStreak) 天")
                LabeledContent("最长连续", value: "\(tracker.longestStreak) 天")
                LabeledContent("完成夜数", value: "\(tracker.records.count) 夜")
            }
            Section("睡眠夜 · 实际打卡时间") {
                if tracker.records.isEmpty {
                    ContentUnavailableView("第一晚，从现在开始", systemImage: "moon.zzz",
                                           description: Text("点击「我睡了」后，你的记录会出现在这里。"))
                }
                ForEach(tracker.records.sorted { $0.nightID > $1.nightID }) { record in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(record.nightID).font(.headline)
                        Text(record.completedAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.subheadline).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                }
            }
            Section {
                Text("凌晨打卡归属前一睡眠夜，窗口结束后进入新一天。连续记录按是否打卡计算；当前版本不设额外的早睡截止线。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }.navigationTitle("睡眠记录")
    }
}
