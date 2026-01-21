package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.MutableSectionedDeltaList
import com.latenighthack.deltalist.Section
import com.latenighthack.deltalist.SectionedDeltaList
import com.latenighthack.deltalist.mutableSectionedDeltaListOf
import com.latenighthack.deltalist.operators.flatten
import java.util.UUID

data class SectionHeader(val title: String, val color: Long)

sealed class SectionRow {
    data class Header(val header: SectionHeader) : SectionRow()
    data class ItemRow(val item: Item) : SectionRow()
}

class SectionedListViewModel {
    private val sectionColors = listOf(
        0xFFE57373, // Red
        0xFF81C784, // Green
        0xFF64B5F6, // Blue
        0xFFFFD54F, // Yellow
        0xFFBA68C8  // Purple
    )

    private val _sections: MutableSectionedDeltaList<SectionHeader, Item> = mutableSectionedDeltaListOf(
        listOf(
            Section(
                SectionHeader("Favorites", sectionColors[0]),
                listOf(
                    Item(UUID.randomUUID().toString(), "Favorite 1"),
                    Item(UUID.randomUUID().toString(), "Favorite 2")
                )
            ),
            Section(
                SectionHeader("Recent", sectionColors[1]),
                listOf(
                    Item(UUID.randomUUID().toString(), "Recent 1"),
                    Item(UUID.randomUUID().toString(), "Recent 2"),
                    Item(UUID.randomUUID().toString(), "Recent 3")
                )
            ),
            Section(
                SectionHeader("All Items", sectionColors[2]),
                listOf(
                    Item(UUID.randomUUID().toString(), "Item A"),
                    Item(UUID.randomUUID().toString(), "Item B")
                )
            )
        )
    )

    val sections: SectionedDeltaList<SectionHeader, Item> = _sections

    val flattenedSections: DeltaList<SectionRow> = _sections.flatten(
        header = { SectionRow.Header(it) },
        item = { SectionRow.ItemRow(it) }
    )

    private var sectionCounter = 0
    private var sectionItemCounter = 100

    fun addSection() {
        val colorIndex = _sections.value.size % sectionColors.size
        _sections.appendSection(
            SectionHeader("Section ${++sectionCounter}", sectionColors[colorIndex]),
            listOf(Item(UUID.randomUUID().toString(), "New Item ${++sectionItemCounter}"))
        )
    }

    fun removeSection(index: Int) {
        if (index in 0 until _sections.value.size) {
            _sections.removeSection(index)
        }
    }

    fun addItemToSection(sectionIndex: Int) {
        if (sectionIndex in 0 until _sections.value.size) {
            _sections.appendItem(sectionIndex, Item(UUID.randomUUID().toString(), "Added ${++sectionItemCounter}"))
        }
    }

    fun removeItemFromSection(sectionIndex: Int, itemIndex: Int) {
        if (sectionIndex in 0 until _sections.value.size) {
            val section = _sections.value[sectionIndex]
            if (itemIndex in 0 until section.items.size) {
                _sections.removeItem(sectionIndex, itemIndex)
            }
        }
    }

    fun moveSection(fromIndex: Int, toIndex: Int) {
        if (fromIndex != toIndex &&
            fromIndex in 0 until _sections.value.size &&
            toIndex in 0 until _sections.value.size
        ) {
            _sections.moveSection(fromIndex, toIndex)
        }
    }

    fun clearSections() {
        _sections.reload(emptyList())
    }
}
