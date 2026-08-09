package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.Tuplet
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.events.TupletSpan
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.EditGeometry.advance

/**
 * Tuplet math shared by insertion, paste, and in-place tuplet conversion: which tuplet group (if
 * any) is active at a given onset ([activeTupletContext]), how to pick a normal-count/beat-unit
 * pairing for an arbitrary tuplet ([tupletSpecFor]), and how to fill the untouched tail of a
 * tuplet group with rests ([fillTupletRests]).
 */
internal object TupletSupport {

    /** The tuplet group (its first event, declared span, and tuplet ratio) covering a given onset. */
    data class TupletContext(
        val start: RuntimeVoiceEvent,
        val span: TupletSpan,
        val tuplet: Tuplet,
    )

    fun activeTupletContext(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        onset: TimeCode,
    ): TupletContext? {
        val onsetAbs = absolute(runtime, onset)
        return voice.events.toList()
            .asSequence()
            .mapNotNull { event ->
                val span = event.tupletSpan ?: return@mapNotNull null
                val tuplet = event.duration.tuplet ?: return@mapNotNull null
                Triple(event, span, tuplet)
            }
            .filter { (event, span, _) ->
                absolute(runtime, event.onset) <= onsetAbs && onsetAbs < absolute(runtime, span.endTimeCode)
            }
            .maxByOrNull { (event, _, _) -> absolute(runtime, event.onset) }
            ?.let { (event, span, tuplet) -> TupletContext(event, span, tuplet) }
    }

    /** Resolve the exact small-note group named by an explicit renderer append intent. */
    fun smallNoteContextByStartId(
        voice: RuntimeVoiceTrack,
        startEventId: EventId,
    ): TupletContext? = voice.events.toList()
        .asSequence()
        .mapNotNull { event ->
            if (event.id != startEventId) return@mapNotNull null
            val span = event.tupletSpan?.takeIf { it.smallNotes } ?: return@mapNotNull null
            val tuplet = event.duration.tuplet ?: return@mapNotNull null
            TupletContext(event, span, tuplet)
        }
        .firstOrNull()

    fun fillTupletRests(
        runtime: RuntimeScore,
        start: TimeCode,
        actualLength: Fraction,
        spec: NoteEditEngine.TupletSpec,
    ): List<RuntimeVoiceEvent> {
        if (!actualLength.isPositive) return emptyList()
        var onset = start
        return tupletRestDurations(actualLength, spec).map { duration ->
            VoiceSpanEditing.createVoiceEvent(
                onset = onset,
                duration = duration,
                pitches = emptyList(),
                articulations = emptyList(),
                isRest = true,
            ).also {
                onset = advance(runtime, onset, duration.toFraction())
            }
        }
    }

    /**
     * Subtract an interval from events inside one tuplet while keeping every remnant on the
     * tuplet's displayed-value grid. Ordinary [VoiceSpanEditing.clearIntervalEvents] cannot do
     * this because an actual length such as 1/24 has no standalone binary note value.
     */
    fun clearTupletInterval(
        runtime: RuntimeScore,
        events: List<RuntimeVoiceEvent>,
        start: TimeCode,
        end: TimeCode,
        context: TupletContext,
    ): List<RuntimeVoiceEvent> {
        val clearStart = absolute(runtime, start)
        val clearEnd = absolute(runtime, end)
        val out = ArrayList<RuntimeVoiceEvent>()
        for (event in events) {
            val eventStart = absolute(runtime, event.onset)
            val eventEnd = eventStart + event.duration.toFraction()
            if (eventEnd <= clearStart || eventStart >= clearEnd) {
                out += event
                continue
            }
            if (clearStart > eventStart) {
                out += remnantEvents(runtime, event, event.onset, clearStart - eventStart, context.tuplet)
            }
            if (clearEnd < eventEnd) {
                out += remnantEvents(runtime, event, end, eventEnd - clearEnd, context.tuplet)
            }
        }
        return out
    }

    private fun remnantEvents(
        runtime: RuntimeScore,
        source: RuntimeVoiceEvent,
        start: TimeCode,
        actualLength: Fraction,
        tuplet: Tuplet,
    ): List<RuntimeVoiceEvent> {
        val displayedLength = actualLength / tuplet.ratio
        var onset = start
        val durations = DurationDecomposer.decompose(displayedLength).map { it.copy(tuplet = tuplet) }
        return durations.mapIndexed { index, duration ->
            val isLast = index == durations.lastIndex
            VoiceSpanEditing.createVoiceEvent(
                onset = onset,
                duration = duration,
                pitches = source.pitches,
                articulations = source.pitchEvent.articulations,
                isRest = source.isRest,
                rendering = if (index == 0) source.rendering else null,
                ties = if (!source.isRest && (!isLast || source.ties.isNotEmpty())) {
                    source.pitches.indices.map { RuntimeTieInfo(it, isLetRing = false) }
                } else {
                    emptyList()
                },
                tupletSpan = if (onset == source.onset) source.tupletSpan else null,
            ).also { onset = advance(runtime, onset, duration.toFraction()) }
        }
    }

    fun tupletRestDurations(actualLength: Fraction, spec: NoteEditEngine.TupletSpec): List<Duration> {
        if (!actualLength.isPositive) return emptyList()
        val normalLength = actualLength / spec.ratio
        val unit = spec.beatUnit.toFraction()
        return buildList {
            var remaining = normalLength
            while (remaining >= unit) {
                add(Duration(spec.beatUnit, tuplet = spec.tuplet))
                remaining -= unit
            }
            if (remaining.isPositive) {
                addAll(DurationDecomposer.decompose(remaining).map { it.copy(tuplet = spec.tuplet) })
            }
        }
    }

    fun tupletSpecFor(totalLength: Fraction, count: Int): NoteEditEngine.TupletSpec? {
        if (count <= 1 || !totalLength.isPositive) return null
        for (normal in normalCandidates(count)) {
            if (normal == count) continue
            val beatLength = totalLength / normal
            val beatUnit = DurationBase.entries.firstOrNull { it.toFraction() == beatLength } ?: continue
            return NoteEditEngine.TupletSpec(count = count, normal = normal, beatUnit = beatUnit)
        }
        return null
    }

    private fun normalCandidates(count: Int): List<Int> {
        val preferred = when (count) {
            2 -> 3
            3 -> 2
            4 -> 3
            in 5..7 -> 4
            8 -> 6
            in 9..15 -> 8
            else -> {
                var n = 1
                while (n * 2 < count) n *= 2
                n
            }
        }
        return (listOf(preferred, 3, 2, 4, 6, 8, 12, 16, 1) + (2..32))
            .filter { it > 0 }
            .distinct()
    }
}
