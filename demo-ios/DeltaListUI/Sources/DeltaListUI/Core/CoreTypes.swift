import Foundation

// MARK: - Core Types
// These types mirror the KMP core library types.
// When the KMP iOS target is built with SKIE, these will be replaced by imports from DeltaListCore.

/// Represents a delta update containing the current list and how it changed.
public struct Delta<T> {
    public let items: [T]
    public let change: Change

    public init(items: [T], change: Change) {
        self.items = items
        self.change = change
    }
}

/// Represents how a list changed.
public enum Change {
    case reload
    case mutations([Mutation])

    public static func mutation(_ mutation: Mutation) -> Change {
        return .mutations([mutation])
    }
}

/// Represents a single mutation operation.
public enum Mutation {
    case insert(index: Int, count: Int)
    case remove(index: Int, count: Int)
    case update(index: Int, count: Int)
    case move(fromIndex: Int, toIndex: Int, count: Int)
}

/// Wraps an item with a session-stable integer identifier.
public protocol StableItem {
    associatedtype Value

    /// A session-unique integer identifier that remains stable as the item moves.
    var stableId: Int { get }

    /// The underlying value.
    var value: Value { get }
}

/// Simple implementation of StableItem.
public struct StableItemImpl<T>: StableItem {
    public let stableId: Int
    public let value: T

    public init(stableId: Int, value: T) {
        self.stableId = stableId
        self.value = value
    }
}

/// A list that supports lazy acquisition and release of items.
public protocol LazyList {
    associatedtype Element

    /// Releases the cached value at the given index.
    func release(index: Int)

    /// Releases all cached values in the list.
    func releaseAll()

    /// Whether the item at the given index currently has an acquired (cached) value.
    func isAcquired(index: Int) -> Bool
}

/// Soft value for paginated lists - either present or not yet loaded.
public enum SoftValue<T> {
    case present(T)
    case notLoaded
}

/// Direction for paginated loading.
public enum LoadDirection {
    case before
    case after
}

/// Drag state for moveable lists.
public enum DragState<T> {
    case idle
    case dragging(item: T, fromIndex: Int, previewIndex: Int)
    case committing(item: T, fromIndex: Int, toIndex: Int)
}
