import SwiftUI

/// Basic list demo screen with ticking items.
/// Shows both SwiftUI and UIKit implementations via tab bar.
struct BasicListView: View {
    @StateObject private var viewModel = ListViewModelAdapter()
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
                    selectedId: $selectedId
                )
            } else {
                BasicListUIKitContent(viewModel: viewModel)
            }
        }
        .navigationTitle("Basic List")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - SwiftUI Content

private struct BasicListSwiftUIContent: View {
    @ObservedObject var viewModel: ListViewModelAdapter
    @Binding var selectedId: String?

    private var selectedIndex: Int? {
        viewModel.tickingItems.firstIndex { $0.item.id == selectedId }
    }

    var body: some View {
        VStack(spacing: 0) {
            List {
                ForEach(viewModel.tickingItems) { stableItem in
                    TickingItemRow(
                        tickingItem: stableItem,
                        isSelected: stableItem.item.id == selectedId
                    )
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if selectedId == stableItem.item.id {
                            selectedId = nil
                        } else {
                            selectedId = stableItem.item.id
                        }
                    }
                    .onDisappear {
                        // Release lazy item when it scrolls out of view
                        stableItem.stop()
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

// MARK: - Ticking Item Row

private struct TickingItemRow: View {
    @ObservedObject var tickingItem: StableTickingItemWrapper
    let isSelected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(tickingItem.item.title)
                .font(.body)

            Text("Ticks: \(tickingItem.tickCount) | StableId: \(tickingItem.stableId)")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Color.blue.opacity(0.2) : Color.clear)
    }
}

// MARK: - Control Buttons

private struct ListControlButtons: View {
    @ObservedObject var viewModel: ListViewModelAdapter
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
                        viewModel.insertBefore(index)
                    }
                    .buttonStyle(.bordered)

                    Button("Insert After") {
                        viewModel.insertAfter(index)
                    }
                    .buttonStyle(.bordered)

                    Button("Remove") {
                        viewModel.removeItem(at: index)
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

// MARK: - UIKit Content (Placeholder)

private struct BasicListUIKitContent: View {
    @ObservedObject var viewModel: ListViewModelAdapter

    var body: some View {
        VStack {
            // UICollectionView wrapped in UIViewControllerRepresentable
            BasicListCollectionViewController(viewModel: viewModel)
                .ignoresSafeArea(edges: .bottom)
        }
    }
}

// MARK: - UIKit Collection View Controller

private struct BasicListCollectionViewController: UIViewControllerRepresentable {
    @ObservedObject var viewModel: ListViewModelAdapter

    func makeUIViewController(context: Context) -> BasicListViewController {
        BasicListViewController(viewModel: viewModel)
    }

    func updateUIViewController(_ uiViewController: BasicListViewController, context: Context) {
        uiViewController.updateItems(viewModel.tickingItems)
    }
}

#Preview {
    NavigationStack {
        BasicListView()
    }
}
