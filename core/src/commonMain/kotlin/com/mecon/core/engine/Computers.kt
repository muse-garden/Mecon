package com.mecon.core.engine

import com.mecon.api.computed.*
import com.mecon.api.primitive.*
import com.mecon.api.runtime.*
import com.mecon.api.runtime.events.*
import com.mecon.api.runtime.tracks.*

import com.mecon.api.primitive.*
import com.mecon.api.runtime.RuntimeMeasure
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.AccidentalDisplay
import com.mecon.api.storage.events.TupletDisplayStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.collection.BPlusTree

/**
 * Computes measure position from onset and time signatures.
 */
object MeasurePositionComputer {

    fun compute(
        onset: TimeCode,
        measures: BPlusTree<Int, RuntimeMeasure, Int>,
        defaultTimeSignature: TimeSignature
    ): MeasurePosition {
        // Extract measure number from TimeCode
        val measureNumber = onset.measure

        // Calculate beat position within measure
        val beatPosition = onset.beat ?: Fraction.ZERO

        // Calculate absolute position from start
        var absolutePosition = Fraction.ZERO
        for (m in 1 until measureNumber) {
            val measure = measures.get(m)
            val timeSignature = measure?.timeSignature ?: defaultTimeSignature
            absolutePosition += timeSignature.measureDuration()
        }
        absolutePosition += beatPosition

        return MeasurePosition(
            measure = measureNumber,
            beatPosition = beatPosition,
            absolutePosition = absolutePosition
        )
    }
}

/**
 * Computes MIDI pitch considering transposition.
 */
object MidiPitchComputer {

    fun compute(
        pitch: Pitch,
        transpositionSemitones: Int = 0
    ): Int {
        return pitch.midiNumber + transpositionSemitones
    }
}

/**
 * Computes staff position from pitch and clef.
 *
 * Staff position is measured in diatonic steps relative to the middle line:
 * - 0 = middle line (B4 in treble clef, D3 in bass clef)
 * - Positive = above middle line
 * - Negative = below middle line
 *
 * Staff line positions for a standard 5-line staff:
 * - 4 = top line
 * - 2 = 4th line
 * - 0 = middle line (3rd line)
 * - -2 = 2nd line
 * - -4 = bottom line
 *
 * Notes at positions > 5 or < -5 require ledger lines.
 */
object StaffPositionComputer {

    /**
     * The diatonic step value for the middle line of each clef.
     * - TREBLE: B4 = diatonicSteps 6
     * - BASS: D3 = diatonicSteps -6
     * - ALTO: C4 = diatonicSteps 0
     * - TENOR: A3 = diatonicSteps -2
     */
    private fun middleLineDiatonicSteps(clef: Clef): Int = when (clef) {
        Clef.TREBLE -> 6    // B4
        Clef.BASS -> -6     // D3
        Clef.ALTO -> 0      // C4
        Clef.TENOR -> -2    // A3
        Clef.PERCUSSION -> 0
    }

    fun compute(
        pitch: Pitch,
        clef: Clef
    ): Int {
        // Staff position = pitch's diatonic steps - middle line's diatonic steps
        return pitch.diatonicSteps - middleLineDiatonicSteps(clef)
    }

    /**
     * Diatonic steps (relative to C4) of the note that would sit at [staffPosition] under [clef].
     * Inverse of [compute]; used to convert a rest's display staff position to MusicXML
     * `<display-step>`/`<display-octave>` and back.
     */
    fun diatonicStepsAt(staffPosition: Int, clef: Clef): Int =
        staffPosition + middleLineDiatonicSteps(clef)

    /** Staff position of a note with [diatonicSteps] (relative to C4) under [clef]. Inverse of [compute]. */
    fun staffPositionOf(diatonicSteps: Int, clef: Clef): Int =
        diatonicSteps - middleLineDiatonicSteps(clef)
}

/**
 * Computes effective accidental to display.
 *
 * Simplified implementation:
 * - Shows accidental if different from key signature
 * - Shows natural if previous note in same measure had different accidental
 */
object EffectiveAccidentalComputer {

    /**
     * Compute effective accidental for a single pitch.
     * This is the new method for the refactored model.
     */
    fun computeForPitch(
        pitch: Pitch,
        keySignature: KeySignature,
        previousPitchesInMeasure: List<Pitch>,
        displayOverride: AccidentalDisplay = AccidentalDisplay.AUTO
    ): Accidental? {
        return when (displayOverride) {
            AccidentalDisplay.FORCE -> pitch.accidental
            AccidentalDisplay.HIDE -> null
            AccidentalDisplay.PARENTHESES, AccidentalDisplay.CAUTIONARY -> pitch.accidental
            AccidentalDisplay.AUTO -> computeAutoForPitch(pitch, keySignature, previousPitchesInMeasure)
        }
    }

    private fun computeAutoForPitch(
        pitch: Pitch,
        keySignature: KeySignature,
        previousPitchesInMeasure: List<Pitch>
    ): Accidental? {
        val noteAccidental = pitch.accidental
        val keyAccidental = keySignature.accidentalFor(pitch.noteName)

        // Find previous pitches with same diatonic position in this measure
        val sameDiatonicPrevious = previousPitchesInMeasure
            .filter { it.diatonicSteps == pitch.diatonicSteps }
            .lastOrNull()

        return when {
            // Previous note in measure has different accidental -> show ours
            sameDiatonicPrevious != null &&
            sameDiatonicPrevious.accidental != noteAccidental -> noteAccidental

            // Previous note has same accidental -> don't show
            sameDiatonicPrevious != null &&
            sameDiatonicPrevious.accidental == noteAccidental -> null

            // No previous note, different from key signature -> show
            noteAccidental != keyAccidental -> noteAccidental

            // Matches key signature -> don't show
            else -> null
        }
    }
}

/**
 * Computes beam groups.
 *
 * Provides two modes:
 * 1. Track-level computation: processes all events together, respecting user-defined beaming
 * 2. Per-event computation: automatic beaming for a single event (legacy)
 */
object BeamGroupComputer {

    /**
     * Compute beaming for all events in a voice track.
     *
     * This method:
     * 1. First processes events with user-defined beaming (from rendering.beaming)
     * 2. Then groups remaining events by beat and applies automatic beaming
     *
     * @param events All events in the voice track
     * @param measures Measure definitions
     * @param defaultTimeSignature Default time signature
     * @return Map of EventId to BeamInfo
     */
    fun computeBeamingForTrack(
        events: List<RuntimeVoiceEvent>,
        measures: BPlusTree<Int, RuntimeMeasure, Int>,
        defaultTimeSignature: TimeSignature
    ): Map<EventId, BeamInfo> {
        val result = mutableMapOf<EventId, BeamInfo>()

        // Filter to beamable events (8th notes and shorter, not rests)
        val beamableEvents = events.filter {
            !it.isRest && it.duration.base.ticks < DurationBase.QUARTER.ticks
        }.sortedBy { it.onset }

        // `rendering.beaming != null` means the user/importer has made an explicit choice.
        // `null` means "fall back to automatic beaming".
        val hasUserDefinedBeaming = beamableEvents.any { it.rendering?.beaming != null }

        // Auto notes adjacent to explicit notes that want to connect through them are "absorbed"
        // into the explicit group so that e.g. a single explicit middle() note stays connected
        // to its surrounding auto-beamed neighbors rather than forming an isolated singleton.
        val absorbedAutoIds = if (hasUserDefinedBeaming) computeAbsorbedAutoIds(beamableEvents) else emptySet()
        val automaticBeamingCandidates = beamableEvents.filter {
            it.rendering?.beaming == null && it.id !in absorbedAutoIds
        }

        // Process user-defined beaming first (includes absorbed auto notes as pass-through members)
        if (hasUserDefinedBeaming) {
            processUserDefinedBeaming(beamableEvents, absorbedAutoIds, result)
        }

        // A tuplet is one rhythmic gesture even when its actual span crosses ordinary beat-group
        // boundaries (for example six eighth-note sextuplet members across a half note). Resolve
        // those groups before meter-based auto beaming, unless the user/importer explicitly chose
        // beam edges for the group.
        val tupletBeamedIds = processAutomaticTupletBeaming(events, automaticBeamingCandidates, result)

        // Process remaining automatic notes by the meter's ordinary beat groups.
        processAutomaticBeaming(
            automaticBeamingCandidates.filter { it.id !in tupletBeamedIds },
            measures,
            defaultTimeSignature,
            result,
        )

        return result
    }

    private fun processAutomaticTupletBeaming(
        allEvents: List<RuntimeVoiceEvent>,
        candidates: List<RuntimeVoiceEvent>,
        result: MutableMap<EventId, BeamInfo>,
    ): Set<EventId> {
        val claimed = mutableSetOf<EventId>()
        for (start in allEvents.filter { it.tupletSpan != null }.sortedBy { it.onset }) {
            val span = start.tupletSpan ?: continue
            val allBeamableMembers = allEvents.filter {
                !it.isRest && it.duration.base.ticks < DurationBase.QUARTER.ticks &&
                    it.onset >= start.onset && it.onset < span.endTimeCode
            }
            if (allBeamableMembers.any { it.rendering?.beaming != null }) continue
            val members = candidates.filter {
                it.id !in claimed && it.onset >= start.onset && it.onset < span.endTimeCode
            }.sortedBy { it.onset }
            if (members.size < 2) continue
            val groupId = BeamGroupId("beam_tuplet_${start.id.value}")
            for ((index, event) in members.withIndex()) {
                val beamCount = BeamInfo.beamCountFromDuration(event.duration)
                val leftCount = members.getOrNull(index - 1)?.let {
                    minOf(beamCount, BeamInfo.beamCountFromDuration(it.duration))
                } ?: 0
                val rightCount = members.getOrNull(index + 1)?.let {
                    minOf(beamCount, BeamInfo.beamCountFromDuration(it.duration))
                } ?: 0
                result[event.id] = BeamInfo(groupId, beamCount, leftCount, rightCount)
                claimed += event.id
            }
        }
        return claimed
    }

    /**
     * For each explicit-beamed note, collect all consecutive auto-beamed (beaming=null) notes
     * reachable in the direction(s) the explicit note wants to connect. These "absorbed" auto notes
     * are pulled into the explicit group instead of being left for auto-beaming, so that e.g. a
     * single explicit middle() note stays in its surrounding auto-beam group rather than forming
     * an isolated singleton that gets discarded.
     */
    private fun computeAbsorbedAutoIds(events: List<RuntimeVoiceEvent>): Set<EventId> {
        val absorbed = mutableSetOf<EventId>()
        for (i in events.indices) {
            val beaming = events[i].rendering?.beaming ?: continue
            if (!beaming.isBeamed) continue
            if (beaming.beamLeft) {
                var j = i - 1
                while (j >= 0 && events[j].rendering?.beaming == null) { absorbed.add(events[j].id); j-- }
            }
            if (beaming.beamRight) {
                var j = i + 1
                while (j < events.size && events[j].rendering?.beaming == null) { absorbed.add(events[j].id); j++ }
            }
        }
        return absorbed
    }

    /**
     * Process events with user-defined beaming.
     *
     * Builds beam groups from events whose [RenderingProps.beaming] is non-null.
     * Explicit [BeamingInfo.NONE] breaks user-defined groups and also suppresses automatic
     * beaming for that note.
     *
     * [absorbedAutoIds] contains auto-beamed notes that were pulled in by adjacent explicit notes
     * (see [computeAbsorbedAutoIds]). They are treated as transparent pass-through members of the
     * explicit group and connect to both their neighbors.
     */
    private fun processUserDefinedBeaming(
        events: List<RuntimeVoiceEvent>,
        absorbedAutoIds: Set<EventId>,
        result: MutableMap<EventId, BeamInfo>
    ) {
        if (events.isEmpty()) return

        // A manual beam control describes the edge beside the selected note. Treat that edge as
        // connected when either endpoint requests it, but only when the notes are temporally
        // adjacent. The input has rests and non-beamable notes filtered out, so list adjacency
        // alone would join beams across gaps (and potentially across layout systems).
        fun hasConnectedEdge(left: RuntimeVoiceEvent, right: RuntimeVoiceEvent): Boolean =
            left.endTime.compareTo(right.onset) == 0 &&
                (left.rendering?.beaming?.beamRight == true ||
                    right.rendering?.beaming?.beamLeft == true ||
                    (left.id in absorbedAutoIds && right.id in absorbedAutoIds))

        val groups = mutableListOf<List<RuntimeVoiceEvent>>()
        var currentGroup = mutableListOf<RuntimeVoiceEvent>()
        for (index in 0 until events.lastIndex) {
            val left = events[index]
            val right = events[index + 1]
            if (hasConnectedEdge(left, right)) {
                if (currentGroup.isEmpty()) currentGroup.add(left)
                currentGroup.add(right)
            } else if (currentGroup.isNotEmpty()) {
                groups.add(currentGroup.toList())
                currentGroup = mutableListOf()
            }
        }
        if (currentGroup.isNotEmpty()) groups.add(currentGroup.toList())

        // Generate BeamInfo for each group
        for (group in groups) {
            if (group.size < 2) continue  // Need at least 2 notes for a beam

            val groupId = BeamGroupId("beam_user_${group.first().id.value}")

            for ((index, event) in group.withIndex()) {
                val totalBeamCount = BeamInfo.beamCountFromDuration(event.duration)

                // Group membership was established from connected edges, so expose every internal
                // edge symmetrically to the renderer even if only one endpoint requested it.
                val beamsLeft = if (index > 0) {
                    val leftNeighbor = group[index - 1]
                    minOf(totalBeamCount, BeamInfo.beamCountFromDuration(leftNeighbor.duration))
                } else 0

                val beamsRight = if (index < group.size - 1) {
                    val rightNeighbor = group[index + 1]
                    minOf(totalBeamCount, BeamInfo.beamCountFromDuration(rightNeighbor.duration))
                } else 0

                result[event.id] = BeamInfo(
                    groupId = groupId,
                    totalBeamCount = totalBeamCount,
                    beamsLeft = beamsLeft,
                    beamsRight = beamsRight
                )
            }
        }
    }

    /**
     * Process events with automatic beaming.
     *
     * Groups events by measure and beat, then applies automatic beaming rules.
     */
    private fun processAutomaticBeaming(
        events: List<RuntimeVoiceEvent>,
        measures: BPlusTree<Int, RuntimeMeasure, Int>,
        defaultTimeSignature: TimeSignature,
        result: MutableMap<EventId, BeamInfo>
    ) {
        // Group by measure and beam group: notes in the same beat group (per the measure's effective
        // time signature grouping — e.g. 6/8 → 3+3, 7/8 → 2+2+3) beam together.
        val eventsByMeasureAndBeat = events.groupBy { event ->
            val measurePosition = MeasurePositionComputer.compute(event.onset, measures, defaultTimeSignature)
            val effectiveTs = measures.get(measurePosition.measure)?.timeSignature ?: defaultTimeSignature
            val groupIndex = effectiveTs.beatGroupIndexOf(measurePosition.beatPosition)
            measurePosition.measure to groupIndex
        }

        // Process each group
        for ((key, eventsInBeat) in eventsByMeasureAndBeat) {
            val (measure, beatIndex) = key
            if (eventsInBeat.size < 2) continue

            val sorted = eventsInBeat.sortedBy { it.onset }
            val groupId = BeamGroupId("beam_${measure}_${beatIndex}_${sorted.first().id.value}")

            for ((index, event) in sorted.withIndex()) {
                val totalBeamCount = BeamInfo.beamCountFromDuration(event.duration)
                val isStart = index == 0
                val isEnd = index == sorted.size - 1

                val beamsLeft = if (isStart) {
                    0
                } else {
                    val leftNeighbor = sorted[index - 1]
                    val leftBeamCount = BeamInfo.beamCountFromDuration(leftNeighbor.duration)
                    minOf(totalBeamCount, leftBeamCount)
                }

                val beamsRight = if (isEnd) {
                    0
                } else {
                    val rightNeighbor = sorted[index + 1]
                    val rightBeamCount = BeamInfo.beamCountFromDuration(rightNeighbor.duration)
                    minOf(totalBeamCount, rightBeamCount)
                }

                result[event.id] = BeamInfo(
                    groupId = groupId,
                    totalBeamCount = totalBeamCount,
                    beamsLeft = beamsLeft,
                    beamsRight = beamsRight
                )
            }
        }
    }

    /**
     * Compute beam info for a VoiceEvent (legacy per-event API).
     *
     * @deprecated Use computeBeamingForTrack instead for better user-defined beaming support
     */
    @Deprecated("Use computeBeamingForTrack instead")
    fun computeForVoiceEvent(
        event: RuntimeVoiceEvent,
        measurePosition: MeasurePosition,
        eventsInSameBeat: List<RuntimeVoiceEvent>,
        beatIndex: Int = 0
    ): BeamInfo? {
        // Only beam eighth notes and shorter
        if (event.duration.base.ticks >= DurationBase.QUARTER.ticks) {
            return null
        }

        // Filter to beamable notes in the same beat (exclude rests)
        val beamableInBeat = eventsInSameBeat
            .filter { !it.isRest && it.duration.base.ticks < DurationBase.QUARTER.ticks }
            .sortedBy { it.onset }

        // Need at least 2 notes for a beam
        if (beamableInBeat.size < 2) {
            return null
        }

        // Use beat index for group ID (all notes in same beat share the same group)
        val groupId = BeamGroupId("beam_${measurePosition.measure}_$beatIndex")
        val index = beamableInBeat.indexOfFirst { it.id == event.id }

        if (index < 0) return null

        val totalBeamCount = BeamInfo.beamCountFromDuration(event.duration)
        val isStart = index == 0
        val isEnd = index == beamableInBeat.size - 1

        // Calculate beams connecting to neighbors
        val beamsLeft = if (isStart) {
            0
        } else {
            val leftNeighbor = beamableInBeat[index - 1]
            val leftBeamCount = BeamInfo.beamCountFromDuration(leftNeighbor.duration)
            minOf(totalBeamCount, leftBeamCount)
        }

        val beamsRight = if (isEnd) {
            0
        } else {
            val rightNeighbor = beamableInBeat[index + 1]
            val rightBeamCount = BeamInfo.beamCountFromDuration(rightNeighbor.duration)
            minOf(totalBeamCount, rightBeamCount)
        }

        return BeamInfo(
            groupId = groupId,
            totalBeamCount = totalBeamCount,
            beamsLeft = beamsLeft,
            beamsRight = beamsRight
        )
    }
}

/**
 * Computes tie targets for voice events.
 *
 * Explicit ties ([RuntimeVoiceEvent.ties]) are resolved using B+ tree lookups on the
 * voice track's [TimeIndexedList] (O(log n) per lookup). When no explicit ties are
 * present, a heuristic fallback detects ties by matching a note's end time to the
 * next event's onset via [TimeIndexedList.at].
 */
object TieTargetComputer {

    data class PitchTieResult(
        val pitchIndex: Int,
        val tieTarget: ComputedTieTarget
    )

    /** Cross-staff render offset of an event (0 = home staff). */
    private fun renderOffset(event: RuntimeVoiceEvent): Int = event.rendering?.crossStaffOffset ?: 0

    /**
     * Compute per-pitch tie targets for a VoiceEvent.
     *
     * @param event The voice event to compute ties for
     * @param trackEvents The full [TimeIndexedList] for this voice track
     * @param useHeuristicFallback Fall back to timing-based detection when [event] has no explicit ties
     */
    fun computePerPitchTies(
        event: RuntimeVoiceEvent,
        trackEvents: TimeIndexedList<RuntimeVoiceEvent>,
        useHeuristicFallback: Boolean = true
    ): List<PitchTieResult> {
        if (event.isRest) return emptyList()

        val results = mutableListOf<PitchTieResult>()
        val pitches = event.pitches
        val explicitTieIndices = event.ties.map { it.pitchIndex }.toSet()

        for (tieInfo in event.ties) {
            if (tieInfo.pitchIndex < 0 || tieInfo.pitchIndex >= pitches.size) continue
            if (tieInfo.isLetRing) {
                results.add(PitchTieResult(tieInfo.pitchIndex, ComputedTieTarget.letRing()))
                continue
            }
            val sourcePitch = pitches[tieInfo.pitchIndex]
            // No following same-pitch note → render the explicit tie as a let-ring (laissez vibrer)
            // instead of dropping it entirely. If a matching note is later inserted after this event,
            // resolveExplicitTieTarget will find it and the arc becomes a real connecting tie.
            val targetEvent = resolveExplicitTieTarget(event, sourcePitch, trackEvents)
            if (targetEvent == null) {
                results.add(PitchTieResult(tieInfo.pitchIndex, ComputedTieTarget.letRing()))
                continue
            }
            // A tie may only connect two notes rendered on the same staff. When the source and
            // target render on different staves (different cross-staff offsets), degrade to let-ring.
            if (renderOffset(event) != renderOffset(targetEvent)) {
                results.add(PitchTieResult(tieInfo.pitchIndex, ComputedTieTarget.letRing()))
                continue
            }
            val targetPitchIndex = targetEvent.pitches.indexOfFirst { it.midiNumber == sourcePitch.midiNumber }
            results.add(
                PitchTieResult(
                    tieInfo.pitchIndex,
                    ComputedTieTarget.toEvent(targetEvent.id, if (targetPitchIndex >= 0) targetPitchIndex else 0)
                )
            )
        }

        if (useHeuristicFallback && event.ties.isEmpty()) {
            for ((pitchIndex, targetEvent, targetPitchIndex) in computeHeuristicTies(event, trackEvents)) {
                if (pitchIndex in explicitTieIndices) continue
                val tieTarget = if (renderOffset(event) != renderOffset(targetEvent)) {
                    ComputedTieTarget.letRing()
                } else {
                    ComputedTieTarget.toEvent(targetEvent.id, targetPitchIndex)
                }
                results.add(PitchTieResult(pitchIndex, tieTarget))
            }
        }

        return results
    }

    /**
     * Resolve explicit tie target using B+ tree lookups on [trackEvents]:
     * 0. If the source is a grace note, delegate to [resolveGraceTieTarget].
     * 1. [TimeIndexedList.firstAfter] to find the immediately next event — connect if same pitch.
     * 2. If that event is in a different measure (or absent), [TimeIndexedList.at] the start of
     *    the next measure (beat = 0) and return the first event there with the same pitch.
     * 3. Return null if neither candidate has the pitch.
     */
    private fun resolveExplicitTieTarget(
        event: RuntimeVoiceEvent,
        pitch: Pitch,
        trackEvents: TimeIndexedList<RuntimeVoiceEvent>
    ): RuntimeVoiceEvent? {
        if (event.isGrace) {
            return resolveGraceTieTarget(event, pitch, trackEvents)
        }

        val nextEvent = trackEvents.firstAfter(event.onset)

        if (nextEvent != null && nextEvent.pitches.any { it.midiNumber == pitch.midiNumber }) {
            return nextEvent
        }

        val nextInDifferentMeasure = nextEvent == null || nextEvent.onset.measure != event.onset.measure
        if (nextInDifferentMeasure) {
            val nextMeasureStart = TimeCode.of(event.onset.measure + 1, Fraction.ZERO)
            return trackEvents.at(nextMeasureStart)
                .firstOrNull { it.pitches.any { p -> p.midiNumber == pitch.midiNumber } }
        }

        return null
    }

    /**
     * Grace-note tie rule: scan forward from a grace source through subsequent
     * events that share the same `(measure, beat)` — i.e. remaining graces in
     * the same group plus the principal note at `grace == null` — and return
     * the first one whose pitch matches. The principal itself is included; an
     * event past it (different beat/measure) ends the search.
     */
    private fun resolveGraceTieTarget(
        event: RuntimeVoiceEvent,
        pitch: Pitch,
        trackEvents: TimeIndexedList<RuntimeVoiceEvent>
    ): RuntimeVoiceEvent? {
        val sourceMeasure = event.onset.measure
        val sourceBeat = event.onset.beat ?: Fraction.ZERO

        for (candidate in trackEvents.atOrAfter(event.onset)) {
            if (candidate.id == event.id) continue
            val candidateBeat = candidate.onset.beat ?: Fraction.ZERO
            val sameBeat = candidate.onset.measure == sourceMeasure && candidateBeat == sourceBeat
            if (!sameBeat) return null
            if (candidate.pitches.any { it.midiNumber == pitch.midiNumber }) {
                return candidate
            }
            if (candidate.onset.grace == null) return null
        }
        return null
    }

    /**
     * Heuristic detection: a note is tied when it ends exactly when another note with
     * the same pitch starts. Uses [TimeIndexedList.at] for an O(log n) onset lookup.
     *
     * Skips candidates that already have an explicit outgoing tie for the matched pitch
     * index to avoid spurious duplicate arcs.
     */
    private fun computeHeuristicTies(
        event: RuntimeVoiceEvent,
        trackEvents: TimeIndexedList<RuntimeVoiceEvent>
    ): List<Triple<Int, RuntimeVoiceEvent, Int>> {
        val endTime = event.onset + event.duration.toFraction()
        val candidateEvents = trackEvents.at(endTime).filter { !it.isRest && it.id != event.id }
        if (candidateEvents.isEmpty()) return emptyList()

        val results = mutableListOf<Triple<Int, RuntimeVoiceEvent, Int>>()
        for ((pitchIndex, pitch) in event.pitches.withIndex()) {
            for (candidate in candidateEvents) {
                val targetPitchIndex = candidate.pitches.indexOfFirst { it.midiNumber == pitch.midiNumber }
                if (targetPitchIndex >= 0) {
                    if (!candidate.ties.any { it.pitchIndex == targetPitchIndex }) {
                        results.add(Triple(pitchIndex, candidate, targetPitchIndex))
                    }
                    break
                }
            }
        }
        return results
    }
}

/**
 * Resolves [com.mecon.api.storage.events.TupletSpan] entries on voice events
 * into [ComputedTupletInfo] objects with concrete end-event references.
 *
 * For each event that carries a `tupletSpan`, the computer scans forward in
 * the (onset-sorted) track for events whose onset is < `endTimeCode`. The
 * last such event becomes the [ComputedTupletInfo.endEventId]. The display
 * style is forwarded verbatim from the storage span — callers control
 * bracket / slur / number presentation directly.
 */
object TupletComputer {

    /**
     * Compute tuplet info for every starting event in a voice track.
     *
     * @param events Track events, in any order — the computer sorts internally.
     * @return Map keyed by the starting event's ID.
     */
    fun computeForTrack(
        events: List<RuntimeVoiceEvent>,
    ): Map<EventId, ComputedTupletInfo> {
        val sorted = events.sortedBy { it.onset }
        val result = mutableMapOf<EventId, ComputedTupletInfo>()

        for ((index, start) in sorted.withIndex()) {
            val span = start.tupletSpan ?: continue

            // Collect events whose onset is strictly inside [start.onset, endTimeCode).
            var lastId = start.id
            var i = index + 1
            while (i < sorted.size && sorted[i].onset < span.endTimeCode) {
                lastId = sorted[i].id
                i++
            }

            result[start.id] = ComputedTupletInfo(
                startEventId = start.id,
                endEventId = lastId,
                count = span.count,
                displayStyle = span.displayStyle,
                smallNotes = span.smallNotes,
            )
        }

        return result
    }
}

/**
 * Resolves per-event slur start/end **counts** into concrete [ComputedSlur]
 * pairs by walking each voice track in onset order with a LIFO stack of
 * currently-open slurs.
 *
 * Rules:
 *  - At each event, **closes are applied before opens**. For each of
 *    `event.slurEnds`, the innermost open slur is popped and emitted with
 *    `endEventId = event.id`.
 *  - For each of `event.slurStarts`, a new open-slur token is pushed,
 *    remembering its stack depth at push time (this becomes [nestingLevel]).
 *  - Slurs left open at end-of-track are dropped (best-effort: malformed
 *    input shouldn't crash rendering).
 */
object SlurResolver {

    private data class OpenSlur(val startEventId: EventId, val depthAtOpen: Int)

    /**
     * Resolve a voice track's slurs, preferring first-class
     * [com.mecon.api.runtime.tracks.RuntimeSlur]s when present and falling back
     * to legacy slurStarts/slurEnds counts otherwise. Centralises the branch so
     * both full and incremental compute stay in sync.
     */
    fun computeForVoiceTrack(voiceTrack: RuntimeVoiceTrack): List<ComputedSlur> {
        val events = voiceTrack.events.toList()
        if (events.isEmpty()) return emptyList()
        return when {
            voiceTrack.slurs.isNotEmpty() ->
                computeFromExplicit(voiceTrack.id, voiceTrack.voiceNumber, voiceTrack.slurs, events)
            events.any { it.slurStarts > 0 || it.slurEnds > 0 } ->
                computeFromCounts(voiceTrack.id, voiceTrack.voiceNumber, events)
            else -> emptyList()
        }
    }

    /**
     * Legacy counts path: walk events in onset order with a LIFO stack.
     * Closes are applied before opens; `depthAtOpen` becomes the nesting level.
     * Each emitted slur gets a deterministic derived id ([derivedSlurId]).
     */
    fun computeFromCounts(
        voiceTrackId: TrackId,
        voiceNumber: Int,
        events: List<RuntimeVoiceEvent>,
    ): List<ComputedSlur> {
        val sorted = events.sortedBy { it.onset }
        val stack = ArrayDeque<OpenSlur>()
        val result = mutableListOf<ComputedSlur>()

        for (event in sorted) {
            // Closes first — pop innermost open slurs.
            repeat(event.slurEnds) {
                val open = stack.removeLastOrNull() ?: return@repeat
                result.add(
                    ComputedSlur(
                        slurId = derivedSlurId(open.startEventId, event.id, open.depthAtOpen),
                        startEventId = open.startEventId,
                        endEventId = event.id,
                        voiceTrackId = voiceTrackId,
                        voiceNumber = voiceNumber,
                        nestingLevel = open.depthAtOpen,
                    )
                )
            }
            // Then opens — push new slur tokens.
            repeat(event.slurStarts) {
                stack.addLast(OpenSlur(startEventId = event.id, depthAtOpen = stack.size))
            }
        }

        return result
    }

    /**
     * Explicit path: first-class slurs carry their own stable id. Nesting level
     * is the number of *other* slurs on the track whose onset interval strictly
     * contains this one's (matches LIFO depth for well-nested slurs).
     */
    fun computeFromExplicit(
        voiceTrackId: TrackId,
        voiceNumber: Int,
        slurs: List<RuntimeSlur>,
        events: List<RuntimeVoiceEvent>,
    ): List<ComputedSlur> {
        val onsetById = events.associate { it.id to it.onset }
        return slurs.mapNotNull { slur ->
            val s = onsetById[slur.startEventId] ?: return@mapNotNull null
            val e = onsetById[slur.endEventId] ?: return@mapNotNull null
            val nesting = slurs.count { other ->
                if (other.id == slur.id) return@count false
                val os = onsetById[other.startEventId] ?: return@count false
                val oe = onsetById[other.endEventId] ?: return@count false
                os <= s && e <= oe && (os < s || e < oe)   // strictly contains [s, e]
            }
            ComputedSlur(
                slurId = slur.id,
                startEventId = slur.startEventId,
                endEventId = slur.endEventId,
                voiceTrackId = voiceTrackId,
                voiceNumber = voiceNumber,
                nestingLevel = nesting,
            )
        }
    }

    /** Stable id for a counts-derived slur (no first-class storage event). */
    private fun derivedSlurId(start: EventId, end: EventId, nesting: Int): EventId =
        EventId("slur:${start.value}->${end.value}:$nesting")
}
