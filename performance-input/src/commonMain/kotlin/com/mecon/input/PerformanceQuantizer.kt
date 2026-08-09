package com.mecon.input

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import kotlin.math.floor

data class QuantizationSettings(
    /** Smallest ordinary-note boundary, in whole-note units. */
    val straightUnit: Fraction = Fraction.SIXTEENTH,
    /** Supported beat subdivisions. 3 and 6 create tuplets; 2 is enabled only in compound meter. */
    val allowedTuplets: Set<Int> = setOf(2, 3, 6),
    /** One metrical beat/group, in whole-note units. */
    val beatUnit: Fraction = Fraction.QUARTER,
    val compoundMeter: Boolean = false,
)

enum class QuantizedGridKind(val tupletCount: Int?) {
    STRAIGHT(null),
    DUPLET(2),
    TRIPLET(3),
    SEXTUPLET(6),
}

data class QuantizedBoundary(
    val position: Fraction,
    val grid: QuantizedGridKind,
)

data class QuantizedTakeSegment(
    val start: Fraction,
    val end: Fraction,
    val pitches: List<Pitch>,
    /** MIDI numbers that continue into the next segment and therefore need a tie-out. */
    val tieOutMidi: Set<Int>,
    val grid: QuantizedGridKind,
) {
    val length: Fraction get() = end - start
    val isRest: Boolean get() = pitches.isEmpty()
}

data class QuantizedPerformanceTake(
    val start: Fraction,
    val end: Fraction,
    val segments: List<QuantizedTakeSegment>,
)

/**
 * Quantizes NoteOn and NoteOff independently, then scans all boundaries by held-pitch set. This
 * naturally splits unequal chord releases and computes per-pitch ties.
 */
object PerformanceQuantizer {
    fun quantize(
        take: RawPerformanceTake,
        clock: PerformanceClock,
        settings: QuantizationSettings,
        minimumPosition: Fraction = clock.anchorPosition,
    ): QuantizedPerformanceTake? {
        if (take.notes.isEmpty()) return null
        val quantizedNotes = take.notes.map { raw ->
            val start = nearestBoundary(clock.positionAt(raw.startedAtNanos), settings)
            var end = nearestBoundary(clock.positionAt(raw.endedAtNanos), settings)
            if (end.position <= start.position) {
                end = nextBoundary(start.position, settings)
            }
            QuantizedNote(raw.pitch, start, end)
        }.map { note ->
            val clampedStart = if (note.start.position < minimumPosition) {
                nearestBoundary(minimumPosition, settings)
            } else {
                note.start
            }
            val clampedEnd = if (note.end.position <= clampedStart.position) {
                nextBoundary(clampedStart.position, settings)
            } else {
                note.end
            }
            note.copy(start = clampedStart, end = clampedEnd)
        }

        val boundaries = quantizedNotes
            .flatMap { listOf(it.start.position, it.end.position) }
            .distinct()
            .sorted()
        if (boundaries.size < 2) return null

        val rawSegments = boundaries.zipWithNext().mapNotNull { (start, end) ->
            if (end <= start) return@mapNotNull null
            val active = quantizedNotes
                .filter { it.start.position <= start && it.end.position >= end }
                .map { it.pitch }
                .distinctBy { it.midiNumber }
                .sortedBy { it.midiNumber }
            val continuing = quantizedNotes
                .filter { it.start.position <= start && it.end.position > end }
                .mapTo(linkedSetOf()) { it.pitch.midiNumber }
            QuantizedTakeSegment(
                start = start,
                end = end,
                pitches = active,
                tieOutMidi = continuing,
                grid = gridForSpan(start, end, quantizedNotes),
            )
        }
        val merged = mergeEqualHeldSets(rawSegments)
        return QuantizedPerformanceTake(merged.first().start, merged.last().end, merged)
    }

    fun nearestBoundary(position: Fraction, settings: QuantizationSettings): QuantizedBoundary {
        val candidates = ArrayList<QuantizedBoundary>()
        candidates += nearestOnGrid(position, settings.straightUnit, QuantizedGridKind.STRAIGHT)
        for (count in settings.allowedTuplets.sorted()) {
            if (count == 2 && !settings.compoundMeter) continue
            val kind = when (count) {
                2 -> QuantizedGridKind.DUPLET
                3 -> QuantizedGridKind.TRIPLET
                6 -> QuantizedGridKind.SEXTUPLET
                else -> continue
            }
            candidates += nearestOnGrid(position, settings.beatUnit / count, kind)
        }
        return candidates.minWithOrNull(
            compareBy<QuantizedBoundary> { distance(it.position, position) }
                .thenBy { if (it.grid == QuantizedGridKind.STRAIGHT) 0 else 1 }
                .thenBy { it.grid.tupletCount ?: 0 },
        )!!
    }

    private data class QuantizedNote(
        val pitch: Pitch,
        val start: QuantizedBoundary,
        val end: QuantizedBoundary,
    )

    private fun nextBoundary(position: Fraction, settings: QuantizationSettings): QuantizedBoundary {
        val steps = buildList {
            add(settings.straightUnit to QuantizedGridKind.STRAIGHT)
            settings.allowedTuplets.sorted().forEach { count ->
                if (count != 2 || settings.compoundMeter) {
                    val kind = when (count) {
                        2 -> QuantizedGridKind.DUPLET
                        3 -> QuantizedGridKind.TRIPLET
                        6 -> QuantizedGridKind.SEXTUPLET
                        else -> null
                    }
                    if (kind != null) add(settings.beatUnit / count to kind)
                }
            }
        }
        val (step, kind) = steps.minBy { it.first }
        return QuantizedBoundary(position + step, kind)
    }

    private fun nearestOnGrid(
        position: Fraction,
        step: Fraction,
        kind: QuantizedGridKind,
    ): QuantizedBoundary {
        val index = floor(position.toDouble() / step.toDouble()).toInt()
        val lower = step * index
        val upper = step * (index + 1)
        return QuantizedBoundary(
            if (distance(lower, position) <= distance(upper, position)) lower else upper,
            kind,
        )
    }

    private fun gridForSpan(
        start: Fraction,
        end: Fraction,
        notes: List<QuantizedNote>,
    ): QuantizedGridKind {
        val touching = notes.flatMap { listOf(it.start, it.end) }
            .filter { it.position == start || it.position == end }
            .map { it.grid }
        return touching.firstOrNull { it != QuantizedGridKind.STRAIGHT } ?: QuantizedGridKind.STRAIGHT
    }

    private fun mergeEqualHeldSets(segments: List<QuantizedTakeSegment>): List<QuantizedTakeSegment> {
        val out = ArrayList<QuantizedTakeSegment>()
        for (segment in segments) {
            val previous = out.lastOrNull()
            if (
                previous != null &&
                previous.end == segment.start &&
                previous.pitches.map { it.midiNumber } == segment.pitches.map { it.midiNumber } &&
                previous.grid == segment.grid
            ) {
                out[out.lastIndex] = previous.copy(
                    end = segment.end,
                    tieOutMidi = segment.tieOutMidi,
                )
            } else {
                out += segment
            }
        }
        return out
    }

    private fun distance(a: Fraction, b: Fraction): Double =
        kotlin.math.abs(a.toDouble() - b.toDouble())

}
