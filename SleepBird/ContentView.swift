import SwiftUI
import Combine

struct ContentView: View {
    @EnvironmentObject private var tracker: SleepTracker
    @EnvironmentObject private var notifications: NotificationManager
    @Environment(\.scenePhase) private var scenePhase
    private let clock = Timer.publish(every: 20, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView {
            NavigationStack { HomeView() }
                .tabItem { Label("今晚", systemImage: "moon.stars.fill") }
            NavigationStack { HistoryView() }
                .tabItem { Label("记录", systemImage: "calendar") }
            NavigationStack { SettingsView() }
                .tabItem { Label("设置", systemImage: "slider.horizontal.3") }
        }
        .task { await notifications.reconcile() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await notifications.reconcile() } }
        }
        .onReceive(clock) { date in
            guard scenePhase == .active else { return }
            let previousID = tracker.night.id
            tracker.now = date
            Task {
                if previousID != tracker.night.id { await notifications.reconcile() }
                else { await notifications.inspect() }
            }
        }
        .alert("SleepBird", isPresented: Binding(
            get: { notifications.errorMessage != nil },
            set: { if !$0 { notifications.errorMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { notifications.errorMessage = nil }
        } message: { Text(notifications.errorMessage ?? "") }
    }
}
