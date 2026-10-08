import XCTest
import DeltaListCore
#if canImport(UIKit)
import UIKit
private typealias Collection = UICollectionView
private typealias NativeItem = UICollectionViewCell
private final class RegressionItem: UICollectionViewCell { var rendered = "" }
#else
import AppKit
private typealias Collection = NSCollectionView
private typealias NativeItem = NSCollectionViewItem
private final class RegressionItem: NSCollectionViewItem {
    var rendered = ""
    override func loadView() { view = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 40)) }
}
#endif

@MainActor
private final class CollectionFixture {
    let collection: Collection
    #if canImport(UIKit)
    let window: UIWindow
    init() {
        let layout = UICollectionViewFlowLayout()
        layout.itemSize = CGSize(width: 360, height: 40)
        collection = UICollectionView(frame: CGRect(x: 0, y: 0, width: 400, height: 500), collectionViewLayout: layout)
        collection.register(RegressionItem.self, forCellWithReuseIdentifier: "regression")
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!
        window = UIWindow(windowScene: scene)
        window.frame = collection.frame
        let controller = UIViewController()
        controller.view = collection
        window.rootViewController = controller
        window.makeKeyAndVisible()
    }
    func item(_ indexPath: IndexPath, text: String) -> NativeItem {
        let item = collection.dequeueReusableCell(withReuseIdentifier: "regression", for: indexPath) as! RegressionItem
        item.rendered = text
        return item
    }
    func rendered(_ indexPath: IndexPath) -> String? {
        collection.layoutIfNeeded()
        return (collection.cellForItem(at: indexPath) as? RegressionItem)?.rendered
    }
    func close() { window.isHidden = true; window.rootViewController = nil }
    #else
    let window: NSWindow
    init() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 500),
                          styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 400, height: 500))
        collection = NSCollectionView(frame: scroll.bounds)
        let layout = NSCollectionViewFlowLayout()
        layout.itemSize = NSSize(width: 360, height: 40)
        collection.collectionViewLayout = layout
        collection.register(RegressionItem.self, forItemWithIdentifier: NSUserInterfaceItemIdentifier("regression"))
        scroll.documentView = collection
        window.contentView = scroll
        window.makeKeyAndOrderFront(nil)
    }
    func item(_ indexPath: IndexPath, text: String) -> NativeItem {
        let item = collection.makeItem(withIdentifier: NSUserInterfaceItemIdentifier("regression"), for: indexPath) as! RegressionItem
        item.rendered = text
        return item
    }
    func rendered(_ indexPath: IndexPath) -> String? {
        collection.layoutSubtreeIfNeeded()
        return (collection.item(at: indexPath) as? RegressionItem)?.rendered
    }
    func close() { window.contentView = nil; window.close() }
    #endif
}

@MainActor
final class AdapterRegressionTests: XCTestCase {
    private func eventually(_ message: String, file: StaticString = #filePath, line: UInt = #line,
                            _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(8)
        while !condition(), Date() < deadline { try? await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertTrue(condition(), message, file: file, line: line)
    }

    func testSectionMovesExposeIntermediateItemCounts() async {
        typealias Section = DeltaListCore.Section<NSString, NSString>
        typealias Delta = DeltaListCore.SectionedDelta<NSString, NSString>
        let a = Section(header: "A", items: ["a" as NSString])
        let b = Section(header: "B", items: ["b1" as NSString, "b2" as NSString])
        let c = Section(header: "C", items: ["c1" as NSString, "c2" as NSString, "c3" as NSString])
        var continuation: AsyncStream<Delta>.Continuation!
        let stream = AsyncStream<Delta> { continuation = $0 }
        let fixture = CollectionFixture()
        #if canImport(UIKit)
        let dataSource = DeltaListCore.SectionedDeltaCollectionDataSource<NSString, NSString>(
            collectionView: fixture.collection, cellProvider: { _, path, value in fixture.item(path, text: value as String) })
        #else
        let dataSource = DeltaListCore.SectionedDeltaNSCollectionDataSource<NSString, NSString>(
            collectionView: fixture.collection, itemProvider: { _, path, value in fixture.item(path, text: value as String) })
        #endif
        defer { dataSource.unbind(); fixture.close() }
        dataSource.bind(to: stream)
        continuation.yield(Delta(sections: [a, b, c], change: SectionedChange.Reload.shared))
        await eventually("initial sections rendered") { fixture.rendered(IndexPath(item: 0, section: 2)) == "c1" }
        continuation.yield(Delta(sections: [c, b, a], change: SectionedChange.Sections(mutations: [
            SectionMutation.Move(fromIndex: 2, toIndex: 0), SectionMutation.Move(fromIndex: 2, toIndex: 1)
        ])))
        await eventually("running section moves rendered") {
            fixture.rendered(IndexPath(item: 0, section: 0)) == "c1" &&
            fixture.rendered(IndexPath(item: 0, section: 1)) == "b1" &&
            fixture.rendered(IndexPath(item: 0, section: 2)) == "a"
        }
        XCTAssertEqual((0..<3).map { fixture.collection.numberOfItems(inSection: $0) }, [3, 2, 1])
    }
}
