package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.ItemLease
import com.latenighthack.deltalist.acquireItemOrGet
import com.latenighthack.deltalist.LazyList
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.Stable
import com.latenighthack.deltalist.acquireOrGet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@JsModule("react")
@JsNonModule
private external object ReactInterop {
    fun useRef(initial: dynamic): dynamic
    fun useState(initial: dynamic): Array<dynamic>
    fun useEffect(effect: dynamic, deps: Array<Any?> = definedExternally)
}

/**
 * Mutable controller behind the stable JS array proxy returned by [useMappedDeltaList].
 *
 * The proxy delegates every read to the current immutable [SoftList] snapshot. Unloaded positions
 * are array holes: ordinary `map`, `forEach`, and iteration see loaded values without accidentally
 * requesting every page. A virtualizer opts into positional loading with `visibleRange(start, end)`.
 */
internal class ReactDeltaListController(
    private var transform: (Any?) -> Any?,
) {
    private var items: SoftList<Any?>? = null
    private var keyAt: ((Int) -> Any?)? = null
    private val acquired = mutableMapOf<Int, ItemLease<Any?>>()
    private val mapped = mutableMapOf<Int, Any?>()
    private val objectKeys: dynamic = js("new WeakMap()")
    private var nextObjectKey = 1
    private var revision = 0

    val proxy: dynamic = createProxy()

    fun updateTransform(next: (Any?) -> Any?) {
        if (transform === next) return
        transform = next
        // A new callback may capture new props while the source snapshot stays the
        // same. Refresh wrappers without disturbing the controller's item leases.
        mapped.clear()
    }

    /**
     * [keys] supplies a default key per position; values with a `key` keep their own. When both
     * this and the previous snapshot have [keys], each held lease follows its key to its new
     * position, so a row that shifts keeps its cached object; otherwise leases stay by position.
     */
    fun update(next: SoftList<Any?>, keys: ((Int) -> Any?)? = null) {
        // Acquire successors first, while previous leases still pin moved cache entries.
        val successor = successors(next, keys)
        val replacements = acquired.keys.mapNotNull { index ->
            val target = successor(index) ?: return@mapNotNull null
            next.acquireItemOrGet(target)?.let { target to it }
        }.toMap()
        releaseAcquired()
        acquired.putAll(replacements)
        mapped.clear()
        items = next
        keyAt = keys
        revision++
    }

    /** Where the row at each previous position is in [next], or null once it is gone. */
    private fun successors(next: SoftList<Any?>, keys: ((Int) -> Any?)?): (Int) -> Int? {
        val previous = keyAt
        if (keys == null || previous == null || acquired.isEmpty()) return { it }
        val positions = (0 until next.size).associateBy(keys)
        return { index -> positions[previous(index)] }
    }

    fun dispose() {
        releaseAcquired()
        mapped.clear()
        items = null
        keyAt = null
    }

    private fun size(): Int = items?.size ?: 0

    private fun loadedCount(): Int {
        val snapshot = items ?: return 0
        var count = 0
        for (index in 0 until snapshot.size) {
            if (snapshot.softGet(index) is SoftValue.Present) count++
        }
        return count
    }

    private fun elementAt(index: Int): dynamic {
        val snapshot = items ?: return js("undefined")
        if (index !in 0 until snapshot.size) return js("undefined")
        if (mapped.containsKey(index)) return mapped[index]

        val lease = acquired[index] ?: snapshot.acquireItemOrGet(index)?.also { acquired[index] = it }
        if (lease == null) return js("undefined")
        val soft = SoftValue.Present(lease.item)

        val value = transform(soft.value)
        installDefaultKey(value, soft.value, index)
        mapped[index] = value
        return value
    }

    private fun installDefaultKey(mappedValue: Any?, rawValue: Any?, index: Int) {
        if (mappedValue == null) return
        val value = mappedValue.asDynamic()
        if (jsTypeOf(value) != "object" || value == null || value.key != js("undefined")) return

        keyAt?.let { keys ->
            value.key = keys(index)
            return
        }

        if (rawValue is Stable) {
            // StableItem wrappers may be recreated on every snapshot/read. Their
            // logical ID, not wrapper identity, must carry React state across updates.
            value.key = "stable:${rawValue.stableId}"
            return
        }

        val raw = rawValue.asDynamic()
        if ((jsTypeOf(raw) == "object" || jsTypeOf(raw) == "function") && raw != null) {
            var key = objectKeys.get(raw)
            if (key == js("undefined")) {
                key = nextObjectKey++
                objectKeys.set(raw, key)
            }
            value.key = key
        }
    }

    private fun keyOf(index: Int): dynamic {
        val keys = keyAt ?: return js("undefined")
        return if (index in 0 until size()) keys(index) else js("undefined")
    }

    internal fun visibleRange(start: Int, endInclusive: Int) {
        val snapshot = items ?: return
        if (snapshot.size == 0 || endInclusive < start) {
            releaseAcquired()
            mapped.clear()
            return
        }
        val first = start.coerceIn(0, snapshot.size - 1)
        val last = endInclusive.coerceIn(first, snapshot.size - 1)

        val leaving = acquired.keys.filter { it < first || it > last }
        for (index in leaving) {
            acquired.remove(index)?.release()
        }
        mapped.keys.filter { it < first || it > last }.forEach(mapped::remove)

        for (index in first..last) {
            if (elementAt(index) == js("undefined")) {
                (snapshot.softGet(index) as? SoftValue.NotLoaded)?.request()
            }
        }
    }

    private fun releaseAcquired() {
        acquired.values.forEach { it.release() }
        acquired.clear()
    }

    private fun createIterator(): dynamic {
        var index = 0
        val iterator: dynamic = js("({})")
        iterator.next = fun(): dynamic {
            while (index < size()) {
                val current = index++
                val value = elementAt(current)
                if (value != js("undefined")) {
                    val result: dynamic = js("({})")
                    result.value = value
                    result.done = false
                    return result
                }
            }
            val result: dynamic = js("({})")
            result.value = js("undefined")
            result.done = true
            return result
        }
        iterator[js("Symbol.iterator")] = { iterator }
        return iterator
    }

    private fun createProxy(): dynamic {
        val target: dynamic = js("[]")
        val handler: dynamic = js("({})")
        val iteratorSymbol: dynamic = js("Symbol.iterator")

        handler.get = fun(array: dynamic, property: dynamic, _receiver: dynamic): dynamic {
            if (property == iteratorSymbol) return { createIterator() }
            if (jsTypeOf(property) != "symbol") {
                val name = property.unsafeCast<String>()
                when (name) {
                    "length", "size", "totalSize" -> return size()
                    "loadedCount" -> return loadedCount()
                    "revision" -> return revision
                    "keyAt" -> return { index: Int -> keyOf(index) }
                    "visibleRange" -> return { start: Int, endInclusive: Int -> visibleRange(start, endInclusive) }
                }
                name.toIntOrNull()?.let { return elementAt(it) }
            }
            return array[property]
        }

        // Array iteration methods use HasProperty before Get. Unloaded slots remain genuine holes,
        // so `map` never turns a render into an eager request for every estimated position.
        handler.has = fun(_array: dynamic, property: dynamic): Boolean {
            if (jsTypeOf(property) == "symbol") return true
            val index = property.unsafeCast<String>().toIntOrNull()
            return if (index == null) true else elementAt(index) != js("undefined")
        }

        return js("new Proxy(target, handler)")
    }
}

/**
 * React hook exposing a DeltaList as one stable, array-compatible delegated object.
 *
 * [transform] is evaluated only when a loaded element is read. It is intended for generated
 * platform wrappers; semantic filtering, mapping, sorting, and grouping still belong upstream.
 */
public fun useMappedDeltaList(
    deltaList: Any,
    transform: (Any?) -> Any?,
): Any {
    val controllerRef = ReactInterop.useRef(null)
    if (controllerRef.current == null) {
        controllerRef.current = ReactDeltaListController(transform)
    }
    val controller = controllerRef.current.unsafeCast<ReactDeltaListController>()
    controller.updateTransform(transform)

    val version = ReactInterop.useState(js("({})"))
    val invalidate = version[1]

    ReactInterop.useEffect({
        var active = true
        val scope = CoroutineScope(SupervisorJob())

        @Suppress("UNCHECKED_CAST")
        val flow = deltaList as Flow<Delta<Any?>>
        scope.launch {
            flow.collect { delta ->
                if (active) {
                    controller.update(delta.items)
                    invalidate(js("({})"))
                }
            }
        }

        val cleanup: () -> Unit = {
            active = false
            scope.cancel()
            controller.dispose()
        }
        cleanup
    }, arrayOf(deltaList))

    return controller.proxy
}

/** Identity-mapped form used by applications consuming DeltaList without BaseKit. */
public fun useDelegatedDeltaList(deltaList: Any): Any =
    useMappedDeltaList(deltaList) { it }
