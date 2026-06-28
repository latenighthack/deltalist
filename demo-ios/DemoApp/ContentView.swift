import SwiftUI

/// Main navigation view with buttons to each demo screen.
struct ContentView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                // Header
                VStack(spacing: 8) {
                    Text("DeltaList Demo")
                        .font(.largeTitle)
                        .fontWeight(.bold)

                    Text("Reactive list library with efficient mutations")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding(.top, 40)

                Spacer()

                // Demo buttons
                VStack(spacing: 16) {
                    NavigationLink(destination: BasicListView()) {
                        DemoButton(
                            title: "Basic List Demo",
                            description: "Ticking items with lazy lifecycle"
                        )
                    }

                    NavigationLink(destination: PaginatedListView()) {
                        DemoButton(
                            title: "Paginated List Demo",
                            description: "10K items with dynamic filtering"
                        )
                    }

                    NavigationLink(destination: BottomPaginatedListView()) {
                        DemoButton(
                            title: "Bottom Paginated List Demo",
                            description: "Chat-style: loads bottom first, add top/bottom"
                        )
                    }

                    NavigationLink(destination: SectionedListView()) {
                        DemoButton(
                            title: "Sectioned List Demo",
                            description: "Headers and items with sections"
                        )
                    }

                    NavigationLink(destination: DragDropView()) {
                        DemoButton(
                            title: "Drag & Drop Demo",
                            description: "Reorderable items with drag state"
                        )
                    }

                    NavigationLink(destination: NotificationsView()) {
                        DemoButton(
                            title: "Notifications Demo",
                            description: "DeltaList mirrored to the system tray"
                        )
                    }
                }
                .padding(.horizontal, 24)

                Spacer()

                // Footer
                Text("Built with DeltaList")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.bottom, 16)
            }
            .navigationBarHidden(true)
        }
    }
}

// MARK: - Demo Button

private struct DemoButton: View {
    let title: String
    let description: String

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(.primary)

                Text(description)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .foregroundColor(.secondary)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
    }
}

#Preview {
    ContentView()
}
