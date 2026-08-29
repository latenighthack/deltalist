// No package - exports at module root for clean JS imports

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.SoftList
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.softGetOrNull
import com.latenighthack.deltalist.softLoadedCount
import com.latenighthack.deltalist.react.useDelegatedDeltaList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@JsModule("react")
@JsNonModule
private external object ReactLib {
    fun useState(initialState: dynamic): Array<dynamic>
    fun useEffect(effect: dynamic, deps: Array<Any?> = definedExternally)
}

/**
 * Builds a plain JS snapshot of a soft list that preserves *load state*, unlike
 * [createItemsProxy] which only exposes the loaded prefix. Mirrors the soft access
 * the iOS/Android demos use (`softGetOrNull` -> Present/NotLoaded):
 *
 *   { size, loadedCount, get(index) -> { loaded, value, request } }
 *
 * `size` includes not-yet-loaded placeholders (the paginated estimate), so a virtualized
 * list can render the full range and trigger a fetch from each placeholder via `request()`.
 */
private fun createSoftSnapshot(items: SoftList<Any?>): dynamic {
    val snapshot: dynamic = js("({})")
    snapshot.size = items.size
    snapshot.loadedCount = items.softLoadedCount()
    snapshot.get = fun(index: Int): dynamic {
        val cell: dynamic = js("({})")
        when (val soft = items.softGetOrNull(index)) {
            is SoftValue.Present -> {
                cell.loaded = true
                cell.value = soft.value
                cell.request = null
            }
            is SoftValue.NotLoaded -> {
                cell.loaded = false
                cell.value = js("undefined")
                cell.request = fun() { soft.request() }
            }
            null -> {
                cell.loaded = false
                cell.value = js("undefined")
                cell.request = null
            }
        }
        return cell
    }
    return snapshot
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun useSoftDeltaList(deltaList: Any): Any {
    val state = ReactLib.useState(js("({ size: 0, loadedCount: 0, get: function() { return { loaded: false, value: undefined, request: null }; } })"))
    val setSnapshot = state[1]

    ReactLib.useEffect({
        // See useDeltaList: gate stale emissions from a cooperatively-cancelled collector.
        var active = true
        val scope = CoroutineScope(SupervisorJob())

        @Suppress("UNCHECKED_CAST")
        val flow = deltaList as Flow<Delta<Any?>>

        scope.launch {
            flow.collect { delta ->
                if (active) setSnapshot(createSoftSnapshot(delta.items))
            }
        }

        val cleanup: () -> Unit = {
            active = false
            scope.cancel()
        }
        cleanup
    }, arrayOf(deltaList))

    return state[0]
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun useDeltaList(deltaList: Any): Any {
    return useDelegatedDeltaList(deltaList)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun useFlow(flow: Any, initial: Any? = null): Any? {
    val state = ReactLib.useState(initial)
    val setValue = state[1]

    ReactLib.useEffect({
        // See useDeltaList: gate stale emissions from a cooperatively-cancelled collector.
        var active = true
        val scope = CoroutineScope(SupervisorJob())

        @Suppress("UNCHECKED_CAST")
        val kotlinFlow = flow as Flow<Any?>

        scope.launch {
            kotlinFlow.collect { value ->
                if (active) setValue(value)
            }
        }

        val cleanup: () -> Unit = {
            active = false
            scope.cancel()
        }
        cleanup
    }, arrayOf(flow))

    return state[0]
}
