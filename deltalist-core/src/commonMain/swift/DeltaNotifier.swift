#if canImport(UserNotifications)
import Foundation
import UserNotifications

// Drives the iOS notification tray from a DeltaList. Swift counterpart of the Kotlin
// `deltalist-android-notifications` module: inserts post, updates re-post the same identifier,
// removals cancel, and moves are no-ops. A reload re-posts only the survivors `shouldRepost`
// accepts. An item whose content is nil is kept off the tray.
//
// Read items only via `delta.acquireLoadedItems()` — touching `delta.items` bridges (and
// force-loads) the entire backing list, which is catastrophic for soft/paginated lists, and
// `loadedItems()` peeks, so a `lazyMap`ped row would be a new object on every delta.
//
// Implementation note: the reconciler, sink and entry types are intentionally NON-generic and
// operate on `AnyObject`. A generic value-type with closure/existential fields crashes the
// compiler's LoadableByAddress pass under library evolution (the resilient mode SKIE compiles the
// framework with). Only the public `DeltaNotifier<T>` shell is generic; it erases `T` to `AnyObject`
// at the boundary and casts back inside the user's closures.

// MARK: - Interaction

/// A user interaction with a posted notification, decoded from the delegate callback.
public struct NotificationInteraction {
    public enum Kind { case dismiss, contentTap, action }

    /// The notification's identifier, which is the item's tray key.
    public let key: String
    /// The id space of a stable-id notifier's `tag#id` identifier, or nil for another identifier.
    public let idSpaceTag: String?
    /// The composed tray id (`idBase + stableId`) of a `tag#id` identifier. Subtract the
    /// notifier's `idBase` to recover the item's stable id; exposed raw so cold handlers can
    /// route without that context.
    public let notifId: Int32?
    public let kind: Kind
    public let actionKey: String?

    init(identifier: String, actionIdentifier: String) {
        self.key = identifier
        if let hash = identifier.lastIndex(of: "#"),
           let composed = Int32(identifier[identifier.index(after: hash)...]) {
            self.idSpaceTag = String(identifier[..<hash])
            self.notifId = composed
        } else {
            self.idSpaceTag = nil
            self.notifId = nil
        }
        switch actionIdentifier {
        case UNNotificationDefaultActionIdentifier:
            self.kind = .contentTap
            self.actionKey = nil
        case UNNotificationDismissActionIdentifier:
            self.kind = .dismiss
            self.actionKey = nil
        default:
            self.kind = .action
            self.actionKey = actionIdentifier
        }
    }
}

/// What a notifier did to the tray for one item, as `DeltaNotifier.operations()` reports it. A
/// caller about to be suspended (a notification action, a background push) can wait for the
/// operation on its key before completing.
public struct TrayOperation {
    public enum Kind { case posted, adopted, cancelled }

    public let key: String
    public let kind: Kind
}

// MARK: - Content scope

/// Passed into the notifier's `content` closure. Mirrors the Kotlin `NotificationScope`: it
/// exposes the item, its tray key, its stable id and the latest `itemState` value, and pre-wires
/// the `threadIdentifier` (group) and `categoryIdentifier` (actions) so callers only set visuals.
@available(iOS 14.0, *)
public final class NotificationScope<T: AnyObject> {
    public let item: T
    /// The notification's identifier: the item's key, or `tag#id` for a stable-id notifier.
    public let key: String
    /// The item's session-stable id, or nil for a keyed notifier.
    public let stableId: Int32?
    public let state: Any?

    private let threadGroupKey: String?
    private let defaultCategoryId: String?

    init(item: T, key: String, stableId: Int32?, state: Any?, threadGroupKey: String?, defaultCategoryId: String?) {
        self.item = item
        self.key = key
        self.stableId = stableId
        self.state = state
        self.threadGroupKey = threadGroupKey
        self.defaultCategoryId = defaultCategoryId
    }

    /// Builds content with the group thread and category already attached. Fill in
    /// title/body/sound/etc. in `build`.
    public func content(
        categoryId: String? = nil,
        _ build: (UNMutableNotificationContent) -> Void
    ) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        if let key = threadGroupKey { content.threadIdentifier = key }
        if let category = categoryId ?? defaultCategoryId { content.categoryIdentifier = category }
        build(content)
        return content
    }
}

// MARK: - Sink

/// The side-effecting surface the reconciler drives. Non-generic (items are `AnyObject`) and a
/// reference type, both to keep the compiler's LoadableByAddress pass happy. Mirrors the Kotlin
/// `NotificationSink`; a fake can be substituted for testing.
///
/// `post` renders an item under its key; nil content cancels the key, posted here or not. On an
/// item's first render (the `Bool`), a notification already delivered may be adopted instead.
@available(iOS 14.0, *)
final class NotificationSink {
    let post: (String, AnyObject, Any?, Bool) -> Void
    let cancel: (String) -> Void

    init(
        post: @escaping (String, AnyObject, Any?, Bool) -> Void,
        cancel: @escaping (String) -> Void
    ) {
        self.post = post
        self.cancel = cancel
    }
}

/// Production sink over `UNUserNotificationCenter`. Re-adding a request with the same identifier
/// replaces the delivered notification, so update/re-post needs no manual diffing.
///
/// Operations run one at a time, in order: checking for a delivered notification is asynchronous,
/// and a cancel must never overtake the post before it.
@available(iOS 14.0, *)
@MainActor
final class CenterNotificationSink {
    private let center: UNUserNotificationCenter
    private let categories: [UNNotificationCategory]
    private let buildContent: (AnyObject, Any?) -> UNNotificationContent?
    private let adoptDelivered: ((AnyObject, UNNotification) -> Bool)?
    private let report: (TrayOperation) -> Void
    private var tail: Task<Void, Never>?

    init(
        center: UNUserNotificationCenter,
        categories: [UNNotificationCategory],
        buildContent: @escaping (AnyObject, Any?) -> UNNotificationContent?,
        adoptDelivered: ((AnyObject, UNNotification) -> Bool)?,
        report: @escaping (TrayOperation) -> Void
    ) {
        self.center = center
        self.categories = categories
        self.buildContent = buildContent
        self.adoptDelivered = adoptDelivered
        self.report = report
        registerCategories()
    }

    /// Categories are process-global on iOS, so union with whatever another feature registered
    /// rather than overwriting it.
    private func registerCategories() {
        guard !categories.isEmpty else { return }
        let toAdd = Set(categories)
        center.getNotificationCategories { [center] existing in
            center.setNotificationCategories(existing.union(toAdd))
        }
    }

    private func enqueue(_ operation: @escaping @MainActor () async -> Void) {
        let previous = tail
        tail = Task { @MainActor in
            await previous?.value
            await operation()
        }
    }

    func post(key: String, item: AnyObject, state: Any?, isFirst: Bool) {
        // Built now, from the item and state this render saw.
        let content = buildContent(item, state)
        let center = self.center
        let adoptDelivered = self.adoptDelivered
        let report = self.report
        enqueue {
            guard let content = content else {
                Self.remove(key, from: center)
                report(TrayOperation(key: key, kind: .cancelled))
                return
            }
            if isFirst, let adoptDelivered = adoptDelivered {
                let delivered = await Self.delivered(key, in: center)
                if let delivered = delivered, adoptDelivered(item, delivered) {
                    report(TrayOperation(key: key, kind: .adopted))
                    return
                }
            }
            let request = UNNotificationRequest(identifier: key, content: content, trigger: nil)
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                center.add(request) { _ in continuation.resume() }
            }
            report(TrayOperation(key: key, kind: .posted))
        }
    }

    func cancel(key: String) {
        let center = self.center
        let report = self.report
        enqueue {
            Self.remove(key, from: center)
            report(TrayOperation(key: key, kind: .cancelled))
        }
    }

    private static func remove(_ key: String, from center: UNUserNotificationCenter) {
        center.removeDeliveredNotifications(withIdentifiers: [key])
        center.removePendingNotificationRequests(withIdentifiers: [key])
    }

    private static func delivered(_ key: String, in center: UNUserNotificationCenter) async -> UNNotification? {
        await withCheckedContinuation { (continuation: CheckedContinuation<UNNotification?, Never>) in
            center.getDeliveredNotifications { notifications in
                continuation.resume(returning: notifications.first { $0.request.identifier == key })
            }
        }
    }

    func asSink() -> NotificationSink {
        NotificationSink(
            post: { [weak self] in self?.post(key: $0, item: $1, state: $2, isFirst: $3) },
            cancel: { [weak self] in self?.cancel(key: $0) }
        )
    }
}

// MARK: - Reconciler

/// One tracked tray entry.
@available(iOS 14.0, *)
final class TrayEntry {
    let key: String
    var item: AnyObject
    var state: Any?
    var isRendered = false
    var stateTask: Task<Void, Never>?
    var stateGeneration = UUID()
    var lastPostAt: Date?
    var pendingFlush: Task<Void, Never>?
    var pendingState: Any?

    init(key: String, item: AnyObject, state: Any?) {
        self.key = key
        self.item = item
        self.state = state
    }
}

/// Translates a stream of item snapshots into post/cancel operations, keyed by tray key.
/// Direct port of the Kotlin `TrayController` (tray order is irrelevant on iOS, so a plain
/// dictionary replaces the ordered map).
///
/// A survivor re-posts only when `shouldRepost` says so, or its state changed; a reload doesn't
/// force one. With a state subscription but no initial state, an item's first render waits for
/// its first state.
@available(iOS 14.0, *)
@MainActor
final class TrayController {
    private let sink: NotificationSink
    private let key: (AnyObject) -> String
    private let shouldRepost: (AnyObject, AnyObject) -> Bool
    private let stateSubscribe: ((AnyObject, @escaping (Any?) -> Void) -> Task<Void, Never>)?
    private let stateInitial: ((AnyObject) -> Any?)?
    private let samplePeriod: TimeInterval

    private var entries: [String: TrayEntry] = [:]

    init(
        sink: NotificationSink,
        key: @escaping (AnyObject) -> String,
        shouldRepost: @escaping (AnyObject, AnyObject) -> Bool,
        stateSubscribe: ((AnyObject, @escaping (Any?) -> Void) -> Task<Void, Never>)?,
        stateInitial: ((AnyObject) -> Any?)?,
        rateLimitPerSecond: Int
    ) {
        self.sink = sink
        self.key = key
        self.shouldRepost = shouldRepost
        self.stateSubscribe = stateSubscribe
        self.stateInitial = stateInitial
        self.samplePeriod = 1.0 / Double(max(1, rateLimitPerSecond))
    }

    var keys: Set<String> { Set(entries.keys) }

    func valueFor(_ key: String) -> AnyObject? { entries[key]?.item }

    private var waitsForFirstState: Bool { stateSubscribe != nil && stateInitial == nil }

    func applyDelta(items newItems: [AnyObject]) {
        var newKeys = Set<String>()
        for item in newItems { newKeys.insert(key(item)) }

        for (key, entry) in entries where !newKeys.contains(key) {
            entry.stateTask?.cancel()
            entry.pendingFlush?.cancel()
            sink.cancel(key)
            entries.removeValue(forKey: key)
        }

        for item in newItems {
            let key = self.key(item)
            if let existing = entries[key] {
                let valueChanged = shouldRepost(existing.item, item)
                let replaced = existing.item !== item
                let oldState = existing.state
                if replaced {
                    // Ownership of the observation follows the raw item, independently of the
                    // caller's value-equality policy for snapshot reposts.
                    existing.stateGeneration = UUID()
                    existing.stateTask?.cancel()
                    existing.pendingFlush?.cancel()
                    existing.pendingFlush = nil
                    existing.pendingState = nil
                    if let stateInitial = stateInitial { existing.state = stateInitial(item) }
                    existing.lastPostAt = nil
                }
                existing.item = item
                if existing.isRendered && (valueChanged || (replaced && !statesEqual(oldState, existing.state))) {
                    sink.post(key, item, existing.state, false)
                }
                if replaced { startStateTask(existing) }
            } else {
                let entry = TrayEntry(key: key, item: item, state: stateInitial?(item))
                entries[key] = entry
                if !waitsForFirstState { render(entry) }
                startStateTask(entry)
            }
        }
    }

    func cancelAll() {
        for entry in entries.values {
            entry.stateTask?.cancel()
            entry.pendingFlush?.cancel()
            sink.cancel(entry.key)
        }
        entries.removeAll()
    }

    /// Stop per-item state collection but leave posted notifications standing.
    func stop() {
        for entry in entries.values {
            entry.stateTask?.cancel()
            entry.stateTask = nil
            entry.pendingFlush?.cancel()
            entry.pendingFlush = nil
            entry.pendingState = nil
        }
    }

    private func render(_ entry: TrayEntry) {
        let isFirst = !entry.isRendered
        entry.isRendered = true
        sink.post(entry.key, entry.item, entry.state, isFirst)
    }

    private func startStateTask(_ entry: TrayEntry) {
        guard let stateSubscribe = stateSubscribe else { return }
        let generation = UUID()
        entry.stateGeneration = generation
        entry.stateTask = stateSubscribe(entry.item) { [weak self, weak entry] newState in
            guard let self = self, let entry = entry, entry.stateGeneration == generation else { return }
            self.onState(entry, newState)
        }
    }

    // Trailing-edge throttle: coalesce rapid emissions to at most one re-post per sample period,
    // always applying the most recent value. Equivalent to the Kotlin `sample()`. An item still
    // waiting for its first state renders with it at once.
    private func onState(_ entry: TrayEntry, _ newState: Any?) {
        if !entry.isRendered {
            entry.state = newState
            entry.lastPostAt = Date()
            render(entry)
            return
        }
        if statesEqual(newState, entry.state) {
            // A reversion supersedes a pending change just like any other emission.
            entry.pendingFlush?.cancel()
            entry.pendingFlush = nil
            entry.pendingState = nil
            return
        }

        let now = Date()
        let elapsed = entry.lastPostAt.map { now.timeIntervalSince($0) } ?? .greatestFiniteMagnitude
        if elapsed >= samplePeriod {
            applyState(entry, newState)
        } else {
            entry.pendingState = newState
            guard entry.pendingFlush == nil else { return }
            let delay = samplePeriod - elapsed
            entry.pendingFlush = Task { @MainActor [weak self, weak entry] in
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                guard let self = self, let entry = entry, !Task.isCancelled else { return }
                entry.pendingFlush = nil
                let latest = entry.pendingState
                entry.pendingState = nil
                if !self.statesEqual(latest, entry.state) {
                    self.applyState(entry, latest)
                }
            }
        }
    }

    private func applyState(_ entry: TrayEntry, _ newState: Any?) {
        entry.state = newState
        entry.lastPostAt = Date()
        entry.pendingFlush?.cancel()
        entry.pendingFlush = nil
        entry.pendingState = nil
        sink.post(entry.key, entry.item, newState, false)
    }

    private func statesEqual(_ a: Any?, _ b: Any?) -> Bool {
        if a == nil && b == nil { return true }
        guard let x = a, let y = b else { return false }
        if let xo = x as? NSObject, let yo = y as? NSObject { return xo.isEqual(yo) }
        return (x as AnyObject) === (y as AnyObject)
    }
}

// MARK: - Notifier

/// The tray keys a notifier tracks, readable from the router's delegate callbacks on any thread.
@available(iOS 14.0, *)
final class TrackedKeys: @unchecked Sendable {
    private let lock = NSLock()
    private var keys = Set<String>()

    func replace(with keys: Set<String>) {
        lock.lock(); self.keys = keys; lock.unlock()
    }

    func contains(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return keys.contains(key)
    }
}

/// Drives the system tray from a `DeltaList`. Bind it to a delta stream with `bind(to:)` (same
/// module) or `bind(erased:)` (SKIE cross-module flows). By default `unbind()` leaves posted
/// notifications standing; set `cancelOnUnbind` or call `cancelAll()` for deterministic teardown.
///
/// Each item's notification identifier is its key: `tag#(idBase + stableId)` for a stable-id
/// notifier, or the item's own durable key (such as a server-issued notification id, which is
/// also the identifier of a remote notification sent with that `apns-collapse-id`). With
/// `adoptDelivered`, an item whose notification is already delivered is adopted rather than
/// posted again.
///
/// Interaction routing requires the host app to install or forward to `DeltaNotificationRouter`
/// (see its docs) — iOS has a single notification-center delegate per process, so unlike Android
/// there is no automatic manifest-merged receiver.
@available(iOS 14.0, *)
@MainActor
public final class DeltaNotifier<T: AnyObject> {
    public nonisolated let idSpaceTag: String
    public nonisolated let foregroundPresentation: UNNotificationPresentationOptions

    private let center: UNUserNotificationCenter
    private let categories: [UNNotificationCategory]
    private let threadGroupKey: String?
    private let key: (T) -> String
    // Stored erased to AnyObject (not T): an optional function-type carrying the generic parameter
    // crashes the compiler's archetype mapper under library evolution.
    private let stableIdOf: ((AnyObject) -> Int32)?
    private let content: (NotificationScope<T>) -> UNNotificationContent?

    /// Leave posted notifications standing on `unbind` unless set. Defaults to false.
    public var cancelOnUnbind: Bool = false

    private let rateLimitPerSecond: Int
    private var shouldRepost: (T, T) -> Bool = { $0 !== $1 }
    // Stored erased to AnyObject (not T): a nested function-type carrying the generic parameter
    // crashes the compiler's archetype mapper under library evolution.
    private var stateInitial: ((AnyObject) -> Any?)?
    private var stateSubscribe: ((AnyObject, @escaping (Any?) -> Void) -> Task<Void, Never>)?
    private var adoptDelivered: ((AnyObject, UNNotification) -> Bool)?
    private var onContentTap: ((T) -> Void)?
    private var onDismiss: ((T) -> Void)?
    private var onAction: ((T, String) -> Void)?

    private var controller: TrayController?
    private var task: Task<Void, Never>?
    private var listeners: [UUID: AsyncStream<TrayOperation>.Continuation] = [:]
    nonisolated let tracked = TrackedKeys()
    // Retains the production sink for the notifier's lifetime. The TrayController only holds the
    // erased `NotificationSink`, whose closures reference this object weakly, so without a strong
    // reference here the sink would deallocate as soon as `start()` returns and every post/cancel
    // would silently become a no-op.
    private var sink: CenterNotificationSink?

    /// A notifier whose identifiers are `tag#(idBase + stableId)`, for session stable ids.
    public convenience init(
        tag: String,
        idBase: Int32 = 0,
        stableId: @escaping (T) -> Int32,
        categories: [UNNotificationCategory] = [],
        threadGroupKey: String? = nil,
        foregroundPresentation: UNNotificationPresentationOptions = [.banner, .list, .sound],
        center: UNUserNotificationCenter = .current(),
        content: @escaping (NotificationScope<T>) -> UNNotificationContent
    ) {
        self.init(
            tag: tag,
            key: { "\(tag)#\(idBase + stableId($0))" },
            stableIdOf: { stableId($0 as! T) },
            categories: categories,
            threadGroupKey: threadGroupKey,
            foregroundPresentation: foregroundPresentation,
            center: center,
            content: { scope in content(scope) }
        )
    }

    /// A notifier whose identifiers are each item's durable `key`, used verbatim. Return nil
    /// content to keep an item off the tray: its notification is removed, whether or not this
    /// notifier posted it. The `tag` only names this notifier.
    public convenience init(
        tag: String,
        key: @escaping (T) -> String,
        categories: [UNNotificationCategory] = [],
        threadGroupKey: String? = nil,
        foregroundPresentation: UNNotificationPresentationOptions = [.banner, .list, .sound],
        center: UNUserNotificationCenter = .current(),
        content: @escaping (NotificationScope<T>) -> UNNotificationContent?
    ) {
        self.init(
            tag: tag,
            key: key,
            stableIdOf: nil,
            categories: categories,
            threadGroupKey: threadGroupKey,
            foregroundPresentation: foregroundPresentation,
            center: center,
            content: content
        )
    }

    private init(
        tag: String,
        key: @escaping (T) -> String,
        stableIdOf: ((AnyObject) -> Int32)?,
        categories: [UNNotificationCategory],
        threadGroupKey: String?,
        foregroundPresentation: UNNotificationPresentationOptions,
        center: UNUserNotificationCenter,
        content: @escaping (NotificationScope<T>) -> UNNotificationContent?
    ) {
        self.idSpaceTag = tag
        self.key = key
        self.stableIdOf = stableIdOf
        self.categories = categories
        self.threadGroupKey = threadGroupKey
        self.foregroundPresentation = foregroundPresentation
        self.center = center
        self.content = content
        self.rateLimitPerSecond = 8
    }

    // MARK: Fluent configuration
    //
    // These return the concrete `DeltaNotifier<T>`, not `Self`. A dynamic `Self` return on a generic
    // class sends the compiler's LoadableByAddress pass into infinite archetype recursion under
    // library evolution — do not "simplify" these back to `-> Self`.

    /// Optional per-item observable state; each emission re-runs `content` and re-posts the same
    /// key. Without an `initial` state, an item's first render waits for its first emission.
    @discardableResult
    public func itemState<S: AsyncSequence>(
        initial: ((T) -> Any?)? = nil,
        _ accessor: @escaping (T) -> S
    ) -> DeltaNotifier<T> {
        if let initial = initial {
            stateInitial = { obj in initial(obj as! T) }
        }
        stateSubscribe = { obj, emit in
            let item = obj as! T
            return Task { @MainActor in
                do {
                    for try await value in accessor(item) {
                        if Task.isCancelled { break }
                        emit(value as Any?)
                    }
                } catch {}
            }
        }
        return self
    }

    /// Override how a surviving item decides to re-post, on an update or a reload. Defaults to
    /// reference identity (`!==`), matching Kotlin; supply value equality for snapshot items.
    @discardableResult
    public func shouldRepost(_ predicate: @escaping (T, T) -> Bool) -> DeltaNotifier<T> {
        shouldRepost = predicate
        return self
    }

    /// Adopts an item's delivered notification instead of posting it again, when `predicate`
    /// accepts it. Only an item's first render adopts; a hidden item (nil content) is removed
    /// instead. Without this, every first render posts.
    @discardableResult
    public func adoptDelivered(
        _ predicate: @escaping (T, UNNotification) -> Bool = { _, _ in true }
    ) -> DeltaNotifier<T> {
        adoptDelivered = { item, delivered in predicate(item as! T, delivered) }
        return self
    }

    @discardableResult
    public func onContentTap(_ handler: @escaping (T) -> Void) -> DeltaNotifier<T> {
        onContentTap = handler
        return self
    }

    @discardableResult
    public func onDismiss(_ handler: @escaping (T) -> Void) -> DeltaNotifier<T> {
        onDismiss = handler
        return self
    }

    @discardableResult
    public func onAction(_ handler: @escaping (T, String) -> Void) -> DeltaNotifier<T> {
        onAction = handler
        return self
    }

    /// Every post, adoption and removal from now on, as the notification center completes it.
    /// Nothing is replayed.
    public func operations() -> AsyncStream<TrayOperation> {
        let id = UUID()
        return AsyncStream { continuation in
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor [weak self] in self?.listeners.removeValue(forKey: id) }
            }
        }
    }

    // MARK: Binding

    @discardableResult
    public func bind<S: AsyncSequence>(to stream: S) -> DeltaNotifier<T> where S.Element == Delta<T> {
        let controller = start()
        let tracked = self.tracked
        task = Task { @MainActor [weak self] in
            do {
                for try await delta in stream {
                    if Task.isCancelled || self == nil { break }
                    controller.applyDelta(items: delta.acquireLoadedItems().map { $0 as AnyObject })
                    tracked.replace(with: controller.keys)
                }
            } catch {}
        }
        return self
    }

    @discardableResult
    public func bind(erased stream: some AsyncSequence) -> DeltaNotifier<T> {
        let controller = start()
        let tracked = self.tracked
        task = Task { @MainActor [weak self] in
            do {
                for try await value in stream {
                    if Task.isCancelled || self == nil { break }
                    let object = value as AnyObject
                    // The concrete-typed `Delta<T>` cast is the fast path when the delta is vended by
                    // this same framework. A delta from a *different* SKIE framework (e.g. the app's
                    // own KMP framework exporting deltalist-core) is a distinct Obj-C class that none
                    // of the typed/protocol casts match, so applyErasedDelta falls back to runtime
                    // selectors. Mirrors the cascade in DeltaDataSource/DeltaList's bind(erased:).
                    if let typed = object as? Delta<T> {
                        controller.applyDelta(items: typed.acquireLoadedItems().map { $0 as AnyObject })
                    } else {
                        applyErasedDelta(object, to: controller)
                    }
                    tracked.replace(with: controller.keys)
                }
            } catch {}
        }
        return self
    }

    public func unbind() {
        DeltaNotificationRouter.shared.unregister(tag: idSpaceTag)
        task?.cancel()
        task = nil
        if cancelOnUnbind {
            controller?.cancelAll()
            tracked.replace(with: [])
        } else {
            controller?.stop()
        }
    }

    /// Remove every notification this notifier posted.
    public func cancelAll() {
        controller?.cancelAll()
        tracked.replace(with: [])
    }

    deinit {
        task?.cancel()
    }

    // MARK: Internal

    func dispatch(_ interaction: NotificationInteraction) -> Bool {
        guard let item = controller?.valueFor(interaction.key) as? T else { return false }
        switch interaction.kind {
        case .contentTap: onContentTap?(item)
        case .dismiss: onDismiss?(item)
        case .action: if let key = interaction.actionKey { onAction?(item, key) }
        }
        return true
    }

    private func report(_ operation: TrayOperation) {
        for listener in listeners.values { listener.yield(operation) }
    }

    private func start() -> TrayController {
        unbind()

        let content = self.content
        let key = self.key
        let stableIdOf = self.stableIdOf
        let threadGroupKey = self.threadGroupKey
        let defaultCategoryId = categories.first?.identifier

        let sink = CenterNotificationSink(
            center: center,
            categories: categories,
            buildContent: { item, state in
                let scope = NotificationScope<T>(
                    item: item as! T,
                    key: key(item as! T),
                    stableId: stableIdOf?(item),
                    state: state,
                    threadGroupKey: threadGroupKey,
                    defaultCategoryId: defaultCategoryId
                )
                return content(scope)
            },
            adoptDelivered: adoptDelivered,
            report: { [weak self] in self?.report($0) }
        )
        self.sink = sink

        let shouldRepost = self.shouldRepost
        let stateInitial = self.stateInitial
        let stateSubscribe = self.stateSubscribe

        let controller = TrayController(
            sink: sink.asSink(),
            key: { key($0 as! T) },
            shouldRepost: { shouldRepost($0 as! T, $1 as! T) },
            stateSubscribe: stateSubscribe,
            stateInitial: stateInitial,
            rateLimitPerSecond: rateLimitPerSecond
        )
        self.controller = controller
        DeltaNotificationRouter.shared.register(self)
        return controller
    }
}

/// Free (non-generic) helper for the erased binding path — kept off the generic notifier so its
/// generic environment doesn't reach the compiler's LoadableByAddress pass.
@available(iOS 14.0, *)
@MainActor
private func applyErasedDelta(_ value: AnyObject, to controller: TrayController) {
    if let delta = value as? Delta<AnyObject> {
        controller.applyDelta(items: delta.acquireLoadedItems().map { $0 as AnyObject })
    } else if let delta = value as? DeltaProtocol {
        controller.applyDelta(items: delta.acquireLoadedItems().map { $0 as AnyObject })
    } else if value.responds(to: DeltaSelector.acquireLoadedItems) {
        // A Delta vended by a *different* SKIE framework (e.g. the app's own KMP framework exporting
        // deltalist-core) is a DISTINCT Obj-C class, and the @objc DeltaProtocol is module-qualified,
        // so neither the typed casts nor the protocol cast match it. Call the exported Kotlin
        // accessors directly by selector — the same runtime-selector mechanism the sectioned data
        // source uses in DeltaList.swift, and the only thing that crosses the framework boundary.
        controller.applyDelta(items: acquireLoadedItemsViaRuntime(value))
    }
}

/// Calls the exported Kotlin `Delta.acquireLoadedItems()` by selector, so a Delta from any SKIE
/// framework (not just this one) can be read through its lazy cache without bridging its backing
/// list. Returns [] if unavailable.
@available(iOS 14.0, *)
private func acquireLoadedItemsViaRuntime(_ obj: AnyObject) -> [AnyObject] {
    typealias Fn = @convention(c) (AnyObject, Selector) -> AnyObject?
    let sel = DeltaSelector.acquireLoadedItems
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return [] }
    let result = unsafeBitCast(imp, to: Fn.self)(obj, sel)
    return (result as? [AnyObject]) ?? []
}

// MARK: - Router

/// Routes notification interactions back to the bound `DeltaNotifier`. iOS exposes a single
/// `UNUserNotificationCenter.delegate` per process, so the host app must wire this up — either by
/// assigning `DeltaNotificationRouter.shared` as the center delegate, or by forwarding from its own
/// delegate via `handle(didReceive:)` / `handle(willPresent:)`.
@available(iOS 14.0, *)
public final class DeltaNotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = DeltaNotificationRouter()

    private struct Registration {
        let presentation: UNNotificationPresentationOptions
        // Whether the notifier tracks this identifier: how a keyed notifier is found.
        let owns: (String) -> Bool
        let dispatch: (NotificationInteraction) -> Void
    }

    private let lock = NSLock()
    private var registrations: [String: Registration] = [:]

    /// Invoked when an interaction arrives but no bound notifier claims it (e.g. the app was
    /// cold-started by the tap). Mirrors Kotlin's `onColdInteraction`.
    public var coldInteractionHandler: ((NotificationInteraction) -> Void)?

    private override init() { super.init() }

    func register<T>(_ notifier: DeltaNotifier<T>) {
        let tracked = notifier.tracked
        let registration = Registration(
            presentation: notifier.foregroundPresentation,
            owns: { tracked.contains($0) },
            dispatch: { [weak notifier] interaction in
                Task { @MainActor [notifier] in _ = notifier?.dispatch(interaction) }
            }
        )
        lock.lock(); registrations[notifier.idSpaceTag] = registration; lock.unlock()
    }

    func unregister(tag: String) {
        lock.lock(); registrations.removeValue(forKey: tag); lock.unlock()
    }

    private func registration(for tag: String) -> Registration? {
        lock.lock(); defer { lock.unlock() }
        return registrations[tag]
    }

    // A stable-id notifier by its identifier's tag, else the keyed notifier that tracks it.
    private func registration(forIdentifier identifier: String, idSpaceTag: String?) -> Registration? {
        if let tag = idSpaceTag, let registration = registration(for: tag) { return registration }
        lock.lock(); let all = Array(registrations.values); lock.unlock()
        return all.first { $0.owns(identifier) }
    }

    /// Request notification authorization. The library never requests silently — call this (or your
    /// own request) before notifications will display.
    public static func requestAuthorization(
        options: UNAuthorizationOptions = [.alert, .sound, .badge],
        completion: ((Bool, Error?) -> Void)? = nil
    ) {
        UNUserNotificationCenter.current().requestAuthorization(options: options) { granted, error in
            completion?(granted, error)
        }
    }

    // MARK: Forwarding API (for apps that own the delegate)

    /// Returns true if the interaction was routed to a bound notifier or the cold handler.
    @discardableResult
    public func handle(didReceive response: UNNotificationResponse) -> Bool {
        let interaction = NotificationInteraction(
            identifier: response.notification.request.identifier,
            actionIdentifier: response.actionIdentifier
        )

        if let registration = registration(forIdentifier: interaction.key, idSpaceTag: interaction.idSpaceTag) {
            registration.dispatch(interaction)
            return true
        }
        if let cold = coldInteractionHandler {
            cold(interaction)
            return true
        }
        return false
    }

    /// Presentation options for a foreground notification posted by a bound notifier, or nil if
    /// this notification isn't ours.
    public func handle(willPresent notification: UNNotification) -> UNNotificationPresentationOptions? {
        let identifier = notification.request.identifier
        let tag = identifier.lastIndex(of: "#").map { String(identifier[..<$0]) }
        return registration(forIdentifier: identifier, idSpaceTag: tag)?.presentation
    }

    // MARK: UNUserNotificationCenterDelegate (used when installed as the center delegate)

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        _ = handle(didReceive: response)
        completionHandler()
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler(handle(willPresent: notification) ?? [])
    }
}
#endif
