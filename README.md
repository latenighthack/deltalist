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

### React

The `useDeltaList` hook (`deltalist-react`) collects the list and returns an
array-like view of the loaded items.

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

