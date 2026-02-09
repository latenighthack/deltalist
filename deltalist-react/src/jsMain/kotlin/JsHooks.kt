// No package - exports at module root for clean JS imports

import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.SoftValue
import com.latenighthack.deltalist.softGetOrNull
import com.latenighthack.deltalist.softLoadedCount
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
 * Creates a JS Proxy wrapping the delta items list.
 * The proxy presents only loaded items (stopping at the first NotLoaded)
 * and defers item access to softGetOrNull — no eager copy.
 *
 * Array.isArray returns true for a Proxy wrapping [], so React
 * treats it as a normal array. .map(), .forEach(), etc. all work
 * because they read `length` and indexed properties through the
 * proxy get trap, then fall back to Array.prototype for methods.
 */
private fun createItemsProxy(items: List<Any?>): dynamic {
    val loadedCount = items.softLoadedCount()

    val handler: dynamic = js("({})")

    handler.get = fun(target: dynamic, prop: dynamic, _receiver: dynamic): dynamic {
        if (jsTypeOf(prop) == "symbol") return target[prop]
        val propStr = prop.unsafeCast<String>()
        if (propStr == "length") return loadedCount
        val index = propStr.toIntOrNull()
        if (index != null && index >= 0 && index < loadedCount) {
            return when (val soft = items.softGetOrNull(index)) {
                is SoftValue.Present -> soft.value
                else -> js("undefined")
            }
        }
        return target[prop]
    }

    // Required: Array.prototype.map checks HasProperty(proxy, "0") etc.
    // Without this trap the check falls through to the empty [] target
    // and every index returns false, so .map() produces an empty result.
    handler.has = fun(_target: dynamic, prop: dynamic): Boolean {
        if (jsTypeOf(prop) == "symbol") return true
        val index = prop.unsafeCast<String>().toIntOrNull()
        return if (index != null) index >= 0 && index < loadedCount else true
    }

    return js("new Proxy([], handler)")
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun useDeltaList(deltaList: Any): Any {
    val state = ReactLib.useState(js("[]"))
    val setItems = state[1]

    ReactLib.useEffect({
        val scope = CoroutineScope(SupervisorJob())

        @Suppress("UNCHECKED_CAST")
        val flow = deltaList as Flow<Delta<Any?>>

        scope.launch {
            flow.collect { delta ->
                setItems(createItemsProxy(delta.items))
            }
        }

        val cleanup: () -> Unit = { scope.cancel() }
        cleanup
    }, arrayOf(deltaList))

    return state[0]
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun useFlow(flow: Any, initial: Any? = null): Any? {
    val state = ReactLib.useState(initial)
    val setValue = state[1]

    ReactLib.useEffect({
        val scope = CoroutineScope(SupervisorJob())

        @Suppress("UNCHECKED_CAST")
        val kotlinFlow = flow as Flow<Any?>

        scope.launch {
            kotlinFlow.collect { value ->
                setValue(value)
            }
        }

        val cleanup: () -> Unit = { scope.cancel() }
        cleanup
    }, arrayOf(flow))

    return state[0]
}
