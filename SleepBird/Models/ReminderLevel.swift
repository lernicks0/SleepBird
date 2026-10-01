import Foundation

enum ReminderLevel: Int, Codable, CaseIterable {
    case one = 1, two, three, four, five

    static func at(_ date: Date, in night: SleepNight, intensity: ReminderIntensity,
                   calendar: Calendar = .current) -> ReminderLevel {
        let isDefaultWindow = calendar.component(.hour, from: night.start) == 20
            && calendar.component(.minute, from: night.start) == 30
            && calendar.component(.hour, from: night.end) == 4
            && calendar.component(.minute, from: night.end) == 0
        let minute: Int
        if isDefaultWindow {
            let c = calendar.dateComponents([.hour, .minute], from: date)
            let wallMinute = c.hour! * 60 + c.minute!
            minute = wallMinute < 12 * 60 ? wallMinute + 24 * 60 : wallMinute
        } else {
            // Custom windows scale the five stages across their duration, including daytime windows.
            let duration = max(1, night.end.timeIntervalSince(night.start))
            minute = 1230 + Int(max(0, date.timeIntervalSince(night.start)) / duration * 450)
        }
        let base: Int
        switch minute {
        case ..<1290: base = 1
        case ..<1350: base = 2
        case ..<1410: base = 3
        case ..<1500: base = 4
        default: base = 5
        }
        let shift = intensity == .gentle ? -1 : (intensity == .wild ? 1 : 0)
        return ReminderLevel(rawValue: min(5, max(1, base + shift)))!
    }
}
