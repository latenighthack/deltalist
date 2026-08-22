import SwiftUI
import DemoCore
import UserNotifications

@main
struct DemoAppMac: App {
    init() {
        // App-wired routing: DeltaListCore never claims the delegate, so install the router here so
        // notification taps/actions route back.
        UNUserNotificationCenter.current().delegate = DeltaNotificationRouter.shared
        DeltaNotificationRouter.requestAuthorization()

        // App-wired row state for the AppKit DeltaRows DSL: DeltaListCore can't collect
        // consumer-framework Kotlin Flows itself, so feed ViewModelBoundCell.viewModelStateDidChange
        // per row here (mirrors the iOS DemoApp wiring).
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
            ContentMacView()
                .frame(minWidth: 720, minHeight: 480)
        }
    }
}
