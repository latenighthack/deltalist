package com.latenighthack.deltalist.android.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.createComposeRule
import com.latenighthack.deltalist.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeaseCompositionTest {
    @get:Rule val compose = createComposeRule()

    private class Pins { var count = 0 }
    private class Rows(val pins: Pins, val row: Any) : AbstractSoftList<Any>(), LeasedLazyList<Any> {
        override val size = 1
        override fun softGet(index: Int) = if (index == 0) SoftValue.Present(row) else null
        override fun acquireItem(index: Int): ItemLease<Any>? {
            if (index != 0) return null
            pins.count++
            return ItemLease(row) { pins.count-- }
        }
        override fun acquire(index: Int): SoftValue<Any> { pins.count++; return SoftValue.Present(row) }
        override fun release(index: Int) { pins.count-- }
        override fun releaseAll() { pins.count = 0 }
        override fun isAcquired(index: Int) = pins.count > 0
    }

    @Test fun handoverAndDisposalKeepPeerOwnership() {
        val pins = Pins()
        val item = Any()
        val snapshot = mutableStateOf<SoftList<Any>>(Rows(pins, item), androidx.compose.runtime.neverEqualPolicy())
        val firstVisible = mutableStateOf(true)
        val secondVisible = mutableStateOf(true)
        var rendered: Any? = null
        compose.setContent {
            if (firstVisible.value) {
                rendered = snapshot.value.rememberItem(0, "first")
                BasicText("first")
            }
            if (secondVisible.value) {
                snapshot.value.rememberItem(0, "second")
                BasicText("second")
            }
        }
        compose.runOnIdle { assertEquals(2, pins.count); assertSame(item, rendered) }
        compose.runOnIdle { snapshot.value = Rows(pins, item) }
        compose.runOnIdle { assertEquals(2, pins.count); assertSame(item, rendered) }
        compose.runOnIdle { firstVisible.value = false }
        compose.runOnIdle { assertEquals(1, pins.count) }
        compose.runOnIdle { secondVisible.value = false }
        compose.runOnIdle { assertEquals(0, pins.count) }
    }
}
