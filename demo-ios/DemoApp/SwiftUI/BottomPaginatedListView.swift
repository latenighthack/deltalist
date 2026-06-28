import SwiftUI
import DemoCore
import DeltaListCore

/// Bottom-anchored ("chat-style") paginated demo: starts scrolled to the bottom, only the bottom
/// items load first, scrolling up loads older pages, and buttons add an item at the top (index 0)
/// and at the bottom (index n).
struct BottomPaginatedListView: View {
    @StateObject private var viewModel = BottomPaginatedListViewModelAdapter()
    // Consolidated DeltaListCore wrapper handles the soft list (per-item placeholders + lazy paging).
    @StateObject private var list = DeltaListCore.DeltaList<DemoCore.KotlinInt>()
    @State private var selectedTab = 0
    // Bumped on "add at bottom" so the visible content scrolls to reveal the appended row.
    @State private var scrollToBottomToken = 0

    var body: some View {
        VStack(spacing: 0) {
            Picker("View Type", selection: $selectedTab) {
                Text("SwiftUI").tag(0)
                Text("UICollectionView").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            BottomAddButtonsBar(
                onAddTop: { viewModel.addAtTop() },
                onAddBottom: {
                    viewModel.addAtBottom()
                    scrollToBottomToken += 1
                }
            )

            if selectedTab == 0 {
                BottomPaginatedSwiftUIContent(viewModel: viewModel, list: list, scrollToBottomToken: scrollToBottomToken)
            } else {
                BottomPaginatedUIKitContent(viewModel: viewModel, list: list, scrollToBottomToken: scrollToBottomToken)
            }
        }
        .navigationTitle("Bottom Paginated")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await list.collect(viewModel.viewModel.messages)
        }
    }
}

// MARK: - SwiftUI Content

private struct BottomPaginatedSwiftUIContent: View {
    @ObservedObject var viewModel: BottomPaginatedListViewModelAdapter
    @ObservedObject var list: DeltaListCore.DeltaList<DemoCore.KotlinInt>
    let scrollToBottomToken: Int

    @State private var didInitialScroll = false

    var body: some View {
        VStack(spacing: 0) {
            BottomPaginatedStatusBar(viewModel: viewModel, list: list)

            ScrollViewReader { proxy in
                List {
                    // Show totalSize rows (per-item skeleton placeholders for unloaded slots).
                    ForEach(0..<list.totalSize, id: \.self) { index in
                        if let number = list.loadedItem(at: index) {
                            BottomNumberRow(number: Int(number.intValue), index: index)
                        } else {
                            SkeletonRow()
                                .onAppear { list.triggerLoad(at: index) }
                        }
                    }
                }
                .listStyle(.plain)
                // Anchor at the bottom once the estimated size is known (skeletons show at the
                // bottom and fill in there). Runs once.
                .onChange(of: list.totalSize) { newSize in
                    if !didInitialScroll && newSize > 1 {
                        didInitialScroll = true
                        proxy.scrollTo(newSize - 1, anchor: .bottom)
                    }
                }
                // Reveal an appended (add-at-bottom) row.
                .onChange(of: scrollToBottomToken) { _ in
                    if list.totalSize > 0 {
                        withAnimation { proxy.scrollTo(list.totalSize - 1, anchor: .bottom) }
                    }
                }
            }

            DivisorFilterBar(viewModel: viewModel)
        }
    }
}

// MARK: - Add Buttons Bar

private struct BottomAddButtonsBar: View {
    let onAddTop: () -> Void
    let onAddBottom: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onAddTop) {
                Text("Add at top (0)").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button(action: onAddBottom) {
                Text("Add at bottom (n)").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }
}

// MARK: - Status Bar

private struct BottomPaginatedStatusBar: View {
    @ObservedObject var viewModel: BottomPaginatedListViewModelAdapter
    @ObservedObject var list: DeltaListCore.DeltaList<DemoCore.KotlinInt>

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Bottom Paginated (scroll up for older)")
                    .font(.headline)

                Text("Loaded: \(viewModel.loadedCount) / Visible rows: \(list.totalSize)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            if viewModel.loadingDirection != nil {
                ProgressView()
            }
        }
        .padding()
        .background(Color(.systemBackground))
    }
}

// MARK: - Number Row

private struct BottomNumberRow: View {
    let number: Int
    let index: Int

    var body: some View {
        HStack {
            // Manually-added items use negative values so they never collide with paginated data.
            if number < 0 {
                Text("Added #\(-number)")
                    .font(.title2)
                    .fontWeight(.medium)
                    .italic()
                    .foregroundColor(.accentColor)
            } else {
                Text("#\(number)")
                    .font(.title2)
                    .fontWeight(.medium)
            }

            Spacer()

            Text("index: \(index)")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 8)
    }
}

// MARK: - Skeleton Row

/// A not-yet-loaded slot rendered as a skeleton item (no spinner, no text).
private struct SkeletonRow: View {
    var body: some View {
        HStack {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color(.systemGray5))
                .frame(width: 120, height: 22)

            Spacer()
        }
        .padding(.vertical, 8)
    }
}

// MARK: - Divisor Filter Bar

private struct DivisorFilterBar: View {
    @ObservedObject var viewModel: BottomPaginatedListViewModelAdapter

    private let divisors = [2, 3, 5, 7, 11]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Exclude numbers divisible by:")
                .font(.caption)
                .foregroundColor(.secondary)

            HStack {
                ForEach(divisors, id: \.self) { divisor in
                    Toggle(isOn: Binding(
                        get: { viewModel.excludeDivisors.contains(divisor) },
                        set: { _ in viewModel.toggleDivisorFilter(divisor) }
                    )) {
                        Text("\(divisor)")
                    }
                    .toggleStyle(.button)
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding()
        .background(Color(.systemBackground))
    }
}

// MARK: - UIKit Content

private struct BottomPaginatedUIKitContent: View {
    @ObservedObject var viewModel: BottomPaginatedListViewModelAdapter
    @ObservedObject var list: DeltaListCore.DeltaList<DemoCore.KotlinInt>
    let scrollToBottomToken: Int

    var body: some View {
        VStack(spacing: 0) {
            BottomPaginatedStatusBar(viewModel: viewModel, list: list)

            BottomPaginatedListViewControllerRepresentable(
                viewModel: viewModel,
                scrollToBottomToken: scrollToBottomToken
            )

            DivisorFilterBar(viewModel: viewModel)
        }
    }
}

// MARK: - UIViewControllerRepresentable

private struct BottomPaginatedListViewControllerRepresentable: UIViewControllerRepresentable {
    let viewModel: BottomPaginatedListViewModelAdapter
    let scrollToBottomToken: Int

    func makeUIViewController(context: Context) -> BottomPaginatedListViewController {
        BottomPaginatedListViewController(viewModel: viewModel.viewModel)
    }

    func updateUIViewController(_ uiViewController: BottomPaginatedListViewController, context: Context) {
        // Reveal an appended (add-at-bottom) row when the token changes.
        if context.coordinator.lastToken != scrollToBottomToken {
            context.coordinator.lastToken = scrollToBottomToken
            if scrollToBottomToken > 0 {
                uiViewController.scrollToBottom(animated: true)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var lastToken = 0
    }
}

#Preview {
    NavigationStack {
        BottomPaginatedListView()
    }
}
