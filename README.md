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
collection with `bind(owner)`.

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

### React

The `useDeltaList` hook (`deltalist-react`) returns one stable JavaScript `Proxy` that delegates
array reads to the current DeltaList snapshot. It supports indexed access, `map`, `forEach`, and
regular iteration without materializing a new array for every delta. Loaded lazy values are acquired
on access and released when the snapshot changes or the hook unmounts.

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
row/cell lifetime. The lazy SwiftUI view retains slot metadata and mounted wrappers,
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
before constructing expensive row objects, then lazily map each section's row flow
before composing it with headers or other sections.

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
