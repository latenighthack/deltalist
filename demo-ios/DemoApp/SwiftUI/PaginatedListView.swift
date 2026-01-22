import SwiftUI

/// Paginated list demo screen with 10K items and dynamic filtering.
struct PaginatedListView: View {
    @StateObject private var viewModel = PaginatedListViewModelAdapter()
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
                PaginatedSwiftUIContent(viewModel: viewModel)
            } else {
                PaginatedUIKitContent(viewModel: viewModel)
            }
        }
        .navigationTitle("Paginated List")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - SwiftUI Content

private struct PaginatedSwiftUIContent: View {
    @ObservedObject var viewModel: PaginatedListViewModelAdapter

    var body: some View {
        VStack(spacing: 0) {
            // Status bar
            PaginatedStatusBar(viewModel: viewModel)

            // List
            List {
                ForEach(Array(viewModel.numbers.enumerated()), id: \.offset) { index, number in
                    NumberItemRow(number: number, index: index)
                }

                // Load more trigger - pagination is handled by the Kotlin ViewModel
                if viewModel.loadedCount < 10_000 {
                    LoadingRow()
                }
            }
            .listStyle(.plain)

            // Filter bar
            DivisorFilterBar(viewModel: viewModel)
        }
    }
}

// MARK: - Status Bar

private struct PaginatedStatusBar: View {
    @ObservedObject var viewModel: PaginatedListViewModelAdapter

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Paginated List (10,000 items)")
                    .font(.headline)

                Text("Loaded: \(viewModel.loadedCount) / Filtered: \(viewModel.numbers.count) / Total: 10,000")
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

// MARK: - Number Item Row

private struct NumberItemRow: View {
    let number: Int
    let index: Int

    var body: some View {
        HStack {
            Text("#\(number)")
                .font(.title2)
                .fontWeight(.medium)

            Spacer()

            Text("index: \(index)")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 8)
    }
}

// MARK: - Loading Row

private struct LoadingRow: View {
    var body: some View {
        HStack {
            ProgressView()
                .padding(.trailing, 8)

            Text("Loading...")
                .foregroundColor(.secondary)

            Spacer()
        }
        .padding(.vertical, 8)
    }
}

// MARK: - Divisor Filter Bar

private struct DivisorFilterBar: View {
    @ObservedObject var viewModel: PaginatedListViewModelAdapter

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

// MARK: - UIKit Content (Placeholder)

private struct PaginatedUIKitContent: View {
    @ObservedObject var viewModel: PaginatedListViewModelAdapter

    var body: some View {
        VStack(spacing: 0) {
            PaginatedStatusBar(viewModel: viewModel)

            // UICollectionView would go here
            Text("UICollectionView implementation")
                .foregroundColor(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            DivisorFilterBar(viewModel: viewModel)
        }
    }
}

#Preview {
    NavigationStack {
        PaginatedListView()
    }
}
