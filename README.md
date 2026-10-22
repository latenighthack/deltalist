# Delta List

> There are only two hard problems in mobile: cache invalidation and responsive lists

## One list, every platform

Write the list once in Kotlin, then render it natively on each platform. The
shared list below is bound to a view on all five supported targets.

### The list (shared Kotlin)

`DeltaList<T>` is a `Flow<Delta<T>>`: every mutation emits a `Delta` carrying the
full `items` snapshot plus a `Change` (a `Reload`, or the minimal set of
`Mutations`). Each binding applies that change efficiently — no manual diffing.

```kotlin
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.mutableDeltaListOf

data class Item(val id: String, val title: String)

class ListViewModel {
    private val _items = mutableDeltaListOf<Item>()
    val items: DeltaList<Item> = _items   // DeltaList<T> = Flow<Delta<T>>

    fun add(title: String) = _items.append(Item(randomId(), title))
    fun removeAt(index: Int) = _items.removeAt(index)
    fun clear() = _items.clear()
}
```

Every binding below consumes the same `viewModel.items`; only the view layer
differs.

#### Empty states are rows, not overlays

`ifEmpty` substitutes a single placeholder item while the list is empty, so the
empty state scrolls and lays out like any other cell and is bound to its own item
model — instead of being an overlay view toggled off item counts. Apply it last,
after any `lazyMap`, and before `sectionedDeltaList`/`concat`/`header` (per-section
placeholders come from applying it to each section's list):

```kotlin
val rows: DeltaList<Row> = _items
    .lazyMap<Item, Row> { Row.Content(it) }
    .ifEmpty { Row.Empty }
```

The placeholder factory runs at most once per collection and its instance is
reused, so binders that key per-row state on item identity see one stable row.
Emptiness is total size, so a paginated list with an unloaded tail is not empty.

### Android — Jetpack Compose

Collect the list as Compose state with `collectAsDeltaState()`
(`deltalist-android-compose`), then hand `delta.items` to a standard `LazyColumn`.

```kotlin
@Composable
fun ItemList(items: DeltaList<Item>) {
    val delta = items.collectAsDeltaState()
    LazyColumn {
        itemsIndexed(delta.items, key = { _, item -> item.id }) { _, item ->
            Text(item.title)
        }
    }
}
```

### Android — RecyclerView

Extend `DeltaAdapter<T, VH>` (`deltalist-android-recyclerview`); it applies deltas
as efficient adapter notifications. Read rows with `getItem(position)` and start
collection with `bind(owner)`. For `Stable` or `StableItem` rows, pass
`stableIds = true` to the adapter constructor (or call `setHasStableIds(true)`
before attachment). Asynchronous data cannot enable stable IDs after RecyclerView
registers its observer.

```kotlin
class ItemAdapter(items: DeltaList<Item>) : DeltaAdapter<Item, ItemAdapter.VH>(items) {
    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(TextView(parent.context))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = getItem(position).title
    }
}

// Wire it up (Activity/Fragment):
recyclerView.layoutManager = LinearLayoutManager(this)
recyclerView.adapter = ItemAdapter(viewModel.items).also { it.bind(this) }
```

### iOS — SwiftUI

The `DeltaList<T>` wrapper (`DeltaListCore`) is an `ObservableObject`; collect the
Kotlin flow from a `.task`, which scopes the subscription to the view's lifetime.

```swift
struct ItemListView: View {
    let viewModel = ListViewModel()
    @StateObject private var list = DeltaList<Item>()

    var body: some View {
        List {
            ForEach(list.loadedItems, id: \.id) { item in
                Text(item.title)
            }
        }
        .task { await list.collect(viewModel.items) }
    }
}
```

### iOS — UIKit

`DeltaCollectionDataSource<T>` (`DeltaListCore`) drives a `UICollectionView`
directly; `bind(erased:)` collects the Kotlin flow and applies batch updates.

```swift
let dataSource = DeltaCollectionDataSource<Item>(
    collectionView: collectionView
) { collectionView, indexPath, item in
    collectionView.dequeueConfiguredReusableCell(
        using: cellRegistration, for: indexPath, item: item)
}
dataSource.bind(erased: viewModel.items)
```

### Apple — generated ViewModel list bindings

Frameworks such as BaseKit can adapt child ViewModels without replacing DeltaList's native engines.
Their generated `ViewModelListBinding<Raw, Element>` carries the original stream, exact child
classifier, identity, and row observation hook. It intentionally has no public map/filter/sort API:
semantic list transformations belong upstream in the ViewModel.

```swift
// SwiftUI (iOS and macOS)
DeltaListView(model.items) { ItemRow(model: $0) }

// Embeddable in a stack, grid, menu, or custom container
DeltaForEach(model.items) { ItemBadge(model: $0) }

// Soft/paginated list with unloaded slots
DeltaLazyListView(model.items, loading: { _ in ProgressView() }) { ItemRow(model: $0) }

// UIKit; the collection view retains the native DeltaCollectionDataSource
collectionView.items(model.items, cell: ItemCell.self)

// AppKit
collectionView.items(model.items, item: ItemCollectionViewItem.self)
```

Polymorphic bindings use a reusable `DeltaUICollectionViewCellMap` or
`DeltaNSCollectionViewItemMap`; the binding element is a generated, exhaustive, list-specific enum.

### Notification tray (Android and Apple)

A notifier makes the system tray another view of a list. An inserted item posts, an update
re-posts under the same identifier, a removal cancels, and a move does nothing. The Android notifier
is in `deltalist-android-notifications`; the Apple one, `DeltaNotifier`, ships in `DeltaListCore`.

For notifications that outlive the process, such as remote pushes, key each item by a durable ID.
The key is the notification's identity: the Android tag (with id `idBase`) and the iOS identifier,
used verbatim. The binding then works with notifications it didn't post:

- `adoptDelivered` adopts an item's notification that the tray already shows, such as one the OS
  displayed from a remote push or an earlier process posted, rather than posting it again. Only an
  item's first render adopts.
- `content` returning null (Swift: nil) keeps an item off the tray. Its notification is cancelled
  whether or not this notifier posted it, so list an item hidden rather than filtering it out.
- `itemState` without an initial value makes an item's first render wait for its first state.
- Survivors re-post only when `shouldRepost(old, new)` accepts them, on an update or a reload.
- `operations` reports each post, adoption and cancellation, so a caller that's about to lose its
  process (a push service, a notification action) can wait for the tray to apply its change.

Items are read through the list's lazy cache, so a `lazyMap`ped row keeps its object between
deltas.

```kotlin
// Android: bound for the application's lifetime, not a screen's
requests.notifier(context, key = { it.notificationId }) {
    channel("requests", "Requests", NotificationManagerCompat.IMPORTANCE_HIGH)
    adoptDelivered { request, delivered -> request.matches(delivered.notification.extras) }
    itemState(accessor = { it.isShown })
    content { request -> if (state != true) null else notification { setContentTitle(request.title) } }
}.bind(applicationScope)
```

```swift
// Apple
let notifier = DeltaNotifier<RequestItem>(tag: "requests", key: { $0.notificationId }) { scope in
    guard scope.state as? Bool == true else { return nil }
    return scope.content { $0.title = scope.item.title }
}
.adoptDelivered { request, delivered in request.matches(delivered.request.content.userInfo) }
.itemState { $0.isShown }
.bind(erased: viewModel.requests)
```

The stable-id forms (`withStableIds().notifier(context) { … }`, and
`DeltaNotifier(tag:idBase:stableId:…)`) name notifications `tag#(idBase + stableId)`. Those IDs
belong to the session, which suits notifications the app posts and clears while it runs.

### React

The `useDeltaList` hook (`deltalist-react`) returns one stable JavaScript `Proxy` that delegates
array reads to the current DeltaList snapshot. It supports indexed access, `map`, `forEach`, and
regular iteration without materializing a new array for every delta. Loaded lazy values are acquired
on access and released when the snapshot changes or the collection stops.

The hooks read the list through `useSyncExternalStore` (React 18 or later). Collection starts on
`Dispatchers.Unconfined` during the first render, so a source that emits on subscription, such as a
`MutableDeltaList` or a `StateFlow`, has its rows in that render. When the last component
unmounts, the collection stops after `ReactFlowStore.defaultGraceMillis` (1000 ms) unless another
mounts first, so StrictMode's remount reuses it. `ReactDeltaListStore` and
`ReactSectionedDeltaListStore` are the same stores without a hook, for code that keeps one list
per view model rather than per component; pass their `subscribe` and `getSnapshot` to
`useSyncExternalStore` and render their `proxy` or `handle`. `ReactFlowStore` does the same for
any `Flow`.

```jsx
import { useDeltaList } from 'your-kmp-module';

function ItemList({ viewModel }) {
    const items = useDeltaList(viewModel.items);
    return (
        <ul>
            {items.map((item) => (
                <li key={item.id}>{item.title}</li>
            ))}
        </ul>
    );
}
```

Soft positions are represented as sparse array holes, so ordinary iteration never fetches every
estimated row. Virtualized lists delegate their viewport explicitly:

```jsx
const items = useDeltaList(viewModel.items)

<VirtualList
    rowCount={items.totalSize}
    rowRenderer={({ index }) => items[index] === undefined
        ? <Skeleton />
        : <ItemRow item={items[index]} />}
    onRowsRendered={({ startIndex, stopIndex }) =>
        items.visibleRange(startIndex, stopIndex)}
/>
```

`visibleRange` acquires loaded lazy values, releases values outside the new range, and requests
unloaded positions. `revision` changes after every delta for virtualizers that cache rendered rows;
the proxy itself intentionally remains referentially stable.

### Delivery and ownership contracts

Mutable flat/sectioned holders and paginated sources deliver a `Reload` to each new
collector. Consecutive publications retain their mutations; a collector that misses
publications receives a reload of the latest snapshot. Producers remain bounded
and conflated. Serialize imperative writes; update callbacks execute once. Do not
apply `conflate`, dropping buffers, or `stateIn` to raw mutation deltas and assume
that their coordinates still describe each subscriber's preceding emission. For
shared immutable ordinary snapshots, apply `asDeltaList { it.key }` after the shared
snapshot flow so each subscriber computes its own history.

`lazyMap` supports both positional `LazyList` operations and owned `ItemLease`
handles. A lease pins one item until `release()`; release is idempotent and follows
the acquired cache entry through moves. A release after removal or reload cannot
evict a replacement at the same index. Acquire the successor before releasing a
previous lease when handing a mounted row to a newer snapshot.

`lazyMapItems` is the sectioned counterpart of `lazyMap`, with one cache per section
and the same leases. Each section's cache follows `Items` changes within that section and
follows its section through `Sections` insert, remove and move. `Update` re-runs the mapper
for that item's cached row only, a header `Update` keeps the section's rows, and `Reload`
clears every cache. A change that does not fit the previous emission is delivered as
`Reload`. It is not called `lazyMap` because both receivers are a `Flow` on the JVM.

`concat`, `concatSections`, `header`, `footer`, `flattenItems`, mapped `flatten`,
and `withStableIds` preserve acquisition/release. Thus `lazyMap → ifEmpty →
composition` is supported. Pure `softGet` may evaluate a mapper, but never pins a
value or requests a page. Superseded snapshots remain readable; their lifecycle
and load-request side effects are disabled. Previously acquired leases can still
be released. Normal completion of a finite source leaves its final snapshot usable.
Custom `LazyList` implementations can adopt `LeasedLazyList` for durable handles;
legacy implementations retain positional release behavior.

Platform bindings own their leases. Compose callers should use `rememberItem`
or `rememberLazyItemState`; plain reads do not install disposal hooks. Swift's
`DeltaList.acquireItem(at:)` exposes an owned handle for custom native renderers.
Generated SwiftUI rows and native collection-view bindings handle pinning during
row/cell lifetime. The UIKit and AppKit sectioned data sources lease each visible cell's
row through the snapshot instead of peeking rows on every delta, and key sections and
rows with Kotlin's `SectionedStableIds` (`sectionKey(at:)`, `itemKey(at:)`). Each section
shows its contiguous loaded prefix. When the collection view reloads (including every
delta from another Kotlin framework, whose change Swift cannot read), displaced leases are
released only after the visible cells have their rows, so a row that changes cells keeps
its object. React's sectioned hook re-pins held rows by their tracked keys, so a row that
shifts within its section keeps its object too. The lazy SwiftUI view retains slot metadata and mounted wrappers,
not an array of every generated child. Nominal keys remain the row identity;
observation follows the acquired raw object's identity.

Caching is not durable domain ownership: retain children by nominal key upstream
when drafts or selection must survive filtering, reloads, or leaving the viewport.

### Empty SwiftUI containers

For an embeddable `DeltaForEach` inside `Form` or `List`, let the mounted container
own collection. The `observing:` initializer only renders and observes rows, so an
empty list does not need an existing row to start its source:

```swift
@StateObject private var list = DeltaList<ItemViewModel>()

var body: some View {
    Form {
        DeltaForEach(model.items, observing: list) { ItemRow(model: $0) }
    }
    .task(id: ObjectIdentifier(model.items)) {
        await model.items.collect(into: list)
    }
}
```

Keep the binding instance stable until its source changes. `DeltaListView` and
`DeltaLazyListView` own concrete `List` containers and manage collection themselves.
The original self-collecting `DeltaForEach` initializer remains available for
compatible custom containers; use `observing:` in row-resolving containers.

### Partial grouping

Both `groupBy` overloads group only the **contiguous loaded prefix**. Given
`[Present(A), NotLoaded, Present(B)]`, only `A` participates. Group counts and mapped
headers describe that partial projection. Filling the gap includes the newly
contiguous values; grouping never requests the missing page. The `flatten` footer
mapper likewise receives the section's loaded prefix.

Use fully loaded collections for authoritative groups/counts. Group domain data
before constructing expensive row objects, then lazily map each section's rows with
`lazyMapItems`, so rows are built only when a binding shows them:

```kotlin
val connections: SectionedDeltaList<Header, ConnectionRow> = entries
    .filterItemsDynamic(matches)
    .groupBy(keySelector = { it.category }, headerMapper = { category, _ -> headerOf(category) })
    .lazyMapItems { entry -> ConnectionRow(entry) }
```

`groupBy` follows upstream mutations into the groups and emits one `SectionedChange`
per emission:

| Upstream edit | Emitted change |
|---|---|
| Groups appear or disappear, and every other group keeps its items and order | `Sections` with `Remove` and `Insert` |
| Edits confined to one group, with the group keys unchanged | `Items(section, …)` in that section's coordinates |
| A mapped header changes while its group's items do not | `Sections` with `Update` |
| Nothing in the projection changes (for example, only unloaded positions) | an empty `Sections` |
| Anything else | `Reload` |

`Reload` covers upstream reloads, groups changing order, edits in several groups, and a
group appearing or disappearing while another group's items change. Moving the only item
of one group into another group is such a case, because it removes a section and changes
another in one emission. Items are matched by position through the upstream mutations,
never by equality, so `SectionedStableIds` and `lazyMapItems` caches survive every change
except `Reload`.

### Native lifecycle tests

`apple-tests` hosts the shipped Swift runtime on iOS and macOS and consumes a
separately exported Kotlin demo fixture. Tests cover cancellation, empty containers,
row observation, pinning, and the cross-framework acquisition ABI. Xcode and
[XcodeGen](https://github.com/yonaskolb/XcodeGen) are required; generated projects
and results stay under the ignored `apple-tests/build` directory.

```sh
apple-tests/run-tests.sh macos
DELTALIST_IOS_DESTINATION='platform=iOS Simulator,id=<UDID>' apple-tests/run-tests.sh ios
```

Validation uses project dependencies and local framework outputs. It does not
publish artifacts, alter Maven Local, or release applications. Never replace the
published bytes of an existing version; use a new version for any later release.
