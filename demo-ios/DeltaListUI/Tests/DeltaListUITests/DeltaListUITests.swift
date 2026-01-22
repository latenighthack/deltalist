import XCTest
@testable import DeltaListUI

final class DeltaListUITests: XCTestCase {

    // MARK: - ShadowIdTracker Tests

    @MainActor
    func testShadowIdTrackerReload() {
        let tracker = ShadowIdTracker<String>()
        let delta = Delta(items: ["a", "b", "c"], change: .reload)

        let result = tracker.apply(delta: delta)

        XCTAssertEqual(result.count, 3)
        XCTAssertEqual(result[0].value, "a")
        XCTAssertEqual(result[1].value, "b")
        XCTAssertEqual(result[2].value, "c")
        XCTAssertEqual(result[0].index, 0)
        XCTAssertEqual(result[1].index, 1)
        XCTAssertEqual(result[2].index, 2)
    }

    @MainActor
    func testShadowIdTrackerInsert() {
        let tracker = ShadowIdTracker<String>()

        // Initial load
        let delta1 = Delta(items: ["a", "b"], change: .reload)
        let result1 = tracker.apply(delta: delta1)
        let originalIds = result1.map { $0.id }

        // Insert at index 1
        let delta2 = Delta(items: ["a", "new", "b"], change: .mutation(.insert(index: 1, count: 1)))
        let result2 = tracker.apply(delta: delta2)

        XCTAssertEqual(result2.count, 3)
        XCTAssertEqual(result2[0].id, originalIds[0]) // "a" keeps its ID
        XCTAssertNotEqual(result2[1].id, originalIds[0]) // "new" has new ID
        XCTAssertNotEqual(result2[1].id, originalIds[1])
        XCTAssertEqual(result2[2].id, originalIds[1]) // "b" keeps its ID
    }

    @MainActor
    func testShadowIdTrackerRemove() {
        let tracker = ShadowIdTracker<String>()

        // Initial load
        let delta1 = Delta(items: ["a", "b", "c"], change: .reload)
        let result1 = tracker.apply(delta: delta1)
        let originalIds = result1.map { $0.id }

        // Remove at index 1
        let delta2 = Delta(items: ["a", "c"], change: .mutation(.remove(index: 1, count: 1)))
        let result2 = tracker.apply(delta: delta2)

        XCTAssertEqual(result2.count, 2)
        XCTAssertEqual(result2[0].id, originalIds[0]) // "a" keeps its ID
        XCTAssertEqual(result2[1].id, originalIds[2]) // "c" keeps its ID
    }

    // MARK: - DeltaListObserver Tests

    @MainActor
    func testDeltaListObserverInitialState() {
        let observer = DeltaListObserver<String>()

        XCTAssertTrue(observer.items.isEmpty)
        XCTAssertTrue(observer.rawItems.isEmpty)
    }

    // MARK: - Core Types Tests

    func testChangeEquality() {
        XCTAssertEqual(Change.reload, Change.reload)
        XCTAssertEqual(
            Change.mutations([.insert(index: 0, count: 1)]),
            Change.mutations([.insert(index: 0, count: 1)])
        )
        XCTAssertNotEqual(Change.reload, Change.mutations([]))
    }

    func testMutationEquality() {
        XCTAssertEqual(
            Mutation.insert(index: 0, count: 1),
            Mutation.insert(index: 0, count: 1)
        )
        XCTAssertNotEqual(
            Mutation.insert(index: 0, count: 1),
            Mutation.insert(index: 1, count: 1)
        )
        XCTAssertNotEqual(
            Mutation.insert(index: 0, count: 1),
            Mutation.remove(index: 0, count: 1)
        )
    }
}
