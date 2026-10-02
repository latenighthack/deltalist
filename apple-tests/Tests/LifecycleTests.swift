import XCTest
import SwiftUI
import DeltaListCore
import DemoCore
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

private typealias Row = DemoCore.BindingProbeRow
private typealias Binding = DeltaListCore.ViewModelListBinding<Row, Row>

@MainActor
private final class Observations {
    var active: [String: ObjectIdentifier] = [:]
    private var tokens: [String: UUID] = [:]
    var starts: [String: Int] = [:]
    func observe(_ row: Row) async {
        starts[row.key, default: 0] += 1
        let token = UUID()
        tokens[row.key] = token
        active[row.key] = ObjectIdentifier(row)
        defer {
            if tokens[row.key] == token { active.removeValue(forKey: row.key); tokens.removeValue(forKey: row.key) }
        }
        let stream = AsyncStream<Void> { _ in }
        for await _ in stream {}
    }
}

@MainActor
private final class RowConfiguration: ObservableObject {
    @Published var binding: Binding
    @Published var store: DeltaListCore.DeltaList<Row>
    init(binding: Binding, store: DeltaListCore.DeltaList<Row>) { self.binding = binding; self.store = store }
}

@MainActor
private struct ConfiguredRows: View {
    @ObservedObject var configuration: RowConfiguration
    var body: some View {
        VStack { DeltaListCore.DeltaForEach(configuration.binding, observing: configuration.store) { Text($0.key) } }
    }
}

@MainActor
private final class MountedView {
    #if canImport(UIKit)
    let window: UIWindow
    let controller: UIHostingController<AnyView>
    init(_ content: AnyView) {
        controller = UIHostingController(rootView: content)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!
        window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 400, height: 500)
        window.windowLevel = .normal + 1
        window.rootViewController = controller
        window.makeKeyAndVisible()
    }
    func close() { controller.rootView = AnyView(EmptyView()); window.isHidden = true; window.rootViewController = nil }
    #else
    let window: NSWindow
    let controller: NSHostingController<AnyView>
    init(_ content: AnyView) {
        controller = NSHostingController(rootView: AnyView(content.frame(width: 400, height: 500)))
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 500),
                          styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentViewController = controller
        window.makeKeyAndOrderFront(nil)
    }
    func close() { controller.rootView = AnyView(EmptyView()); window.contentViewController = nil; window.close() }
    #endif
}

@MainActor
final class LifecycleTests: XCTestCase {
    private func eventually(_ message: String, file: StaticString = #filePath, line: UInt = #line,
                            _ condition: @escaping () -> Bool) async {
        let deadline = Date().addingTimeInterval(8)
        // Allow the host run loop to commit SwiftUI layout between predicate checks.
        while !condition(), Date() < deadline { try? await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertTrue(condition(), message, file: file, line: line)
    }

    private func binding(_ probe: BindingLifecycleProbe, _ observations: Observations? = nil) -> Binding {
        Binding(source: probe.items, classify: { $0 }, identity: { AnyHashable($0.key) },
                observe: { row in if let observations { await observations.observe(row) } })
    }

    func testCrossFrameworkLeaseAcquiresAndReleasesExactlyOnce() async {
        let probe = BindingLifecycleProbe()
        let binding = binding(probe)
        let store = DeltaListCore.DeltaList<Row>()
        let task = Task { await binding.collect(into: store) }
        await eventually("collector started") { probe.activeCollectors == 1 }
        probe.append(key: "A")
        await eventually("snapshot arrived") { store.totalSize == 1 }
        XCTAssertNotNil(store.loadedItem(at: 0))
        let first = store.acquireItem(at: 0)
        let second = store.acquireItem(at: 0)
        XCTAssertNotNil(first)
        XCTAssertTrue(first?.item === second?.item)
        XCTAssertEqual(probe.acquiredCount, 1)
        first?.release(); first?.release()
        XCTAssertEqual(probe.acquiredCount, 1)
        second?.release()
        XCTAssertEqual(probe.acquiredCount, 0)
        task.cancel(); await task.value
    }

    func testDirectCollectionCancellationAndRecollection() async {
        let probe = BindingLifecycleProbe()
        let binding = binding(probe)
        let store = DeltaListCore.DeltaList<Row>()
        var failures = 0
        store.onError = { _ in failures += 1 }
        for i in 0..<3 {
            let task = Task { await binding.collect(into: store) }
            await eventually("one live collector") { probe.activeCollectors == 1 }
            probe.append(key: "row-\(i)")
            await eventually("cross-framework delta applied") { store.totalSize == i + 1 }
            task.cancel()
            await task.value
            await eventually("source stopped") { probe.activeCollectors == 0 }
        }
        probe.append(key: "after-cancel")
        XCTAssertEqual(store.totalSize, 3)
        XCTAssertEqual(failures, 0)
    }

    func testErrorAndAlreadyCancelledCollection() async {
        enum Failure: Error { case expected }
        let stream = AsyncThrowingStream<AnyObject, Error> { $0.finish(throwing: Failure.expected) }
        let binding = Binding(source: stream, classify: { $0 }, identity: { $0.key })
        let store = DeltaListCore.DeltaList<Row>()
        var failures = 0
        store.onError = { _ in failures += 1 }
        await binding.collect(into: store)
        XCTAssertEqual(failures, 1)
        let task = Task { await binding.collect(into: store) }
        task.cancel()
        await task.value
        XCTAssertEqual(failures, 1)
    }

    func testEmptyContainerOwnedForEachAndReopening() async {
        for kind in 0..<3 {
            let probe = BindingLifecycleProbe()
            let observations = Observations()
            let binding = binding(probe, observations)
            let store = DeltaListCore.DeltaList<Row>()
            for cycle in 0..<2 {
                let rows = DeltaListCore.DeltaForEach(binding, observing: store) { Text($0.key) }
                let container: AnyView
                switch kind {
                case 0: container = AnyView(Form { rows })
                case 1: container = AnyView(List { rows })
                default: container = AnyView(VStack { rows })
                }
                let host = MountedView(AnyView(container.task { await binding.collect(into: store) }))
                await eventually("empty container starts source") { probe.activeCollectors == 1 }
                probe.append(key: "first")
                await eventually("first row observed and pinned") {
                    observations.active["first"] != nil && probe.acquiredCount == 1
                }
                let identity = observations.active["first"]
                probe.append(key: "second")
                await eventually("second row observed") { observations.active.count == 2 }
                let starts = observations.starts["first"]
                probe.move(from: 0, to: 1)
                await eventually("move applied to store") { store.loadedItem(at: 1)?.key == "first" }
                await eventually("move preserves raw observation") { observations.active["first"] == identity }
                XCTAssertEqual(observations.starts["first"], starts, "move retains the observation task")
                probe.clear()
                await eventually("empty releases rows") { observations.active.isEmpty && probe.acquiredCount == 0 }
                probe.append(key: "again-\(cycle)")
                await eventually("row after empty") { observations.active.count == 1 }
                host.close()
                await eventually("unmount stops source and rows") {
                    probe.activeCollectors == 0 && observations.active.isEmpty && probe.acquiredCount == 0
                }
                probe.clear()
            }
        }
    }

    func testMountedRowChangesBindingAndStore() async {
        let first = BindingLifecycleProbe()
        first.append(key: "A")
        let second = BindingLifecycleProbe()
        second.append(key: "A")
        let firstObservations = Observations()
        let secondObservations = Observations()
        let firstBinding = binding(first, firstObservations)
        let secondBinding = binding(second, secondObservations)
        let firstStore = DeltaListCore.DeltaList<Row>()
        let secondStore = DeltaListCore.DeltaList<Row>()
        let firstTask = Task { await firstBinding.collect(into: firstStore) }
        let secondTask = Task { await secondBinding.collect(into: secondStore) }
        await eventually("both stores loaded") { firstStore.totalSize == 1 && secondStore.totalSize == 1 }
        let configuration = RowConfiguration(binding: firstBinding, store: firstStore)
        let host = MountedView(AnyView(ConfiguredRows(configuration: configuration)))
        await eventually("first observer mounted") { firstObservations.active.count == 1 && first.acquiredCount == 1 }
        let rawIdentity = firstObservations.active["A"]
        configuration.binding = secondBinding
        await eventually("new binding observes same raw object") {
            firstObservations.active.isEmpty && secondObservations.active["A"] == rawIdentity
        }
        configuration.store = secondStore
        await eventually("new store owns mounted row") {
            first.acquiredCount == 0 && second.acquiredCount == 1 &&
                secondObservations.active["A"] != nil && secondObservations.active["A"] != rawIdentity
        }
        host.close()
        await eventually("replacement row released") { second.acquiredCount == 0 && secondObservations.active.isEmpty }
        firstTask.cancel(); secondTask.cancel()
        await firstTask.value; await secondTask.value
    }

    func testCollectionViewLeaseAndBindingReplacement() async {
        let first = BindingLifecycleProbe()
        first.reload(keys: ["A", "B"])
        let second = BindingLifecycleProbe()
        second.append(key: "C")
        #if canImport(UIKit)
        let collection = UICollectionView(frame: .zero, collectionViewLayout: UICollectionViewFlowLayout())
        let dataSource = DeltaListCore.DeltaCollectionDataSource<Row>(collectionView: collection) { _, _, _ in
            UICollectionViewCell(frame: .zero)
        }
        #else
        let collection = NSCollectionView(frame: .zero)
        collection.collectionViewLayout = NSCollectionViewFlowLayout()
        let dataSource = DeltaListCore.DeltaNSCollectionDataSource<Row>(collectionView: collection) { _, _, _ in
            let item = NSCollectionViewItem()
            item.view = NSView()
            return item
        }
        #endif
        binding(first).bind(to: dataSource)
        await eventually("first native binding") { first.activeCollectors == 1 && dataSource.totalSize == 2 }
        XCTAssertEqual(first.acquiredCount, 0)
        #if canImport(UIKit)
        let cell = dataSource.collectionView(collection, cellForItemAt: IndexPath(item: 0, section: 0))
        #else
        let cell = dataSource.collectionView(collection, itemForRepresentedObjectAt: IndexPath(item: 0, section: 0))
        #endif
        XCTAssertEqual(first.acquiredCount, 1)
        first.move(from: 0, to: 1)
        await eventually("native move applied") { dataSource.getLoadedItemAt(index: 1)?.key == "A" }
        #if canImport(UIKit)
        dataSource.collectionView(collection, didEndDisplaying: cell, forItemAt: IndexPath(item: 0, section: 0))
        #else
        dataSource.collectionView(collection, didEndDisplaying: cell, forRepresentedObjectAt: IndexPath(item: 0, section: 0))
        #endif
        XCTAssertEqual(first.acquiredCount, 0)
        binding(second).bind(to: dataSource)
        await eventually("replacement cancels old source") {
            first.activeCollectors == 0 && second.activeCollectors == 1 && dataSource.totalSize == 1
        }
        dataSource.unbind()
        await eventually("native unbind") { second.activeCollectors == 0 && second.acquiredCount == 0 }
    }

    func testConvenienceListsPinMountedRowsAndCancel() async {
        for lazy in [false, true] {
            let probe = BindingLifecycleProbe()
            let observations = Observations()
            let binding = binding(probe, observations)
            let content: AnyView = lazy
                ? AnyView(DeltaListCore.DeltaLazyListView(binding, loading: { _ in ProgressView() }) { Text($0.key) })
                : AnyView(DeltaListCore.DeltaListView(binding) { Text($0.key) })
            let host = MountedView(content)
            await eventually("empty list starts") { probe.activeCollectors == 1 }
            probe.reload(keys: (0..<100).map { "row-\($0)" })
            await eventually("visible rows acquired") { probe.acquiredCount > 0 && !observations.active.isEmpty }
            XCTAssertLessThan(probe.acquiredCount, 100)
            host.close()
            await eventually("list teardown") { probe.activeCollectors == 0 && observations.active.isEmpty && probe.acquiredCount == 0 }
        }
    }
}
