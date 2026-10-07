import Foundation
import Combine
import UserNotifications
import UIKit

struct PendingNotificationInfo: Identifiable {
    let id: String
    let date: Date?
    let body: String
    let nightID: String?
    let isTest: Bool
}

@MainActor
final class NotificationManager: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationManager()
    static let categoryID = "SLEEPBIRD_SLEEP_NIGHT"
    static let sleepActionID = "SLEEPBIRD_I_AM_ASLEEP"
    @Published private(set) var authorization: UNAuthorizationStatus = .notDetermined
    @Published private(set) var pending: [PendingNotificationInfo] = []
    @Published private(set) var deliveredTonight = 0
    @Published private(set) var isScheduling = false
    @Published var errorMessage: String?
    private let center: UNUserNotificationCenter
    private var needsReconcile = false
    private var scheduledSound: Bool?
    private var isAddingTest = false
    private var observedDeliveryIDs: Set<String>

    override init() {
        center = .current()
        observedDeliveryIDs = Set(UserDefaults.standard.stringArray(forKey: "SleepBird.deliveredIDs") ?? [])
        super.init()
        center.delegate = self
        let action = UNNotificationAction(identifier: Self.sleepActionID, title: "我睡了 💤", options: [])
        let category = UNNotificationCategory(identifier: Self.categoryID, actions: [action],
                                               intentIdentifiers: [], options: [])
        center.setNotificationCategories([category])
    }

    var canNotify: Bool { authorization == .authorized || authorization == .provisional || authorization == .ephemeral }
    var authorizationText: String {
        switch authorization {
        case .notDetermined: return "尚未开启"
        case .denied: return "已关闭"
        case .authorized: return "已开启"
        case .provisional: return "静默授权"
        case .ephemeral: return "临时授权"
        @unknown default: return "未知"
        }
    }

    func requestPermission() async {
        do { _ = try await center.requestAuthorization(options: [.alert, .sound, .badge]) }
        catch { errorMessage = "通知授权失败：\(error.localizedDescription)" }
        await reconcile()
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    // MainActor serializes mutations; the dirty loop handles reentrancy across awaits.
    // A completion/settings change during an add always causes another reconciliation.
    func reconcile() async {
        needsReconcile = true
        guard !isScheduling else { return }
        isScheduling = true
        defer { isScheduling = false }
        while needsReconcile {
            needsReconcile = false
            let tracker = SleepTracker.shared
            tracker.refresh()
            let systemSettings = await center.notificationSettings()
            authorization = systemSettings.authorizationStatus
            let existing = await center.pendingNotificationRequests()
            let tests = existing.filter { $0.identifier.hasPrefix("sleepbird.test.") }
            let desired = tracker.plans.flatMap(\.reminders)
                .filter { $0.fireDate > Date() && !tracker.completed($0.nightID) }
                .sorted { $0.fireDate < $1.fireDate }
                .prefix(max(0, 60 - tests.count))
            let desiredIDs = Set(desired.map(\.id))
            let obsolete = existing.filter {
                $0.identifier.hasPrefix("sleepbird.") && !$0.identifier.hasPrefix("sleepbird.test.")
                    && (!desiredIDs.contains($0.identifier) || !canNotify || tracker.storageError != nil)
            }.map(\.identifier)
            center.removePendingNotificationRequests(withIdentifiers: obsolete)
            let soundChanged = scheduledSound != tracker.settings.sound
            if canNotify && tracker.storageError == nil {
                let existingIDs = Set(existing.map(\.identifier)).subtracting(obsolete)
                errorMessage = nil
                for reminder in desired where !existingIDs.contains(reminder.id) || soundChanged {
                    guard !tracker.completed(reminder.nightID), reminder.fireDate > Date() else { continue }
                    guard tracker.plans.contains(where: { $0.reminders.contains(where: { $0.id == reminder.id }) }) else { continue }
                    let content = UNMutableNotificationContent()
                    content.title = "SleepBird · 晚安冲刺！🐦 LEVEL \(reminder.level.rawValue)"
                    content.body = reminder.message
                    content.categoryIdentifier = Self.categoryID
                    content.threadIdentifier = reminder.nightID
                    content.sound = tracker.settings.sound ? .default : nil
                    let plan = tracker.plans.first { $0.nightID == reminder.nightID }
                    content.userInfo = ["nightID": reminder.nightID,
                                        "logicalDate": plan?.logicalDate.timeIntervalSince1970 ?? 0,
                                        "isTest": false]
                    let calendar = SleepNight.calendar
                    var components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second],
                                                              from: reminder.fireDate)
                    components.calendar = calendar
                    components.timeZone = calendar.timeZone
                    let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
                    do { try await center.add(UNNotificationRequest(identifier: reminder.id, content: content, trigger: trigger)) }
                    catch { errorMessage = "部分提醒未能加入系统队列：\(error.localizedDescription)" }
                    if tracker.completed(reminder.nightID) {
                        center.removePendingNotificationRequests(withIdentifiers: [reminder.id])
                    }
                }
                scheduledSound = tracker.settings.sound
            }
            await inspect()
        }
    }

    func completeCurrentNight() async {
        let tracker = SleepTracker.shared
        tracker.refresh()
        let id = tracker.night.id
        tracker.complete()
        guard tracker.completed(id) else { return }
        // Cancel known requests synchronously before fetching the authoritative system queue.
        let known = tracker.plans.filter { $0.nightID == id }.flatMap(\.reminders).map(\.id)
            + pending.filter { $0.nightID == id }.map(\.id)
        center.removePendingNotificationRequests(withIdentifiers: known)
        await cancelNight(id)
        await reconcile()
    }

    private func cancelNight(_ id: String) async {
        let requests = await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers: requests.filter {
            ($0.content.userInfo["nightID"] as? String) == id
        }.map(\.identifier))
        let delivered = await center.deliveredNotifications()
        center.removeDeliveredNotifications(withIdentifiers: delivered.filter {
            ($0.request.content.userInfo["nightID"] as? String) == id
        }.map { $0.request.identifier })
    }

    func scheduleTest(after seconds: TimeInterval) async {
        guard !isAddingTest else { return }
        isAddingTest = true
        defer { isAddingTest = false }
        if authorization == .notDetermined { await requestPermission() }
        guard canNotify else { errorMessage = "请先在系统设置中开启通知。"; return }
        let tracker = SleepTracker.shared
        tracker.refresh()
        guard !tracker.isComplete else {
            errorMessage = "本睡眠夜已完成。先在调试页清除今晚完成状态，再测试通知。"
            return
        }
        guard tracker.storageError == nil else { return }
        let requests = await center.pendingNotificationRequests()
        guard requests.filter({ $0.identifier.hasPrefix("sleepbird.test.") }).count < 4 else {
            errorMessage = "最多同时安排 4 条测试通知，请等待或取消测试队列。"
            return
        }
        let night = tracker.night
        let content = UNMutableNotificationContent()
        content.title = "SleepBird · 测试通知"
        content.body = "\(Int(seconds)) 秒测试到达！长按后点「我睡了 💤」可以测试打卡。"
        content.categoryIdentifier = Self.categoryID
        content.threadIdentifier = night.id
        content.sound = tracker.settings.sound ? .default : nil
        content.userInfo = ["nightID": night.id, "logicalDate": night.logicalDate.timeIntervalSince1970, "isTest": true]
        let id = "sleepbird.test.\(night.id).\(UUID().uuidString)"
        do {
            try await center.add(UNNotificationRequest(identifier: id, content: content,
                                                       trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)))
            if tracker.completed(night.id) { center.removePendingNotificationRequests(withIdentifiers: [id]) }
        } catch { errorMessage = "测试通知失败：\(error.localizedDescription)" }
        await reconcile()
    }

    func cancelTests() async {
        let requests = await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers: requests.filter {
            $0.identifier.hasPrefix("sleepbird.test.")
        }.map(\.identifier))
        await reconcile()
    }

    func inspect() async {
        let requests = await center.pendingNotificationRequests()
        pending = requests.map {
            PendingNotificationInfo(id: $0.identifier, date: nextTriggerDate($0.trigger), body: $0.content.body,
                                    nightID: $0.content.userInfo["nightID"] as? String,
                                    isTest: $0.identifier.hasPrefix("sleepbird.test."))
        }.sorted { ($0.date ?? .distantFuture) < ($1.date ?? .distantFuture) }
        let delivered = await center.deliveredNotifications()
        for item in delivered where !((item.request.content.userInfo["isTest"] as? Bool) ?? false) {
            observedDeliveryIDs.insert(item.request.identifier)
        }
        // These are observed deliveries, never inferred from timestamps. Cleared notifications
        // that were never observed by the app cannot be counted by the public iOS API.
        let retainedIDs = Set(SleepTracker.shared.plans.flatMap(\.reminders).map(\.id))
        observedDeliveryIDs.formIntersection(retainedIDs)
        UserDefaults.standard.set(Array(observedDeliveryIDs), forKey: "SleepBird.deliveredIDs")
        let tonightIDs = Set(SleepTracker.shared.plans.filter { $0.nightID == SleepTracker.shared.night.id }
            .flatMap(\.reminders).map(\.id))
        deliveredTonight = observedDeliveryIDs.intersection(tonightIDs).count
    }

    private func nextTriggerDate(_ trigger: UNNotificationTrigger?) -> Date? {
        if let calendar = trigger as? UNCalendarNotificationTrigger { return calendar.nextTriggerDate() }
        if let interval = trigger as? UNTimeIntervalNotificationTrigger { return interval.nextTriggerDate() }
        return nil
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        Task { @MainActor in
            defer { completionHandler() }
            let tracker = SleepTracker.shared
            tracker.refresh()
            if response.actionIdentifier == Self.sleepActionID,
               let id = response.notification.request.content.userInfo["nightID"] as? String {
                // Ignore actions on stale nights; they must never complete today's task.
                if id == tracker.night.id {
                    await self.completeCurrentNight()
                } else {
                    await self.cancelNight(id)
                    await self.reconcile()
                }
            } else { await self.reconcile() }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        Task { @MainActor in
            let tracker = SleepTracker.shared
            let id = notification.request.content.userInfo["nightID"] as? String
            guard id.map({ !tracker.completed($0) }) ?? true else { completionHandler([]); return }
            var options: UNNotificationPresentationOptions = [.banner, .list]
            if tracker.settings.sound { options.insert(.sound) }
            completionHandler(options)
            if !((notification.request.content.userInfo["isTest"] as? Bool) ?? false) {
                self.observedDeliveryIDs.insert(notification.request.identifier)
            }
            await self.inspect()
        }
    }
}
