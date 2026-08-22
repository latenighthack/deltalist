import SwiftUI
import DemoCore
import DeltaListCore

/// Paginated demo (macOS): a large soft/paginated list with dynamic divisor filtering, in both
/// SwiftUI and AppKit.
struct PaginatedListMacView: View {
    @StateObject private var viewModel = PaginatedListViewModelAdapter()
    @StateObject private var list = DeltaListCore.DeltaList<DemoCore.KotlinInt>()
    @State private var mode = 0

    var body: some View {
        VStack(spacing: 0) {
            RenderModePicker(selection: $mode)
            PaginatedStatusBar(viewModel: viewModel, list: list)

            if mode == 0 {
                List {
                    ForEach(0..<list.totalSize, id: \.self) { index in
                        if let number = list.loadedItem(at: index) {
                            NumberRow(number: Int(number.intValue), index: index)
                        } else {
                            SkeletonRow().onAppear { list.triggerLoad(at: index) }
                        }
                    }
                }
                .listStyle(.inset)
            } else {
                AppKitControllerView { PaginatedListCollectionController(viewModel: viewModel.viewModel) }
            }

            Divider()
            DivisorFilterBar(excluded: viewModel.excludeDivisors) { viewModel.toggleDivisorFilter($0) }
        }
        .navigationTitle("Paginated List")
        .task { await list.collect(viewModel.viewModel.paginatedNumbers) }
    }
}

struct PaginatedStatusBar: View {
    @ObservedObject var viewModel: PaginatedListViewModelAdapter
    @ObservedObject var list: DeltaListCore.DeltaList<DemoCore.KotlinInt>

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Paginated List").font(.headline)
                Text("Loaded: \(viewModel.loadedCount) / Filtered: \(list.loadedItems.count) / Total: \(list.totalSize)")
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            if viewModel.loadingDirection != nil { ProgressView().controlSize(.small) }
        }
        .padding()
        .background(Color.barBackground)
    }
}

struct NumberRow: View {
    let number: Int
    let index: Int
    var body: some View {
        HStack {
            Text("#\(number)").font(.title3).fontWeight(.medium)
            Spacer()
            Text("index: \(index)").font(.caption).foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }
}

struct SkeletonRow: View {
    var body: some View {
        HStack {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color(nsColor: .separatorColor))
                .frame(width: 120, height: 20)
            Spacer()
        }
        .padding(.vertical, 6)
    }
}

struct DivisorFilterBar: View {
    let excluded: Set<Int>
    let onToggle: (Int) -> Void
    private let divisors = [2, 3, 5, 7, 11]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Exclude numbers divisible by:").font(.caption).foregroundColor(.secondary)
            HStack {
                ForEach(divisors, id: \.self) { divisor in
                    Toggle(isOn: Binding(
                        get: { excluded.contains(divisor) },
                        set: { _ in onToggle(divisor) }
                    )) { Text("\(divisor)") }
                    .toggleStyle(.button)
                }
            }
        }
        .padding()
        .background(Color.barBackground)
    }
}
