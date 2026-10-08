package com.latenighthack.deltalist

/**
 * Preserve the identity of each original slot while normalizing arbitrary imperative edits.
 * Insert/Update payloads are read from the final snapshot, so they may only reference the
 * final position of that slot. No item equality, materialization, or user callback is needed.
 */
internal fun normalizeMutations(initialSize: Int, operations: List<Mutation>): List<Mutation> {
    if (operations.size < 2 || operations.none { it is Mutation.Insert || it is Mutation.Update }) return operations

    val finalSlots = (0 until initialSize).toMutableList()
    val reads = mutableListOf<Pair<Int, Int>>()
    val updated = mutableSetOf<Int>()
    var insertedSlot = -1
    for (operation in operations) {
        when (operation) {
            is Mutation.Insert -> repeat(operation.count) { offset ->
                val index = operation.index + offset
                val slot = insertedSlot--
                finalSlots.add(index, slot)
                reads += index to slot
            }
            is Mutation.Remove -> repeat(operation.count) { finalSlots.removeAt(operation.index) }
            is Mutation.Update -> repeat(operation.count) { offset ->
                val index = operation.index + offset
                val slot = finalSlots[index]
                reads += index to slot
                updated += slot
            }
            is Mutation.Move -> repeat(operation.count) { offset ->
                val slot = finalSlots.removeAt(operation.fromIndex + offset)
                finalSlots.add(operation.toIndex + offset, slot)
            }
        }
    }

    // Keep already valid edits, including the exact direction of explicit moves.
    if (reads.all { (index, slot) -> finalSlots.getOrNull(index) == slot }) return operations

    val result = mutableListOf<Mutation>()
    val remaining = finalSlots.toSet()
    val working = (0 until initialSize).toMutableList()
    var index = 0
    while (index < working.size) {
        if (working[index] !in remaining) {
            working.removeAt(index)
            result += Mutation.Remove(index)
        } else index++
    }

    for ((target, slot) in finalSlots.withIndex()) {
        when {
            working.getOrNull(target) == slot -> Unit
            slot < 0 -> {
                working.add(target, slot)
                result += Mutation.Insert(target)
            }
            else -> {
                val from = working.indexOf(slot)
                working.add(target, working.removeAt(from))
                result += Mutation.Move(from, target)
            }
        }
    }
    finalSlots.forEachIndexed { position, slot ->
        if (slot >= 0 && slot in updated) result += Mutation.Update(position)
    }
    return result
}
