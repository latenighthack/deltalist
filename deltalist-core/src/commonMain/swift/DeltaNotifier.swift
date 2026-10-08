#if canImport(UserNotifications)
import Foundation
import UserNotifications

// Drives the iOS notification tray from a DeltaList. Swift counterpart of the Kotlin
// `deltalist-android-notifications` module: inserts post, updates re-post the same identifier,
// removals cancel, moves are no-ops, and reload re-posts survivors.
//
// Read items only via `delta.loadedItems()` — touching `delta.items` bridges (and force-loads)
// the entire backing list, which is catastrophic for soft/paginated lists.
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

    public let idSpaceTag: String
    /// The composed tray identifier (`idBase + stableId`). Subtract the notifier's `idBase` to
    /// recover the item's stable id; exposed raw so cold handlers can route without that context.
    public let notifId: Int32
    public let kind: Kind
    public let actionKey: String?

    init?(identifier: String, actionIdentifier: String) {
        guard let hash = identifier.lastIndex(of: "#"),
              let composed = Int32(identifier[identifier.index(after: hash)...])
        else { return nil }

        self.idSpaceTag = String(identifier[..<hash])
        self.notifId = composed
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

// MARK: - Content scope

/// Passed into the notifier's `content` closure. Mirrors the Kotlin `NotificationScope`: it
/// exposes the item, its stable id and the latest `itemState` value, and pre-wires the
/// `threadIdentifier` (group) and `categoryIdentifier` (actions) so callers only set visuals.
@available(iOS 14.0, *)
public final class NotificationScope<T: AnyObject> {
    public let item: T
    public let stableId: Int32
    public let state: Any?

    private let threadGroupKey: String?
    private let defaultCategoryId: String?

    init(item: T, stableId: Int32, state: Any?, threadGroupKey: String?, defaultCategoryId: String?) {
        self.item = item
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
@available(iOS 14.0, *)
final class NotificationSink {
    let post: (Int32, AnyObject, Any?) -> Void
    let cancel: (Int32) -> Void

    init(
        post: @escaping (Int32, AnyObject, Any?) -> Void,
        cancel: @escaping (Int32) -> Void
    ) {
        self.post = post
        self.cancel = cancel
    }
}

/// Production sink over `UNUserNotificationCenter`. Re-adding a request with the same identifier
/// replaces the delivered notification, so update/re-post needs no manual diffing.
@available(iOS 14.0, *)
final class CenterNotificationSink {
    private let center: UNUserNotificationCenter
    private let tag: String
    private let idBase: Int32
    private let categories: [UNNotificationCategory]
    private let buildContent: (AnyObject, Any?) -> UNNotificationContent

    init(
        center: UNUserNotificationCenter,
        tag: String,
        idBase: Int32,
        categories: [UNNotificationCategory],
        buildContent: @escaping (AnyObject, Any?) -> UNNotificationContent
    ) {
        self.center = center
        self.tag = tag
        self.idBase = idBase
        self.categories = categories
        self.buildContent = buildContent
        registerCategories()
    }

    private func identifier(_ stableId: Int32) -> String { "\(tag)#\(idBase + stableId)" }

    /// Categories are process-global on iOS, so union with whatever another feature registered
    /// rather than overwriting it.
    private func registerCategories() {
        guard !categories.isEmpty else { return }
        let toAdd = Set(categories)
        center.getNotificationCategories { [center] existing in
            center.setNotificationCategories(existing.union(toAdd))
        }
    }

    func post(stableId: Int32, item: AnyObject, state: Any?) {
        let content = buildContent(item, state)
        let request = UNNotificationRequest(identifier: identifier(stableId), content: content, trigger: nil)
        center.add(request)
    }

    func cancel(stableId: Int32) {
        let id = identifier(stableId)
        center.removeDeliveredNotifications(withIdentifiers: [id])
        center.removePendingNotificationRequests(withIdentifiers: [id])
    }

    func asSink() -> NotificationSink {
        NotificationSink(
            post: { [weak self] in self?.post(stableId: $0, item: $1, state: $2) },
            cancel: { [weak self] in self?.cancel(stableId: $0) }
        )
    }
}

// MARK: - Reconciler

/// One tracked tray entry.
@available(iOS 14.0, *)
final class TrayEntry {
    let stableId: Int32
    var item: AnyObject
    var state: Any?
    var stateTask: Task<Void, Never>?
    var lastPostAt: Date?
    var pendingFlush: Task<Void, Never>?
    var pendingState: Any?

    init(stableId: Int32, item: AnyObject, state: Any?) {
        self.stableId = stableId
        self.item = item
        self.state = state
    }
}

/// Translates a stream of item snapshots into post/cancel operations, keyed by stable id.
/// Direct port of the Kotlin `TrayController` (tray order is irrelevant on iOS, so a plain
/// dictionary replaces the ordered map).
@available(iOS 14.0, *)
@MainActor
final class TrayController {
    private let sink: NotificationSink
    private let stableId: (AnyObject) -> Int32
    private let shouldRepost: (AnyObject, AnyObject) -> Bool
    private let stateSubscribe: ((AnyObject, @escaping (Any?) -> Void) -> Task<Void, Never>)?
    private let stateInitial: ((AnyObject) -> Any?)?
    private let samplePeriod: TimeInterval

    private var entries: [Int32: TrayEntry] = [:]

    init(
        sink: NotificationSink,
        stableId: @escaping (AnyObject) -> Int32,
        shouldRepost: @escaping (AnyObject, AnyObject) -> Bool,
        stateSubscribe: ((AnyObject, @escaping (Any?) -> Void) -> Task<Void, Never>)?,
        stateInitial: ((AnyObject) -> Any?)?,
        rateLimitPerSecond: Int
    ) {
        self.sink = sink
        self.stableId = stableId
        self.shouldRepost = shouldRepost
        self.stateSubscribe = stateSubscribe
        self.stateInitial = stateInitial
        self.samplePeriod = 1.0 / Double(max(1, rateLimitPerSecond))
    }

    func valueFor(_ id: Int32) -> AnyObject? { entries[id]?.item }

    func applyDelta(items newItems: [AnyObject], isReload: Bool) {
        var newIds = Set<Int32>()
        for item in newItems { newIds.insert(stableId(item)) }

        for (id, entry) in entries where !newIds.contains(id) {
            entry.stateTask?.cancel()
            entry.pendingFlush?.cancel()
            sink.cancel(id)
            entries.removeValue(forKey: id)
        }

        for item in newItems {
            let id = stableId(item)
            if let existing = entries[id] {
                let valueChanged = shouldRepost(existing.item, item)
                existing.item = item
                if valueChanged || isReload {
                    sink.post(id, item, existing.state)
                }
            } else {
                let entry = TrayEntry(stableId: id, item: item, state: stateInitial?(item))
                entries[id] = entry
                sink.post(id, item, entry.state)
                startStateTask(entry)
            }
        }
    }

    func cancelAll() {
        for entry in entries.values {
            entry.stateTask?.cancel()
            entry.pendingFlush?.cancel()
            sink.cancel(entry.stableId)
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

    private func startStateTask(_ entry: TrayEntry) {
        guard let stateSubscribe = stateSubscribe else { return }
        entry.stateTask = stateSubscribe(entry.item) { [weak self, weak entry] newState in
            guard let self = self, let entry = entry else { return }
            self.onState(entry, newState)
        }
    }

    // Trailing-edge throttle: coalesce rapid emissions to at most one re-post per sample period,
    // always applying the most recent value. Equivalent to the Kotlin `sample()`.
    private func onState(_ entry: TrayEntry, _ newState: Any?) {
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
        sink.post(entry.stableId, entry.item, newState)
    }

    private func statesEqual(_ a: Any?, _ b: Any?) -> Bool {
        if a == nil && b == nil { return true }
        guard let x = a, let y = b else { return false }
        if let xo = x as? NSObject, let yo = y as? NSObject { return xo.isEqual(yo) }
        return (x as AnyObject) === (y as AnyObject)
    }
}

// MARK: - Notifier

/// Drives the system tray from a `DeltaList`. Bind it to a delta stream with `bind(to:)` (same
/// module) or `bind(erased:)` (SKIE cross-module flows). By default `unbind()` leaves posted
/// notifications standing; set `cancelOnUnbind` or call `cancelAll()` for deterministic teardown.
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
    private let idBase: Int32
    private let categories: [UNNotificationCategory]
    private let threadGroupKey: String?
    private let stableId: (T) -> Int32
    private let content: (NotificationScope<T>) -> UNNotificationContent

    /// Leave posted notifications standing on `unbind` unless set. Defaults to false.
    public var cancelOnUnbind: Bool = false

    private let rateLimitPerSecond: Int
    private var shouldRepost: (T, T) -> Bool = { $0 !== $1 }
    // Stored erased to AnyObject (not T): a nested function-type carrying the generic parameter
    // crashes the compiler's archetype mapper under library evolution.
    private var stateInitial: ((AnyObject) -> Any?)?
    private var stateSubscribe: ((AnyObject, @escaping (Any?) -> Void) -> Task<Void, Never>)?
    private var onContentTap: ((T) -> Void)?
    private var onDismiss: ((T) -> Void)?
    private var onAction: ((T, String) -> Void)?

    private var controller: TrayController?
    private var task: Task<Void, Never>?
    // Retains the production sink for the notifier's lifetime. The TrayController only holds the
    // erased `NotificationSink`, whose closures reference this object weakly, so without a strong
    // reference here the sink would deallocate as soon as `start()` returns and every post/cancel
    // would silently become a no-op.
    private var sink: CenterNotificationSink?

    public init(
        tag: String,
        idBase: Int32 = 0,
        stableId: @escaping (T) -> Int32,
        categories: [UNNotificationCategory] = [],
        threadGroupKey: String? = nil,
        foregroundPresentation: UNNotificationPresentationOptions = [.banner, .list, .sound],
        center: UNUserNotificationCenter = .current(),
        content: @escaping (NotificationScope<T>) -> UNNotificationContent
    ) {
        self.idSpaceTag = tag
        self.idBase = idBase
        self.stableId = stableId
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

    /// Optional per-item observable state; each emission re-runs `content` and re-posts the same id.
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

    /// Override how a surviving item decides to re-post. Defaults to reference identity (`!==`),
    /// matching Kotlin; supply value equality if SKIE doesn't preserve identity across emissions.
    @discardableResult
    public func shouldRepost(_ predicate: @escaping (T, T) -> Bool) -> DeltaNotifier<T> {
        shouldRepost = predicate
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

    // MARK: Binding

    @discardableResult
    public func bind<S: AsyncSequence>(to stream: S) -> DeltaNotifier<T> where S.Element == Delta<T> {
        let controller = start()
        task = Task { @MainActor [weak self] in
            do {
                for try await delta in stream {
                    if Task.isCancelled || self == nil { break }
                    controller.applyDelta(
                        items: delta.loadedItems().map { $0 as AnyObject },
                        isReload: delta.change is Change.Reload
                    )
                }
            } catch {}
        }
        return self
    }

    @discardableResult
    public func bind(erased stream: some AsyncSequence) -> DeltaNotifier<T> {
        let controller = start()
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
                        controller.applyDelta(
                            items: typed.loadedItems().map { $0 as AnyObject },
                            isReload: typed.change is Change.Reload
                        )
                    } else {
                        applyErasedDelta(object, to: controller)
                    }
                }
            } catch {}
        }
        return self
    }

    public func unbind() {
        DeltaNotificationRouter.shared.unregister(tag: idSpaceTag)
        task?.cancel()
        task = nil
        if cancelOnUnbind { controller?.cancelAll() } else { controller?.stop() }
    }

    /// Remove every notification this notifier posted.
    public func cancelAll() {
        controller?.cancelAll()
    }

    deinit {
        task?.cancel()
    }

    // MARK: Internal

    func dispatch(_ interaction: NotificationInteraction) -> Bool {
        let id = interaction.notifId - idBase
        guard let item = controller?.valueFor(id) as? T else { return false }
        switch interaction.kind {
        case .contentTap: onContentTap?(item)
        case .dismiss: onDismiss?(item)
        case .action: if let key = interaction.actionKey { onAction?(item, key) }
        }
        return true
    }

    private func start() -> TrayController {
        unbind()

        let content = self.content
        let stableId = self.stableId
        let threadGroupKey = self.threadGroupKey
        let categories = self.categories
        let defaultCategoryId = categories.first?.identifier

        let sink = CenterNotificationSink(
            center: center,
            tag: idSpaceTag,
            idBase: idBase,
            categories: categories,
            buildContent: { item, state in
                let scope = NotificationScope<T>(
                    item: item as! T,
                    stableId: stableId(item as! T),
                    state: state,
                    threadGroupKey: threadGroupKey,
                    defaultCategoryId: defaultCategoryId
                )
                return content(scope)
            }
        )
        self.sink = sink

        let shouldRepost = self.shouldRepost
        let stateInitial = self.stateInitial
        let stateSubscribe = self.stateSubscribe

        let controller = TrayController(
            sink: sink.asSink(),
            stableId: { stableId($0 as! T) },
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
        controller.applyDelta(items: delta.loadedItems().map { $0 as AnyObject },
                              isReload: delta.change is Change.Reload)
    } else if let delta = value as? DeltaProtocol {
        controller.applyDelta(items: delta.loadedItems().map { $0 as AnyObject },
                              isReload: delta.change is Change.Reload)
    } else if value.responds(to: DeltaSelector.loadedItems) {
        // A Delta vended by a *different* SKIE framework (e.g. the app's own KMP framework exporting
        // deltalist-core) is a DISTINCT Obj-C class, and the @objc DeltaProtocol is module-qualified,
        // so neither the typed casts nor the protocol cast match it. Call the exported Kotlin
        // accessors directly by selector — the same runtime-selector mechanism the sectioned data
        // source uses in DeltaList.swift, and the only thing that crosses the framework boundary.
        controller.applyDelta(items: loadedItemsViaRuntime(value),
                              isReload: changeIsReloadViaRuntime(value))
    }
}

/// Calls the exported Kotlin `Delta.loadedItems()` by selector, so a Delta from any SKIE framework
/// (not just this one) can be read without bridging its backing list. Returns [] if unavailable.
@available(iOS 14.0, *)
private func loadedItemsViaRuntime(_ obj: AnyObject) -> [AnyObject] {
    typealias Fn = @convention(c) (AnyObject, Selector) -> AnyObject?
    let sel = DeltaSelector.loadedItems
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return [] }
    let result = unsafeBitCast(imp, to: Fn.self)(obj, sel)
    return (result as? [AnyObject]) ?? []
}

/// Detects whether a cross-framework Delta's `change` is a `Reload` by Obj-C class name (a direct
/// `is Change.Reload` cast fails across frameworks). Non-reload is the safe default.
@available(iOS 14.0, *)
private func changeIsReloadViaRuntime(_ obj: AnyObject) -> Bool {
    typealias Fn = @convention(c) (AnyObject, Selector) -> AnyObject?
    let sel = DeltaSelector.change
    guard let imp = DeltaIMPCache.shared.imp(for: obj, sel) else { return false }
    guard let change = unsafeBitCast(imp, to: Fn.self)(obj, sel) else { return false }
    return String(describing: type(of: change)).contains("ChangeReload")
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
        let dispatch: (NotificationInteraction) -> Void
    }

    private let lock = NSLock()
    private var registrations: [String: Registration] = [:]

    /// Invoked when an interaction arrives but no notifier is bound to its tag (e.g. the app was
    /// cold-started by the tap). Mirrors Kotlin's `onColdInteraction`.
    public var coldInteractionHandler: ((NotificationInteraction) -> Void)?

    private override init() { super.init() }

    func register<T>(_ notifier: DeltaNotifier<T>) {
        let registration = Registration(
            presentation: notifier.foregroundPresentation,
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
        guard let interaction = NotificationInteraction(
            identifier: response.notification.request.identifier,
            actionIdentifier: response.actionIdentifier
        ) else { return false }

        if let registration = registration(for: interaction.idSpaceTag) {
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
        guard let hash = identifier.lastIndex(of: "#") else { return nil }
        let tag = String(identifier[..<hash])
        return registration(for: tag)?.presentation
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
