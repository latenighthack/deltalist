package com.latenighthack.deltalist.android.notifications

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Stable
import com.latenighthack.deltalist.StableItem
import com.latenighthack.deltalist.operators.lazyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Wrapped<T>(override val stableId: Int, override val value: T) : StableItem<T>

/** Identity-distinct value holder so the controller's instance-based update detection is exercised. */
private class Box(val label: String)

/** A self-identifying item used to exercise the raw [Stable] path (no [StableItem] wrapper). */
private data class RawItem(override val stableId: Int, val label: String) : Stable

private class FakeSink<E, T> : NotificationSink<E, T> {
    val ops = mutableListOf<String>()
    val states = mutableMapOf<String, Any?>()
    val firstRenders = mutableListOf<String>()
    var postCount = 0
    val summaries = mutableListOf<List<E>>()

    override fun post(key: String, value: T, state: Any?, isFirst: Boolean) {
        ops.add("post:$key")
        states[key] = state
        if (isFirst) firstRenders.add(key)
        postCount++
    }

    override fun cancel(key: String) {
        ops.add("cancel:$key")
    }

    override fun postSummary(items: List<E>) {
        ops.add("summary:${items.size}")
        summaries.add(items.toList())
    }

    override fun cancelSummary() {
        ops.add("summaryCancel")
    }
}

private fun <T> controller(
    sink: FakeSink<Wrapped<T>, T>,
    grouped: Boolean = false,
    scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
    stateAccessor: ((T) -> Flow<Any?>)? = null,
    stateInitial: ((T) -> Any?)? = null,
    rateLimitPerSecond: Int = 8,
    shouldRepost: (T, T) -> Boolean = { old, new -> old !== new },
) = TrayController(
    scope, sink, grouped, keyOf = { it.stableId.toString() }, shouldRepost = shouldRepost,
    stateAccessor = stateAccessor, stateInitial = stateInitial, rateLimitPerSecond = rateLimitPerSecond,
    valueOf = { it.value },
)

/** A keyed controller over the items themselves, as `notifier(context, key)` builds it. */
private fun <T> keyedController(
    sink: FakeSink<T, T>,
    key: (T) -> String,
    scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
    stateAccessor: ((T) -> Flow<Any?>)? = null,
    stateInitial: ((T) -> Any?)? = null,
    shouldRepost: (T, T) -> Boolean = { old, new -> old !== new },
) = TrayController(
    scope, sink, grouped = false, keyOf = key, shouldRepost = shouldRepost,
    stateAccessor = stateAccessor, stateInitial = stateInitial, rateLimitPerSecond = 8, valueOf = { it },
)

private fun <E> mutations(items: List<E>, vararg ops: Mutation): Delta<E> =
    Delta(items, Change.Mutations(ops.toList()))

private fun <E> reload(items: List<E>): Delta<E> =
    Delta(items, Change.Reload)

class TrayControllerTest {

    @Test
    fun insert_posts_new_notifications() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink)

        tray.applyDelta(mutations(listOf(Wrapped(1, Box("a")), Wrapped(2, Box("b"))), Mutation.Insert(0, 2)))

        assertEquals(listOf("post:1", "post:2"), sink.ops)
    }

    @Test
    fun update_reposts_same_id_only() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink)
        val a = Wrapped(1, Box("a"))
        tray.applyDelta(reload(listOf(a, Wrapped(2, Box("b")))))
        sink.ops.clear()

        // b replaced with a fresh instance at the same stable id; a is unchanged.
        tray.applyDelta(mutations(listOf(a, Wrapped(2, Box("b2"))), Mutation.Update(1)))

        assertEquals(listOf("post:2"), sink.ops)
    }

    @Test
    fun remove_cancels_that_id() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink)
        val a = Wrapped(1, Box("a"))
        val c = Wrapped(3, Box("c"))
        tray.applyDelta(reload(listOf(a, Wrapped(2, Box("b")), c)))
        sink.ops.clear()

        tray.applyDelta(mutations(listOf(a, c), Mutation.Remove(1)))

        assertEquals(listOf("cancel:2"), sink.ops)
    }

    @Test
    fun move_is_a_tray_no_op() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink)
        val a = Wrapped(1, Box("a"))
        val b = Wrapped(2, Box("b"))
        tray.applyDelta(reload(listOf(a, b)))
        sink.ops.clear()

        // Same instances, reordered.
        tray.applyDelta(mutations(listOf(b, a), Mutation.Move(0, 1)))

        assertTrue(sink.ops.isEmpty(), "move should not touch the tray, got ${sink.ops}")
    }

    @Test
    fun reload_with_regenerated_ids_cancels_old_and_posts_new() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink)
        tray.applyDelta(reload(listOf(Wrapped(1, Box("a")), Wrapped(2, Box("b")))))
        sink.ops.clear()

        tray.applyDelta(reload(listOf(Wrapped(3, Box("c")), Wrapped(4, Box("d")))))

        assertEquals(listOf("cancel:1", "cancel:2", "post:3", "post:4"), sink.ops)
    }

    @Test
    fun reload_with_same_items_does_not_repost() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink)
        val a = Wrapped(1, Box("a"))
        val b = Wrapped(2, Box("b"))
        tray.applyDelta(reload(listOf(a, b)))
        sink.ops.clear()

        tray.applyDelta(reload(listOf(a, Wrapped(2, Box("b2")))))

        assertEquals(listOf("post:2"), sink.ops)
    }

    @Test
    fun value_equal_survivors_do_not_repost_with_value_should_repost() {
        data class Snapshot(val id: String, val revision: Int)
        val sink = FakeSink<Snapshot, Snapshot>()
        val tray = keyedController(sink, key = { it.id }, shouldRepost = { old, new -> old != new })
        tray.applyDelta(reload(listOf(Snapshot("pnt_a", 1), Snapshot("pnt_b", 1))))
        sink.ops.clear()

        // Fresh but equal instances, and one real change.
        tray.applyDelta(reload(listOf(Snapshot("pnt_a", 1), Snapshot("pnt_b", 2))))

        assertEquals(listOf("post:pnt_b"), sink.ops)
    }

    @Test
    fun keyed_items_reconcile_on_their_keys_and_mark_only_first_renders() {
        data class Push(val notificationId: String, val title: String)
        val sink = FakeSink<Push, Push>()
        val tray = keyedController(sink, key = { it.notificationId })
        val a = Push("pnt_a", "a")
        val b = Push("pnt_b", "b")
        tray.applyDelta(mutations(listOf(a), Mutation.Insert(0)))
        tray.applyDelta(mutations(listOf(Push("pnt_a", "a2"), b), Mutation.Update(0), Mutation.Insert(1)))
        tray.applyDelta(mutations(listOf(b), Mutation.Remove(0)))

        assertEquals(listOf("post:pnt_a", "post:pnt_a", "post:pnt_b", "cancel:pnt_a"), sink.ops)
        assertEquals(listOf("pnt_a", "pnt_b"), sink.firstRenders)
    }

    @Test
    fun lazy_mapped_rows_keep_their_objects_so_unrelated_changes_do_not_repost() = kotlinx.coroutines.test.runTest {
        class Row(val id: String)
        val sink = FakeSink<Row, Row>()
        val tray = keyedController(sink, key = { it.id })
        val source = com.latenighthack.deltalist.mutableDeltaListOf(listOf("a", "b"))
        val job = launch { source.lazyMap { Row(it) }.collect { tray.applyDelta(it) } }
        runCurrent()
        assertEquals(listOf("post:a", "post:b"), sink.ops)
        sink.ops.clear()

        source.append("c")
        runCurrent()

        assertEquals(listOf("post:c"), sink.ops)
        job.cancel()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun without_an_initial_state_a_replacement_renders_only_with_its_own_state() = runTest {
        class Item(val id: String, val label: String, val state: MutableStateFlow<Any?>)
        val sink = FakeSink<Item, Item>()
        val tray = keyedController(
            sink,
            key = { it.id },
            scope = backgroundScope,
            stateAccessor = { it.state },
            shouldRepost = { old, new -> old.label != new.label },
        )
        tray.applyDelta(mutations(listOf(Item("pnt_a", "a", MutableStateFlow("old"))), Mutation.Insert(0)))
        advanceTimeBy(130); runCurrent()
        sink.ops.clear()

        tray.applyDelta(mutations(listOf(Item("pnt_a", "review", MutableStateFlow("new"))), Mutation.Update(0)))
        assertTrue(sink.ops.isEmpty(), "re-posted with the old item's state, got ${sink.ops}")

        advanceTimeBy(130); runCurrent()
        assertEquals(listOf("post:pnt_a"), sink.ops)
        assertEquals("new", sink.states["pnt_a"])
        assertEquals(listOf("pnt_a"), sink.firstRenders)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun without_an_initial_state_the_first_render_waits_for_the_first_state() = runTest {
        class Item(val id: String, val isShown: MutableStateFlow<Any?>)
        val sink = FakeSink<Item, Item>()
        val shown = MutableStateFlow<Any?>(false)
        val tray = keyedController(sink, key = { it.id }, scope = backgroundScope, stateAccessor = { it.isShown })

        tray.applyDelta(mutations(listOf(Item("pnt_a", shown)), Mutation.Insert(0)))
        assertTrue(sink.ops.isEmpty(), "rendered before its state, got ${sink.ops}")

        advanceTimeBy(130); runCurrent()
        assertEquals(listOf("post:pnt_a"), sink.ops)
        assertEquals(false, sink.states["pnt_a"])
        assertEquals(listOf("pnt_a"), sink.firstRenders)

        shown.value = true
        advanceTimeBy(130); runCurrent()
        assertEquals(listOf("post:pnt_a", "post:pnt_a"), sink.ops)
        assertEquals(listOf("pnt_a"), sink.firstRenders)
    }

    @Test
    fun grouping_posts_and_clears_summary() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink, grouped = true)

        tray.applyDelta(mutations(listOf(Wrapped(1, Box("a"))), Mutation.Insert(0, 1)))
        assertEquals(listOf("post:1", "summary:1"), sink.ops)
        sink.ops.clear()

        tray.applyDelta(mutations(emptyList(), Mutation.Remove(0)))
        assertEquals(listOf("cancel:1", "summaryCancel"), sink.ops)
    }

    @Test
    fun summary_reflects_updated_values_order_and_reload() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink, grouped = true)
        val a = Wrapped(1, Box("a"))
        val b = Wrapped(2, Box("b"))
        tray.applyDelta(reload(listOf(a, b)))

        val updated = Wrapped(1, Box("updated"))
        tray.applyDelta(mutations(listOf(updated, b), Mutation.Update(0)))
        assertEquals(listOf("updated", "b"), sink.summaries.last().map { it.value.label })

        tray.applyDelta(mutations(listOf(b, updated), Mutation.Move(0, 1)))
        assertEquals(listOf(2, 1), sink.summaries.last().map { it.stableId })

        tray.applyDelta(reload(listOf(b, updated)))
        assertEquals(4, sink.summaries.size)
    }

    @Test
    fun raw_stable_items_reconcile_on_their_own_id_without_wrapper() {
        // E = T = RawItem, payload = the item itself (valueOf = identity).
        val sink = FakeSink<RawItem, RawItem>()
        val tray = TrayController(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            sink = sink,
            grouped = false,
            keyOf = { it.stableId.toString() },
            shouldRepost = { old, new -> old !== new },
            stateAccessor = null,
            stateInitial = null,
            rateLimitPerSecond = 8,
            valueOf = { it },
        )

        val a = RawItem(7, "a")
        val b = RawItem(9, "b")
        tray.applyDelta(reload(listOf(a, b)))
        assertEquals(listOf("post:7", "post:9"), sink.ops)
        sink.ops.clear()

        // Same instances reordered -> no tray churn; one removed -> cancel by its own id.
        tray.applyDelta(mutations(listOf(b, a), Mutation.Move(0, 1)))
        assertTrue(sink.ops.isEmpty(), "move should not touch the tray, got ${sink.ops}")

        tray.applyDelta(mutations(listOf(a), Mutation.Remove(0)))
        assertEquals(listOf("cancel:9"), sink.ops)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun replacement_switches_state_source_and_resets_initial_state() = runTest {
        class StatefulBox(initial: String) { val state = MutableStateFlow<Any?>(initial) }
        val sink = FakeSink<Wrapped<StatefulBox>, StatefulBox>()
        val old = StatefulBox("old")
        val fresh = StatefulBox("new")
        val tray = controller(sink, scope = backgroundScope,
            stateAccessor = { it.state }, stateInitial = { it.state.value })

        tray.applyDelta(reload(listOf(Wrapped(1, old))))
        runCurrent()
        assertEquals(1, old.state.subscriptionCount.value)

        tray.applyDelta(mutations(listOf(Wrapped(1, fresh)), Mutation.Update(0)))
        assertEquals("new", sink.states["1"])
        runCurrent()
        assertEquals(0, old.state.subscriptionCount.value)
        assertEquals(1, fresh.state.subscriptionCount.value)

        fresh.state.value = "updated"
        advanceTimeBy(130); runCurrent()
        assertEquals("updated", sink.states["1"])
        val posts = sink.postCount
        old.state.value = "stale"
        advanceTimeBy(130); runCurrent()
        assertEquals(posts, sink.postCount)
        assertEquals("updated", sink.states["1"])
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun item_state_emissions_repost_and_coalesce_under_rate_limit() = runTest {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val state = MutableStateFlow<Any?>(0)
        val tray = controller(
            sink,
            scope = backgroundScope,
            stateAccessor = { state },
            stateInitial = { 0 },
            rateLimitPerSecond = 8, // 125ms window
        )

        tray.applyDelta(mutations(listOf(Wrapped(1, Box("a"))), Mutation.Insert(0, 1)))
        assertEquals(1, sink.postCount)
        assertEquals(0, sink.states["1"])

        // Three rapid updates inside one sampling window collapse to a single re-post of the latest.
        state.value = 1
        state.value = 2
        state.value = 3
        advanceTimeBy(130)
        runCurrent()

        assertEquals(2, sink.postCount)
        assertEquals(3, sink.states["1"])
    }
}
