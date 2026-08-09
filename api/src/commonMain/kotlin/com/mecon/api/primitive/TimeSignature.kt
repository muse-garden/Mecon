package com.mecon.api.primitive

import kotlinx.serialization.Serializable

/**
 * Special time signature symbols.
 */
enum class TimeSignatureSymbol {
    COMMON,  // C (4/4)
    CUT      // ¢ (2/2)
}

/**
 * Time signature (meter).
 */
@Serializable
data class TimeSignature(
    val numerator: Int,
    val denominator: Int,
    val symbol: TimeSignatureSymbol? = null,
    /**
     * Explicit beam grouping for this meter, in denominator-units (their sum must equal
     * [numerator]). `null` = use [defaultBeatGroups]. Lets the user pick e.g. 7/8 as 2+2+3
     * vs 3+2+2. Persisted with the meter so automatic beaming (see
     * `BeamGroupComputer.processAutomaticBeaming`) groups notes accordingly. An invalid list
     * (wrong sum) is ignored and falls back to the default.
     */
    val beatGroups: List<Int>? = null
) {
    init {
        require(numerator > 0) { "Numerator must be positive" }
        require(denominator > 0) { "Denominator must be positive" }
        require(denominator and (denominator - 1) == 0) { "Denominator must be a power of 2" }
    }

    /** Number of beats per measure */
    val beatsPerMeasure: Int get() = numerator

    /** Beat unit as a fraction of a whole note */
    val beatUnit: Fraction get() = Fraction(1, denominator)

    /**
     * Total duration of one measure as a fraction of a whole note.
     */
    fun measureDuration(): Fraction = Fraction(numerator, denominator)

    /**
     * Get the duration base that corresponds to one beat.
     */
    fun beatDurationBase(): DurationBase? = when (denominator) {
        1 -> DurationBase.WHOLE
        2 -> DurationBase.HALF
        4 -> DurationBase.QUARTER
        8 -> DurationBase.EIGHTH
        16 -> DurationBase.SIXTEENTH
        32 -> DurationBase.THIRTY_SECOND
        else -> null
    }

    /**
     * Check if this is a compound meter (beats divisible by 3).
     */
    val isCompound: Boolean
        get() = numerator % 3 == 0 && numerator > 3

    /**
     * Check if this is a simple meter.
     */
    val isSimple: Boolean get() = !isCompound

    /**
     * Get the grouping of beats (for beaming), in denominator-units per group.
     * Uses the explicit [beatGroups] when present and valid, otherwise [defaultBeatGroups].
     */
    fun beatGrouping(): List<Int> =
        beatGroups?.takeIf { it.isNotEmpty() && it.sum() == numerator } ?: defaultBeatGroups()

    /**
     * The automatic grouping for this meter (ignoring any explicit [beatGroups]):
     * compound meters group by 3, simple meters give one group per beat.
     */
    fun defaultBeatGroups(): List<Int> =
        if (isCompound) List(numerator / 3) { 3 } else List(numerator) { 1 }

    /**
     * Candidate beam groupings offered to the user for this meter (each sums to [numerator]).
     * Simple meters (2/3/4-beat) return a single default so the picker hides the selector;
     * compound (6/9/12…) and irregular (5, 7, 11…) meters enumerate the sensible 2+3 partitions,
     * with a canonical default first (all-3s for compound, otherwise 2…2+3).
     */
    fun beatGroupCandidates(): List<List<Int>> {
        val offer = isCompound || numerator >= 5
        if (!offer) return listOf(defaultBeatGroups())
        val compositions = compositionsInto23(numerator)
        if (compositions.isEmpty()) return listOf(defaultBeatGroups())
        return (listOf(canonical23(numerator)) + compositions).distinct()
    }

    /**
     * Which beam group (0-based index into [beatGrouping]) the given in-measure [beatPosition]
     * (as a fraction of a whole note) falls in. A position past the last group's boundary
     * (overfull measure) maps to the last group.
     */
    fun beatGroupIndexOf(beatPosition: Fraction): Int {
        val groups = beatGrouping()
        val units = beatPosition * denominator  // position in denominator-units
        var cumulative = 0
        for ((i, g) in groups.withIndex()) {
            cumulative += g
            if (units < Fraction(cumulative, 1)) return i
        }
        return groups.size - 1
    }

    override fun toString(): String = when (symbol) {
        TimeSignatureSymbol.COMMON -> "C"
        TimeSignatureSymbol.CUT -> "¢"
        else -> "$numerator/$denominator"
    }

    companion object {
        // Common time signatures
        val COMMON = TimeSignature(4, 4, TimeSignatureSymbol.COMMON)
        val CUT = TimeSignature(2, 2, TimeSignatureSymbol.CUT)

        // Simple meters
        val TWO_FOUR = TimeSignature(2, 4)
        val THREE_FOUR = TimeSignature(3, 4)
        val FOUR_FOUR = TimeSignature(4, 4)

        // Compound meters
        val SIX_EIGHT = TimeSignature(6, 8)
        val NINE_EIGHT = TimeSignature(9, 8)
        val TWELVE_EIGHT = TimeSignature(12, 8)

        // Irregular meters
        val FIVE_FOUR = TimeSignature(5, 4)
        val SEVEN_EIGHT = TimeSignature(7, 8)

        /**
         * Parse time signature from string (e.g., "4/4", "6/8").
         */
        fun fromString(s: String): TimeSignature? {
            return when (s.uppercase()) {
                "C" -> COMMON
                "¢", "CUT" -> CUT
                else -> {
                    val parts = s.split("/")
                    if (parts.size != 2) return null
                    val num = parts[0].toIntOrNull() ?: return null
                    val denom = parts[1].toIntOrNull() ?: return null
                    TimeSignature(num, denom)
                }
            }
        }
    }
}

/** All ordered compositions of [n] into parts of 2 and 3 (empty if none exist, e.g. n = 1). */
private fun compositionsInto23(n: Int): List<List<Int>> {
    if (n == 0) return listOf(emptyList())
    if (n < 0) return emptyList()
    val result = mutableListOf<List<Int>>()
    for (part in listOf(2, 3)) {
        for (rest in compositionsInto23(n - part)) {
            result.add(listOf(part) + rest)
        }
    }
    return result
}

/** Canonical 2+3 grouping of [n]: all 3s when divisible by 3, all 2s when even, else 2…2+3. */
private fun canonical23(n: Int): List<Int> = when {
    n % 3 == 0 -> List(n / 3) { 3 }
    n % 2 == 0 -> List(n / 2) { 2 }
    else -> List((n - 3) / 2) { 2 } + 3
}
