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
        print("NotifierRegressionTests: 3 passed")
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
}
