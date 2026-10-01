import Foundation

struct SleepRecord: Codable, Identifiable {
    var id: String { nightID }
    let nightID: String
    let logicalDate: Date
    let completedAt: Date
}

struct SleepNight: Identifiable {
    let id: String
    let logicalDate: Date
    let start: Date
    let end: Date

    static var calendar: Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = .current
        return result
    }

    static func key(_ date: Date, calendar: Calendar = SleepNight.calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }

    static func make(day: Date, settings: AppSettings, calendar: Calendar = SleepNight.calendar) -> SleepNight {
        let day = calendar.startOfDay(for: day)
        let start = calendar.date(bySettingHour: settings.startMinute / 60,
                                  minute: settings.startMinute % 60, second: 0, of: day)!
        let endDay = settings.endMinute <= settings.startMinute
            ? calendar.date(byAdding: .day, value: 1, to: day)! : day
        let end = calendar.date(bySettingHour: settings.endMinute / 60,
                                minute: settings.endMinute % 60, second: 0, of: endDay)!
        return SleepNight(id: key(day, calendar: calendar), logicalDate: day, start: start, end: end)
    }

    // Before the overnight window ends, actions belong to yesterday's sleep night.
    // At the exact end, the new logical day starts. Daytime check-ins belong to today.
    static func current(at now: Date = Date(), settings: AppSettings,
                        calendar: Calendar = SleepNight.calendar) -> SleepNight {
        let today = calendar.startOfDay(for: now)
        if settings.endMinute <= settings.startMinute {
            let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
            let previous = make(day: yesterday, settings: settings, calendar: calendar)
            if now < previous.end { return previous }
        }
        return make(day: today, settings: settings, calendar: calendar)
    }
}

enum ReminderIntensity: String, Codable, CaseIterable, Identifiable {
    case gentle, normal, wild
    var id: String { rawValue }
    var title: String {
        switch self { case .gentle: return "温和"; case .normal: return "正常"; case .wild: return "疯狂" }
    }
}
enum ReminderFrequency: String, Codable, CaseIterable, Identifiable {
    case low, normal, high
    var id: String { rawValue }
    var title: String {
        switch self { case .low: return "低"; case .normal: return "正常"; case .high: return "高" }
    }
    var gapRange: ClosedRange<Int> {
        switch self { case .low: return 65...90; case .normal: return 32...55; case .high: return 20...35 }
    }
}
struct AppSettings: Codable, Equatable {
    var startMinute = 20 * 60 + 30
    var endMinute = 4 * 60
    var intensity: ReminderIntensity = .normal
    var frequency: ReminderFrequency = .normal
    var sound = true
    var haptics = true
    var isValid: Bool {
        (0..<1440).contains(startMinute) && (0..<1440).contains(endMinute)
            && startMinute != endMinute && (endMinute - startMinute + 1440) % 1440 >= 30
    }
}
struct PlannedReminder: Codable, Identifiable {
    let id: String
    let nightID: String
    let fireDate: Date
    let level: ReminderLevel
    let message: String
}
struct NightPlan: Codable {
    let nightID: String
    let logicalDate: Date
    let reminders: [PlannedReminder]
}
