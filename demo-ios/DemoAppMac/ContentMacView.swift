import SwiftUI

/// Sidebar navigation over the seven DeltaList demos (macOS-idiomatic replacement for the iOS
/// NavigationStack menu). Each demo offers a SwiftUI ↔ AppKit rendering toggle.
struct ContentMacView: View {
    enum Demo: String, CaseIterable, Identifiable {
        case basic = "Basic List"
        case paginated = "Paginated List"
        case bottomPaginated = "Bottom Paginated"
        case sectioned = "Sectioned List"
        case sorted = "Sorted List"
        case dragDrop = "Drag & Drop"
        case notifications = "Notifications"

        var id: String { rawValue }

        var subtitle: String {
            switch self {
            case .basic: return "Ticking items with lazy lifecycle"
            case .paginated: return "Soft list with dynamic filtering"
            case .bottomPaginated: return "Chat-style: loads bottom first"
            case .sectioned: return "Headers and items with sections"
            case .sorted: return "Unordered set → 4-column grid"
            case .dragDrop: return "Reorderable items with drag state"
            case .notifications: return "DeltaList mirrored to the tray"
            }
        }

        var symbol: String {
            switch self {
            case .basic: return "list.bullet"
            case .paginated: return "arrow.down.doc"
            case .bottomPaginated: return "bubble.left.and.bubble.right"
            case .sectioned: return "square.stack.3d.up"
            case .sorted: return "square.grid.3x3"
            case .dragDrop: return "arrow.up.arrow.down"
            case .notifications: return "bell.badge"
            }
        }
    }

    @State private var selection: Demo? = .basic

    var body: some View {
        NavigationSplitView {
            List(Demo.allCases, selection: $selection) { demo in
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(demo.rawValue)
                        Text(demo.subtitle).font(.caption).foregroundColor(.secondary)
                    }
                } icon: {
                    Image(systemName: demo.symbol)
                }
                .tag(demo)
            }
            .navigationTitle("DeltaList")
            .frame(minWidth: 240)
        } detail: {
            switch selection {
            case .basic: BasicListMacView()
            case .paginated: PaginatedListMacView()
            case .bottomPaginated: BottomPaginatedListMacView()
            case .sectioned: SectionedListMacView()
            case .sorted: SortedListMacView()
            case .dragDrop: DragDropMacView()
            case .notifications: NotificationsMacView()
            case .none:
                Text("Select a demo").foregroundColor(.secondary)
            }
        }
    }
}
