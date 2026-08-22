import SwiftUI
import DemoCore
import DeltaListCore

/// Basic list demo (macOS): ticking items with lazy per-row state, in both SwiftUI and AppKit.
struct BasicListMacView: View {
    private let viewModel = ListViewModel()
    @StateObject private var list = DeltaListCore.DeltaList<DemoCore.StableItem>()

    @State private var mode = 0
    @State private var selectedId: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            RenderModePicker(selection: $mode)

            if mode == 0 {
                BasicListSwiftUIContent(viewModel: viewModel, items: list.loadedItems, selectedId: $selectedId)
            } else {
                AppKitControllerView { BasicListCollectionController(viewModel: viewModel) }
            }
        }
        .navigationTitle("Basic List")
        .task { await list.collect(viewModel.tickingItems) }
    }
}

private struct BasicListSwiftUIContent: View {
    let viewModel: ListViewModel
    let items: [DemoCore.StableItem]
    @Binding var selectedId: String?

    private var selectedIndex: Int? {
        items.firstIndex { ($0.value as? TickingItem)?.item.id == selectedId }
    }

    var body: some View {
        VStack(spacing: 0) {
            List {
                ForEach(items, id: \.stableId) { stableItem in
                    if let ticking = stableItem.value as? TickingItem {
                        TickingItemRow(
                            stableId: stableItem.stableId,
                            tickingItem: ticking,
                            isSelected: ticking.item.id == selectedId
                        )
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selectedId = (selectedId == ticking.item.id) ? nil : ticking.item.id
                        }
                    }
                }
            }
            .listStyle(.inset)

            Divider()

            ControlButtons(viewModel: viewModel, selectedIndex: selectedIndex, onClearSelection: { selectedId = nil })
                .padding()
        }
    }
}

private struct TickingItemRow: View {
    let stableId: Int32
    let tickingItem: TickingItem
    let isSelected: Bool

    @DeltaListCore.ItemState var tickCount: DemoCore.KotlinInt

    init(stableId: Int32, tickingItem: TickingItem, isSelected: Bool) {
        self.stableId = stableId
        self.tickingItem = tickingItem
        self.isSelected = isSelected
        _tickCount = DeltaListCore.ItemState(wrappedValue: DemoCore.KotlinInt(int: 0), tickingItem.tickCount)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(tickingItem.item.title).font(.body)
            Text("Ticks: \(tickCount.intValue) | StableId: \(stableId)")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Color.accentColor.opacity(0.2) : Color.clear)
        .onDisappear { $tickCount.pause() }
        .onAppear { $tickCount.resume() }
    }
}

private struct ControlButtons: View {
    let viewModel: ListViewModel
    let selectedIndex: Int?
    let onClearSelection: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button("Add") { viewModel.addItem() }
                Button("Batch Add") { viewModel.batchAdd() }
                Button("Clear") { viewModel.clear(); onClearSelection() }
            }
            if let index = selectedIndex {
                HStack {
                    Button("Insert Before") { viewModel.insertBefore(index: Int32(index)) }
                    Button("Insert After") { viewModel.insertAfter(index: Int32(index)) }
                    Button("Remove") { viewModel.removeItem(index: Int32(index)); onClearSelection() }
                        .tint(.red)
                }
            }
        }
        .buttonStyle(.bordered)
    }
}
