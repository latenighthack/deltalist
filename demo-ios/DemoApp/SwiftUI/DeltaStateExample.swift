import SwiftUI
import DemoCore
import DeltaListCore

/// Example demonstrating @DeltaState with Kotlin types directly.
/// This shows the simplified API enabled by the Swift utilities bundled in DeltaListCore.
///
/// Before (with adapter):
///   @StateObject private var viewModel = ListViewModelAdapter()
///   ... viewModel.items ...
///
/// After (with @DeltaState):
///   @DeltaState var delta: Delta<Item>
///   ... delta.items ...
@available(iOS 14.0, *)
struct DeltaStateExampleView: View {
    let viewModel = ListViewModel()

    @State private var items: [Item] = []
    @StateObject private var observer = ItemListObserver()

    var body: some View {
        VStack(spacing: 0) {
            Text("@DeltaState Example")
                .font(.headline)
                .padding()

            List {
                ForEach(items, id: \.id) { item in
                    VStack(alignment: .leading) {
                        Text(item.title)
                            .font(.body)
                        Text("ID: \(item.id)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .listStyle(.plain)

            HStack {
                Button("Add Item") {
                    viewModel.addItem()
                }
                .buttonStyle(.bordered)

                Button("Clear") {
                    viewModel.clear()
                }
                .buttonStyle(.bordered)
            }
            .padding()
        }
        .onAppear {
            observer.start(from: viewModel.items) { [self] newItems in
                self.items = newItems
            }
        }
        .onDisappear {
            observer.stop()
        }
    }
}

/// Simple observer that wraps DeltaList collection.
@MainActor
class ItemListObserver: ObservableObject {
    private var task: Task<Void, Never>?

    func start(from flow: some AsyncSequence, onUpdate: @escaping @MainActor ([Item]) -> Void) {
        task?.cancel()
        task = Task { @MainActor in
            do {
                for try await delta in flow {
                    if Task.isCancelled { break }
                    // The delta is a Kotlin Delta<Item> type
                    if let d = delta as? Delta<Item> {
                        let items = d.items.compactMap { $0 as? Item }
                        onUpdate(items)
                    }
                }
            } catch {}
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }
}

#Preview {
    DeltaStateExampleView()
}
