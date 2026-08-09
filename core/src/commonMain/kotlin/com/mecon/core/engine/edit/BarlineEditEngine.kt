package com.mecon.core.engine.edit

import com.mecon.api.primitive.BarlineType
import com.mecon.api.runtime.RuntimeMeasure
import com.mecon.api.runtime.RuntimeScore

/**
 * Immutable edits for a logical measure boundary.
 *
 * Boundary 0 is the opening line. Boundary N (N > 0) is the right side of
 * measure N and, when present, the left side of measure N + 1.
 */
object BarlineEditEngine {
    data class Result(
        val score: RuntimeScore,
        val boundaryMeasure: Int,
        val type: BarlineType,
        val repeatCount: Int,
    )

    fun set(
        score: RuntimeScore,
        boundaryMeasure: Int,
        type: BarlineType,
        repeatCount: Int = 2,
    ): Result? {
        val measures = score.measures.map { it.value }.sortedBy { it.number }
        val maxMeasure = measures.lastOrNull()?.number ?: return null
        if (boundaryMeasure !in 0..maxMeasure) return null

        val normalizedCount = repeatCount.coerceAtLeast(2)
        var initialType = score.initialBarlineType
        var changed = false

        val updated = measures.map { measure ->
            var next = measure

            // Every palette choice replaces the complete logical boundary, so
            // clear repeat state on both adjacent measure sides first.
            if (measure.number == boundaryMeasure && measure.repeatEnd) {
                next = next.copy(repeatEnd = false, repeatCount = 2)
            }
            if (measure.number == boundaryMeasure + 1 && measure.repeatStart) {
                next = next.copy(repeatStart = false)
            }

            when (type) {
                BarlineType.REPEAT_LEFT -> {
                    if (measure.number == boundaryMeasure + 1 && !next.repeatStart) {
                        next = next.copy(repeatStart = true)
                    }
                    if (measure.number == boundaryMeasure && next.endBarlineType != null) {
                        next = next.copy(endBarlineType = null)
                    }
                }
                BarlineType.REPEAT_RIGHT -> {
                    if (measure.number == boundaryMeasure) {
                        next = next.copy(
                            endBarlineType = null,
                            repeatEnd = true,
                            repeatCount = normalizedCount,
                        )
                    }
                }
                BarlineType.REPEAT_BOTH -> {
                    if (measure.number == boundaryMeasure) {
                        next = next.copy(
                            endBarlineType = null,
                            repeatEnd = true,
                            repeatCount = normalizedCount,
                        )
                    }
                    if (measure.number == boundaryMeasure + 1 && !next.repeatStart) {
                        next = next.copy(repeatStart = true)
                    }
                }
                else -> {
                    if (boundaryMeasure == 0) {
                        initialType = type
                    } else if (measure.number == boundaryMeasure) {
                        next = next.copy(endBarlineType = type)
                    }
                }
            }

            if (next != measure) changed = true
            next
        }

        if (initialType != score.initialBarlineType) changed = true
        if (!changed) return null
        return Result(
            score = score.copy(initialBarlineType = initialType).replaceMeasures(updated),
            boundaryMeasure = boundaryMeasure,
            type = type,
            repeatCount = normalizedCount,
        )
    }

    /** Repeat count currently attached to this boundary, defaulting to two passes. */
    fun repeatCountAt(score: RuntimeScore, boundaryMeasure: Int): Int =
        repeatCountBoundaryAt(score, boundaryMeasure)
            ?.let(score::getMeasure)
            ?.repeatCount
            ?.coerceAtLeast(2)
            ?: 2

    /**
     * Resolve the repeat-end boundary controlled by a selected repeat sign. A
     * start sign therefore exposes the count of the next matching end sign.
     */
    fun repeatCountBoundaryAt(score: RuntimeScore, boundaryMeasure: Int): Int? {
        if (score.getMeasure(boundaryMeasure)?.repeatEnd == true) return boundaryMeasure
        if (score.getMeasure(boundaryMeasure + 1)?.repeatStart != true) return null
        return score.measures
            .map { it.value }
            .asSequence()
            .filter { it.number > boundaryMeasure && it.repeatEnd }
            .minByOrNull { it.number }
            ?.number
    }

    fun setRepeatCount(
        score: RuntimeScore,
        selectedBoundaryMeasure: Int,
        repeatCount: Int,
    ): Result? {
        val target = repeatCountBoundaryAt(score, selectedBoundaryMeasure) ?: return null
        val measure = score.getMeasure(target) ?: return null
        val normalized = repeatCount.coerceAtLeast(2)
        if (measure.repeatCount == normalized) return null
        val updated = score.measures.map { it.value }.map {
            if (it.number == target) it.copy(repeatCount = normalized) else it
        }
        val selectedType = when {
            score.getMeasure(selectedBoundaryMeasure)?.repeatEnd == true &&
                score.getMeasure(selectedBoundaryMeasure + 1)?.repeatStart == true -> BarlineType.REPEAT_BOTH
            score.getMeasure(selectedBoundaryMeasure)?.repeatEnd == true -> BarlineType.REPEAT_RIGHT
            score.getMeasure(selectedBoundaryMeasure + 1)?.repeatStart == true -> BarlineType.REPEAT_LEFT
            else -> BarlineType.SINGLE
        }
        return Result(score.replaceMeasures(updated), selectedBoundaryMeasure, selectedType, normalized)
    }
}
