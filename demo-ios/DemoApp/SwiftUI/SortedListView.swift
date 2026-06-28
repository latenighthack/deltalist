import SwiftUI
import DemoCore
import DeltaListCore

/// Sorted list demo: an unordered set of profiles rendered as a 4-column grid, sorted
/// alphabetically by full name. Offers both a SwiftUI (LazyVGrid) and a UICollectionView rendering.
struct SortedListView: View {
    // Kotlin ViewModel directly - no adapter needed.
    private let viewModel = SortedListViewModel()

    // Drives the SwiftUI grid; collection is scoped to the view via `.task` below.
    @StateObject private var list = DeltaListCore.DeltaList<DemoCore.Profile>()

    @State private var selectedTab = 0

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 4)

    var body: some View {
        VStack(spacing: 0) {
            Picker("View Type", selection: $selectedTab) {
                Text("SwiftUI").tag(0)
                Text("UICollectionView").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            if selectedTab == 0 {
                SortedListSwiftUIContent(viewModel: viewModel, profiles: list.loadedItems, columns: columns)
            } else {
                SortedListUIKitContent(viewModel: viewModel)
            }

            Button("Add") {
                viewModel.addRandom()
            }
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity)
            .padding()
        }
        .navigationTitle("Sorted List")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await list.collect(viewModel.profiles)
        }
    }
}

// MARK: - SwiftUI Content

private struct SortedListSwiftUIContent: View {
    let viewModel: SortedListViewModel
    let profiles: [DemoCore.Profile]
    let columns: [GridItem]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(profiles, id: \.id) { profile in
                    VStack(spacing: 2) {
                        Text(profile.firstName)
                            .font(.body)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(profile.lastName)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    .frame(maxWidth: .infinity)
                    .aspectRatio(1, contentMode: .fit)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(12)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        viewModel.remove(profile: profile)
                    }
                }
            }
            .padding()
        }
    }
}

// MARK: - UIKit Content

private struct SortedListUIKitContent: View {
    let viewModel: SortedListViewModel

    var body: some View {
        SortedListCollectionViewWrapper(viewModel: viewModel)
            .ignoresSafeArea(edges: .bottom)
    }
}

private struct SortedListCollectionViewWrapper: UIViewControllerRepresentable {
    let viewModel: SortedListViewModel

    func makeUIViewController(context: Context) -> SortedListViewController {
        SortedListViewController(viewModel: viewModel)
    }

    func updateUIViewController(_ uiViewController: SortedListViewController, context: Context) {}
}

#Preview {
    NavigationStack {
        SortedListView()
    }
}
