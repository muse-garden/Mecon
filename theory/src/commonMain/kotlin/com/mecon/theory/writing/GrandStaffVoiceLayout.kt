package com.mecon.theory.writing

import kotlinx.serialization.Serializable

/**
 * Default distribution of notation voices over a two-staff grand staff.
 *
 * These are editable notation lanes, not analytical monodic voices. The sum is also the maximum
 * simultaneous-note capacity used by writing surfaces that opt into a fixed polyphony limit.
 */
@Serializable
data class GrandStaffVoiceLayout(
    val upperVoiceCount: Int,
    val lowerVoiceCount: Int,
) {
    init {
        require(upperVoiceCount > 0) { "A grand staff needs at least one upper-staff voice" }
        require(lowerVoiceCount > 0) { "A grand staff needs at least one lower-staff voice" }
    }

    val capacity: Int get() = upperVoiceCount + lowerVoiceCount

    companion object {
        fun defaultFor(capacity: Int): GrandStaffVoiceLayout {
            require(capacity >= 2) { "A grand-staff voice layout needs at least two voices" }
            val upper = (capacity + 1) / 2
            return GrandStaffVoiceLayout(
                upperVoiceCount = upper,
                lowerVoiceCount = capacity - upper,
            )
        }
    }
}
