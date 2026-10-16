package com.latenighthack.deltalist.android.compose

import android.os.Bundle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.latenighthack.deltalist.*
import com.latenighthack.deltalist.operators.lazyMap
import com.latenighthack.deltalist.operators.lazyMapItems
import com.latenighthack.deltalist.operators.sectionedDeltaList
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SectionedStateTest {
    @get:Rule val compose = createComposeRule()

    private sealed interface Header {
        data object Popular : Header
        data object More : Header
    }

    @Test fun keysFollowSectionAndItemMutations() {
        val list = mutableSectionedDeltaListOf(listOf(
            Section(Header.Popular, listOf("a", "b")),
            Section(Header.More, listOf("c")),
        ))
        var state: SectionedDeltaState<Header, String>? = null
        compose.setContent { state = list.collectAsSectionedDeltaState() }

        lateinit var popularKey: Any
        lateinit var bKey: Any
        lateinit var cKey: Any
        compose.runOnIdle {
            val sections = state!!.sections
            popularKey = sections[0].key
            bKey = sections[0].itemKey(1)
            cKey = sections[1].itemKey(0)
            val all = sections.flatMap { s -> listOf(s.key) + (0 until s.size).map(s::itemKey) }
            assertEquals(all.size, all.toSet().size)
            list.moveSection(0, 1)
        }
        compose.runOnIdle {
            assertEquals(Header.More, state!!.sections[0].header)
            assertEquals(popularKey, state!!.sections[1].key)
            assertEquals(cKey, state!!.sections[0].itemKey(0))
            list.removeItem(1, 0)
        }
        compose.runOnIdle {
            assertEquals(bKey, state!!.sections[1].itemKey(0))
            assertTrue(state!!.change is SectionedChange.Items)
        }
    }

    private class Row(val value: String)

    @Test fun lazilyMappedSectionsKeepTheirRowsAndKeysAcrossSectionChanges() {
        val list = mutableSectionedDeltaListOf(listOf(Section("Work", listOf("w1", "w2"))))
        val rows = list.lazyMapItems { Row(it) }
        val shown = mutableMapOf<Any, Row>()
        var state: SectionedDeltaState<String, Row>? = null
        compose.setContent {
            val current = rows.collectAsSectionedDeltaState()
            state = current
            LazyColumn(Modifier.height(400.dp)) {
                for (section in current.sections) {
                    item(key = section.key) { BasicText(section.header) }
                    sectionItems(section) { index, row ->
                        shown[section.itemKey(index)] = row
                        BasicText(row.value)
                    }
                }
            }
        }
        lateinit var keys: List<Any>
        lateinit var before: List<Row>
        compose.runOnIdle {
            val work = state!!.sections.single()
            keys = (0 until work.size).map(work::itemKey)
            before = keys.map { shown.getValue(it) }
            list.insertSection(0, "Personal", listOf("p"))
        }
        compose.runOnIdle {
            val work = state!!.sections[1]
            assertEquals(keys, (0 until work.size).map(work::itemKey))
            keys.forEachIndexed { index, key -> assertSame(before[index], shown.getValue(key)) }
            assertEquals("p", shown.getValue(state!!.sections[0].itemKey(0)).value)
            list.removeSection(0)
        }
        compose.runOnIdle {
            assertEquals(keys, (0 until state!!.sections.single().size).map(state!!.sections.single()::itemKey))
            keys.forEachIndexed { index, key -> assertSame(before[index], shown.getValue(key)) }
        }
    }

    @Test fun domainKeysAreSaveableAndSeparateFromHeaderKeys() {
        val list = flowOf(SectionedDelta(listOf(Section("h", listOf("h"))), SectionedChange.Reload))
        var state: SectionedDeltaState<String, String>? = null
        compose.setContent { state = list.collectAsSectionedDeltaState(headerKey = { it }, itemKey = { it }) }
        compose.runOnIdle {
            val section = state!!.sections.single()
            assertNotEquals(section.key, section.itemKey(0))
            val bundle = Bundle().apply { putParcelable("key", section.itemKey(0) as android.os.Parcelable) }
            assertEquals(section.itemKey(0), bundle.getParcelable<SectionedRowKey>("key"))
        }
    }

    @Test fun lazyItemsAreLeasedWhileComposedAndPlaceholdersRequest() {
        val source = mutableDeltaListOf(listOf("a", "b"))
        val requested = mutableListOf<Int>()
        val soft = object : AbstractSoftList<String>() {
            override val size = 1
            override fun softGet(index: Int): SoftValue<String>? =
                if (index == 0) SoftValue.NotLoaded { requested += index } else null
        }
        val sections = sectionedDeltaList(
            "Lazy" to source.lazyMap { it.uppercase() },
            "Soft" to flowOf(Delta(soft, Change.Reload)),
        )
        val visible = mutableStateOf(true)
        var latest: LazyList<*>? = null
        val rendered = mutableListOf<String>()
        compose.setContent {
            val state = sections.collectAsSectionedDeltaState()
            latest = state.sections.firstOrNull()?.items as? LazyList<*>
            if (visible.value) {
                LazyColumn(Modifier.height(400.dp)) {
                    for (section in state.sections) {
                        item(key = section.key) { BasicText(section.header) }
                        sectionItems(section, placeholder = { BasicText("loading") }) { _, item ->
                            rendered += item
                            BasicText(item)
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(rendered.containsAll(listOf("A", "B")))
            assertTrue(latest!!.isAcquired(0))
            assertTrue(latest!!.isAcquired(1))
            assertEquals(listOf(0), requested)
            visible.value = false
        }
        compose.runOnIdle {
            assertFalse(latest!!.isAcquired(0))
            assertFalse(latest!!.isAcquired(1))
        }
    }
}
