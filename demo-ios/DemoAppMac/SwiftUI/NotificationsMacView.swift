import SwiftUI
import DemoCore
import DeltaListCore
import UserNotifications

/// Notifications demo (macOS): each download is a tray notification driven by a DeltaNotifier;
/// progress updates live via @ItemState, and actions/dismiss route back through closures. SwiftUI
/// only (mirrors iOS, which has no UIKit rendering for this screen).
struct NotificationsMacView: View {
    @StateObject private var model = NotificationsMacModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Each download is a tray notification. Progress updates live via itemState; "
                + "dismiss and the action buttons route back through closures.")
                .font(.subheadline).foregroundColor(.secondary)

            HStack {
                Button("Add") { model.add() }
                Button("Tick") { model.tick() }
                Button("Clear") { model.clear() }.tint(.red)
            }
            .buttonStyle(.bordered)

            Text("Active downloads: \(model.items.count)").font(.subheadline.bold())

            List {
                ForEach(model.items, id: \.id) { download in
                    DownloadRow(download: download)
                }
            }
            .listStyle(.inset)
        }
        .padding()
        .navigationTitle("Notifications")
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }
}

private struct DownloadRow: View {
    let download: DownloadVm
    @DeltaListCore.ItemState var progress: DemoCore.KotlinInt

    init(download: DownloadVm) {
        self.download = download
        _progress = DeltaListCore.ItemState(wrappedValue: DemoCore.KotlinInt(int: 0), download.progress)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(download.fileName).font(.body)
            ProgressView(value: Double(progress.intValue), total: 100)
            Text(progress.intValue >= 100 ? "Complete" : "\(progress.intValue)%")
                .font(.caption).foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class NotificationsMacModel: ObservableObject {
    private let viewModel = NotificationsViewModel()
    @Published private(set) var items: [DownloadVm] = []

    private var notifier: DeltaListCore.DeltaNotifier<DemoCore.StableItem>?
    private var listTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?

    func start() {
        guard notifier == nil else { return }

        let category = UNNotificationCategory(
            identifier: "downloads",
            actions: [
                UNNotificationAction(identifier: "pause", title: "Pause", options: []),
                UNNotificationAction(identifier: "cancel", title: "Cancel", options: [.destructive]),
            ],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )

        let notifier = DeltaListCore.DeltaNotifier<DemoCore.StableItem>(
            tag: "downloads",
            idBase: 1000,
            stableId: { $0.stableId },
            categories: [category],
            threadGroupKey: "downloads-group"
        ) { scope in
            let download = scope.item.value as! DownloadVm
            let pct = (scope.state as? DemoCore.KotlinInt)?.intValue ?? 0
            let done = pct >= 100
            return scope.content { content in
                content.title = download.fileName
                content.body = done ? "Complete" : "\(pct)%"
            }
        }
        .itemState { ($0.value as! DownloadVm).progress }
        .onContentTap { _ in }
        .onAction { [weak self] item, key in
            guard let download = item.value as? DownloadVm else { return }
            if key == "cancel" { self?.viewModel.removeById(id: download.id) }
        }
        .onDismiss { [weak self] item in
            guard let download = item.value as? DownloadVm else { return }
            self?.viewModel.removeById(id: download.id)
        }
        notifier.cancelOnUnbind = true
        notifier.bind(erased: viewModel.stableDownloads)
        self.notifier = notifier

        listTask = Task { @MainActor [weak self] in
            guard let self = self else { return }
            do {
                for try await delta in self.viewModel.downloads {
                    if Task.isCancelled { break }
                    if let d = delta as? DemoCore.Delta<DownloadVm> {
                        self.items = d.loadedItems().compactMap { $0 as? DownloadVm }
                    } else if let d = delta as? DeltaListCore.Delta<AnyObject> {
                        self.items = d.loadedItems().compactMap { $0 as? DownloadVm }
                    }
                }
            } catch {}
        }

        tickTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 800_000_000)
                self?.viewModel.tickAll()
            }
        }
    }

    func stop() {
        notifier?.unbind()
        notifier = nil
        listTask?.cancel(); listTask = nil
        tickTask?.cancel(); tickTask = nil
    }

    func add() { viewModel.add() }
    func tick() { viewModel.tickAll() }
    func clear() { viewModel.clear() }
}
