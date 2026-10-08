@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.latenighthack.deltalist.react

import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.withStableIds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

class StableKeyTest {
    @Test fun stableKeysMustSurviveMove() = runTest {
        val source = mutableDeltaListOf(listOf("a", "b"))
        val controller = ReactDeltaListController { js("({})") }
        backgroundScope.launch { source.withStableIds().collect { controller.update(it.items) } }
        runCurrent()
        val key = controller.proxy[0].key
        source.move(0, 1); runCurrent()
        assertEquals(key, controller.proxy[1].key)
        source.set(1, "updated-a"); runCurrent()
        assertEquals(key, controller.proxy[1].key)
        controller.dispose()
    }
    @Test fun callerKeysTakePrecedenceOverStableIds() = runTest {
        val source = mutableDeltaListOf(listOf("a"))
        val controller = ReactDeltaListController { js("({ key: 'caller-key' })") }
        backgroundScope.launch { source.withStableIds().collect { controller.update(it.items) } }
        runCurrent()
        assertEquals("caller-key", controller.proxy[0].key)
        controller.dispose()
    }
}
