package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.mutableDeltaListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlin.js.Promise
import kotlin.test.*

@JsModule("react") @JsNonModule
private external object StoreTestReact {
    fun createElement(type: dynamic, props: dynamic, vararg children: dynamic): dynamic
}
@JsModule("react-dom/client") @JsNonModule
private external object StoreTestReactClient {
    fun createRoot(container: dynamic): dynamic
}
@JsModule("react-dom") @JsNonModule
private external object StoreTestReactDOM {
    fun flushSync(callback: () -> Unit)
}

private class Counted<T>(private val upstream: Flow<T>) {
    var started = 0
    var active = 0
    val flow: Flow<T> = flow {
        started++; active++
        try { emitAll(upstream) } finally { active-- }
    }
}

private fun after(millis: Int, block: () -> Unit): Promise<Unit> = Promise { resolve, reject ->
    js("setTimeout")({
        try { block(); resolve(Unit) } catch (error: Throwable) { reject(error) }
    }, millis)
}

class ReactFlowStoreTest {
    @Test fun firstSnapshotIsTheSynchronousEmission() {
        val source = Counted(MutableStateFlow("hot"))
        val store = ReactFlowStore("initial", source.flow, graceMillis = 10)
        assertEquals("hot", store.getSnapshot())
        assertEquals(1, source.started)
        store.stop()
        assertEquals(0, source.active)
    }

    @Test fun aDelayedSourceStartsWithTheInitialValue(): Promise<Unit> {
        val store = ReactFlowStore("initial", flow { delay(1); emit("later") }, graceMillis = 1000)
        var notified = 0
        val unsubscribe = store.subscribe { notified++ }
        assertEquals("initial", store.getSnapshot())
        return after(20) {
            assertEquals("later", store.getSnapshot())
            assertEquals(1, notified)
            unsubscribe()
            store.stop()
        }
    }

    @Test fun resubscribingWithinTheGraceKeepsOneCollection(): Promise<Unit> {
        val state = MutableStateFlow(1)
        val source = Counted(state)
        val store = ReactFlowStore(0, source.flow, graceMillis = 10)
        assertEquals(1, store.getSnapshot())
        var notified = 0
        val first = store.subscribe { notified++ }
        first()
        val second = store.subscribe { notified++ }
        state.value = 2
        assertEquals(2, store.getSnapshot())
        assertEquals(1, notified)
        return after(30) {
            assertEquals(1, source.started)
            assertEquals(1, source.active)
            second()
        }.then { after(30) { assertEquals(0, source.active) } }
    }

    @Test fun anUnsubscribedSnapshotStopsAfterTheGrace(): Promise<Unit> {
        val source = Counted(MutableStateFlow("hot"))
        var stopped = 0
        val store = ReactFlowStore("initial", source.flow, graceMillis = 10, onStop = { stopped++ })
        store.getSnapshot()
        assertTrue(store.isCollecting)
        return after(30) {
            assertFalse(store.isCollecting)
            assertEquals(0, source.active)
            assertEquals(1, stopped)
            assertEquals("hot", store.getSnapshot(), "the snapshot survives a stop")
            assertEquals(2, source.started, "a later read starts again")
            store.stop()
        }
    }

    @Test fun anEqualEmissionKeepsTheSnapshotAndDoesNotNotify() {
        val state = MutableStateFlow(listOf("a"))
        val store = ReactFlowStore(emptyList(), state, graceMillis = 10)
        var notified = 0
        val unsubscribe = store.subscribe { notified++ }
        val first = store.getSnapshot()
        state.value = listOf("a")
        assertSame(first, store.getSnapshot())
        assertEquals(0, notified)
        unsubscribe()
        store.stop()
    }

    @Test fun aHotListRendersItsRowsOnTheFirstRender() {
        val source = mutableDeltaListOf(listOf("a", "b"))
        val container: dynamic = js("document.createElement('div')")
        val root = StoreTestReactClient.createRoot(container)
        val renders = mutableListOf<String>()
        val component: (dynamic) -> dynamic = {
            val list = useMappedDeltaList(source) { "row-$it" }
            val text = js("Array.from")(list).join(",").unsafeCast<String>()
            renders += text
            StoreTestReact.createElement("div", null, text)
        }
        StoreTestReactDOM.flushSync { root.render(StoreTestReact.createElement(component, null)) }
        assertEquals(listOf("row-a,row-b"), renders.distinct())
        StoreTestReactDOM.flushSync { root.unmount() }
    }

    @Test fun aListStoreKeepsItsProxyAcrossStops(): Promise<Unit> {
        val source = mutableDeltaListOf(listOf("a"))
        val store = ReactDeltaListStore(source, { it }, graceMillis = 10)
        val proxy = store.proxy
        assertEquals(1, store.getSnapshot())
        assertEquals("a", proxy.asDynamic()[0])
        return after(30) {
            assertSame(proxy, store.proxy)
            val unsubscribe = store.subscribe {}
            assertEquals(2, store.getSnapshot())
            assertEquals("a", proxy.asDynamic()[0])
            unsubscribe()
        }
    }
}
