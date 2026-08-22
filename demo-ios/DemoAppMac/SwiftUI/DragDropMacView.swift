import SwiftUI
import DemoCore

/// Drag & drop demo (macOS): reorderable items with a live drag-state bar and pinned (immovable)
/// items, in both SwiftUI and AppKit.
struct DragDropMacView: View {
    @StateObject private var viewModel = DragDropViewModelAdapter()
    @State private var mode = 0

    var body: some View {
        VStack(spacing: 0) {
            RenderModePicker(selection: $mode)
            DragStatusBar(dragState: viewModel.dragState)

            if mode == 0 {
                List {
                    ForEach(viewModel.items) { item in
                        DraggableRow(item: item, canMove: viewModel.canMove(item: item))
                            .moveDisabled(!viewModel.canMove(item: item))
                    }
                    .onMove { source, destination in viewModel.moveItem(from: source, to: destination) }
                }
                .listStyle(.inset)
            } else {
                AppKitControllerView { DragDropCollectionController(viewModel: viewModel.viewModel) }
            }

            Divider()
            HStack {
                Button("Add") { viewModel.addItem() }
                Button("Add Pinned") { viewModel.addPinnedItem() }
                Button("Clear") { viewModel.clear() }
                Button("Reset") { viewModel.reset() }
            }
            .buttonStyle(.bordered)
            .padding()
        }
        .navigationTitle("Drag & Drop")
    }
}

private struct DragStatusBar: View {
    let dragState: DragStateWrapper

    var body: some View {
        HStack {
            switch dragState {
            case .idle:
                Text("Drag items to reorder").foregroundColor(.secondary)
            case .dragging(let item, let fromIndex, let previewIndex):
                Text("Dragging: \(item.title) (\(fromIndex) → \(previewIndex))")
            case .committing(let item, let fromIndex, let toIndex):
                ProgressView().controlSize(.small).padding(.trailing, 8)
                Text("Saving: \(item.title) (\(fromIndex) → \(toIndex))")
            }
            Spacer()
        }
        .padding()
        .background(background)
    }

    private var background: Color {
        switch dragState {
        case .idle: return Color.barBackground
        case .dragging: return Color.accentColor.opacity(0.1)
        case .committing: return Color.orange.opacity(0.1)
        }
    }
}

private struct DraggableRow: View {
    let item: ItemWrapper
    let canMove: Bool

    var body: some View {
        HStack {
            Image(systemName: canMove ? "line.3.horizontal" : "lock.fill")
                .foregroundColor(.secondary)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(.body)
                Text(canMove ? "Drag to reorder" : "Cannot be moved")
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 4)
        .background(canMove ? Color.clear : Color.red.opacity(0.08))
    }
}
