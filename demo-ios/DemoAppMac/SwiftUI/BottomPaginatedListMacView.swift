import SwiftUI
import DemoCore
import DeltaListCore

/// Bottom-paginated / chat-style demo (macOS): loads the bottom page first, scroll up for older,
/// add at top/bottom. Both SwiftUI and AppKit.
struct BottomPaginatedListMacView: View {
    @StateObject private var viewModel = BottomPaginatedListViewModelAdapter()
    @StateObject private var list = DeltaListCore.DeltaList<DemoCore.KotlinInt>()
    @State private var mode = 0
    @State private var scrollToBottomToken = 0

    var body: some View {
        VStack(spacing: 0) {
            RenderModePicker(selection: $mode)

            HStack(spacing: 12) {
                Button("Add at top (0)") { viewModel.addAtTop() }.frame(maxWidth: .infinity)
                Button("Add at bottom (n)") { viewModel.addAtBottom(); scrollToBottomToken += 1 }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .padding(.horizontal)
            .padding(.bottom, 8)

            BottomStatusBar(viewModel: viewModel, list: list)

            if mode == 0 {
                ScrollViewReader { proxy in
                    List {
                        ForEach(0..<list.totalSize, id: \.self) { index in
                            if let number = list.loadedItem(at: index) {
                                BottomNumberRow(number: Int(number.intValue), index: index)
                            } else {
                                SkeletonRow().onAppear { list.triggerLoad(at: index) }
                            }
                        }
                    }
                    .listStyle(.inset)
                    .onChange(of: scrollToBottomToken) { _ in
                        if list.totalSize > 0 { withAnimation { proxy.scrollTo(list.totalSize - 1, anchor: .bottom) } }
                    }
                }
            } else {
                AppKitControllerView(
                    make: { BottomPaginatedListCollectionController(viewModel: viewModel.viewModel) },
                    update: { controller in if scrollToBottomToken > 0 { controller.scrollToBottom() } }
                )
            }

            Divider()
            DivisorFilterBar(excluded: viewModel.excludeDivisors) { viewModel.toggleDivisorFilter($0) }
        }
        .navigationTitle("Bottom Paginated")
        .task { await list.collect(viewModel.viewModel.messages) }
    }
}

private struct BottomStatusBar: View {
    @ObservedObject var viewModel: BottomPaginatedListViewModelAdapter
    @ObservedObject var list: DeltaListCore.DeltaList<DemoCore.KotlinInt>

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Bottom Paginated (scroll up for older)").font(.headline)
                Text("Loaded: \(viewModel.loadedCount) / Visible rows: \(list.totalSize)")
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            if viewModel.loadingDirection != nil { ProgressView().controlSize(.small) }
        }
        .padding()
        .background(Color.barBackground)
    }
}

private struct BottomNumberRow: View {
    let number: Int
    let index: Int
    var body: some View {
        HStack {
            if number < 0 {
                Text("Added #\(-number)").font(.title3).fontWeight(.medium).italic().foregroundColor(.accentColor)
            } else {
                Text("#\(number)").font(.title3).fontWeight(.medium)
            }
            Spacer()
            Text("index: \(index)").font(.caption).foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }
}
