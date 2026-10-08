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
class SnapshotItemTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sameKeyUpdatedValueMustBeRendered() {
        val snapshot = mutableStateOf(listOf("old").asSoftList())
        var rendered = ""
        compose.setContent { rendered = snapshot.value.rememberItem(0, "stable-key"); BasicText(rendered) }
        compose.runOnIdle { assertEquals("old", rendered) }
        compose.runOnIdle { snapshot.value = listOf("new").asSoftList() }
        compose.runOnIdle { assertEquals("new", rendered) }
    }
    private class LegacyRows(val value: String) : AbstractSoftList<String>(), LazyList<String> {
        var pins = 0
        override val size = 1
        override fun softGet(index: Int) = if (index == 0) SoftValue.Present(value) else null
        override fun acquire(index: Int): SoftValue<String> { pins++; return SoftValue.Present(value) }
        override fun release(index: Int) { pins-- }
        override fun releaseAll() { pins = 0 }
        override fun isAcquired(index: Int) = pins > 0
    }

    @Test fun legacyLazySnapshotsTransferOwnershipEvenWhenStructurallyEqual() {
        val old = LegacyRows("same")
        val fresh = LegacyRows("same")
        val snapshot = mutableStateOf<SoftList<String>>(old, androidx.compose.runtime.neverEqualPolicy())
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) BasicText(snapshot.value.rememberItem(0, "stable-key"))
        }
        compose.runOnIdle { assertEquals(1, old.pins); snapshot.value = fresh }
        compose.runOnIdle { assertEquals(0, old.pins); assertEquals(1, fresh.pins); visible.value = false }
        compose.runOnIdle { assertEquals(0, fresh.pins) }
    }
}
