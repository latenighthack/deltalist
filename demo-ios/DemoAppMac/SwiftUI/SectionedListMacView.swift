import SwiftUI
import DemoCore
import DeltaListCore

/// Sectioned demo (macOS): headers + items with section/item mutations, in both SwiftUI and AppKit.
struct SectionedListMacView: View {
    private let viewModel = SectionedListViewModel()
    @StateObject private var sectionList = DeltaListCore.SectionedDeltaList<SectionHeader, Item>()

    @State private var mode = 0
    @State private var selectedSection: Int? = nil
    @State private var selectedItem: Int? = nil
    @State private var appkitSection: Int = -1

    private var sections: [ItemSectionWrapper] {
        sectionList.sections.map { ItemSectionWrapper(header: $0.header, items: $0.items) }
    }

    var body: some View {
        VStack(spacing: 0) {
            RenderModePicker(selection: $mode)

            if mode == 0 {
                List {
                    ForEach(Array(sections.enumerated()), id: \.element.id) { sectionIndex, section in
                        Section {
                            ForEach(Array(section.items.enumerated()), id: \.element.id) { itemIndex, item in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title).font(.body)
                                    Text("ID: \(item.id.prefix(8))…").font(.caption).foregroundColor(.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 2)
                                .background(selectedSection == sectionIndex && selectedItem == itemIndex ? Color.accentColor.opacity(0.2) : Color.clear)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    if selectedSection == sectionIndex && selectedItem == itemIndex {
                                        selectedItem = nil
                                    } else {
                                        selectedSection = sectionIndex; selectedItem = itemIndex
                                    }
                                }
                            }
                        } header: {
                            Text(section.header.title)
                                .font(.headline).foregroundColor(.white)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(8)
                                .background(section.header.color.opacity(selectedSection == sectionIndex ? 1.0 : 0.8))
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    selectedSection = (selectedSection == sectionIndex) ? nil : sectionIndex
                                    selectedItem = nil
                                }
                        }
                    }
                }
                .listStyle(.inset)

                Divider()
                SectionedControls(
                    viewModel: viewModel, sectionCount: sections.count,
                    selectedSection: $selectedSection, selectedItem: $selectedItem
                )
                .padding()
            } else {
                AppKitControllerView(make: {
                    let controller = SectionedListCollectionController(viewModel: viewModel)
                    controller.onSectionSelected = { appkitSection = $0 }
                    return controller
                })

                if appkitSection >= 0 {
                    Divider()
                    HStack {
                        Button("Add Item") { viewModel.addItemToSection(sectionIndex: Int32(appkitSection)) }
                        Button("Remove Section") { viewModel.removeSection(index: Int32(appkitSection)); appkitSection = -1 }
                            .tint(.red)
                    }
                    .buttonStyle(.bordered)
                    .padding()
                }
            }
        }
        .navigationTitle("Sectioned List")
        .task { await sectionList.collect(viewModel.sections) }
    }
}

private struct SectionedControls: View {
    let viewModel: SectionedListViewModel
    let sectionCount: Int
    @Binding var selectedSection: Int?
    @Binding var selectedItem: Int?

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button("+ Section") { viewModel.addSection() }
                if let index = selectedSection {
                    Button("- Section") {
                        viewModel.removeSection(index: Int32(index)); selectedSection = nil; selectedItem = nil
                    }.tint(.red)
                }
                Button("Clear") { viewModel.clearSections(); selectedSection = nil; selectedItem = nil }
            }
            HStack {
                if let sectionIndex = selectedSection {
                    Button("+ Item") { viewModel.addItemToSection(sectionIndex: Int32(sectionIndex)) }
                    if let itemIndex = selectedItem {
                        Button("- Item") {
                            viewModel.removeItemFromSection(sectionIndex: Int32(sectionIndex), itemIndex: Int32(itemIndex))
                            selectedItem = nil
                        }.tint(.red)
                    }
                    if sectionIndex > 0 {
                        Button("Move Up") {
                            viewModel.moveSection(fromIndex: Int32(sectionIndex), toIndex: Int32(sectionIndex - 1))
                            selectedSection = sectionIndex - 1
                        }
                    }
                    if sectionIndex < sectionCount - 1 {
                        Button("Move Down") {
                            viewModel.moveSection(fromIndex: Int32(sectionIndex), toIndex: Int32(sectionIndex + 1))
                            selectedSection = sectionIndex + 1
                        }
                    }
                }
            }
        }
        .buttonStyle(.bordered)
    }
}
