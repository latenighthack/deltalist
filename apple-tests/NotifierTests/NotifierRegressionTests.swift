import Foundation

@MainActor
private final class StateFixture {
    var emit: ((Any?) -> Void)!
    var posts: [Int?] = []
    var controller: TrayController!
    init() {
        controller = TrayController(
            sink: NotificationSink(post: { [weak self] _, _, state, _ in self?.posts.append(state as? Int) }, cancel: { _ in }),
            key: { _ in "1" }, shouldRepost: { $0 !== $1 },
            stateSubscribe: { [weak self] _, emit in self?.emit = emit; return Task {} },
            stateInitial: { _ in 0 }, rateLimitPerSecond: 8)
        controller.applyDelta(items: [NSObject()])
    }
}

@main
private struct NotifierRegressionTests {
    @MainActor static func main() async {
        await testThrottlePublishesLatestState()
        await testReversionCancelsPendingState()
        await testNilCanBeTheLatestState()
        await testReplacementRestartsStateAndRejectsOldEmissions()
        testKeyedItemsReconcileOnTheirKeys()
        testReloadRepostsOnlyChangedSurvivors()
        await testFirstRenderWaitsForTheFirstState()
        print("NotifierRegressionTests: 7 passed")
    }

    // Sleeping for a fixed interval can resume the test before a delayed flush task has even
    // started its timer on a busy CI executor. Wait for the observable result instead.
    @MainActor private static func eventually(
        _ message: String,
        _ condition: () -> Bool
    ) async {
        let deadline = ProcessInfo.processInfo.systemUptime + 5
        while !condition(), ProcessInfo.processInfo.systemUptime < deadline {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        precondition(condition(), message)
    }

    @MainActor private static func testThrottlePublishesLatestState() async {
        let fixture = StateFixture()
        fixture.emit(1); fixture.emit(2); fixture.emit(3)
        await eventually("Trailing flush must publish the latest emission") {
            fixture.posts == [0, 1, 3]
        }
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
        await eventually("A nil state is a value, not an absent pending emission") {
            fixture.posts == [0, 1, nil]
        }
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
            sink: NotificationSink(post: { _, item, state, _ in posts.append((item, state as? Int)) }, cancel: { _ in }),
            key: { _ in "1" }, shouldRepost: { _, _ in false },
            stateSubscribe: { item, emit in
                starts += 1
                emitters[ObjectIdentifier(item)] = emit
                let source = AsyncStream<Void> { continuation in
                    continuation.onTermination = { _ in Task { @MainActor in cancellations += 1 } }
                }
                return Task { for await _ in source {} }
            }, stateInitial: { ($0 as! Item).initial }, rateLimitPerSecond: 8)
        controller.applyDelta(items: [old])
        emitters[ObjectIdentifier(old)]?(1)
        emitters[ObjectIdentifier(old)]?(2) // Must not flush against the replacement.
        controller.applyDelta(items: [replacement])
        precondition(starts == 2, "Same-ID replacement must subscribe to the new raw object")
        precondition(posts.last?.0 === replacement && posts.last?.1 == 10, "Replacement must use its own initial state")
        emitters[ObjectIdentifier(old)]?(99) // A cancelled, noncooperative old producer.
        emitters[ObjectIdentifier(replacement)]?(11)
        await eventually("Replacement must cancel the old state stream and publish its own state") {
            cancellations == 1 && posts.last?.0 === replacement && posts.last?.1 == 11
        }
        precondition(!posts.contains { $0.1 == 99 }, "Late emissions from the cancelled source must be rejected")
        precondition(cancellations == 1, "Replacement must cancel the old state stream")
        controller.stop()
    }

    private final class Push: NSObject {
        let notificationId: String
        let revision: Int
        init(_ notificationId: String, _ revision: Int = 1) {
            self.notificationId = notificationId
            self.revision = revision
        }
    }

    @MainActor private final class Recorder {
        var operations: [String] = []
        var firstRenders: [String] = []
        lazy var sink = NotificationSink(
            post: { [unowned self] key, _, _, isFirst in
                self.operations.append("post:\(key)")
                if isFirst { self.firstRenders.append(key) }
            },
            cancel: { [unowned self] key in self.operations.append("cancel:\(key)") }
        )
    }

    @MainActor private static func keyedController(
        _ recorder: Recorder,
        stateSubscribe: ((AnyObject, @escaping (Any?) -> Void) -> Task<Void, Never>)? = nil
    ) -> TrayController {
        TrayController(
            sink: recorder.sink,
            key: { ($0 as! Push).notificationId },
            shouldRepost: { ($0 as! Push).revision != ($1 as! Push).revision },
            stateSubscribe: stateSubscribe, stateInitial: nil, rateLimitPerSecond: 8)
    }

    @MainActor private static func testKeyedItemsReconcileOnTheirKeys() {
        let recorder = Recorder()
        let controller = keyedController(recorder)
        let b = Push("pnt_b")
        controller.applyDelta(items: [Push("pnt_a")])
        controller.applyDelta(items: [Push("pnt_a", 2), b])
        controller.applyDelta(items: [b])
        precondition(recorder.operations == ["post:pnt_a", "post:pnt_a", "post:pnt_b", "cancel:pnt_a"],
                     "Keyed items must post, re-post and cancel under their own keys, got \(recorder.operations)")
        precondition(recorder.firstRenders == ["pnt_a", "pnt_b"], "Only an item's first render may adopt")
        controller.stop()
    }

    @MainActor private static func testReloadRepostsOnlyChangedSurvivors() {
        let recorder = Recorder()
        let controller = keyedController(recorder)
        controller.applyDelta(items: [Push("pnt_a"), Push("pnt_b")])
        recorder.operations.removeAll()
        // Fresh objects, as a reload delivers them: only the changed revision re-posts.
        controller.applyDelta(items: [Push("pnt_a"), Push("pnt_b", 2)])
        precondition(recorder.operations == ["post:pnt_b"],
                     "A reload must not re-post unchanged survivors, got \(recorder.operations)")
        controller.stop()
    }

    @MainActor private static func testFirstRenderWaitsForTheFirstState() async {
        let recorder = Recorder()
        var emit: ((Any?) -> Void)?
        let controller = keyedController(recorder, stateSubscribe: { _, emitter in emit = emitter; return Task {} })
        controller.applyDelta(items: [Push("pnt_a")])
        precondition(recorder.operations.isEmpty, "An item must not render before its first state")
        emit?(false)
        precondition(recorder.operations == ["post:pnt_a"] && recorder.firstRenders == ["pnt_a"],
                     "The first state must render at once, as the first render")
        emit?(true)
        await eventually("A later state re-posts, not as a first render") {
            recorder.operations == ["post:pnt_a", "post:pnt_a"] && recorder.firstRenders == ["pnt_a"]
        }
        controller.stop()
    }
}
