package com.mecon.core.engine.edit

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.EditGeometry.timeCodeAt
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.VoiceSpanEditing.clearIntervalEvents
import com.mecon.core.engine.edit.VoiceSpanEditing.fillGaps
import com.mecon.core.engine.edit.VoiceSpanEditing.fillRange

/** Exact piano-roll edits whose semantics do not belong to renderer or Compose gesture code. */
internal object DirectNoteEditing {
    fun editExactPitches(
        runtime: RuntimeScore,
        edits: List<NoteEditEngine.ExactPitchEdit>,
    ): NoteEditEngine.TransposeResult? {
        if (edits.isEmpty()) return null
        var current = runtime
        val touchedMeasures = linkedSetOf<Int>()
        val movedEvents = arrayListOf<NoteEditEngine.MovedEvent>()

        for ((voiceId, voiceEdits) in edits.groupBy { it.voiceTrackId }) {
            val voice = current.getVoiceTrack(voiceId) ?: continue
            val byEvent = voiceEdits.groupBy { it.eventId }
            var changed = false
            val replacements = voice.events.toList().map { event ->
                val eventEdits = byEvent[event.id].orEmpty()
                if (event.isRest || eventEdits.isEmpty()) return@map event
                val replacementsByIndex = eventEdits
                    .filter { it.pitchIndex in event.pitches.indices }
                    .associate { it.pitchIndex to it.pitch }
                if (replacementsByIndex.isEmpty()) return@map event
                val unsorted = event.pitches.mapIndexed { index, pitch ->
                    replacementsByIndex[index] ?: pitch
                }
                // Do not collapse two independently editable chord tones onto one sounding pitch.
                if (unsorted.map { it.midiNumber }.distinct().size != unsorted.size) return@map event
                if (unsorted == event.pitches) return@map event

                val order = unsorted.indices.sortedBy { unsorted[it].midiNumber }
                val oldToNew = IntArray(order.size).also { mapping ->
                    order.forEachIndexed { newIndex, oldIndex -> mapping[oldIndex] = newIndex }
                }
                val movedOldIndices = replacementsByIndex.keys
                val newTies = event.ties.mapNotNull { tie ->
                    when {
                        tie.pitchIndex in movedOldIndices -> null
                        tie.pitchIndex in event.pitches.indices ->
                            RuntimeTieInfo(oldToNew[tie.pitchIndex], tie.isLetRing)
                        else -> null
                    }
                }
                changed = true
                touchedMeasures += event.onset.measure
                movedEvents += NoteEditEngine.MovedEvent(
                    eventId = event.id,
                    pitchIndices = movedOldIndices.mapTo(linkedSetOf()) { oldToNew[it] },
                )
                event.copy(
                    pitchEvent = event.pitchEvent.copy(pitches = order.map(unsorted::get)),
                    ties = newTies,
                )
            }
            if (changed) current = replaceVoice(current, voice, replacements)
        }
        if (movedEvents.isEmpty()) return null
        return NoteEditEngine.TransposeResult(
            score = current,
            intervals = touchedMeasures.sorted().map { measure ->
                TimeRange(
                    TimeCode.of(measure, Fraction.ZERO),
                    TimeCode.of(measure + 1, Fraction.ZERO),
                )
            },
            movedEvents = movedEvents,
        )
    }

    fun editRangeBoundary(
        runtime: RuntimeScore,
        edit: NoteEditEngine.RangeBoundaryEdit,
    ): NoteEditEngine.RangeBoundaryResult? {
        require(edit.minimumLength.isPositive) { "Minimum note length must be positive" }
        val voice = runtime.getVoiceTrack(edit.voiceTrackId) ?: return null
        val events = voice.events.toList()
        val selected = events.firstOrNull { it.id == edit.eventId && !it.isRest && !it.isGrace }
            ?: return null
        if (selected.duration.tuplet != null || selected.tupletSpan != null) return null

        val currentSpan = tiedSpan(runtime, events, selected)
        val pitchedOutside = events
            .filter { !it.isRest && it.id !in currentSpan.eventIds && !it.isGrace }
        val previous = pitchedOutside
            .filter { eventEnd(runtime, it) <= currentSpan.start }
            .maxByOrNull { eventEnd(runtime, it) }
        val next = pitchedOutside
            .filter { eventStart(runtime, it) >= currentSpan.end }
            .minByOrNull { eventStart(runtime, it) }
        val adjacentPrevious = previous?.takeIf { eventEnd(runtime, it) == currentSpan.start }
        val adjacentNext = next?.takeIf { eventStart(runtime, it) == currentSpan.end }
        val requested = absolute(runtime, edit.target)
        val scoreEnd = runtime.measures.fold(Fraction.ZERO) { sum, entry ->
            sum + entry.value.duration
        }

        val movedBoundary = when (edit.boundary) {
            NoteEditEngine.RangeBoundary.START -> {
                val lower = adjacentPrevious?.let {
                    eventStart(runtime, it) + edit.minimumLength
                } ?: (previous?.let { eventEnd(runtime, it) } ?: Fraction.ZERO)
                requested.coerceBetween(lower, currentSpan.end - edit.minimumLength)
            }
            NoteEditEngine.RangeBoundary.END -> {
                val upper = adjacentNext?.let {
                    eventEnd(runtime, it) - edit.minimumLength
                } ?: (next?.let { eventStart(runtime, it) } ?: scoreEnd)
                requested.coerceBetween(currentSpan.start + edit.minimumLength, upper)
            }
        }
        val oldBoundary = when (edit.boundary) {
            NoteEditEngine.RangeBoundary.START -> currentSpan.start
            NoteEditEngine.RangeBoundary.END -> currentSpan.end
        }
        if (movedBoundary == oldBoundary) return null

        data class Replacement(
            val oldIds: Set<EventId>,
            val template: RuntimeVoiceEvent,
            val start: Fraction,
            val end: Fraction,
        )

        val replacements = buildList {
            when (edit.boundary) {
                NoteEditEngine.RangeBoundary.START -> {
                    adjacentPrevious?.let { previousEvent ->
                        add(
                            Replacement(
                                oldIds = setOf(previousEvent.id),
                                template = previousEvent,
                                start = eventStart(runtime, previousEvent),
                                end = movedBoundary,
                            )
                        )
                    }
                    add(
                        Replacement(
                            oldIds = currentSpan.eventIds,
                            template = currentSpan.template,
                            start = movedBoundary,
                            end = currentSpan.end,
                        )
                    )
                }
                NoteEditEngine.RangeBoundary.END -> {
                    add(
                        Replacement(
                            oldIds = currentSpan.eventIds,
                            template = currentSpan.template,
                            start = currentSpan.start,
                            end = movedBoundary,
                        )
                    )
                    adjacentNext?.let { nextEvent ->
                        add(
                            Replacement(
                                oldIds = setOf(nextEvent.id),
                                template = nextEvent,
                                start = movedBoundary,
                                end = eventEnd(runtime, nextEvent),
                            )
                        )
                    }
                }
            }
        }
        if (replacements.any { it.end - it.start < edit.minimumLength }) return null

        val removedIds = replacements.flatMapTo(linkedSetOf()) { it.oldIds }
        val affectedStart = minOf(
            oldBoundary,
            movedBoundary,
            replacements.minOf { it.start },
        )
        val affectedEnd = maxOf(
            oldBoundary,
            movedBoundary,
            replacements.maxOf { it.end },
        )
        val startTime = timeCodeAt(runtime, affectedStart)
        val endTime = timeCodeAt(runtime, affectedEnd)
        val kept = clearIntervalEvents(
            runtime,
            events.filterNot { it.id in removedIds },
            startTime,
            endTime,
        )
        val replacementIds = linkedMapOf<EventId, EventId>()
        val resultIds = arrayListOf<EventId>()
        val materialized = replacements.flatMap { replacement ->
            materialize(runtime, replacement.template, replacement.start, replacement.end).also {
                val firstId = it.firstOrNull()?.id ?: return null
                replacement.oldIds.forEach { oldId -> replacementIds[oldId] = firstId }
                resultIds += firstId
            }
        }
        val fromMeasure = startTime.measure
        val canonicalEnd = timeCodeAt(runtime, affectedEnd)
        val toMeasure = (
            if (canonicalEnd.beat == Fraction.ZERO) canonicalEnd.measure - 1
            else canonicalEnd.measure
            ).coerceAtLeast(fromMeasure)
        val filled = fillGaps(runtime, kept + materialized, fromMeasure, toMeasure)
        return NoteEditEngine.RangeBoundaryResult(
            score = replaceVoice(runtime, voice, filled),
            intervals = listOf(
                TimeRange(
                    TimeCode.of(fromMeasure, Fraction.ZERO),
                    TimeCode.of(toMeasure + 1, Fraction.ZERO),
                )
            ),
            resultEventIds = resultIds,
            replacementEventIds = replacementIds,
        )
    }

    private data class TiedSpan(
        val eventIds: Set<EventId>,
        val template: RuntimeVoiceEvent,
        val start: Fraction,
        val end: Fraction,
    )

    private fun tiedSpan(
        runtime: RuntimeScore,
        events: List<RuntimeVoiceEvent>,
        selected: RuntimeVoiceEvent,
    ): TiedSpan {
        val sorted = events.filterNot { it.isRest || it.isGrace }.sortedBy { eventStart(runtime, it) }
        var first = sorted.indexOfFirst { it.id == selected.id }
        var last = first
        fun tiesWholeChord(event: RuntimeVoiceEvent): Boolean =
            event.pitches.isNotEmpty() &&
                event.ties.mapTo(hashSetOf()) { it.pitchIndex }
                    .containsAll(event.pitches.indices.toSet())
        while (first > 0) {
            val previous = sorted[first - 1]
            val current = sorted[first]
            if (
                eventEnd(runtime, previous) != eventStart(runtime, current) ||
                previous.pitches != current.pitches ||
                !tiesWholeChord(previous)
            ) break
            first -= 1
        }
        while (last < sorted.lastIndex) {
            val current = sorted[last]
            val following = sorted[last + 1]
            if (
                eventEnd(runtime, current) != eventStart(runtime, following) ||
                current.pitches != following.pitches ||
                !tiesWholeChord(current)
            ) break
            last += 1
        }
        val members = sorted.subList(first, last + 1)
        return TiedSpan(
            eventIds = members.mapTo(linkedSetOf()) { it.id },
            template = members.first(),
            start = eventStart(runtime, members.first()),
            end = eventEnd(runtime, members.last()),
        )
    }

    private fun materialize(
        runtime: RuntimeScore,
        template: RuntimeVoiceEvent,
        start: Fraction,
        end: Fraction,
    ): List<RuntimeVoiceEvent> {
        val pieces = fillRange(
            runtime = runtime,
            start = timeCodeAt(runtime, start),
            length = end - start,
            pitches = template.pitches,
            articulations = template.pitchEvent.articulations,
            isRest = false,
            trailingTie = template.ties.isNotEmpty(),
        )
        return pieces.mapIndexed { index, piece ->
            when (index) {
                0 -> piece.copy(
                    rendering = template.rendering,
                    slurStarts = template.slurStarts,
                )
                pieces.lastIndex -> piece.copy(slurEnds = template.slurEnds)
                else -> piece
            }
        }
    }

    private fun eventStart(runtime: RuntimeScore, event: RuntimeVoiceEvent): Fraction =
        absolute(runtime, event.onset)

    private fun eventEnd(runtime: RuntimeScore, event: RuntimeVoiceEvent): Fraction =
        eventStart(runtime, event) + event.duration.toFraction()

    private fun Fraction.coerceBetween(minimum: Fraction, maximum: Fraction): Fraction =
        when {
            maximum < minimum -> minimum
            this < minimum -> minimum
            this > maximum -> maximum
            else -> this
        }
}
