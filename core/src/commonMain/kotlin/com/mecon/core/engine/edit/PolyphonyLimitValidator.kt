package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore

/** A half-open time span at which the score exceeds its configured simultaneous-note capacity. */
data class PolyphonyOverflow(
    val start: TimeCode,
    val end: TimeCode?,
    val soundingNoteCount: Int,
)

data class PolyphonyLimitValidation(
    val limit: Int,
    val peak: Int,
    val overflows: List<PolyphonyOverflow>,
) {
    val isValid: Boolean get() = overflows.isEmpty()
}

/**
 * Validates total sounding noteheads across all notation voices.
 *
 * Events use half-open spans, so an event ending exactly when another begins does not briefly
 * consume an extra slot. Chord pitches count individually; rests and grace notes do not consume
 * capacity.
 */
object PolyphonyLimitValidator {
    fun validate(score: RuntimeScore, limit: Int): PolyphonyLimitValidation {
        require(limit > 0) { "Polyphony limit must be positive" }
        val changes = buildMap<Fraction, Int> {
            score.voiceTracks.values.forEach { voice ->
                voice.events.forEach { event ->
                    val count = event.pitches.size
                    if (count == 0 || event.isGrace) return@forEach
                    val start = EditGeometry.absolute(score, event.onset)
                    val end = start + event.duration.toFraction()
                    put(start, getOrElse(start) { 0 } + count)
                    put(end, getOrElse(end) { 0 } - count)
                }
            }
        }
        if (changes.isEmpty()) {
            return PolyphonyLimitValidation(limit = limit, peak = 0, overflows = emptyList())
        }

        // `toSortedMap` is JVM-only. A sorted immutable entry list is sufficient for the sweep and
        // keeps this validator usable by the Kotlin/JS score-editing session.
        val points = changes.entries.sortedBy { it.key }
        var sounding = 0
        var peak = 0
        val overflows = mutableListOf<PolyphonyOverflow>()
        points.forEachIndexed { index, (absolute, delta) ->
            sounding += delta
            peak = maxOf(peak, sounding)
            val next = points.getOrNull(index + 1)?.key
            if (sounding > limit && next != null && next > absolute) {
                overflows += PolyphonyOverflow(
                    start = EditGeometry.timeCodeAt(score, absolute),
                    end = EditGeometry.timeCodeAt(score, next),
                    soundingNoteCount = sounding,
                )
            }
        }
        return PolyphonyLimitValidation(
            limit = limit,
            peak = peak,
            overflows = overflows.mergeAdjacent(),
        )
    }
}

private fun List<PolyphonyOverflow>.mergeAdjacent(): List<PolyphonyOverflow> {
    if (isEmpty()) return emptyList()
    val merged = mutableListOf(first())
    drop(1).forEach { next ->
        val previous = merged.last()
        if (
            previous.end == next.start &&
            previous.soundingNoteCount == next.soundingNoteCount
        ) {
            merged[merged.lastIndex] = previous.copy(end = next.end)
        } else {
            merged += next
        }
    }
    return merged
}
