import Foundation
import SwiftUI
import DemoCore

/// Wraps the shared Kotlin SectionedListViewModel for use in SwiftUI and UIKit.
/// Uses SKIE's automatic Flow→AsyncSequence conversion to eliminate FlowCollector boilerplate.
@MainActor
class SectionedListViewModelAdapter: ObservableObject {
    private let viewModel = SectionedListViewModel()

    @Published private(set) var sections: [ItemSectionWrapper] = []

    private var sectionsTask: Task<Void, Never>?

    init() {
        startCollecting()
    }

    private func startCollecting() {
        // SKIE converts SectionedDeltaList (which is a Flow) to AsyncSequence automatically
        sectionsTask = Task { @MainActor [weak self] in
            guard let self = self else { return }

            for await delta in self.viewModel.sections {
                if Task.isCancelled { break }
                self.sections = delta.sections.compactMap { section in
                    ItemSectionWrapper(kotlinSection: section)
                }
            }
        }
    }

    func stopCollecting() {
        sectionsTask?.cancel()
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
    }
}
