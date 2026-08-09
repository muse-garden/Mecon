package com.mecon.theory.constraint

/**
 * One shared harmonic-preference score for symbolic enumeration and realized four-part search.
 *
 * [superstrongDeltas] use upward modulo-seven root-degree deltas. Similar chords are identified
 * solely by root degree, so inversion, arity, and quality do not reset recurrence distance.
 */
data class RootProgressionScoringPolicy(
    val superstrongDeltas: Set<Int>,
    val superstrongWeight: Double,
    val consecutiveSuperstrongWeight: Double,
    val similarChordProximityWeight: Double,
) {
    init {
        require(superstrongDeltas.isNotEmpty() && superstrongDeltas.all { it in 1..6 })
        require(superstrongWeight >= 0.0)
        require(consecutiveSuperstrongWeight >= 0.0)
        require(similarChordProximityWeight >= 0.0)
    }

    fun score(rootDegrees: List<Int>): RootProgressionScore {
        require(rootDegrees.all { it in 1..7 }) { "Root degrees must be in 1..7" }
        val deltas = rootDegrees.zipWithNext { before, after -> (after - before).mod(7) }
        val superstrongCount = deltas.count { it in superstrongDeltas }
        val consecutiveSuperstrongCount = deltas.zipWithNext().count { (before, after) ->
            before in superstrongDeltas && after in superstrongDeltas
        }
        val proximityPenalty = rootDegrees.indices.sumOf { earlier ->
            (earlier + 1 until rootDegrees.size)
                .filter { later -> rootDegrees[earlier] == rootDegrees[later] }
                .sumOf { later ->
                    similarChordProximityWeight / (later - earlier).toDouble()
                }
        }
        return RootProgressionScore(
            superstrongMotionCount = superstrongCount,
            consecutiveSuperstrongCount = consecutiveSuperstrongCount,
            similarChordProximityPenalty = proximityPenalty,
            total = superstrongCount * superstrongWeight +
                consecutiveSuperstrongCount * consecutiveSuperstrongWeight +
                proximityPenalty,
        )
    }
}

data class RootProgressionScore(
    val superstrongMotionCount: Int,
    val consecutiveSuperstrongCount: Int,
    val similarChordProximityPenalty: Double,
    val total: Double,
)
