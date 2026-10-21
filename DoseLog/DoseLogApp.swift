import SwiftUI

@main
@MainActor
struct DoseLogApp: App {
    @StateObject private var store = RecordStore()
    @StateObject private var reminders = ReminderCoordinator()

    var body: some Scene {
        WindowGroup {
            ContentView(store: store, reminders: reminders)
        }
    }
}
