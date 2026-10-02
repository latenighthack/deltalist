@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.android.recyclerview

import android.app.Activity
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.lazyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeaseAdapterTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.STARTED }
    }
    private class Holder(view: TextView) : RecyclerView.ViewHolder(view)

    @Test fun repeatedReadsMoveAndUnbindReleaseOwnedItems() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val source = mutableDeltaListOf(listOf("A", "B"))
        val adapter = object : DeltaAdapter<Any, Holder>(source.lazyMap { Any() }) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
            override fun onBindViewHolder(holder: Holder, position: Int) { getItem(position) }
            fun snapshot() = items as LazyList<*>
        }
        try {
            val view = RecyclerView(activity)
            view.layoutManager = LinearLayoutManager(activity)
            view.adapter = adapter
            activity.setContentView(view)
            adapter.bind(Owner()); runCurrent()
            val first = adapter.getItem(0)
            assertSame(first, adapter.getItem(0))
            source.move(0, 1); runCurrent()
            assertSame(first, adapter.getItem(1))
            assertTrue(adapter.snapshot().isAcquired(1))
            adapter.unbind()
            assertFalse(adapter.snapshot().isAcquired(1))
            adapter.unbind()
        } finally {
            adapter.unbind(); activity.finish(); runCurrent(); Dispatchers.resetMain()
        }
    }
}
