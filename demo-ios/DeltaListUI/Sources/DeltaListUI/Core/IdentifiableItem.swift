import Foundation

/// Wraps an item with a shadow UUID for use with SwiftUI ForEach and DiffableDataSource.
/// This is used when the underlying items don't have stable IDs of their own.
public struct IdentifiableItem<T>: Identifiable {
    /// Shadow ID for ForEach/DiffableDataSource identification.
    public let id: UUID

    /// The underlying value.
    public let value: T

    /// The index of this item in the list.
    public let index: Int

    public init(id: UUID = UUID(), value: T, index: Int) {
        self.id = id
        self.value = value
        self.index = index
    }
}

/// Tracks stable shadow IDs across delta mutations.
/// Ensures that items maintain consistent IDs as the list changes.
@MainActor
public class ShadowIdTracker<T> {
    private var shadowIds: [UUID] = []

    public init() {}

    /// Updates the shadow IDs based on a delta change and returns identifiable items.
    /// - Parameter delta: The delta containing items and how they changed.
    /// - Returns: An array of IdentifiableItems with stable shadow IDs.
    public func apply(delta: Delta<T>) -> [IdentifiableItem<T>] {
        switch delta.change {
        case .reload:
            // Generate new IDs for all items
            shadowIds = delta.items.map { _ in UUID() }

        case .mutations(let operations):
            // Apply each mutation to maintain ID stability
            for mutation in operations {
                switch mutation {
                case .insert(let index, let count):
                    let newIds = (0..<count).map { _ in UUID() }
                    shadowIds.insert(contentsOf: newIds, at: index)

                case .remove(let index, let count):
                    shadowIds.removeSubrange(index..<(index + count))

                case .update:
                    // Updates don't change IDs
                    break

                case .move(let fromIndex, let toIndex, let count):
                    let movedIds = Array(shadowIds[fromIndex..<(fromIndex + count)])
                    shadowIds.removeSubrange(fromIndex..<(fromIndex + count))
                    let insertIndex = fromIndex < toIndex ? toIndex - count : toIndex
                    shadowIds.insert(contentsOf: movedIds, at: insertIndex)
                }
            }
        }

        // Build identifiable items
        return zip(delta.items.indices, zip(delta.items, shadowIds)).map { index, pair in
            IdentifiableItem(id: pair.1, value: pair.0, index: index)
        }
    }

    /// Resets all tracked IDs.
    public func reset() {
        shadowIds = []
    }
}

/// Extension to handle StableItem types more efficiently.
extension ShadowIdTracker where T: StableItem {
    /// For StableItem types, we can use the stableId directly instead of shadow UUIDs.
    /// Returns items wrapped with their stableId converted to a stable identifier.
    public func applyStable(delta: Delta<T>) -> [StableIdentifiableItem<T>] {
        return delta.items.enumerated().map { index, item in
            StableIdentifiableItem(stableId: item.stableId, value: item, index: index)
        }
    }
}

/// Wrapper for StableItem that conforms to Identifiable using the stable ID.
public struct StableIdentifiableItem<T: StableItem>: Identifiable {
    public var id: Int { stableId }

    public let stableId: Int
    public let value: T
    public let index: Int

    public init(stableId: Int, value: T, index: Int) {
        self.stableId = stableId
        self.value = value
        self.index = index
    }
}
