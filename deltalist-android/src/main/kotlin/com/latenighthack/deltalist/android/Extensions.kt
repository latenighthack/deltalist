package com.latenighthack.deltalist.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.latenighthack.deltalist.Change
import com.latenighthack.deltalist.Delta
import com.latenighthack.deltalist.DeltaList

@Composable
fun <T> DeltaList<T>.collectAsDeltaState(
    initial: List<T> = emptyList()
): Delta<T> {
    val delta by collectAsState(initial = Delta(initial, Change.Reload))
    return delta
}
