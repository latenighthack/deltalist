import SwiftUI

/// ViewModifier that releases lazy items when they leave the viewport.
/// Use this in LazyColumn/LazyRow items to manage lazy list lifecycle.
public struct LazyLifecycleModifier: ViewModifier {
    let index: Int
    let onRelease: (Int) -> Void

    public init(index: Int, onRelease: @escaping (Int) -> Void) {
        self.index = index
        self.onRelease = onRelease
    }

    public func body(content: Content) -> some View {
        content
            .onDisappear {
                onRelease(index)
            }
    }
}

public extension View {
    /// Applies lazy lifecycle management to this view.
    /// - Parameters:
    ///   - index: The index of the item in the list.
    ///   - onRelease: Called when the item should be released.
    func lazyLifecycle(index: Int, onRelease: @escaping (Int) -> Void) -> some View {
        modifier(LazyLifecycleModifier(index: index, onRelease: onRelease))
    }

    /// Applies lazy lifecycle management using a DeltaListObserver.
    /// - Parameters:
    ///   - index: The index of the item in the list.
    ///   - observer: The observer managing the lazy list.
    @MainActor
    func lazyLifecycle<T>(index: Int, observer: DeltaListObserver<T>) -> some View {
        modifier(LazyLifecycleModifier(index: index) { idx in
            observer.releaseItem(at: idx)
        })
    }

    /// Applies lazy lifecycle management using a StableDeltaListObserver.
    @MainActor
    func lazyLifecycle<T: StableItem>(index: Int, observer: StableDeltaListObserver<T>) -> some View {
        modifier(LazyLifecycleModifier(index: index) { idx in
            observer.releaseItem(at: idx)
        })
    }
}

// MARK: - Cleanup Modifier

/// ViewModifier that performs cleanup when the view disappears.
public struct CleanupModifier: ViewModifier {
    let onCleanup: () -> Void

    public init(onCleanup: @escaping () -> Void) {
        self.onCleanup = onCleanup
    }

    public func body(content: Content) -> some View {
        content
            .onDisappear {
                onCleanup()
            }
    }
}

public extension View {
    /// Performs cleanup when this view disappears.
    func onCleanup(_ action: @escaping () -> Void) -> some View {
        modifier(CleanupModifier(onCleanup: action))
    }
}
