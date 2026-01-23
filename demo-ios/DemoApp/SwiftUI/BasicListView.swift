import SwiftUI
import DemoCore
import DeltaListCore

/// Basic list demo screen with ticking items.
/// Uses DeltaListCore utilities (@ItemState for per-item observation) and a simple observer for the list.
struct BasicListView: View {
    // Use Kotlin ViewModel directly - no adapter needed!
    private let viewModel = ListViewModel()

    // Simple observer using DeltaListCore patterns
    @StateObject private var tickingItemsObserver = TickingItemsObserver()

    @State private var selectedTab = 0
    @State private var selectedId: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            // Tab selector
            Picker("View Type", selection: $selectedTab) {
                Text("SwiftUI").tag(0)
                Text("UICollectionView").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            // Content
            if selectedTab == 0 {
                BasicListSwiftUIContent(
                    viewModel: viewModel,
                    items: tickingItemsObserver.items,
                    selectedId: $selectedId
                )
            } else {
                BasicListUIKitContent(
                    viewModel: viewModel,
                    items: tickingItemsObserver.items
                )
            }
        }
        .navigationTitle("Basic List")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            tickingItemsObserver.bind(to: viewModel.tickingItems)
        }
        .onDisappear {
            tickingItemsObserver.unbind()
        }
    }
}

// MARK: - Ticking Items Observer

/// Simple observer for the list items.
/// This is a minimal wrapper that handles flow collection using DeltaListCore patterns.
@MainActor
class TickingItemsObserver: ObservableObject {
    @Published private(set) var items: [DemoCore.StableItem] = []

    private var task: Task<Void, Never>?

    func bind(to flow: some AsyncSequence) {
        unbind()
        task = Task { @MainActor [weak self] in
            do {
                for try await delta in flow {
                    if Task.isCancelled { break }
                    guard let self = self else { break }
                    // Delta<StableItem<TickingItem>> - extract items using runtime casting
                    if let d = delta as? DemoCore.Delta<DemoCore.StableItem> {
                        self.items = d.items.compactMap { $0 as? DemoCore.StableItem }
                    } else if let d = delta as? DeltaListCore.Delta<AnyObject> {
                        self.items = d.items.compactMap { $0 as? DemoCore.StableItem }
                    }
                }
            } catch {}
        }
    }

    func unbind() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }
}

// MARK: - SwiftUI Content

private struct BasicListSwiftUIContent: View {
    let viewModel: ListViewModel
    let items: [DemoCore.StableItem]
    @Binding var selectedId: String?

    private var selectedIndex: Int? {
        items.firstIndex { item in
            guard let tickingItem = item.value as? TickingItem else { return false }
            return tickingItem.item.id == selectedId
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            List {
                ForEach(items, id: \.stableId) { stableItem in
                    if let tickingItem = stableItem.value as? TickingItem {
                        // Use @ItemState in the row for automatic tick observation
                        TickingItemRow(
                            stableId: stableItem.stableId,
                            tickingItem: tickingItem,
                            isSelected: tickingItem.item.id == selectedId
                        )
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if selectedId == tickingItem.item.id {
                                selectedId = nil
                            } else {
                                selectedId = tickingItem.item.id
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)

            // Control buttons
            ListControlButtons(
                viewModel: viewModel,
                selectedIndex: selectedIndex,
                onClearSelection: { selectedId = nil }
            )
        }
    }
}

// MARK: - Ticking Item Row (Uses @ItemState)

/// Row view that uses @ItemState from DeltaListCore for automatic tick count observation.
/// This replaces the TickingItemWrapper ObservableObject class entirely.
private struct TickingItemRow: View {
    let stableId: Int32
    let tickingItem: TickingItem
    let isSelected: Bool

    // @ItemState from DeltaListCore - automatically observes the Kotlin StateFlow!
    @DeltaListCore.ItemState var tickCount: DemoCore.KotlinInt

    init(stableId: Int32, tickingItem: TickingItem, isSelected: Bool) {
        self.stableId = stableId
        self.tickingItem = tickingItem
        self.isSelected = isSelected
        // Initialize @ItemState with the tickCount StateFlow
        _tickCount = DeltaListCore.ItemState(wrappedValue: DemoCore.KotlinInt(int: 0), tickingItem.tickCount)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(tickingItem.item.title)
                .font(.body)

            Text("Ticks: \(tickCount.intValue) | StableId: \(stableId)")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Color.blue.opacity(0.2) : Color.clear)
        .onDisappear {
            // Pause observation when scrolling off-screen
            $tickCount.pause()
        }
        .onAppear {
            // Resume observation when scrolling back on-screen
            $tickCount.resume()
        }
    }
}

// MARK: - Control Buttons

private struct ListControlButtons: View {
    let viewModel: ListViewModel
    let selectedIndex: Int?
    let onClearSelection: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button("Add") {
                    viewModel.addItem()
                }
                .buttonStyle(.bordered)

                Button("Batch Add") {
                    viewModel.batchAdd()
                }
                .buttonStyle(.bordered)

                Button("Clear") {
                    viewModel.clear()
                    onClearSelection()
                }
                .buttonStyle(.bordered)
            }

            if let index = selectedIndex {
                HStack {
                    Button("Insert Before") {
                        viewModel.insertBefore(index: Int32(index))
                    }
                    .buttonStyle(.bordered)

                    Button("Insert After") {
                        viewModel.insertAfter(index: Int32(index))
                    }
                    .buttonStyle(.bordered)

                    Button("Remove") {
                        viewModel.removeItem(index: Int32(index))
                        onClearSelection()
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                }
            }
        }
        .padding()
        .background(Color(.systemBackground))
    }
}

// MARK: - UIKit Content

private struct BasicListUIKitContent: View {
    let viewModel: ListViewModel
    let items: [DemoCore.StableItem]

    var body: some View {
        VStack {
            BasicListCollectionViewWrapper(viewModel: viewModel, items: items)
                .ignoresSafeArea(edges: .bottom)
        }
    }
}

// MARK: - UIKit Collection View Wrapper

private struct BasicListCollectionViewWrapper: UIViewControllerRepresentable {
    let viewModel: ListViewModel
    let items: [DemoCore.StableItem]

    func makeUIViewController(context: Context) -> BasicListViewController {
        BasicListViewController(viewModel: viewModel)
    }

    func updateUIViewController(_ uiViewController: BasicListViewController, context: Context) {
        uiViewController.updateItems(items)
    }
}

#Preview {
    NavigationStack {
        BasicListView()
    }
}
