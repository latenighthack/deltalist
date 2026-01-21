package com.latenighthack.deltalist.android.recyclerview

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.Mutation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

abstract class DeltaAdapter<T, VH : RecyclerView.ViewHolder>(
    private val deltaList: DeltaList<T>
) : RecyclerView.Adapter<VH>() {

    private var items: List<T> = emptyList()
    private var job: Job? = null

    fun bind(owner: LifecycleOwner) {
        job?.cancel()
        job = owner.lifecycleScope.launch {
            deltaList.collect { delta -> applyDelta(delta) }
        }
    }

    fun unbind() {
        job?.cancel()
        job = null
    }

    private fun applyDelta(delta: Delta<T>) {
        items = delta.items
        when (val change = delta.change) {
            is Change.Reload -> notifyDataSetChanged()
            is Change.Mutations -> {
                change.operations.forEach { mutation ->
                    when (mutation) {
                        is Mutation.Insert -> notifyItemRangeInserted(mutation.index, mutation.count)
                        is Mutation.Remove -> notifyItemRangeRemoved(mutation.index, mutation.count)
                        is Mutation.Update -> notifyItemRangeChanged(mutation.index, mutation.count)
                        is Mutation.Move -> {
                            repeat(mutation.count) { i ->
                                notifyItemMoved(mutation.fromIndex + i, mutation.toIndex + i)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    protected fun getItem(position: Int): T = items[position]
}
