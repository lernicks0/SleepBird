import Foundation

struct ReminderScheduler {
    static func generate(night: SleepNight, settings: AppSettings,
                         avoidingFirstOffset: Int? = nil, calendar: Calendar = SleepNight.calendar) -> NightPlan {
        var reminders: [PlannedReminder] = []
        let offsets = Array(5...18).filter { $0 != avoidingFirstOffset }
        var date = night.start.addingTimeInterval(Double(offsets.randomElement()!) * 60)
        var previous: String?
        while date < night.end {
            let level = ReminderLevel.at(date, in: night, intensity: settings.intensity, calendar: calendar)
            let message = NotificationMessageProvider.message(for: level, excluding: previous)
            reminders.append(PlannedReminder(id: "sleepbird.\(night.id).\(UUID().uuidString)",
                                             nightID: night.id, fireDate: date,
                                             level: level, message: message))
            previous = message
            date = date.addingTimeInterval(Double(Int.random(in: settings.frequency.gapRange)) * 60)
        }
        return NightPlan(nightID: night.id, logicalDate: night.logicalDate, reminders: reminders)
    }
}
