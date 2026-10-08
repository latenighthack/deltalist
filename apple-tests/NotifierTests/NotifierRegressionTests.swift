import Foundation

@MainActor
private final class StateFixture {
    var emit: ((Any?) -> Void)!
    var posts: [Int?] = []
    var controller: TrayController!
    init() {
        controller = TrayController(
            sink: NotificationSink(post: { [weak self] _, _, state in self?.posts.append(state as? Int) }, cancel: { _ in }),
            stableId: { _ in 1 }, shouldRepost: { $0 !== $1 },
            stateSubscribe: { [weak self] _, emit in self?.emit = emit; return Task {} },
            stateInitial: { _ in 0 }, rateLimitPerSecond: 8)
        controller.applyDelta(items: [NSObject()], isReload: true)
    }
}

@main
private struct NotifierRegressionTests {
    @MainActor static func main() async {
        await testThrottlePublishesLatestState()
        await testReversionCancelsPendingState()
        await testNilCanBeTheLatestState()
        await testReplacementRestartsStateAndRejectsOldEmissions()
        print("NotifierRegressionTests: 4 passed")
    }

    @MainActor private static func testThrottlePublishesLatestState() async {
        let fixture = StateFixture()
        fixture.emit(1); fixture.emit(2); fixture.emit(3)
        try? await Task.sleep(nanoseconds: 250_000_000)
        precondition(fixture.posts == [0, 1, 3], "Trailing flush must publish the latest emission")
        fixture.controller.stop()
    }

    @MainActor private static func testReversionCancelsPendingState() async {
        let fixture = StateFixture()
        fixture.emit(1); fixture.emit(2); fixture.emit(1)
        try? await Task.sleep(nanoseconds: 250_000_000)
        precondition(fixture.posts == [0, 1], "Reverting to the posted state must discard the pending change")
        fixture.controller.stop()
    }

    @MainActor private static func testNilCanBeTheLatestState() async {
        let fixture = StateFixture()
        fixture.emit(1); fixture.emit(2); fixture.emit(nil)
        try? await Task.sleep(nanoseconds: 250_000_000)
        precondition(fixture.posts == [0, 1, nil], "A nil state is a value, not an absent pending emission")
        fixture.controller.stop()
    }

    @MainActor private static func testReplacementRestartsStateAndRejectsOldEmissions() async {
        final class Item: NSObject {
            let initial: Int
            init(_ initial: Int) { self.initial = initial }
        }
        var emitters: [ObjectIdentifier: (Any?) -> Void] = [:]
        var starts = 0
        var cancellations = 0
        var posts: [(AnyObject, Int?)] = []
        let old = Item(0), replacement = Item(10)
        let controller = TrayController(
            sink: NotificationSink(post: { _, item, state in posts.append((item, state as? Int)) }, cancel: { _ in }),
            stableId: { _ in 1 }, shouldRepost: { _, _ in false },
            stateSubscribe: { item, emit in
                starts += 1
                emitters[ObjectIdentifier(item)] = emit
                let source = AsyncStream<Void> { continuation in
                    continuation.onTermination = { _ in Task { @MainActor in cancellations += 1 } }
                }
                return Task { for await _ in source {} }
            }, stateInitial: { ($0 as! Item).initial }, rateLimitPerSecond: 8)
        controller.applyDelta(items: [old], isReload: true)
        emitters[ObjectIdentifier(old)]?(1)
        emitters[ObjectIdentifier(old)]?(2) // Must not flush against the replacement.
        controller.applyDelta(items: [replacement], isReload: false)
        precondition(starts == 2, "Same-ID replacement must subscribe to the new raw object")
        precondition(posts.last?.0 === replacement && posts.last?.1 == 10, "Replacement must use its own initial state")
        emitters[ObjectIdentifier(old)]?(99) // A cancelled, noncooperative old producer.
        emitters[ObjectIdentifier(replacement)]?(11)
        try? await Task.sleep(nanoseconds: 250_000_000)
        precondition(posts.last?.0 === replacement && posts.last?.1 == 11, "Only the new item may drive replacement state")
        precondition(!posts.contains { $0.1 == 99 }, "Late emissions from the cancelled source must be rejected")
        precondition(cancellations == 1, "Replacement must cancel the old state stream")
        controller.stop()
    }

}
