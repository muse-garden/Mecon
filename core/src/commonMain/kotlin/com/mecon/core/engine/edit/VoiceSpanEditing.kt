package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.events.TupletSpan
import com.mecon.api.storage.events.GraceNoteInfo
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.EditGeometry.measureLength
import com.mecon.core.engine.edit.EditGeometry.timeCodeAt

/**
 * Primitives for carving and re-filling spans of a voice's events with rests/notes: clearing a
 * span ([clearInterval]), materialising a span into engraved note or rest values ([fillRange]),
 * merging adjacent rests back into their canonical values ([consolidateRests]), and padding holes
 * left after an edit ([fillGaps]). Shared by every feature that rewrites part of a voice's
 * timeline (insertion, deletion, paste, duration/tuplet edits).
 */
internal object VoiceSpanEditing {

    /**
     * Subtract the half-open span `[start, end)` from every event of [voice] that overlaps it,
     * returning the surviving events (untouched ones kept by identity). An event fully inside the
     * span is removed; one that straddles a boundary is trimmed; one that fully *contains* the
     * span is split into two notes (the spec's "拆成两个音符"). Trimmed/​split remnants preserve
     * the original pitches and articulations and are re-expressed as tied note values via
     * [fillRange].
     */
    fun clearInterval(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        start: TimeCode,
        end: TimeCode,
    ): List<RuntimeVoiceEvent> = clearIntervalEvents(runtime, voice.events.toList(), start, end)

    /**
     * List-based variant of [clearInterval]: subtract `[start, end)` from [events] directly, so a
     * caller that has already removed/added events (e.g. an in-place duration edit) can clear a
     * span without rebuilding a [RuntimeVoiceTrack] first.
     */
    fun clearIntervalEvents(
        runtime: RuntimeScore,
        events: List<RuntimeVoiceEvent>,
        start: TimeCode,
        end: TimeCode,
    ): List<RuntimeVoiceEvent> {
        val clearStart = absolute(runtime, start)
        val clearEnd = absolute(runtime, end)
        val out = ArrayList<RuntimeVoiceEvent>()

        for (event in events) {
            val evStart = absolute(runtime, event.onset)
            val evEnd = evStart + event.duration.toFraction()

            // No overlap with [clearStart, clearEnd) → keep as-is (preserves identity).
            if (evEnd <= clearStart || evStart >= clearEnd) {
                out.add(event)
                continue
            }

            val articulations = event.pitchEvent.articulations
            // Left remnant [evStart, clearStart)
            if (clearStart > evStart) {
                out += fillRange(
                    runtime, event.onset, clearStart - evStart,
                    event.pitches, articulations, event.isRest, trailingTie = false,
                )
            }
            // Right remnant [clearEnd, evEnd)
            if (clearEnd < evEnd) {
                out += fillRange(
                    runtime, end, evEnd - clearEnd,
                    event.pitches, articulations, event.isRest, trailingTie = false,
                )
            }
            // Fully covered → dropped (no remnant emitted).
        }
        return out
    }

    /**
     * Materialise the span starting at [start] of total [length] (whole-note units) into a list of
     * voice events. The span is walked measure-by-measure; each measure-local segment is
     * decomposed into note values ([DurationDecomposer]). For non-rests, every piece is tied to
     * the piece that follows it, so a note overflowing a barline (or merely needing two note
     * values) renders as tied notes; [trailingTie] additionally ties the very last piece to
     * whatever comes next.
     */
    fun fillRange(
        runtime: RuntimeScore,
        start: TimeCode,
        length: Fraction,
        pitches: List<Pitch>,
        articulations: List<Articulation>,
        isRest: Boolean,
        trailingTie: Boolean,
    ): List<RuntimeVoiceEvent> {
        if (!length.isPositive) return emptyList()

        // Collect (onset, duration) pieces in order.
        val pieces = ArrayList<Pair<TimeCode, Duration>>()
        var measure = start.measure
        var beat = start.beat ?: Fraction.ZERO
        var remaining = length
        while (remaining.isPositive) {
            val measureLen = measureLength(runtime, measure)
            val available = measureLen - beat
            val chunk = if (remaining <= available) remaining else available
            // Notes use the fewest-pieces (dotted) decomposition; rests follow the engraving rule
            // of metric alignment (short→long into the strong beat, then long→short out of it).
            val segment = if (isRest)
                restDurations(beat, chunk)
            else
                DurationDecomposer.decompose(chunk)
            for (duration in segment) {
                pieces.add(TimeCode.of(measure, beat) to duration)
                beat = beat + duration.toFraction()
            }
            remaining = remaining - chunk
            if (remaining.isPositive) {
                measure += 1
                beat = Fraction.ZERO
            }
        }

        return pieces.mapIndexed { index, (onset, duration) ->
            val isLast = index == pieces.lastIndex
            val tieOut = !isRest && pitches.isNotEmpty() && (!isLast || trailingTie)
            val pitchEvent = RuntimePitchEvent.create(
                onset = onset,
                pitches = if (isRest) emptyList() else pitches,
            ).copy(articulations = articulations)
            RuntimeVoiceEvent.create(
                onset = onset,
                pitchEvent = pitchEvent,
                duration = duration,
                ties = if (tieOut) pitches.indices.map { RuntimeTieInfo(it, isLetRing = false) }
                       else emptyList(),
            )
        }
    }

    fun createVoiceEvent(
        onset: TimeCode,
        duration: Duration,
        pitches: List<Pitch>,
        articulations: List<Articulation>,
        isRest: Boolean,
        rendering: RenderingProps? = null,
        ties: List<RuntimeTieInfo> = emptyList(),
        tupletSpan: TupletSpan? = null,
        graceInfo: GraceNoteInfo? = null,
    ): RuntimeVoiceEvent {
        val pitchEvent = RuntimePitchEvent.create(
            onset = onset,
            pitches = if (isRest) emptyList() else pitches,
        ).copy(articulations = articulations)
        return RuntimeVoiceEvent.create(
            onset = onset,
            pitchEvent = pitchEvent,
            duration = duration,
            rendering = rendering,
            ties = ties,
            tupletSpan = tupletSpan,
            graceInfo = graceInfo,
        )
    }

    /**
     * Re-engrave runs of consecutive rests **within measures `[fromMeasure, toMeasure]`** into the
     * canonical metrically-aligned rest values ([restDurations] via [fillRange]). Notes, and any
     * events outside the measure range, pass through untouched (by identity). A "run" is a maximal
     * stretch of adjacent rests inside one measure (rests never cross a barline). Idempotent on an
     * already-canonical run, so re-running it is safe. This is what merges the small rests left by
     * a batch of single-event deletions into engraved rests.
     */
    fun consolidateRests(
        runtime: RuntimeScore,
        events: List<RuntimeVoiceEvent>,
        fromMeasure: Int,
        toMeasure: Int,
    ): List<RuntimeVoiceEvent> {
        val sorted = events.sortedBy { absolute(runtime, it.onset) }
        val out = ArrayList<RuntimeVoiceEvent>()
        var i = 0
        while (i < sorted.size) {
            val head = sorted[i]
            if (!head.isRest || head.onset.measure !in fromMeasure..toMeasure) {
                out.add(head)
                i++
                continue
            }
            // Extend a run of rests that are contiguous in time and stay in this measure.
            val runStart = head.onset
            var runEndAbs = absolute(runtime, runStart) + head.duration.toFraction()
            var j = i + 1
            while (j < sorted.size) {
                val next = sorted[j]
                if (!next.isRest || next.onset.measure != runStart.measure) break
                if (absolute(runtime, next.onset).compareTo(runEndAbs) != 0) break
                runEndAbs += next.duration.toFraction()
                j++
            }
            out += fillRange(
                runtime, runStart, runEndAbs - absolute(runtime, runStart),
                emptyList(), emptyList(), isRest = true, trailingTie = false,
            )
            i = j
        }
        return out
    }

    /**
     * Decompose a measure-local rest span `[localStart, localStart+length)` into rest note values
     * by repeatedly taking, at the current position, the **largest plain note value that both fits
     * and is metrically aligned** there (its length must divide the position from the bar start). A
     * rest may not begin in the middle of a beat stronger than itself, so when the span starts off
     * a strong beat the alignment constraint forces small values first (short→long up to that
     * beat); once on a strong beat it merges as far as possible, then shrinks toward the end
     * (long→short). So a half rest at beat 0 of 4/4 stays a single half rest, and a 32nd at beat 0
     * leaves `32nd + 16th + 8th` (into beat 1) then a quarter + half — never one rest per beat.
     */
    fun restDurations(localStart: Fraction, length: Fraction): List<Duration> {
        if (!length.isPositive) return emptyList()
        val out = ArrayList<Duration>()
        val segEnd = localStart + length
        var pos = localStart
        while (pos < segEnd) {
            val rest = largestAlignedRest(pos, segEnd - pos)
            out += rest
            pos = pos + rest.toFraction()
        }
        return out
    }

    /**
     * The largest plain (un-dotted) note value `d` such that `d <= remaining` and [pos] (measured
     * in whole notes from the bar start) is an exact multiple of `d` — i.e. a rest of that value
     * may legally begin at [pos] without straddling a stronger beat. [DurationBase.entries] is
     * already ordered largest→smallest, so the first match is the answer.
     */
    private fun largestAlignedRest(pos: Fraction, remaining: Fraction): Duration {
        for (base in DurationBase.entries) {
            val f = base.toFraction()
            if (f <= remaining && (pos / f).denominator == 1) return Duration(base)
        }
        return Duration(DurationBase.entries.last()) // smallest value; unreachable for grid-aligned spans
    }

    /**
     * Pad the holes of [events] within measures `[fromMeasure, toMeasure]` (inclusive) with rests
     * so the touched bars stay completely filled. Events are kept verbatim; only the uncovered
     * spans — before the first event, between events, and after the last event up to the end of
     * [toMeasure] — are materialised as rests via [fillRange]. Events lying outside the range pass
     * through untouched.
     */
    fun fillGaps(
        runtime: RuntimeScore,
        events: List<RuntimeVoiceEvent>,
        fromMeasure: Int,
        toMeasure: Int,
    ): List<RuntimeVoiceEvent> {
        if (toMeasure < fromMeasure) return events
        val rangeStart = absolute(runtime, TimeCode.of(fromMeasure, Fraction.ZERO))
        val rangeEnd = absolute(runtime, TimeCode.of(toMeasure, Fraction.ZERO)) +
            measureLength(runtime, toMeasure)

        val sorted = events.sortedBy { absolute(runtime, it.onset) }
        val rests = ArrayList<RuntimeVoiceEvent>()
        var cursor = rangeStart
        for (event in sorted) {
            val evStart = absolute(runtime, event.onset)
            val evEnd = evStart + event.duration.toFraction()
            if (evEnd <= rangeStart) continue          // entirely before the range
            if (evStart >= rangeEnd) break             // sorted → nothing left inside the range
            if (evStart > cursor) {
                rests += fillRange(
                    runtime, timeCodeAt(runtime, cursor), evStart - cursor,
                    emptyList(), emptyList(), isRest = true, trailingTie = false,
                )
            }
            if (evEnd > cursor) cursor = evEnd
        }
        if (cursor < rangeEnd) {
            rests += fillRange(
                runtime, timeCodeAt(runtime, cursor), rangeEnd - cursor,
                emptyList(), emptyList(), isRest = true, trailingTie = false,
            )
        }
        return events + rests
    }
}
