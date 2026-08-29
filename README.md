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
