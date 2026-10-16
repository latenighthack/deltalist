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

    return slotMutations((0 until initialSize).toList(), finalSlots, updated)
}

/**
 * Running-coordinate mutations that turn [initial] into [final]. Slots are identities: a slot in
 * [final] but not in [initial] is inserted, one only in [initial] is removed, and a shared slot
 * moves when its position changes. Slots in [updated] that remain are updated at their final
 * position. Both lists must be free of duplicates.
 */
internal fun slotMutations(initial: List<Int>, final: List<Int>, updated: Set<Int>): List<Mutation> {
    val result = mutableListOf<Mutation>()
    val remaining = final.toSet()
    val retained = initial.toSet()
    val working = initial.toMutableList()
    var index = 0
    while (index < working.size) {
        if (working[index] !in remaining) {
            working.removeAt(index)
            result += Mutation.Remove(index)
        } else index++
    }

    for ((target, slot) in final.withIndex()) {
        when {
            working.getOrNull(target) == slot -> Unit
            slot !in retained -> {
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
    final.forEachIndexed { position, slot ->
        if (slot in retained && slot in updated) result += Mutation.Update(position)
    }
    return result
}
