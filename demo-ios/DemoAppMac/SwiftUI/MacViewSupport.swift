import SwiftUI
import AppKit

/// Segmented SwiftUI ↔ AppKit rendering toggle shared by every demo screen.
struct RenderModePicker: View {
    @Binding var selection: Int

    var body: some View {
        Picker("Rendering", selection: $selection) {
            Text("SwiftUI").tag(0)
            Text("AppKit").tag(1)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .padding()
    }
}

/// Hosts an `NSViewController` (the AppKit rendering) inside SwiftUI. The controller is rebuilt only
/// when SwiftUI recreates the representable, so its DeltaList binding lives for the view's lifetime.
struct AppKitControllerView<Controller: NSViewController>: NSViewControllerRepresentable {
    let make: () -> Controller
    var update: (Controller) -> Void = { _ in }

    func makeNSViewController(context: Context) -> Controller { make() }
    func updateNSViewController(_ nsViewController: Controller, context: Context) { update(nsViewController) }
}

/// Standard window-background fill used behind control bars.
extension Color {
    static var barBackground: Color { Color(nsColor: .windowBackgroundColor) }
    static var tileBackground: Color { Color(nsColor: .controlBackgroundColor) }
}
