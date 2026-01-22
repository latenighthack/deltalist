import Foundation
import SwiftUI
import DemoCore

/// Wraps a Kotlin SectionHeader for use in Swift.
struct SectionHeaderWrapper: Identifiable, Hashable {
    let id: String
    let title: String
    let color: Color

    init(kotlinHeader: SectionHeader) {
        self.id = kotlinHeader.title
        self.title = kotlinHeader.title
        // Convert ARGB Long to SwiftUI Color
        let argb = UInt64(kotlinHeader.color)
        let red = Double((argb >> 16) & 0xFF) / 255.0
        let green = Double((argb >> 8) & 0xFF) / 255.0
        let blue = Double(argb & 0xFF) / 255.0
        self.color = Color(red: red, green: green, blue: blue)
    }
}

/// Row type for flattened sectioned lists.
enum SectionRowWrapper: Identifiable, Hashable {
    case header(SectionHeaderWrapper)
    case itemRow(ItemWrapper, sectionIndex: Int)

    var id: String {
        switch self {
        case .header(let header):
            return "header-\(header.id)"
        case .itemRow(let item, _):
            return "item-\(item.id)"
        }
    }
}

/// Section wrapper containing a header and items.
struct ItemSectionWrapper: Identifiable {
    let id: String
    let header: SectionHeaderWrapper
    var items: [ItemWrapper]

    init?(kotlinSection: Any) {
        guard let section = kotlinSection as? DemoCore.Section<SectionHeader, Item>,
              let header = section.header else {
            return nil
        }
        self.header = SectionHeaderWrapper(kotlinHeader: header)
        self.id = self.header.id
        self.items = section.items.compactMap { item -> ItemWrapper? in
            guard let kotlinItem = item as? Item else { return nil }
            return ItemWrapper(kotlinItem: kotlinItem)
        }
    }
}

/// Wraps the shared Kotlin SectionedListViewModel for use in SwiftUI and UIKit.
@MainActor
class SectionedListViewModelAdapter: ObservableObject {
    private let viewModel = SectionedListViewModel()

    @Published private(set) var sections: [ItemSectionWrapper] = []
    @Published private(set) var flattenedRows: [SectionRowWrapper] = []

    private var sectionsTask: Task<Void, Never>?
    private var flattenedTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // Collect flattened sections
        flattenedTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = SectionRowDeltaFlowCollector { [weak self] delta in
                guard let self = self else { return }
                self.flattenedRows = delta.items.compactMap { item -> SectionRowWrapper? in
                    if let header = item as? SectionRow.Header {
                        return .header(SectionHeaderWrapper(kotlinHeader: header.header))
                    } else if let itemRow = item as? SectionRow.ItemRow {
                        return .itemRow(ItemWrapper(kotlinItem: itemRow.item), sectionIndex: 0)
                    }
                    return nil
                }
            }

            do {
                try await self.viewModel.flattenedSections.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }

        // Also collect raw sections for section-level operations
        sectionsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            let collector = SectionedDeltaFlowCollector { [weak self] delta in
                guard let self = self else { return }
                self.sections = delta.sections.compactMap { section in
                    ItemSectionWrapper(kotlinSection: section)
                }
            }

            do {
                try await self.viewModel.sections.collect(collector: collector)
            } catch {
                // Collection ended
            }
        }
    }

    func stopCollecting() {
        sectionsTask?.cancel()
        flattenedTask?.cancel()
    }

    // MARK: - Actions

    func addSection() {
        viewModel.addSection()
    }

    func removeSection(at index: Int) {
        viewModel.removeSection(index: Int32(index))
    }

    func addItemToSection(_ sectionIndex: Int) {
        viewModel.addItemToSection(sectionIndex: Int32(sectionIndex))
    }

    func removeItemFromSection(_ sectionIndex: Int, itemIndex: Int) {
        viewModel.removeItemFromSection(sectionIndex: Int32(sectionIndex), itemIndex: Int32(itemIndex))
    }

    func moveSection(from fromIndex: Int, to toIndex: Int) {
        viewModel.moveSection(fromIndex: Int32(fromIndex), toIndex: Int32(toIndex))
    }

    func clearSections() {
        viewModel.clearSections()
    }

    deinit {
        sectionsTask?.cancel()
        flattenedTask?.cancel()
    }
}

// MARK: - Flow Collectors

/// FlowCollector for DeltaList<SectionRow> flows.
class SectionRowDeltaFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onDelta: (Delta<SectionRow>) -> Void

    init(onDelta: @escaping (Delta<SectionRow>) -> Void) {
        self.onDelta = onDelta
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let delta = value as? Delta<SectionRow> {
            Task { @MainActor [self] in
                self.onDelta(delta)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}

/// FlowCollector for SectionedDeltaList flows.
class SectionedDeltaFlowCollector: Kotlinx_coroutines_coreFlowCollector {
    private let onDelta: (SectionedDelta<AnyObject, AnyObject>) -> Void

    init(onDelta: @escaping (SectionedDelta<AnyObject, AnyObject>) -> Void) {
        self.onDelta = onDelta
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let delta = value as? SectionedDelta<AnyObject, AnyObject> {
            Task { @MainActor [self] in
                self.onDelta(delta)
                completionHandler(nil)
            }
        } else {
            completionHandler(nil)
        }
    }
}
