import SwiftUI
import DemoCore
import DeltaListCore

/// Sorted demo (macOS): an unordered set of profiles projected into an alphabetically sorted
/// 4-column grid, in both SwiftUI and AppKit.
struct SortedListMacView: View {
    private let viewModel = SortedListViewModel()
    @StateObject private var list = DeltaListCore.DeltaList<DemoCore.Profile>()
    @State private var mode = 0

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 4)

    var body: some View {
        VStack(spacing: 0) {
            RenderModePicker(selection: $mode)

            if mode == 0 {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(list.loadedItems, id: \.id) { profile in
                            VStack(spacing: 2) {
                                Text(profile.firstName).font(.body).lineLimit(1).minimumScaleFactor(0.7)
                                Text(profile.lastName).font(.caption).foregroundColor(.secondary)
                                    .lineLimit(1).minimumScaleFactor(0.7)
                            }
                            .frame(maxWidth: .infinity)
                            .aspectRatio(1, contentMode: .fit)
                            .background(Color.tileBackground)
                            .cornerRadius(12)
                            .contentShape(Rectangle())
                            .onTapGesture { viewModel.remove(profile: profile) }
                        }
                    }
                    .padding()
                }
            } else {
                AppKitControllerView { SortedListCollectionController(viewModel: viewModel) }
            }

            Divider()
            Button("Add") { viewModel.addRandom() }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .padding()
        }
        .navigationTitle("Sorted List")
        .task { await list.collect(viewModel.profiles) }
    }
}
