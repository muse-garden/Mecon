package com.mecon.core.engine.edit

import com.mecon.api.primitive.DiatonicTranspose
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.events.TupletSpan
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.EditGeometry.advance
import com.mecon.core.engine.edit.EditGeometry.isMeasureLocalSpan
import com.mecon.core.engine.edit.EditGeometry.timeCodeAt
import com.mecon.core.engine.edit.EditGeometry.wholeMeasure
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.TupletSupport.activeTupletContext
import com.mecon.core.engine.edit.TupletSupport.clearTupletInterval
import com.mecon.core.engine.edit.TupletSupport.fillTupletRests
import com.mecon.core.engine.edit.TupletSupport.smallNoteContextByStartId
import com.mecon.core.engine.edit.TupletSupport.tupletSpecFor
import com.mecon.core.engine.edit.VoiceSpanEditing.clearIntervalEvents
import com.mecon.core.engine.edit.VoiceSpanEditing.fillGaps
import com.mecon.core.engine.edit.VoiceSpanEditing.fillRange
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap

/**
 * In-place property edits that keep an event's onset fixed and rewrite one attribute: note value
 * ([editDurations]), tuplet grouping ([applyTuplets]), accidental spelling ([editAccidentals]),
 * ties ([editTies]), explicit beaming ([editBeaming]), or a rest's display staff position
 * ([moveRest]).
 */
internal object NotePropertyEdits {

    fun createSmallNoteRegions(
        runtime: RuntimeScore,
        edits: List<NoteEditEngine.SmallNoteEdit>,
    ): NoteEditEngine.EditOutcome {
        if (edits.isEmpty()) return NoteEditEngine.EditOutcome.NoOp
        if (edits.map { it.voiceTrackId }.distinct().size != 1) {
            return NoteEditEngine.EditOutcome.Conflict
        }

        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val resultIds = ArrayList<EventId>()
        for (edit in edits) {
            val voice = current.getVoiceTrack(edit.voiceTrackId)
                ?: return NoteEditEngine.EditOutcome.Conflict
            val selected = voice.events.toList()
                .filter { it.id in edit.eventIds }
                .sortedBy { it.onset }
            if (
                selected.isEmpty() ||
                selected.any { !it.isRest || it.duration.tuplet != null || it.tupletSpan != null }
            ) {
                return NoteEditEngine.EditOutcome.Conflict
            }

            val first = selected.first()
            val start = first.onset
            if (selected.any { it.onset.measure != start.measure }) {
                return NoteEditEngine.EditOutcome.Conflict
            }
            val startAbs = absolute(current, start)
            val endAbs = selected.maxOf { absolute(current, it.onset) + it.duration.toFraction() }
            val end = timeCodeAt(current, endAbs)
            if (!isMeasureLocalSpan(start, end)) return NoteEditEngine.EditOutcome.Conflict

            val eventsInRange = voice.events.toList()
                .filter { event ->
                    val eventStart = absolute(current, event.onset)
                    val eventEnd = eventStart + event.duration.toFraction()
                    eventStart >= startAbs && eventEnd <= endAbs
                }
                .sortedBy { it.onset }
            if (eventsInRange.mapTo(mutableSetOf()) { it.id } != edit.eventIds) {
                return NoteEditEngine.EditOutcome.Conflict
            }

            val totalLength = endAbs - startAbs
            val preferred = selected.size.coerceAtLeast(2)
            val count = (listOf(preferred) + (2..9)).distinct()
                .firstOrNull { tupletSpecFor(totalLength, it) != null }
                ?: return NoteEditEngine.EditOutcome.Conflict
            val spec = tupletSpecFor(totalLength, count)
                ?: return NoteEditEngine.EditOutcome.Conflict
            val span = TupletSpan(
                endTimeCode = end,
                count = count,
                beatUnit = spec.beatUnit,
                displayStyle = com.mecon.api.storage.events.TupletDisplayStyle.NONE,
                smallNotes = true,
            )
            val generated = fillTupletRests(current, start, totalLength, spec)
            if (generated.isEmpty()) return NoteEditEngine.EditOutcome.Conflict
            val placeholders = generated.mapIndexed { index, event ->
                val rendering = (event.rendering ?: RenderingProps.DEFAULT).copy(
                    scale = SMALL_NOTE_SCALE,
                    // Rests are capacity placeholders inside an open small-note region. They retain
                    // timing and snapping information but must not appear as entered notation.
                    hidden = true,
                )
                val placeholder = event.copy(
                    rendering = rendering,
                    tupletSpan = if (index == 0) span else null,
                )
                if (index == 0) {
                    placeholder.copy(
                        id = first.id,
                        pitchEvent = first.pitchEvent.copy(onset = placeholder.onset),
                    )
                } else {
                    placeholder
                }
            }
            val selectedIds = eventsInRange.mapTo(mutableSetOf()) { it.id }
            val kept = voice.events.toList().filter { it.id !in selectedIds }
            val filled = fillGaps(current, kept + placeholders, start.measure, start.measure)
            current = replaceVoice(current, voice, filled)
            intervals += wholeMeasure(start.measure)
            resultIds += selected.map { it.id }
        }
        return NoteEditEngine.EditOutcome.Changed(
            score = current,
            intervals = intervals.distinct(),
            resultEventIds = resultIds,
        )
    }

    fun applyTuplets(runtime: RuntimeScore, edits: List<NoteEditEngine.TupletEdit>): NoteEditEngine.EditOutcome {
        if (edits.isEmpty()) return NoteEditEngine.EditOutcome.NoOp
        if (edits.map { it.voiceTrackId }.distinct().size != 1) return NoteEditEngine.EditOutcome.Conflict

        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val resultIds = ArrayList<EventId>()
        var changed = false

        for (edit in edits) {
            val voice = current.getVoiceTrack(edit.voiceTrackId) ?: continue
            val selected = voice.events.toList()
                .filter { it.id in edit.eventIds }
                .sortedBy { it.onset }
            if (selected.isEmpty()) continue
            if (selected.any { it.duration.tuplet != null || it.tupletSpan != null }) return NoteEditEngine.EditOutcome.Conflict

            val first = selected.first()
            val start = first.onset
            if (selected.any { it.onset.measure != start.measure }) return NoteEditEngine.EditOutcome.Conflict
            val startAbs = absolute(current, start)
            val endAbs = selected.maxOf { absolute(current, it.onset) + it.duration.toFraction() }
            val end = timeCodeAt(current, endAbs)
            if (!isMeasureLocalSpan(start, end)) return NoteEditEngine.EditOutcome.Conflict

            val eventsInRange = voice.events.toList()
                .filter {
                    val evStart = absolute(current, it.onset)
                    val evEnd = evStart + it.duration.toFraction()
                    evStart >= startAbs && evEnd <= endAbs
                }
                .sortedBy { it.onset }
            if (eventsInRange.map { it.id }.toSet() != edit.eventIds) return NoteEditEngine.EditOutcome.Conflict
            if (eventsInRange.any { it.duration.tuplet != null || it.tupletSpan != null }) return NoteEditEngine.EditOutcome.Conflict

            val totalLength = endAbs - startAbs
            val spec = tupletSpecFor(totalLength, edit.count) ?: return NoteEditEngine.EditOutcome.Conflict
            val converted = ArrayList<RuntimeVoiceEvent>()
            val tupletSpan = TupletSpan(
                endTimeCode = end,
                count = edit.count,
                beatUnit = spec.beatUnit,
                displayStyle = spec.displayStyle,
            )
            for ((index, event) in eventsInRange.withIndex()) {
                val oldOffset = absolute(current, event.onset) - startAbs
                val onset = timeCodeAt(current, startAbs + oldOffset * spec.ratio)
                val duration = event.duration.copy(tuplet = spec.tuplet)
                converted += event.copy(
                    onset = onset,
                    pitchEvent = event.pitchEvent.copy(onset = onset),
                    duration = duration,
                    tupletSpan = if (index == 0) tupletSpan else null,
                )
            }

            val convertedEndAbs = converted.maxOf { absolute(current, it.onset) + it.duration.toFraction() }
            val tailStart = timeCodeAt(current, convertedEndAbs)
            val tailLength = endAbs - convertedEndAbs
            val rests = fillTupletRests(current, tailStart, tailLength, spec)
            val kept = voice.events.toList().filter { it.id !in eventsInRange.map(RuntimeVoiceEvent::id).toSet() }
            val filled = fillGaps(current, kept + converted + rests, start.measure, start.measure)
            current = replaceVoice(current, voice, filled)
            intervals += wholeMeasure(start.measure)
            resultIds += converted.map { it.id }
            changed = true
        }

        return if (changed) NoteEditEngine.EditOutcome.Changed(current, intervals.distinct(), resultIds) else NoteEditEngine.EditOutcome.NoOp
    }

    /**
     * Change selected events' displayed values. Ordinary non-tuplet events retain overwrite
     * semantics. Ordinary tuplets preserve their ratio and reject a member whose new actual end
     * exceeds the group span. Small-note groups keep their fixed total span, change only targeted
     * displayed values, and re-ratio/re-space entered members without materialising rests.
     */
    fun editDurations(runtime: RuntimeScore, edits: List<NoteEditEngine.DurationEdit>): NoteEditEngine.EditOutcome {
        if (edits.isEmpty()) return NoteEditEngine.EditOutcome.NoOp

        val smallGroupEdits =
            LinkedHashMap<Pair<TrackId, EventId>, MutableList<NoteEditEngine.DurationEdit>>()

        // Conflict pre-check on the untouched timeline. Small-note groups have a fixed metered span
        // but a dynamic common ratio, so their displayed-duration edits are collected and rebuilt as
        // one group below. Ordinary tuplets keep their ratio and reject any member duration whose
        // actual end would cross the declared exclusive endpoint.
        for ((voiceId, voiceEdits) in edits.groupBy { it.voiceTrackId }) {
            val voice = runtime.getVoiceTrack(voiceId) ?: continue
            val all = voice.events.toList()
            val editedIds = voiceEdits.map { it.eventId }.toSet()
            for (edit in voiceEdits) {
                val event = all.firstOrNull { it.id == edit.eventId } ?: continue
                val context = activeTupletContext(runtime, voice, event.onset)
                if (context?.span?.smallNotes == true) {
                    smallGroupEdits.getOrPut(voiceId to context.start.id) { ArrayList() }.add(edit)
                    continue
                }
                val effectiveDuration = if (context != null) {
                    edit.duration.copy(tuplet = context.tuplet)
                } else {
                    edit.duration
                }
                val newLen = effectiveDuration.toFraction()
                if (
                    context != null &&
                    absolute(runtime, event.onset) + newLen >
                        absolute(runtime, context.span.endTimeCode)
                ) {
                    return NoteEditEngine.EditOutcome.Conflict
                }
                if (newLen <= event.duration.toFraction()) continue // shrink / same → always legal
                val evAbs = absolute(runtime, event.onset)
                val newEndAbs = evAbs + newLen
                val nextNoteAbs = all.asSequence()
                    .filter { !it.isRest && it.id != event.id && it.id in editedIds }
                    .map { absolute(runtime, it.onset) }
                    .filter { it > evAbs }
                    .minOrNull()
                if (nextNoteAbs != null && nextNoteAbs < newEndAbs) return NoteEditEngine.EditOutcome.Conflict
            }
        }

        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val resultIds = ArrayList<EventId>()
        var changed = false

        val smallEditKeys = HashSet<Pair<TrackId, EventId>>()
        for ((key, groupEdits) in smallGroupEdits) {
            groupEdits.forEach { smallEditKeys += it.voiceTrackId to it.eventId }
            val (voiceId, startEventId) = key
            val voice = current.getVoiceTrack(voiceId) ?: continue
            val context = smallNoteContextByStartId(voice, startEventId) ?: continue
            val result = replaceSmallNoteDurations(current, voice, context, groupEdits) ?: continue
            current = result.score
            intervals += result.editInterval
            resultIds += groupEdits.map { it.eventId }
            changed = true
        }

        for (edit in edits) {
            if ((edit.voiceTrackId to edit.eventId) in smallEditKeys) continue
            val voice = current.getVoiceTrack(edit.voiceTrackId) ?: continue
            val event = voice.events.toList().firstOrNull { it.id == edit.eventId } ?: continue
            val tupletContext = activeTupletContext(current, voice, event.onset)
            val effectiveDuration = if (tupletContext != null) {
                edit.duration.copy(tuplet = tupletContext.tuplet)
            } else {
                edit.duration
            }
            if (event.duration == effectiveDuration) continue
            if (event.isGrace) {
                val updated = voice.events.toList().map {
                    if (it.id == event.id) it.copy(duration = effectiveDuration) else it
                }
                current = replaceVoice(current, voice, updated)
                intervals += wholeMeasure(event.onset.measure)
                resultIds += event.id
                changed = true
                continue
            }
            val r = if (tupletContext != null) {
                replaceTupletEventDuration(current, voice, event, effectiveDuration, tupletContext)
            } else {
                replaceEventDuration(current, voice, event, effectiveDuration)
            }
            current = r.score
            intervals.add(r.editInterval)
            r.insertedEventId?.let { resultIds.add(it) }
            changed = true
        }
        return if (changed) NoteEditEngine.EditOutcome.Changed(current, intervals, resultIds) else NoteEditEngine.EditOutcome.NoOp
    }

    /**
     * Edit only the selected members' displayed values, then derive one new common ratio so all
     * entered small notes still occupy the fixed region. Other members retain identity, pitch,
     * articulations, ties, rendering, and displayed base/dots; no capacity rests are materialised.
     */
    private fun replaceSmallNoteDurations(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        context: TupletSupport.TupletContext,
        edits: List<NoteEditEngine.DurationEdit>,
    ): NoteEditEngine.Result? {
        val regionStart = context.start.onset
        val regionEnd = context.span.endTimeCode
        val members = voice.events.toList()
            .filter { !it.isRest && it.onset >= regionStart && it.onset < regionEnd }
            .sortedBy { it.onset }
        if (members.isEmpty()) return null
        val durationById = edits.associate { it.eventId to it.duration.copy(tuplet = null) }
        if (members.none { member ->
                durationById[member.id]?.let { it != member.duration.copy(tuplet = null) } == true
            }
        ) {
            return null
        }

        val displayed = members.map { member ->
            durationById[member.id] ?: member.duration.copy(tuplet = null)
        }
        val totalDisplayed = displayed.fold(Fraction.ZERO) { sum, duration ->
            sum + duration.toFraction()
        }
        if (!totalDisplayed.isPositive) return null
        val regionLength = absolute(runtime, regionEnd) - absolute(runtime, regionStart)
        val ratio = (regionLength / totalDisplayed).simplified()
        if (!ratio.isPositive) return null
        val tuplet = com.mecon.api.primitive.Tuplet(
            actual = ratio.denominator,
            normal = ratio.numerator,
        )
        val span = context.span.copy(count = members.size.coerceAtLeast(2))

        var onset = regionStart
        val rebuilt = members.mapIndexed { index, member ->
            member.copy(
                onset = onset,
                pitchEvent = member.pitchEvent.copy(onset = onset),
                duration = displayed[index].copy(tuplet = tuplet),
                tupletSpan = if (index == 0) span else null,
            ).also {
                onset = advance(runtime, onset, it.duration.toFraction())
            }
        }
        val regionIds = voice.events.toList()
            .filter { it.onset >= regionStart && it.onset < regionEnd }
            .mapTo(mutableSetOf()) { it.id }
        val updated = voice.events.toList().filter { it.id !in regionIds } + rebuilt
        return NoteEditEngine.Result(
            score = replaceVoice(runtime, voice, updated),
            editInterval = TimeRange(regionStart, regionEnd),
            insertedEventId = edits.firstOrNull()?.eventId,
        )
    }

    /**
     * Tuplet-local duration replacement. The ratio and span remain fixed; shrinking writes only a
     * tuplet rest into the freed member tail, and growing clears only within the group.
     */
    private fun replaceTupletEventDuration(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        event: RuntimeVoiceEvent,
        duration: Duration,
        context: TupletSupport.TupletContext,
    ): NoteEditEngine.Result {
        val start = event.onset
        val oldLength = event.duration.toFraction()
        val newLength = duration.toFraction()
        val newEnd = advance(runtime, start, newLength)
        val oldEnd = advance(runtime, start, oldLength)
        val withoutEvent = voice.events.toList().filter { it.id != event.id }
        val cleared = clearTupletInterval(runtime, withoutEvent, start, newEnd, context)
        val replacement = event.copy(
            duration = duration,
            tupletSpan = if (event.id == context.start.id) context.span else event.tupletSpan,
        )
        val freedRests = if (newLength < oldLength) {
            fillTupletRests(
                runtime = runtime,
                start = newEnd,
                actualLength = oldLength - newLength,
                spec = NoteEditEngine.TupletSpec(
                    count = context.tuplet.actual,
                    normal = context.tuplet.normal,
                    beatUnit = context.span.beatUnit,
                    displayStyle = context.span.displayStyle,
                ),
            )
        } else {
            emptyList()
        }
        return NoteEditEngine.Result(
            score = replaceVoice(runtime, voice, cleared + replacement + freedRests),
            editInterval = TimeRange(context.start.onset, context.span.endTimeCode),
            insertedEventId = event.id,
        )
    }

    /**
     * Replace [event]'s duration with [duration] in place: drop the original, clear its new span
     * (consuming the following rests), materialise the new note/rest at the same onset via [fillRange]
     * (so a grown note re-ties across barlines, a shrunk one re-engraves cleanly), then pad the touched
     * measures back to full with rests. The event's existing tie-out is preserved.
     */
    private fun replaceEventDuration(
        runtime: RuntimeScore,
        voice: RuntimeVoiceTrack,
        event: RuntimeVoiceEvent,
        duration: Duration,
    ): NoteEditEngine.Result {
        val start = event.onset
        val newLen = duration.toFraction()
        val newEnd = advance(runtime, start, newLen)
        val oldEnd = advance(runtime, start, event.duration.toFraction())
        // Re-tie the whole chord out if it tied out before (per-pitch granularity is rebuilt by fillRange).
        val trailingTie = !event.isRest && event.pitches.isNotEmpty() && event.ties.isNotEmpty()

        val withoutEvent = voice.events.toList().filter { it.id != event.id }
        val cleared = clearIntervalEvents(runtime, withoutEvent, start, newEnd)
        val inserted = fillRange(
            runtime, start, newLen,
            event.pitches, event.pitchEvent.articulations, event.isRest, trailingTie,
        )
        // Touched span runs to whichever of the old/new ends is later (shrinking leaves a rest tail).
        val widerEnd = if (oldEnd >= newEnd) oldEnd else newEnd
        val endBeat = widerEnd.beat ?: Fraction.ZERO
        val fromMeasure = start.measure
        val toMeasure = if (endBeat.isPositive) widerEnd.measure else widerEnd.measure - 1
        val filled = fillGaps(runtime, cleared + inserted, fromMeasure, toMeasure)
        val widened = TimeRange(
            TimeCode.of(fromMeasure, Fraction.ZERO),
            TimeCode.of(toMeasure + 1, Fraction.ZERO),
        )
        return NoteEditEngine.Result(replaceVoice(runtime, voice, filled), widened, inserted.firstOrNull()?.id)
    }

    /** Respell the selected pitches of each [NoteEditEngine.AccidentalEdit]'s event. Timing is untouched, so there is never a conflict. */
    fun editAccidentals(runtime: RuntimeScore, edits: List<NoteEditEngine.AccidentalEdit>): NoteEditEngine.EditOutcome {
        if (edits.isEmpty()) return NoteEditEngine.EditOutcome.NoOp
        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val resultIds = ArrayList<EventId>()
        var changed = false
        for ((voiceId, voiceEdits) in edits.groupBy { it.voiceTrackId }) {
            val voice = current.getVoiceTrack(voiceId) ?: continue
            val byEvent = voiceEdits.associateBy { it.eventId }
            var voiceChanged = false
            val newEvents = voice.events.toList().map { event ->
                if (event.id !in byEvent || event.isRest || event.pitches.isEmpty()) return@map event
                val edit = byEvent.getValue(event.id)
                val accidental = edit.accidental
                val targets = edit.pitchIndices // null = whole chord
                val key = current.getKeySignatureAt(event.onset.measure)
                val newPitches = event.pitches.mapIndexed { idx, p ->
                    if (targets != null && idx !in targets) p
                    else if (accidental == null) DiatonicTranspose.spell(key, p.diatonicSteps)
                    else p.copy(chromaticOffset = accidental.offset)
                }
                if (newPitches == event.pitches) return@map event
                voiceChanged = true
                resultIds.add(event.id)
                intervals.add(wholeMeasure(event.onset.measure))
                withRespelledPitches(event, newPitches)
            }
            if (voiceChanged) {
                current = replaceVoice(current, voice, newEvents)
                changed = true
            }
        }
        return if (changed) NoteEditEngine.EditOutcome.Changed(current, intervals, resultIds) else NoteEditEngine.EditOutcome.NoOp
    }

    /** Add or remove the trailing tie on the selected pitches of each [NoteEditEngine.TieEdit]'s event. Timing is untouched. */
    fun editTies(runtime: RuntimeScore, edits: List<NoteEditEngine.TieEdit>): NoteEditEngine.EditOutcome {
        if (edits.isEmpty()) return NoteEditEngine.EditOutcome.NoOp
        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val resultIds = ArrayList<EventId>()
        val invalidatedGeometry = LinkedHashMap<EventId, MutableSet<Int>>()
        var changed = false
        for ((voiceId, voiceEdits) in edits.groupBy { it.voiceTrackId }) {
            val voice = current.getVoiceTrack(voiceId) ?: continue
            val byEvent = voiceEdits.associateBy { it.eventId }
            var voiceChanged = false
            val newEvents = voice.events.toList().map { event ->
                if (event.id !in byEvent || event.isRest || event.pitches.isEmpty()) return@map event
                val edit = byEvent.getValue(event.id)
                val targets = (edit.pitchIndices ?: event.pitches.indices.toSet())
                    .filter { it in event.pitches.indices }
                val newTies = if (edit.tieOut) {
                    // Union the existing ties with the targeted pitches (keep others untouched).
                    val byIdx = event.ties.associateBy { it.pitchIndex }.toMutableMap()
                    for (i in targets) byIdx.getOrPut(i) { RuntimeTieInfo(i, isLetRing = false) }
                    byIdx.values.sortedBy { it.pitchIndex }
                } else {
                    event.ties.filter { it.pitchIndex !in targets }
                }
                if (newTies == event.ties) return@map event
                invalidatedGeometry.getOrPut(event.id) { LinkedHashSet() }.addAll(targets)
                voiceChanged = true
                resultIds.add(event.id)
                // A tie binds this event to its successor (possibly in the next measure), so report both.
                intervals.add(TimeRange(
                    TimeCode.of(event.onset.measure, Fraction.ZERO),
                    TimeCode.of(event.onset.measure + 2, Fraction.ZERO),
                ))
                event.copy(ties = newTies)
            }
            if (voiceChanged) {
                current = replaceVoice(current, voice, newEvents)
                changed = true
            }
        }
        if (changed && invalidatedGeometry.isNotEmpty()) {
            val scoreGeometry = current.geometry
            if (scoreGeometry != null) {
                val tieMap = ((scoreGeometry.ties as? PersistentMap<
                    EventId,
                    List<com.mecon.api.storage.TieGeometry>
                >) ?: scoreGeometry.ties.toPersistentMap()).builder()
                invalidatedGeometry.forEach { (eventId, pitchIndices) ->
                    val kept = tieMap[eventId].orEmpty()
                        .filterNot { it.sourcePitchIndex in pitchIndices }
                    if (kept.isEmpty()) tieMap.remove(eventId) else tieMap[eventId] = kept
                }
                current = current.copy(geometry = scoreGeometry.copy(ties = tieMap.build()))
            }
        }
        return if (changed) NoteEditEngine.EditOutcome.Changed(current, intervals, resultIds) else NoteEditEngine.EditOutcome.NoOp
    }

    /** Apply explicit beam overrides to the given events. */
    fun editBeaming(runtime: RuntimeScore, edits: List<NoteEditEngine.BeamingEdit>): NoteEditEngine.EditOutcome {
        if (edits.isEmpty()) return NoteEditEngine.EditOutcome.NoOp
        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val resultIds = ArrayList<EventId>()
        var changed = false
        for ((voiceId, voiceEdits) in edits.groupBy { it.voiceTrackId }) {
            val voice = current.getVoiceTrack(voiceId) ?: continue
            val byEvent = voiceEdits.associateBy { it.eventId }
            var voiceChanged = false
            val newEvents = voice.events.toList().map { event ->
                if (event.id !in byEvent) return@map event
                val edit = byEvent.getValue(event.id)
                if (event.rendering?.beaming == edit.beaming) return@map event
                val base = event.rendering ?: RenderingProps.DEFAULT
                voiceChanged = true
                resultIds.add(event.id)
                intervals.add(wholeMeasure(event.onset.measure))
                event.copy(rendering = base.copy(beaming = edit.beaming))
            }
            if (voiceChanged) {
                current = replaceVoice(current, voice, newEvents)
                changed = true
            }
        }
        return if (changed) NoteEditEngine.EditOutcome.Changed(current, intervals, resultIds) else NoteEditEngine.EditOutcome.NoOp
    }

    /**
     * Set (or clear, when [NoteEditEngine.RestMoveTarget.staffPosition] is null) the display staff
     * position of each target rest via [com.mecon.api.storage.RenderingProps.restStaffPosition]. Only
     * the rest's own measure is touched (a rest's vertical position affects nothing else), so each edit
     * reports a single whole-measure interval and the batch is always incremental-friendly.
     */
    fun moveRest(runtime: RuntimeScore, targets: List<NoteEditEngine.RestMoveTarget>): NoteEditEngine.EditOutcome {
        if (targets.isEmpty()) return NoteEditEngine.EditOutcome.NoOp
        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val resultIds = ArrayList<EventId>()
        var changed = false
        for ((voiceId, voiceTargets) in targets.groupBy { it.voiceTrackId }) {
            val voice = current.getVoiceTrack(voiceId) ?: continue
            val byEvent = voiceTargets.associateBy { it.eventId }
            var voiceChanged = false
            val newEvents = voice.events.toList().map { event ->
                if (event.id !in byEvent || !event.isRest) return@map event
                val target = byEvent.getValue(event.id)
                if (event.rendering?.restStaffPosition == target.staffPosition) return@map event
                val base = event.rendering ?: RenderingProps.DEFAULT
                voiceChanged = true
                resultIds.add(event.id)
                intervals.add(wholeMeasure(event.onset.measure))
                event.copy(rendering = base.copy(restStaffPosition = target.staffPosition))
            }
            if (voiceChanged) {
                current = replaceVoice(current, voice, newEvents)
                changed = true
            }
        }
        return if (changed) NoteEditEngine.EditOutcome.Changed(current, intervals, resultIds) else NoteEditEngine.EditOutcome.NoOp
    }

    /**
     * Replace an event's pitches with [newPitches] (same notes, respelled), re-sorting the chord by
     * sounding pitch and re-indexing the existing ties onto the sorted order. No tie is dropped (an
     * accidental change keeps every notehead), unlike a transpose.
     */
    private fun withRespelledPitches(
        event: RuntimeVoiceEvent,
        newPitches: List<Pitch>,
    ): RuntimeVoiceEvent {
        val order = newPitches.indices.sortedBy { newPitches[it].midiNumber }
        val sorted = order.map { newPitches[it] }
        val oldToNew = IntArray(order.size).also { arr ->
            order.forEachIndexed { newIdx, oldIdx -> arr[oldIdx] = newIdx }
        }
        val newTies = event.ties.mapNotNull { tie ->
            if (tie.pitchIndex in newPitches.indices)
                RuntimeTieInfo(oldToNew[tie.pitchIndex], tie.isLetRing)
            else null
        }
        return event.copy(
            pitchEvent = event.pitchEvent.copy(pitches = sorted),
            ties = newTies,
        )
    }

    private const val SMALL_NOTE_SCALE = 0.7f
}
