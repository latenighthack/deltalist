import SwiftUI

/// Drag and drop demo screen with reorderable items.
struct DragDropView: View {
    @StateObject private var viewModel = DragDropViewModelAdapter()
    @State private var selectedTab = 0

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
                DragDropSwiftUIContent(viewModel: viewModel)
            } else {
                DragDropUIKitContent(viewModel: viewModel)
            }
        }
        .navigationTitle("Drag & Drop")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - SwiftUI Content

private struct DragDropSwiftUIContent: View {
    @ObservedObject var viewModel: DragDropViewModelAdapter

    var body: some View {
        VStack(spacing: 0) {
            // Drag status bar
            DragStatusBar(dragState: viewModel.dragState)

            // Reorderable list
            List {
                ForEach(viewModel.items) { item in
                    DraggableItemRow(
                        item: item,
                        canMove: viewModel.canMove(item: item)
                    )
                }
                .onMove { source, destination in
                    viewModel.moveItem(from: source, to: destination)
                }
            }
            .listStyle(.plain)
            .environment(\.editMode, .constant(.active))

            // Control buttons
            DragDropControlButtons(viewModel: viewModel)
        }
    }
}

// MARK: - Drag Status Bar

private struct DragStatusBar: View {
    let dragState: DragStateWrapper

    var body: some View {
        HStack {
            switch dragState {
            case .idle:
                Text("Drag items to reorder")
                    .foregroundColor(.secondary)

            case .dragging(let item, let fromIndex, let previewIndex):
                Text("Dragging: \(item.title) (\(fromIndex) → \(previewIndex))")
                    .foregroundColor(.primary)

            case .committing(let item, let fromIndex, let toIndex):
                ProgressView()
                    .padding(.trailing, 8)
                Text("Saving: \(item.title) (\(fromIndex) → \(toIndex))")
                    .foregroundColor(.primary)
            }

            Spacer()
        }
        .padding()
        .background(backgroundColor)
    }

    private var backgroundColor: Color {
        switch dragState {
        case .idle:
            return Color(.systemBackground)
        case .dragging:
            return Color.blue.opacity(0.1)
        case .committing:
            return Color.orange.opacity(0.1)
        }
    }
}

// MARK: - Draggable Item Row

private struct DraggableItemRow: View {
    let item: ItemWrapper
    let canMove: Bool

    var body: some View {
        HStack {
            if canMove {
                Image(systemName: "line.3.horizontal")
                    .foregroundColor(.secondary)
                    .padding(.trailing, 8)
            } else {
                Spacer()
                    .frame(width: 32)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.body)

                Text(canMove ? "Drag to reorder" : "Cannot be moved")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()
        }
        .padding(.vertical, 8)
        .background(canMove ? Color.clear : Color.red.opacity(0.1))
        .moveDisabled(!canMove)
    }
}

// MARK: - Control Buttons

private struct DragDropControlButtons: View {
    @ObservedObject var viewModel: DragDropViewModelAdapter

    var body: some View {
        HStack {
            Button("Add") {
                viewModel.addItem()
            }
            .buttonStyle(.bordered)

            Button("Add Pinned") {
                viewModel.addPinnedItem()
            }
            .buttonStyle(.bordered)

            Button("Clear") {
                viewModel.clear()
            }
            .buttonStyle(.bordered)

            Button("Reset") {
                viewModel.reset()
            }
            .buttonStyle(.bordered)
        }
        .padding()
        .background(Color(.systemBackground))
    }
}

// MARK: - UIKit Content (Placeholder)

private struct DragDropUIKitContent: View {
    @ObservedObject var viewModel: DragDropViewModelAdapter

    var body: some View {
        VStack(spacing: 0) {
            DragStatusBar(dragState: viewModel.dragState)

            Text("UICollectionView implementation with drag delegates")
                .foregroundColor(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            DragDropControlButtons(viewModel: viewModel)
        }
    }
}

#Preview {
    NavigationStack {
        DragDropView()
    }
}
