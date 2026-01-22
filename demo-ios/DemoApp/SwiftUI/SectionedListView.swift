import SwiftUI

/// Sectioned list demo screen with headers and items.
struct SectionedListView: View {
    @StateObject private var viewModel = SectionedListViewModelAdapter()
    @State private var selectedTab = 0
    @State private var selectedSectionIndex: Int? = nil
    @State private var selectedItemIndex: Int? = nil

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
                SectionedSwiftUIContent(
                    viewModel: viewModel,
                    selectedSectionIndex: $selectedSectionIndex,
                    selectedItemIndex: $selectedItemIndex
                )
            } else {
                SectionedUIKitContent(viewModel: viewModel)
            }
        }
        .navigationTitle("Sectioned List")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - SwiftUI Content

private struct SectionedSwiftUIContent: View {
    @ObservedObject var viewModel: SectionedListViewModelAdapter
    @Binding var selectedSectionIndex: Int?
    @Binding var selectedItemIndex: Int?

    var body: some View {
        VStack(spacing: 0) {
            List {
                ForEach(viewModel.flattenedRows) { row in
                    switch row {
                    case .header(let header):
                        SectionHeaderRow(
                            header: header,
                            isSelected: selectedSectionIndex == sectionIndex(for: row),
                            onTap: {
                                let index = sectionIndex(for: row)
                                if selectedSectionIndex == index {
                                    selectedSectionIndex = nil
                                } else {
                                    selectedSectionIndex = index
                                    selectedItemIndex = nil
                                }
                            }
                        )
                        .listRowInsets(EdgeInsets())

                    case .itemRow(let item, let sectionIndex):
                        SectionItemRow(
                            item: item,
                            isSelected: selectedSectionIndex == sectionIndex && selectedItemIndex == itemIndex(for: row),
                            onTap: {
                                let itemIdx = itemIndex(for: row)
                                if selectedSectionIndex == sectionIndex && selectedItemIndex == itemIdx {
                                    selectedItemIndex = nil
                                } else {
                                    selectedSectionIndex = sectionIndex
                                    selectedItemIndex = itemIdx
                                }
                            }
                        )
                    }
                }
            }
            .listStyle(.plain)

            // Control buttons
            SectionedControlButtons(
                viewModel: viewModel,
                selectedSectionIndex: $selectedSectionIndex,
                selectedItemIndex: $selectedItemIndex
            )
        }
    }

    private func sectionIndex(for row: SectionRowWrapper) -> Int? {
        var sectionCount = 0
        for r in viewModel.flattenedRows {
            if case .header = r {
                if r.id == row.id {
                    return sectionCount
                }
                sectionCount += 1
            }
        }
        return nil
    }

    private func itemIndex(for row: SectionRowWrapper) -> Int? {
        guard case .itemRow(_, let sectionIndex) = row else { return nil }

        var currentSection = -1
        var itemCount = 0

        for r in viewModel.flattenedRows {
            switch r {
            case .header:
                currentSection += 1
                itemCount = 0
            case .itemRow:
                if currentSection == sectionIndex {
                    if r.id == row.id {
                        return itemCount
                    }
                    itemCount += 1
                }
            }
        }
        return nil
    }
}

// MARK: - Section Header Row

private struct SectionHeaderRow: View {
    let header: SectionHeaderWrapper
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Text(header.title)
            .font(.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(header.color.opacity(isSelected ? 1.0 : 0.8))
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
    }
}

// MARK: - Section Item Row

private struct SectionItemRow: View {
    let item: ItemWrapper
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.title)
                .font(.body)

            Text("ID: \(item.id.prefix(8))...")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.leading, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Color.blue.opacity(0.2) : Color.clear)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}

// MARK: - Control Buttons

private struct SectionedControlButtons: View {
    @ObservedObject var viewModel: SectionedListViewModelAdapter
    @Binding var selectedSectionIndex: Int?
    @Binding var selectedItemIndex: Int?

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button("+ Section") {
                    viewModel.addSection()
                }
                .buttonStyle(.bordered)

                if selectedSectionIndex != nil {
                    Button("- Section") {
                        if let index = selectedSectionIndex {
                            viewModel.removeSection(at: index)
                            selectedSectionIndex = nil
                            selectedItemIndex = nil
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                }

                Button("Clear") {
                    viewModel.clearSections()
                    selectedSectionIndex = nil
                    selectedItemIndex = nil
                }
                .buttonStyle(.bordered)
            }

            HStack {
                if let sectionIndex = selectedSectionIndex {
                    Button("+ Item") {
                        viewModel.addItemToSection(sectionIndex)
                    }
                    .buttonStyle(.bordered)

                    if let itemIndex = selectedItemIndex {
                        Button("- Item") {
                            viewModel.removeItemFromSection(sectionIndex, itemIndex: itemIndex)
                            selectedItemIndex = nil
                        }
                        .buttonStyle(.bordered)
                        .tint(.red)
                    }

                    if sectionIndex > 0 {
                        Button("Move Up") {
                            viewModel.moveSection(from: sectionIndex, to: sectionIndex - 1)
                            selectedSectionIndex = sectionIndex - 1
                        }
                        .buttonStyle(.bordered)
                    }

                    if sectionIndex < viewModel.sections.count - 1 {
                        Button("Move Down") {
                            viewModel.moveSection(from: sectionIndex, to: sectionIndex + 1)
                            selectedSectionIndex = sectionIndex + 1
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
        .padding()
        .background(Color(.systemBackground))
    }
}

// MARK: - UIKit Content (Placeholder)

private struct SectionedUIKitContent: View {
    @ObservedObject var viewModel: SectionedListViewModelAdapter

    var body: some View {
        Text("UICollectionView implementation")
            .foregroundColor(.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    NavigationStack {
        SectionedListView()
    }
}
