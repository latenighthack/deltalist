package com.latenighthack.deltalist.android.notifications

import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.Mutation
import com.latenighthack.deltalist.Stable
import com.latenighthack.deltalist.StableItem
import kotlinx.coroutines.Dispatchers
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

private class FakeSink<E : Stable, T> : NotificationSink<E, T> {
    val ops = mutableListOf<String>()
    val states = mutableMapOf<Int, Any?>()
    var postCount = 0
    val summaries = mutableListOf<List<E>>()

    override fun post(stableId: Int, value: T, state: Any?) {
        ops.add("post:$stableId")
        states[stableId] = state
        postCount++
    }

    override fun cancel(stableId: Int) {
        ops.add("cancel:$stableId")
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
) = TrayController(scope, sink, grouped, stateAccessor, stateInitial, rateLimitPerSecond, valueOf = { it.value })

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
    fun reload_with_same_ids_force_reposts() {
        val sink = FakeSink<Wrapped<Box>, Box>()
        val tray = controller(sink)
        val a = Wrapped(1, Box("a"))
        val b = Wrapped(2, Box("b"))
        tray.applyDelta(reload(listOf(a, b)))
        sink.ops.clear()

        tray.applyDelta(reload(listOf(a, b)))

        assertEquals(listOf("post:1", "post:2"), sink.ops)
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
        assertEquals("new", sink.states[1])
        runCurrent()
        assertEquals(0, old.state.subscriptionCount.value)
        assertEquals(1, fresh.state.subscriptionCount.value)

        fresh.state.value = "updated"
        advanceTimeBy(130); runCurrent()
        assertEquals("updated", sink.states[1])
        val posts = sink.postCount
        old.state.value = "stale"
        advanceTimeBy(130); runCurrent()
        assertEquals(posts, sink.postCount)
        assertEquals("updated", sink.states[1])
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
        assertEquals(0, sink.states[1])

        // Three rapid updates inside one sampling window collapse to a single re-post of the latest.
        state.value = 1
        state.value = 2
        state.value = 3
        advanceTimeBy(130)
        runCurrent()

        assertEquals(2, sink.postCount)
        assertEquals(3, sink.states[1])
    }
}
