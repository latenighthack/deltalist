package com.latenighthack.deltalist.demo

import com.latenighthack.deltalist.DeltaList
import com.latenighthack.deltalist.operators.asSortedDeltaList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random

data class Profile(
    val id: String,
    val firstName: String,
    val lastName: String
) {
    val fullName: String get() = "$firstName $lastName"
}

/**
 * Demonstrates [asSortedDeltaList]: an unordered [Set] of profiles is projected into an
 * alphabetically-sorted [DeltaList] (by full name). Adding or removing a profile mutates the
 * set, and the operator emits the minimal changeset to keep the sorted grid in order.
 */
class SortedListViewModel {
    private val random = Random(42)

    // A fixed pool of 100 profiles; only those currently in [_profiles] are displayed.
    private val pool: List<Profile> = buildPool()

    private val _profiles = MutableStateFlow(pool.shuffled(random).take(10).toSet())

    /** The displayed profiles, sorted alphabetically by full name, as a minimal-changeset list. */
    val profiles: DeltaList<Profile> =
        _profiles.asSortedDeltaList(idSelector = { it.id }, sortBy = { it.fullName })

    /** Adds one not-yet-shown profile from the pool to the set (no-op once all 100 are shown). */
    fun addRandom() {
        val current = _profiles.value
        val unused = pool.filter { it !in current }
        if (unused.isNotEmpty()) {
            _profiles.value = current + unused.random(random)
        }
    }

    /** Removes a profile from the set. */
    fun remove(profile: Profile) = removeById(profile.id)

    /** Removes the profile with the given id from the set. */
    fun removeById(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }.toSet()
    }

    private fun buildPool(): List<Profile> {
        val firstNames = listOf(
            "Olivia", "Liam", "Emma", "Noah", "Ava", "Oliver", "Sophia", "Elijah",
            "Isabella", "James", "Mia", "Benjamin", "Charlotte", "Lucas", "Amelia", "Mason",
            "Harper", "Ethan", "Evelyn", "Logan"
        )
        val lastNames = listOf(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
            "Thomas", "Taylor", "Moore", "Jackson", "Martin"
        )
        return (0 until 100).map { i ->
            Profile(
                id = "profile-$i",
                firstName = firstNames.random(random),
                lastName = lastNames.random(random)
            )
        }
    }
}
