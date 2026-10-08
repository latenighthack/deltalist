@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.android.recyclerview

import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.*
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StableIdConfigurationTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.STARTED }
    }
    private class Holder(view: TextView) : RecyclerView.ViewHolder(view)
    private data class Row(override val stableId: Int) : Stable
    private class Adapter(source: DeltaList<Row>, stableIds: Boolean = false) : DeltaAdapter<Row, Holder>(source, stableIds) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) {}
    }

    @Test fun stableRowsCanArriveAfterObserverAttached() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = mutableDeltaListOf<Row>()
        val adapter = Adapter(source, stableIds = true)
        try {
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {})
            adapter.bind(Owner()); runCurrent()
            source.append(Row(7)); runCurrent()
            assertTrue(adapter.hasStableIds())
            assertEquals(7L, adapter.getItemId(0))
            source.append(Row(9)); runCurrent()
            assertEquals(2, adapter.itemCount)
        } finally { adapter.unbind(); Dispatchers.resetMain() }
    }

    @Test fun legacyAttachedAdapterDoesNotChangeStableIdMode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = mutableDeltaListOf<Row>()
        val adapter = Adapter(source)
        try {
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {})
            adapter.bind(Owner()); runCurrent()
            source.append(Row(7)); runCurrent()
            assertFalse(adapter.hasStableIds())
            assertEquals(1, adapter.itemCount)
            source.append(Row(9)); runCurrent()
            assertEquals(2, adapter.itemCount)
        } finally { adapter.unbind(); Dispatchers.resetMain() }
    }

    @Test fun existingSetterAndUnobservedDetectionRemainSupported() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = mutableDeltaListOf(listOf(Row(7)))
        val detected = Adapter(source)
        val configured = Adapter(source).apply {
            setHasStableIds(true)
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {})
        }
        try {
            detected.bind(Owner()); configured.bind(Owner()); runCurrent()
            assertTrue(detected.hasStableIds())
            assertTrue(configured.hasStableIds())
            assertEquals(7L, configured.getItemId(0))
        } finally { detected.unbind(); configured.unbind(); Dispatchers.resetMain() }
    }
}
