import XCTest
import UserNotifications
@testable import SleepBird

@MainActor
final class SleepBirdTests: XCTestCase {
    private var calendar: Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return result
    }
    private func date(_ value: String) -> Date {
        let formatter = ISO8601DateFormatter()
        return formatter.date(from: value + "+08:00")!
    }
    private func withTracker(_ body: (SleepTracker) throws -> Void) rethrows {
        let name = "SleepBird.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        try body(SleepTracker(defaults: defaults, calendar: calendar))
    }

    func testMidnightBelongsToPreviousNight() {
        let night = SleepNight.current(at: date("2026-10-02T00:30:00"), settings: AppSettings(), calendar: calendar)
        XCTAssertEqual(night.id, "2026-10-01")
        XCTAssertEqual(night.start, date("2026-10-01T20:30:00"))
        XCTAssertEqual(night.end, date("2026-10-02T04:00:00"))
    }
    func testRolloverAtExactEnd() {
        XCTAssertEqual(SleepNight.current(at: date("2026-10-02T03:59:59"), settings: AppSettings(), calendar: calendar).id,
                       "2026-10-01")
        XCTAssertEqual(SleepNight.current(at: date("2026-10-02T04:00:00"), settings: AppSettings(), calendar: calendar).id,
                       "2026-10-02")
        XCTAssertEqual(SleepNight.current(at: date("2026-10-02T20:29:59"), settings: AppSettings(), calendar: calendar).id,
                       "2026-10-02")
    }
    func testCustomSameDayWindow() {
        var settings = AppSettings()
        settings.startMinute = 18 * 60
        settings.endMinute = 23 * 60
        let night = SleepNight.current(at: date("2026-10-02T01:00:00"), settings: settings, calendar: calendar)
        XCTAssertEqual(night.id, "2026-10-02")
        XCTAssertEqual(night.end, date("2026-10-02T23:00:00"))
    }
    func testWindowValidation() {
        var settings = AppSettings()
        XCTAssertTrue(settings.isValid)
        settings.endMinute = settings.startMinute
        XCTAssertFalse(settings.isValid)
        settings.endMinute = settings.startMinute + 10
        XCTAssertFalse(settings.isValid)
        settings.endMinute = 1440
        XCTAssertFalse(settings.isValid)
        settings.startMinute = -1
        XCTAssertFalse(settings.isValid)
    }
    func testCustomDaytimeLevelsIncrease() {
        var settings = AppSettings()
        settings.startMinute = 8 * 60
        settings.endMinute = 20 * 60
        let night = SleepNight.make(day: date("2026-10-01T12:00:00"), settings: settings, calendar: calendar)
        var last = 0
        for minute in stride(from: 0, to: 12 * 60, by: 15) {
            let level = ReminderLevel.at(night.start.addingTimeInterval(Double(minute * 60)), in: night,
                                         intensity: .normal, calendar: calendar)
            XCTAssertGreaterThanOrEqual(level.rawValue, last)
            last = level.rawValue
        }
        XCTAssertEqual(last, 5)
    }
    func testLevelsAtBoundaries() {
        let night = SleepNight.make(day: date("2026-10-01T12:00:00"), settings: AppSettings(), calendar: calendar)
        let cases: [(String, ReminderLevel)] = [
            ("2026-10-01T20:30:00", .one), ("2026-10-01T21:29:59", .one),
            ("2026-10-01T21:30:00", .two), ("2026-10-01T22:30:00", .three),
            ("2026-10-01T23:30:00", .four), ("2026-10-02T00:59:59", .four),
            ("2026-10-02T01:00:00", .five)
        ]
        for (time, level) in cases {
            XCTAssertEqual(ReminderLevel.at(date(time), in: night, intensity: .normal, calendar: calendar), level)
        }
    }
    func testIntensityClampsAndDoesNotDecrease() {
        let night = SleepNight.make(day: date("2026-10-01T12:00:00"), settings: AppSettings(), calendar: calendar)
        XCTAssertEqual(ReminderLevel.at(night.start, in: night, intensity: .gentle, calendar: calendar), .one)
        XCTAssertEqual(ReminderLevel.at(night.start, in: night, intensity: .wild, calendar: calendar), .two)
        XCTAssertEqual(ReminderLevel.at(date("2026-10-02T03:30:00"), in: night, intensity: .wild, calendar: calendar), .five)
    }
    func testRandomPlansStayInsideWindowAndRespectMinimumGap() {
        for frequency in ReminderFrequency.allCases {
            var settings = AppSettings()
            settings.frequency = frequency
            let night = SleepNight.make(day: date("2026-10-01T12:00:00"), settings: settings, calendar: calendar)
            for _ in 0..<100 {
                let plan = ReminderScheduler.generate(night: night, settings: settings, calendar: calendar)
                XCTAssertFalse(plan.reminders.isEmpty)
                for reminder in plan.reminders {
                    XCTAssertGreaterThanOrEqual(reminder.fireDate, night.start)
                    XCTAssertLessThan(reminder.fireDate, night.end)
                    XCTAssertEqual(reminder.nightID, night.id)
                }
                for (a, b) in zip(plan.reminders, plan.reminders.dropFirst()) {
                    XCTAssertGreaterThanOrEqual(b.fireDate.timeIntervalSince(a.fireDate), Double(frequency.gapRange.lowerBound * 60))
                    XCTAssertLessThanOrEqual(b.fireDate.timeIntervalSince(a.fireDate), Double(frequency.gapRange.upperBound * 60))
                    XCTAssertGreaterThanOrEqual(b.level.rawValue, a.level.rawValue)
                    XCTAssertNotEqual(a.message, b.message)
                }
            }
        }
    }
    func testEachLevelHasFifteenUniqueMessages() {
        for level in ReminderLevel.allCases {
            let messages = NotificationMessageProvider.messages[level]!
            XCTAssertGreaterThanOrEqual(messages.count, 15)
            XCTAssertEqual(Set(messages).count, messages.count)
            for _ in 0..<50 {
                XCTAssertNotEqual(NotificationMessageProvider.message(for: level, excluding: messages[0]), messages[0])
            }
        }
    }
    func testAdjacentNightsAlwaysUseDifferentFirstOffset() {
        withTracker { tracker in
            tracker.refresh(at: date("2026-10-01T12:00:00"))
            let plans = tracker.plans.sorted { $0.logicalDate < $1.logicalDate }
            for (a, b) in zip(plans, plans.dropFirst()) {
                let aNight = SleepNight.make(day: a.logicalDate, settings: tracker.settings, calendar: calendar)
                let bNight = SleepNight.make(day: b.logicalDate, settings: tracker.settings, calendar: calendar)
                XCTAssertNotEqual(a.reminders.first!.fireDate.timeIntervalSince(aNight.start),
                                  b.reminders.first!.fireDate.timeIntervalSince(bNight.start))
            }
        }
    }
    func testPlanIsStableAcrossRefresh() {
        withTracker { tracker in
            tracker.refresh(at: date("2026-10-01T21:00:00"))
            let ids = tracker.plans.flatMap(\.reminders).map(\.id)
            tracker.refresh(at: date("2026-10-01T21:05:00"))
            XCTAssertEqual(ids, tracker.plans.flatMap(\.reminders).map(\.id))
        }
    }
    func testCheckInIsIdempotentAndRecordsActualTime() {
        withTracker { tracker in
            let time = date("2026-10-02T00:30:00")
            XCTAssertTrue(tracker.complete(at: time))
            XCTAssertFalse(tracker.complete(at: time.addingTimeInterval(2)))
            XCTAssertEqual(tracker.records.count, 1)
            XCTAssertEqual(tracker.records.first?.nightID, "2026-10-01")
            XCTAssertEqual(tracker.records.first?.completedAt, time)
            XCTAssertEqual(tracker.currentStreak, 1)
        }
    }
    func testConsecutiveNightsAndMissedNightReset() {
        withTracker { tracker in
            tracker.complete(at: date("2026-10-01T22:00:00"))
            tracker.complete(at: date("2026-10-03T00:30:00")) // October 2 night
            XCTAssertEqual(tracker.currentStreak, 2)
            tracker.refresh(at: date("2026-10-03T12:00:00"))
            XCTAssertEqual(tracker.currentStreak, 2) // Today's task remains open.
            tracker.refresh(at: date("2026-10-04T04:00:00"))
            XCTAssertEqual(tracker.currentStreak, 0) // October 3 was missed.
            XCTAssertEqual(tracker.longestStreak, 2)
            tracker.complete(at: date("2026-10-04T21:00:00"))
            XCTAssertEqual(tracker.currentStreak, 1)
            XCTAssertEqual(tracker.longestStreak, 2)
        }
    }
    func testYearAndLeapDayStreaks() {
        withTracker { tracker in
            tracker.complete(at: date("2024-02-28T22:00:00"))
            tracker.complete(at: date("2024-02-29T22:00:00"))
            tracker.complete(at: date("2024-03-01T22:00:00"))
            XCTAssertEqual(tracker.currentStreak, 3)
            tracker.complete(at: date("2025-12-31T22:00:00"))
            tracker.complete(at: date("2026-01-01T22:00:00"))
            XCTAssertEqual(tracker.currentStreak, 2)
            XCTAssertEqual(tracker.longestStreak, 3)
        }
    }
    func testSettingsChangePreservesCompletion() {
        withTracker { tracker in
            tracker.complete(at: Date())
            let id = tracker.night.id
            var settings = tracker.settings
            settings.sound = false
            settings.frequency = .high
            tracker.updateSettings(settings)
            XCTAssertTrue(tracker.completed(id))
            XCTAssertFalse(tracker.settings.sound)
        }
    }
    func testUserDefaultsRoundTrip() {
        let name = "SleepBird.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let original = SleepTracker(defaults: defaults, calendar: calendar)
        original.refresh(at: date("2026-10-01T12:00:00"))
        original.complete(at: date("2026-10-02T00:30:00"))
        let restored = SleepTracker(defaults: defaults, calendar: calendar)
        restored.refresh(at: date("2026-10-02T00:31:00"))
        XCTAssertTrue(restored.isComplete)
        XCTAssertEqual(restored.currentStreak, 1)
        XCTAssertEqual(restored.longestStreak, 1)
        XCTAssertEqual(restored.latestCompletion?.nightID, "2026-10-01")
        XCTAssertEqual(original.plans.flatMap(\.reminders).map(\.id), restored.plans.flatMap(\.reminders).map(\.id))
    }
    func testCorruptDataIsNotSilentlyOverwritten() {
        let name = "SleepBird.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let corrupt = Data("broken".utf8)
        defaults.set(corrupt, forKey: "SleepBird.state.v1")
        let tracker = SleepTracker(defaults: defaults, calendar: calendar)
        XCTAssertNotNil(tracker.storageError)
        XCTAssertFalse(tracker.complete(at: date("2026-10-01T22:00:00")))
        tracker.refresh()
        XCTAssertEqual(defaults.data(forKey: "SleepBird.state.v1"), corrupt)
    }
    func testDSTWindowUsesCalendarDayRatherThan86400Seconds() {
        var la = Calendar(identifier: .gregorian)
        la.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        let day = ISO8601DateFormatter().date(from: "2026-03-07T20:30:00-08:00")!
        let night = SleepNight.make(day: day, settings: AppSettings(), calendar: la)
        XCTAssertEqual(la.component(.hour, from: night.end), 4)
        XCTAssertEqual(la.component(.day, from: night.end), 8)
        XCTAssertEqual(night.end.timeIntervalSince(night.start), 6.5 * 3600)
    }

    func testNotificationActionRegistration() async {
        _ = NotificationManager.shared
        let categories = await UNUserNotificationCenter.current().notificationCategories()
        let category = categories.first { $0.identifier == NotificationManager.categoryID }
        XCTAssertEqual(category?.actions.first?.identifier, NotificationManager.sleepActionID)
        XCTAssertFalse(category?.actions.first?.options.contains(.foreground) ?? true)
    }
}
