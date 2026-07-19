import SwiftUI
import DemoCore
import UserNotifications

@main
struct DemoApp: App {
    init() {
        // App-wired routing: DeltaListCore never claims the delegate, so install the router here
        // (or forward from your own delegate). Required for notification taps/actions to route back.
        UNUserNotificationCenter.current().delegate = DeltaNotificationRouter.shared
        DeltaNotificationRouter.requestAuthorization()

        // App-wired row state: DeltaListCore can't collect consumer-framework Kotlin Flows itself,
        // so install the adapter that feeds ViewModelBoundCell.viewModelStateDidChange per row.
        DeltaRowBinding.stateProvider = { item, emit in
            guard let stableItem = item as? DemoCore.StableItem,
                  let ticking = stableItem.value as? TickingItem else { return nil }
            let task = Task { @MainActor in
                for await count in ticking.tickCount {
                    emit(count)
                }
            }
            return { task.cancel() }
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
