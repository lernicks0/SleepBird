import Foundation
import Combine

@MainActor
final class SleepTracker: ObservableObject {
    static let shared = SleepTracker()
    @Published private(set) var settings: AppSettings
    @Published private(set) var records: [SleepRecord]
    @Published private(set) var plans: [NightPlan]
    @Published private(set) var longestStreak: Int
    @Published var now = Date()
    @Published var storageError: String?
    private let defaults: UserDefaults
    private var calendar: Calendar
    private var schedulingTimeZone: String
    private let followsSystemTimeZone: Bool

    private struct State: Codable {
        var settings: AppSettings
        var records: [SleepRecord]
        var plans: [NightPlan]
        var longestStreak: Int
    }

    init(defaults: UserDefaults = .standard, calendar: Calendar? = nil) {
        self.defaults = defaults
        self.calendar = calendar ?? SleepNight.calendar
        followsSystemTimeZone = calendar == nil
        schedulingTimeZone = defaults.string(forKey: "SleepBird.schedulingTimeZone") ?? (calendar ?? SleepNight.calendar).timeZone.identifier
        var loaded: State?
        var failure: String?
        if let data = defaults.data(forKey: "SleepBird.state.v1") {
            do {
                let candidate = try JSONDecoder().decode(State.self, from: data)
                if candidate.settings.isValid { loaded = candidate }
                else { failure = "保存的提醒窗口无效。原始数据已保留，请在调试页重置。" }
            }
            catch { failure = "本机数据无法读取。原始数据已保留，请先检查再重置。" }
        }
        settings = loaded?.settings ?? AppSettings()
        records = loaded?.records ?? []
        plans = loaded?.plans ?? []
        longestStreak = loaded?.longestStreak ?? 0
        storageError = failure
    }

    var night: SleepNight { SleepNight.current(at: now, settings: settings, calendar: calendar) }
    var isComplete: Bool { completed(night.id) }
    var currentStreak: Int {
        var cursor = night.logicalDate
        if !completed(SleepNight.key(cursor, calendar: calendar)) {
            cursor = calendar.date(byAdding: .day, value: -1, to: cursor)!
        }
        var count = 0
        while completed(SleepNight.key(cursor, calendar: calendar)) {
            count += 1
            cursor = calendar.date(byAdding: .day, value: -1, to: cursor)!
        }
        return count
    }
    var latestCompletion: SleepRecord? { records.max { $0.completedAt < $1.completedAt } }
    func completed(_ id: String) -> Bool { records.contains { $0.nightID == id } }

    @discardableResult
    func complete(nightID: String? = nil, logicalDate: Date? = nil, at date: Date = Date()) -> Bool {
        now = date
        let target = SleepNight.current(at: date, settings: settings, calendar: calendar)
        let id = nightID ?? target.id
        guard !completed(id), storageError == nil else { return false }
        records.append(SleepRecord(nightID: id, logicalDate: logicalDate ?? target.logicalDate, completedAt: date))
        longestStreak = max(longestStreak, longestRun())
        save()
        return true
    }

    private func longestRun() -> Int {
        let ids = Set(records.map(\.nightID)).sorted()
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        var previous: Date?
        var run = 0
        var best = 0
        for id in ids {
            guard let day = formatter.date(from: id) else { continue }
            if let previous, formatter.calendar.dateComponents([.day], from: previous, to: day).day == 1 {
                run += 1
            } else { run = 1 }
            previous = day
            best = max(best, run)
        }
        return best
    }

    func refresh(at date: Date = Date()) {
        now = date
        guard storageError == nil else { return }
        if defaults.integer(forKey: "SleepBird.messageVersion") != 2 {
            // Upgrade copy while keeping the already-randomized times and record dates.
            plans = plans.map { plan in
                var previous: String?
                let reminders = plan.reminders.map { reminder in
                    let message = NotificationMessageProvider.message(for: reminder.level, excluding: previous)
                    previous = message
                    return PlannedReminder(id: reminder.id, nightID: reminder.nightID,
                                           fireDate: reminder.fireDate, level: reminder.level, message: message)
                }
                return NightPlan(nightID: plan.nightID, logicalDate: plan.logicalDate, reminders: reminders)
            }
            save()
            guard storageError == nil else { return }
            defaults.set(2, forKey: "SleepBird.messageVersion")
        }
        if followsSystemTimeZone { calendar = SleepNight.calendar }
        if schedulingTimeZone != calendar.timeZone.identifier {
            plans.removeAll()
            schedulingTimeZone = calendar.timeZone.identifier
        }
        defaults.set(schedulingTimeZone, forKey: "SleepBird.schedulingTimeZone")
        let cutoff = calendar.date(byAdding: .day, value: -2, to: night.logicalDate)!
        plans.removeAll { $0.logicalDate < cutoff }
        // Finite horizon: refresh on foreground and notification actions.
        for offset in 0..<8 {
            let day = calendar.date(byAdding: .day, value: offset, to: night.logicalDate)!
            let candidate = SleepNight.make(day: day, settings: settings, calendar: calendar)
            if !plans.contains(where: { $0.nightID == candidate.id }) {
                let previousDay = calendar.date(byAdding: .day, value: -1, to: day)!
                let previous = plans.first { $0.nightID == SleepNight.key(previousDay, calendar: calendar) }
                let previousNight = SleepNight.make(day: previousDay, settings: settings, calendar: calendar)
                let offset = previous?.reminders.first.map { Int($0.fireDate.timeIntervalSince(previousNight.start) / 60) }
                plans.append(ReminderScheduler.generate(night: candidate, settings: settings,
                                                       avoidingFirstOffset: offset, calendar: calendar))
            }
        }
        save()
    }

    func updateSettings(_ value: AppSettings) {
        guard storageError == nil, value.isValid else { return }
        settings = value
        plans.removeAll()
        refresh()
    }
    func regenerate() { plans.removeAll { $0.nightID == night.id }; refresh() }
    func clearTonight() { records.removeAll { $0.nightID == night.id }; save() }
    func clearStreak() { records.removeAll(); longestStreak = 0; save() }
    func resetCorruptStorage() {
        defaults.removeObject(forKey: "SleepBird.state.v1")
        storageError = nil
        refresh()
    }
    private func save() {
        guard storageError == nil else { return }
        do {
            let state = State(settings: settings, records: records, plans: plans, longestStreak: longestStreak)
            defaults.set(try JSONEncoder().encode(state), forKey: "SleepBird.state.v1")
        } catch { storageError = "保存本机数据失败：\(error.localizedDescription)" }
    }
}
