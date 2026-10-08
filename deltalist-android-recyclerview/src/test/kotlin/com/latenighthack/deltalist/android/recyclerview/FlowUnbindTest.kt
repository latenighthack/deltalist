@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.android.recyclerview

import android.app.Activity
import android.view.ViewGroup
import android.widget.TextView
import android.view.View
import androidx.lifecycle.*
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.latenighthack.deltalist.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowUnbindTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.STARTED }
    }
    private class Holder(view: TextView) : RecyclerView.ViewHolder(view)
    private data class Row(override val stableId: Int): Stable
    private class FlowRow(val state: MutableStateFlow<String>)

    @Test fun unbindMustStopAttachedHolderFlow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = FlowRow(MutableStateFlow("before"))
        val source = mutableDeltaListOf(listOf(row))
        val updates = mutableListOf<String>()
        var stops = 0
        val adapter = object : FlowDeltaAdapter<FlowRow, String, Holder>(source, { it.state }) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
            override fun onBindItem(holder: Holder, position: Int, item: FlowRow) {}
            override fun onItemStateChanged(holder: Holder, state: String) { updates += state }
            override fun onItemFlowStopped(holder: Holder) { stops++ }
        }
        val view = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); this.adapter = adapter }
        activity.setContentView(view)
        var holder: Holder? = null
        try {
            adapter.bind(Owner()); runCurrent()
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 400); runCurrent()
            holder = view.findViewHolderForAdapterPosition(0) as Holder
            assertEquals(listOf("before"), updates)
            adapter.unbind(); runCurrent()
            row.state.value = "after"; runCurrent()
            assertEquals(listOf("before"), updates)
            assertEquals(0, row.state.subscriptionCount.value)
            assertEquals(1, stops)
            adapter.unbind(); runCurrent()
            assertEquals(1, stops)
            adapter.bind(Owner()); runCurrent()
            adapter.onBindViewHolder(holder, 0, mutableListOf()); runCurrent()
            row.state.value = "rebound"; runCurrent()
            assertEquals("rebound", updates.last())
            assertEquals(1, row.state.subscriptionCount.value)
        } finally { holder?.let { adapter.onViewDetachedFromWindow(it) }; adapter.unbind(); activity.finish(); runCurrent(); Dispatchers.resetMain() }
    }
}
