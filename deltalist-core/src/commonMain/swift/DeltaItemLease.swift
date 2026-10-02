import Foundation

@objc private protocol DeltaLeaseRuntimeABI {
    @objc(acquireItemAtIndex:) func acquireItemAt(index: Int32) -> AnyObject?
    var item: AnyObject? { get }
    @objc(release_) func release()
}

/// One native renderer's ownership. The Kotlin handle resolves release by cache identity.
public final class DeltaItemLease<T: AnyObject> {
    public let item: T
    private var cleanup: (() -> Void)?
    init(item: T, cleanup: @escaping () -> Void) {
        self.item = item
        self.cleanup = cleanup
    }
    public func release() {
        let action = cleanup
        cleanup = nil
        action?()
    }
    deinit { cleanup?() }
}

func acquireDeltaItem<T: AnyObject>(_ delta: AnyObject, index: Int) -> DeltaItemLease<T>? {
    if let typed = delta as? Delta<T> {
        guard let lease = typed.acquireItemAt(index: Int32(index)) else { return nil }
        guard let item = lease.item as? T else { lease.release(); return nil }
        return DeltaItemLease(item: item) { lease.release() }
    }
    // The consumer framework exports distinct Obj-C classes. Do not cast its lease to ours.
    let acquire = #selector(DeltaLeaseRuntimeABI.acquireItemAt(index:))
    typealias Acquire = @convention(c) (AnyObject, Selector, Int32) -> AnyObject?
    guard let acquireIMP = DeltaIMPCache.shared.imp(for: delta, acquire),
          let lease = unsafeBitCast(acquireIMP, to: Acquire.self)(delta, acquire, Int32(index)) else { return nil }
    let itemSelector = #selector(getter: DeltaLeaseRuntimeABI.item)
    let releaseSelector = #selector(DeltaLeaseRuntimeABI.release)
    typealias Read = @convention(c) (AnyObject, Selector) -> AnyObject?
    typealias Release = @convention(c) (AnyObject, Selector) -> Void
    guard let releaseIMP = DeltaIMPCache.shared.imp(for: lease, releaseSelector) else { return nil }
    let cleanup = { unsafeBitCast(releaseIMP, to: Release.self)(lease, releaseSelector) }
    guard let readIMP = DeltaIMPCache.shared.imp(for: lease, itemSelector),
          let item = unsafeBitCast(readIMP, to: Read.self)(lease, itemSelector) as? T else {
        cleanup()
        return nil
    }
    return DeltaItemLease(item: item, cleanup: cleanup)
}
