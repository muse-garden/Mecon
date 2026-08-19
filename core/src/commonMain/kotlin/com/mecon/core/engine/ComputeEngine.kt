package com.mecon.core.engine

import com.mecon.api.computed.*
import com.mecon.api.primitive.*
import com.mecon.api.runtime.*
import com.mecon.api.runtime.events.*
import com.mecon.api.runtime.tracks.*
import com.mecon.api.storage.AccidentalDisplay
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.StorageClefChange
import com.mecon.api.storage.tracks.StorageFermata

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeStaffTrack
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack

/**
 * Compute engine - calculates all derived fields for a score.
 *
 * This is a simplified implementation that performs full recalculation.
 * When the score changes, call compute() to regenerate all computed events.
 *
 * In the new model, computation is based on VoiceEvents (rendered notes)
 * rather than PitchEvents (raw pitch data).
 */
class ComputeEngine(
    private val score: RuntimeScore
) {

    /** Staves in display order; index increases downward. Used to resolve cross-staff render targets. */
    private val orderedStaffs: List<RuntimeStaffTrack> = score.orderedStaffs()
    private val staffIndexById: Map<TrackId, Int> =
        orderedStaffs.withIndex().associate { (i, s) -> s.id to i }
    private val staffPitchTimelines: Map<TrackId, StaffPitchContext.Timeline> =
        score.staffTracks.values.associate { it.id to StaffPitchContext.timeline(it) }

    /**
     * Resolve the staff a note actually renders on, honoring a cross-staff offset
     * ([com.mecon.api.storage.RenderingProps.crossStaffOffset]). `-1` = staff above,
     * `+1` = staff below; null/0 or an unknown home staff returns [homeStaff]. The
     * target index is clamped to the available staff range.
     */
    private fun resolveRenderStaff(homeStaff: RuntimeStaffTrack, offset: Int?): RuntimeStaffTrack {
        if (offset == null || offset == 0 || orderedStaffs.isEmpty()) return homeStaff
        val homeIndex = staffIndexById[homeStaff.id] ?: return homeStaff
        val targetIndex = (homeIndex + offset).coerceIn(0, orderedStaffs.lastIndex)
        return orderedStaffs[targetIndex]
    }

    /**
     * Result of notation event computation.
     */
    data class NotationEvents(
        val barlines: List<ComputedBarline>,
        val clefs: List<ComputedClef>,
        val keySignatures: List<ComputedKeySignature>,
        val timeSignatures: List<ComputedTimeSignature>
    )
    /**
     * Compute all voice events in the score.
     * Returns a persistent [ComputedEventStore] keyed by [EventId].
     */
    fun compute(): ComputedEventStore {
        val result = mutableListOf<ComputedVoiceEvent>()

        // Process each staff track
        for ((_, staff) in score.staffTracks) {
            // Process each voice track in the staff
            for (voiceTrack in staff.voiceTracks) {
                result.addAll(computeVoiceTrack(staff, voiceTrack))
            }
        }

        // Synthesize a whole-measure rest for every measure that has no notes, so
        // empty measures (e.g. a freshly created score) render a rest rather than
        // blank space. These are derived, not stored — see [computeImplicitMeasureRests].
        result.addAll(computeImplicitMeasureRests())

        return ComputedEventStore.of(result)
    }

    /**
     * Build implicit whole-measure rests for measures that contain no events.
     *
     * One rest is emitted per staff per empty measure, placed on the staff's first
     * voice track. A measure counts as empty when none of the staff's voices have an
     * event whose onset falls in that measure. Rests are generated up to the larger
     * of the declared measure count and the last measure that actually contains an
     * event, matching the barline range from [computeBarlines].
     */
    private fun computeImplicitMeasureRests(): List<ComputedVoiceEvent> {
        val declaredMaxMeasure = score.measures.maxOfOrNull { it.key } ?: 0
        val lastEventMeasure = score.staffTracks.values
            .flatMap { it.voiceTracks }
            .flatMap { it.events.toList() }
            .maxOfOrNull { it.onset.measure } ?: 0
        val maxMeasure = maxOf(declaredMaxMeasure, lastEventMeasure)
        if (maxMeasure < 1) return emptyList()

        val rests = mutableListOf<ComputedVoiceEvent>()
        for ((_, staff) in score.staffTracks) {
            val firstVoice = staff.voiceTracks.firstOrNull() ?: continue
            val occupiedMeasures = staff.voiceTracks
                .flatMap { it.events.toList() }
                .mapTo(mutableSetOf()) { it.onset.measure }

            for (measure in 1..maxMeasure) {
                if (measure in occupiedMeasures) continue
                rests += buildMeasureRest(firstVoice, measure)
            }
        }
        return rests
    }

    /**
     * Windowed counterpart of [computeImplicitMeasureRests] for the incremental path: emit whole-
     * measure rests only for empty measures whose number is in [window] (clamped to `1..maxMeasure`).
     *
     * Used after a local edit to refresh just the rests that the edit could have added or removed;
     * [maxMeasure] is the score's measure count (unchanged on the incremental path — structural
     * edits go through full recompute) so the window never emits rests past the score's end.
     */
    fun computeImplicitRestsInWindow(window: IntRange, maxMeasure: Int): List<ComputedVoiceEvent> {
        val lo = maxOf(1, window.first)
        val hi = minOf(maxMeasure, window.last)
        if (hi < lo) return emptyList()

        val rests = mutableListOf<ComputedVoiceEvent>()
        for ((_, staff) in score.staffTracks) {
            val firstVoice = staff.voiceTracks.firstOrNull() ?: continue
            val occupied = staff.voiceTracks
                .flatMap { it.events.range(TimeCode.ofMeasure(lo), TimeCode.ofMeasure(hi + 1)) }
                .filter { it.onset.measure in lo..hi }
                .mapTo(mutableSetOf()) { it.onset.measure }
            for (measure in lo..hi) {
                if (measure in occupied) continue
                rests += buildMeasureRest(firstVoice, measure)
            }
        }
        return rests
    }

    /** Synthesize the implicit whole-measure rest placed on [firstVoice] for an empty [measure]. */
    private fun buildMeasureRest(firstVoice: RuntimeVoiceTrack, measure: Int): ComputedVoiceEvent {
        val onset = TimeCode.of(measure, Fraction.ZERO)
        return ComputedVoiceEvent(
            id = EventId("rest-${firstVoice.id.value}-m$measure"),
            onset = onset,
            duration = Duration.WHOLE,
            rendering = null,
            pitchData = emptyList(),
            measurePosition = MeasurePositionComputer.compute(
                onset = onset,
                measures = score.measures,
                defaultTimeSignature = score.defaultTimeSignature
            ),
            isRest = true,
            beamInfo = null,
            originVoiceTrackId = firstVoice.id
        )
    }

    /**
     * Resolve per-event slur start/end counts across all voices into
     * concrete [ComputedSlur] pairs.
     */
    fun computeSlurs(): List<ComputedSlur> {
        val result = mutableListOf<ComputedSlur>()
        for ((_, staff) in score.staffTracks) {
            for (voiceTrack in staff.voiceTracks) {
                result.addAll(SlurResolver.computeForVoiceTrack(voiceTrack))
            }
        }
        return result
    }

    /**
     * Compute all notation events (barlines, clefs, key signatures, time signatures).
     *
     * @param barlineConnectivity Per-system connected staff ranges to stamp onto every
     *   barline. Empty means each barline falls back to per-staff rendering.
     */
    fun computeNotationEvents(
        barlineConnectivity: List<StaffIndexRange> = emptyList()
    ): NotationEvents {
        val barlines = computeBarlines(barlineConnectivity)
        val clefs = computeClefs(barlines)
        val keySignatures = computeKeySignatures(barlines)
        val timeSignatures = computeTimeSignatures(barlines)

        return NotationEvents(
            barlines = barlines,
            clefs = clefs,
            keySignatures = keySignatures,
            timeSignatures = timeSignatures
        )
    }

    /**
     * Compute all events in a voice track (full pass).
     */
    private fun computeVoiceTrack(
        staff: RuntimeStaffTrack,
        voiceTrack: RuntimeVoiceTrack
    ): List<ComputedVoiceEvent> =
        // The full pass simply hands the *entire* event list to the shared core, so the
        // windowed path (computeVoiceTrackRange) and this one are guaranteed identical.
        computeVoiceTrackEvents(staff, voiceTrack, voiceTrack.events.toList())

    /**
     * Compute the [ComputedVoiceEvent]s for the voice events of [voiceTrack] whose onset
     * falls in the measure window [windowMeasures], for incremental recompute.
     *
     * The materialised "compute set" is the window's whole measures, **expanded** so that no
     * beam group or tuplet span is cut by the window boundary (see [collectComputeSet]) — this
     * keeps accidentals (measure-local), beaming and tuplet resolution correct. Tie targets are
     * resolved against the *full* [voiceTrack] events, so a tie may still point outside the window.
     *
     * Every event in the (possibly expanded) compute set is returned; callers merge these into the
     * previous result by [EventId]. Returns an empty list when the window contains no events.
     */
    fun computeVoiceTrackRange(
        staff: RuntimeStaffTrack,
        voiceTrack: RuntimeVoiceTrack,
        windowMeasures: IntRange
    ): List<ComputedVoiceEvent> =
        computeVoiceTrackEvents(staff, voiceTrack, collectComputeSet(voiceTrack.events, windowMeasures))

    /**
     * Shared core of the full and windowed voice-track passes: given the exact set of events to
     * compute ([computeSet]), resolve beaming + tuplets over that set and map each event. Tie
     * resolution always uses the full [RuntimeVoiceTrack.events] list (passed through
     * [computeVoiceEvent]) so cross-window tie targets resolve correctly.
     */
    private fun computeVoiceTrackEvents(
        staff: RuntimeStaffTrack,
        voiceTrack: RuntimeVoiceTrack,
        computeSet: List<RuntimeVoiceEvent>
    ): List<ComputedVoiceEvent> {
        if (computeSet.isEmpty()) return emptyList()

        // Group events by measure for accidental calculation. The compute set always contains
        // whole measures, so each measure's accidental context is complete.
        val eventsByMeasure = computeSet.groupBy { it.onset.measure }

        // Compute beaming for the compute set. User-defined beaming first, then automatic beaming.
        val beamInfoMap = BeamGroupComputer.computeBeamingForTrack(
            events = computeSet,
            measures = score.measures,
            defaultTimeSignature = score.defaultTimeSignature
        )

        // Resolve tuplet spans to concrete end-event references.
        val tupletInfoMap = TupletComputer.computeForTrack(
            events = computeSet,
        )
        val fermataByEventId = score.globalTrack.events
            .filterIsInstance<StorageFermata>()
            .mapNotNull { fermata ->
                voiceTrack.events.toList().lastOrNull { !it.isGrace && it.onset < fermata.onset }
                    ?.id
                    ?.let { it to ComputedFermata(fermata.id, fermata.onset, fermata.extension, fermata.shape) }
            }
            .toMap()

        return computeSet.map { event ->
            computeVoiceEvent(
                event,
                staff,
                voiceTrack.events,
                eventsByMeasure,
                beamInfoMap,
                tupletInfoMap,
                fermataByEventId[event.id],
            )
        }
    }

    /**
     * Materialise the events to recompute for a measure window, expanded so the window boundary
     * never cuts a beam group or tuplet span (decision Q2: manual beams may cross barlines).
     *
     * Expansion stabilises by repeatedly widening `[lo, hi]` while:
     *  - a window event's tuplet span ends past `hi` (forward extension);
     *  - a tuplet starting just before `lo` reaches into the window (bounded backward scan);
     *  - the manual-beamed event immediately before/after the window continues a group into it.
     *
     * The widening is bounded by actual group sizes, so it stays O(window + groups), not O(track).
     */
    private fun collectComputeSet(
        events: TimeIndexedList<RuntimeVoiceEvent>,
        windowMeasures: IntRange
    ): List<RuntimeVoiceEvent> {
        if (events.isEmpty() || windowMeasures.isEmpty()) return emptyList()

        var lo = windowMeasures.first
        var hi = windowMeasures.last

        // Number of measures to scan backward for a tuplet that started before the window.
        // Tuplets spanning more than this many measures do not occur in practice.
        val backwardTupletScan = 4

        while (true) {
            var newLo = lo
            var newHi = hi
            val windowEvents = materializeMeasures(events, lo, hi)

            // Forward tuplet extension: a tuplet starting in-window may end past `hi`.
            for (e in windowEvents) {
                val span = e.tupletSpan ?: continue
                if (span.endTimeCode > TimeCode.ofMeasure(hi + 1)) {
                    newHi = maxOf(newHi, span.endTimeCode.measure)
                }
            }

            // Backward tuplet reach: a tuplet starting just before the window may cover its start.
            val backScanStart = TimeCode.ofMeasure(maxOf(1, lo - backwardTupletScan))
            for (e in events.range(backScanStart, TimeCode.ofMeasure(lo))) {
                if (e.onset.measure >= lo) continue
                val span = e.tupletSpan ?: continue
                if (span.endTimeCode > TimeCode.ofMeasure(lo)) newLo = minOf(newLo, e.onset.measure)
            }

            // Manual-beam continuation across the forward boundary.
            val lastInWindow = events.lastBefore(TimeCode.ofMeasure(hi + 1))
            if (lastInWindow != null && lastInWindow.onset.measure in lo..hi && continuesBeamRight(lastInWindow)) {
                val next = events.firstAtOrAfter(TimeCode.ofMeasure(hi + 1))
                if (next != null && continuesBeamLeft(next)) newHi = maxOf(newHi, next.onset.measure)
            }

            // Manual-beam continuation across the backward boundary.
            val firstInWindow = events.firstAtOrAfter(TimeCode.ofMeasure(lo))
            if (firstInWindow != null && firstInWindow.onset.measure in lo..hi && continuesBeamLeft(firstInWindow)) {
                val prev = events.lastBefore(TimeCode.ofMeasure(lo))
                if (prev != null && continuesBeamRight(prev)) newLo = minOf(newLo, prev.onset.measure)
            }

            if (newLo == lo && newHi == hi) return windowEvents
            lo = newLo
            hi = newHi
        }
    }

    /** Events whose onset measure is in `[lo, hi]`, in onset order. */
    private fun materializeMeasures(
        events: TimeIndexedList<RuntimeVoiceEvent>,
        lo: Int,
        hi: Int
    ): List<RuntimeVoiceEvent> {
        if (hi < lo) return emptyList()
        // A grace group anchored at beat zero sorts before TimeCode.ofMeasure(lo), because its
        // third component is negative. Start one measure earlier, then retain by onset measure.
        return events.range(TimeCode.ofMeasure((lo - 1).coerceAtLeast(0)), TimeCode.ofMeasure(hi + 1))
            .filter { it.onset.measure in lo..hi }
    }

    /** True if [event] carries manual beaming that does not end at it (a group continues to its right). */
    private fun continuesBeamRight(event: RuntimeVoiceEvent): Boolean {
        val beaming = event.rendering?.beaming ?: return false
        return beaming.isBeamed && beaming.beamRight && !beaming.isBeamEnd
    }

    /** True if [event] carries manual beaming that does not start at it (a group continues to its left). */
    private fun continuesBeamLeft(event: RuntimeVoiceEvent): Boolean {
        val beaming = event.rendering?.beaming ?: return false
        return beaming.isBeamed && beaming.beamLeft && !beaming.isBeamStart
    }

    /**
     * Compute a single voice event.
     */
    private fun computeVoiceEvent(
        event: RuntimeVoiceEvent,
        staff: RuntimeStaffTrack,
        trackEvents: TimeIndexedList<RuntimeVoiceEvent>,
        eventsByMeasure: Map<Int, List<RuntimeVoiceEvent>>,
        beamInfoMap: Map<EventId, BeamInfo>,
        tupletInfoMap: Map<EventId, ComputedTupletInfo>,
        fermata: ComputedFermata?,
    ): ComputedVoiceEvent {
        // === Derived fields ===

        val measurePosition = MeasurePositionComputer.compute(
            onset = event.onset,
            measures = score.measures,
            defaultTimeSignature = score.defaultTimeSignature
        )

        val transpositionSemitones = staff.transposition?.interval?.semitones ?: 0
        val keySignature = score.getKeySignatureAt(measurePosition.measure)

        // Cross-staff: vertical position uses the *target* staff's clef. Transposition,
        // key and accidental context stay on the home staff (musical, not geometric).
        val renderStaff = resolveRenderStaff(staff, event.rendering?.crossStaffOffset)

        // Resolve the clef in effect at this event's onset (mid-score clef changes).
        val clef = staffPitchTimelines.getValue(renderStaff.id).at(event.onset).clef

        // Resolve octave-shift display offset: 8va moves the written pitch down an octave
        // (-7 diatonic steps) so the sounding pitch renders closer to the staff.
        // 8vb moves it up (+7). Home staff owns the attachment brackets.
        val octaveShiftOffset = StaffPitchContext.octaveShiftDiatonicOffset(event.onset, staff)

        // Get events in same measure for accidental calculation
        val eventsInMeasure = eventsByMeasure[measurePosition.measure] ?: emptyList()

        // Compute per-pitch ties. Ties must be explicit (set via the tie tool / imported from
        // `<tied>`); the timing-based heuristic is disabled so that merely placing two same-pitch
        // notes back-to-back does NOT silently tie them together.
        val perPitchTies = TieTargetComputer.computePerPitchTies(event, trackEvents, useHeuristicFallback = false)
        val tiesByPitchIndex = perPitchTies.associateBy { it.pitchIndex }

        // Simultaneous pitches on the same diatonic staff position need explicit signs when their
        // spellings differ. For example, C-natural and C-sharp in C major must show BOTH signs; using
        // the ordinary measure state alone would suppress the natural and make the split heads
        // ambiguous. This is musical derivation, so it belongs in Computed rather than Renderer.
        val conflictingUnisonSteps = event.pitches
            .groupBy { it.diatonicSteps }
            .filterValues { pitches -> pitches.map { it.accidental }.distinct().size > 1 }
            .keys

        // Compute data for each pitch in the event
        val pitchData = event.pitches.mapIndexed { pitchIndex, pitch ->
            val tieTarget = tiesByPitchIndex[pitchIndex]?.tieTarget
            computePitchData(
                pitch,
                clef,
                octaveShiftOffset,
                keySignature,
                transpositionSemitones,
                event,
                eventsInMeasure,
                tieTarget,
                forceAccidental = pitch.diatonicSteps in conflictingUnisonSteps,
            )
        }

        // === Context-dependent fields ===

        // Get beam info from the pre-computed map (replaces the old per-event calculation)
        val beamInfo = beamInfoMap[event.id]

        return ComputedVoiceEvent.from(
            runtime = event,
            pitchData = pitchData,
            measurePosition = measurePosition,
            beamInfo = beamInfo,
            tupletInfo = tupletInfoMap[event.id],
            fermata = fermata,
        )
    }

    /**
     * Compute data for a single pitch within a voice event.
     */
    private fun computePitchData(
        pitch: Pitch,
        clef: Clef,
        octaveShiftDiatonicOffset: Int,
        keySignature: KeySignature,
        transpositionSemitones: Int,
        currentEvent: RuntimeVoiceEvent,
        eventsInMeasure: List<RuntimeVoiceEvent>,
        tieTarget: ComputedTieTarget? = null,
        forceAccidental: Boolean = false,
    ): ComputedPitchData {
        val midiPitch = MidiPitchComputer.compute(pitch, transpositionSemitones)
        val staffPosition = StaffPitchContext.staffPosition(pitch, clef, octaveShiftDiatonicOffset)

        // Get all pitches that appeared before this event in the measure
        val previousPitchesInMeasure = eventsInMeasure
            .filter { it.onset < currentEvent.onset }
            .flatMap { it.pitches }

        val effectiveAccidental = EffectiveAccidentalComputer.computeForPitch(
            pitch = pitch,
            keySignature = keySignature,
            previousPitchesInMeasure = previousPitchesInMeasure,
            displayOverride = if (forceAccidental) AccidentalDisplay.FORCE else AccidentalDisplay.AUTO,
        )

        // Staff lines are at positions -4, -2, 0, 2, 4
        // Ledger lines are needed at positions <= -6 or >= 6
        val needsLedgerLine = staffPosition < -5 || staffPosition > 5

        return ComputedPitchData(
            pitch = pitch,
            midiPitch = midiPitch,
            staffPosition = staffPosition,
            effectiveAccidental = effectiveAccidental,
            needsLedgerLine = needsLedgerLine,
            tieTarget = tieTarget
        )
    }

    /**
     * Compute barlines at measure boundaries.
     *
     * Barlines are positioned at the END of each measure, with TimeCode [measure, measureDuration].
     * This ensures barlines are strictly between events:
     * - Barline time > all events in previous measure (events end before the barline)
     * - Barline time < all events in next measure (which start at [nextMeasure, 0])
     */
    private fun computeBarlines(
        connectivity: List<StaffIndexRange> = emptyList()
    ): List<ComputedBarline> {
        val barlines = mutableListOf<ComputedBarline>()

        // Get time signature and calculate measure duration (as a beat count)
        val timeSignature = score.defaultTimeSignature
        val measureDuration = Fraction(timeSignature.numerator, timeSignature.denominator)

        // Find the last event time to determine number of measures
        var lastEventTime = TimeCode.ZERO
        for ((_, staff) in score.staffTracks) {
            for (voice in staff.voiceTracks) {
                for (event in voice.events) {
                    val endTime = event.onset + event.duration.toFraction()
                    if (endTime > lastEventTime) {
                        lastEventTime = endTime
                    }
                }
            }
        }

        // If no events, create just one measure
        if (lastEventTime == TimeCode.ZERO) {
            lastEventTime = TimeCode(listOf(Fraction.ONE, measureDuration))
        }

        // The score may declare more (empty) measures than the events span — an
        // empty new score, or trailing rests-only measures. Render at least up to
        // the highest declared measure number so the declared length is honoured.
        val declaredMaxMeasure = score.measures.maxOfOrNull { it.key } ?: 0

        // Add initial barline at start. A forward repeat belongs to measure 1's
        // left boundary, so it replaces the ordinary opening line.
        val firstMeasure = score.measures.get(1)
        barlines.add(ComputedBarline(
            time = TimeCode.ZERO,
            type = if (firstMeasure?.repeatStart == true) {
                BarlineType.REPEAT_LEFT
            } else {
                score.initialBarlineType
            },
            measureNumber = 0,
            connectedStaffRanges = connectivity
        ))

        // Generate measure-ending barlines
        var measureNum = 1
        while (true) {
            // Barline at the END of measure N: TimeCode([N, measureDuration + epsilon])
            // We use (2*numerator + 1) / (2*denominator) to get a value slightly larger than measureDuration
            // This ensures the barline is strictly after any event ending at measureDuration
            // and strictly before any event in measure N+1 (which start at [N+1, 0])
            val barlineBeat = Fraction(
                measureDuration.numerator * 2 + 1,
                measureDuration.denominator * 2
            )
            val barlineTime = TimeCode.of(measureNum, barlineBeat)

            // Only end the score once we've both passed the last event AND reached
            // the last declared measure.
            val isLastBoundary = barlineTime >= lastEventTime && measureNum >= declaredMaxMeasure
            val measure = score.measures.get(measureNum)
            val nextMeasure = score.measures.get(measureNum + 1)
            val repeatEnd = measure?.repeatEnd == true
            val repeatStart = nextMeasure?.repeatStart == true
            val explicitType = measure?.endBarlineType
            val barlineType = when {
                repeatEnd && repeatStart -> BarlineType.REPEAT_BOTH
                repeatEnd -> BarlineType.REPEAT_RIGHT
                repeatStart -> BarlineType.REPEAT_LEFT
                explicitType != null -> explicitType
                isLastBoundary -> BarlineType.FINAL
                else -> BarlineType.SINGLE
            }

            barlines.add(ComputedBarline(
                time = barlineTime,
                type = barlineType,
                measureNumber = measureNum,
                connectedStaffRanges = connectivity
            ))

            // Stop after final barline
            if (isLastBoundary) {
                break
            }

            measureNum++
        }

        return barlines
    }

    /**
     * Compute clefs at the start of each staff and at mid-score clef changes.
     *
     * The initial clef is placed at [TimeCode.ZERO]. Changes preserve their
     * storage onset so they can appear anywhere in a measure.
     */
    private fun computeClefs(barlines: List<ComputedBarline>): List<ComputedClef> {
        val clefs = mutableListOf<ComputedClef>()

        for ((trackId, staff) in score.staffTracks) {
            clefs.add(ComputedClef(
                time = TimeCode.ZERO,
                staffTrackId = trackId,
                clef = staff.clef,
                isInitial = true
            ))

            for (change in staff.clefChanges.sortedBy { it.onset }) {
                clefs.add(ComputedClef(
                    time = change.onset,
                    staffTrackId = trackId,
                    clef = change.clef,
                    isInitial = false
                ))
            }
        }

        return clefs
    }

    /**
     * Compute key signatures at the start of each staff and at key changes.
     *
     * Walks through measures in order, emitting a [ComputedKeySignature] whenever
     * the effective key differs from the previous measure. For mid-score changes,
     * cancellation naturals are computed so the renderer knows which natural signs
     * to draw before the new accidentals.
     *
     * Key change events are placed at the preceding barline time so the layout
     * engine groups them with the barline (just like initial key signatures share
     * TimeCode.ZERO with the initial barline). This matches standard engraving
     * practice where key changes appear right after the barline.
     */
    private fun computeKeySignatures(barlines: List<ComputedBarline>): List<ComputedKeySignature> {
        val result = mutableListOf<ComputedKeySignature>()

        // Map measureNumber → barline time for the barline at the END of that measure.
        // A key change at the start of measure N uses barline[N-1]'s time.
        val barlineTimeByMeasure = barlines.associate { it.measureNumber to it.time }

        val sortedMeasures = score.measures.sortedBy { it.key }.map { it.value }

        for ((trackId, staff) in score.staffTracks) {
            result.add(ComputedKeySignature(
                time = TimeCode.ZERO,
                staffTrackId = trackId,
                keySignature = staff.keySignature,
                isInitial = true
            ))

            var prevKey = staff.keySignature
            for (measure in sortedMeasures) {
                val currentKey = measure.keySignature
                if (currentKey != prevKey) {
                    val changeTime = barlineTimeByMeasure[measure.number - 1]
                        ?: TimeCode.of(measure.number, Fraction.ZERO)
                    result.add(ComputedKeySignature(
                        time = changeTime,
                        staffTrackId = trackId,
                        keySignature = currentKey,
                        isInitial = false,
                        cancellationNaturals = computeCancellationNaturals(prevKey, currentKey)
                    ))
                    prevKey = currentKey
                }
            }
        }

        return result
    }

    /**
     * Determine which natural signs are needed when transitioning from [oldKey] to [newKey].
     *
     * Standard engraving rules:
     * - Same type, adding accidentals (e.g. G→D): no naturals needed
     * - Same type, removing accidentals (e.g. D→G): naturals for removed ones
     * - Different type (sharps↔flats): naturals for ALL old accidentals
     * - To no accidentals (e.g. G→C): naturals for all old accidentals
     */
    private fun computeCancellationNaturals(
        oldKey: KeySignature,
        newKey: KeySignature
    ): List<CancellationNatural> {
        val oldFifths = oldKey.fifths
        val newFifths = newKey.fifths
        if (oldFifths == 0) return emptyList()

        val oldAccidentals = oldKey.accidentals()
        val fromSharp = oldFifths > 0
        val sameDirection = (oldFifths > 0 && newFifths > 0) || (oldFifths < 0 && newFifths < 0)

        if (sameDirection && kotlin.math.abs(newFifths) >= kotlin.math.abs(oldFifths)) {
            return emptyList()
        }

        if (!sameDirection) {
            // Switching accidental type (sharp↔flat) or going to 0: cancel ALL old accidentals
            return oldAccidentals.map { CancellationNatural(noteName = it.first, fromSharpKey = fromSharp) }
        }

        // Same direction, fewer accidentals: cancel the removed ones
        val newAccidentalNames = newKey.accidentals().map { it.first }.toSet()
        return oldAccidentals
            .filter { it.first !in newAccidentalNames }
            .map { CancellationNatural(noteName = it.first, fromSharpKey = fromSharp) }
    }

    /**
     * Compute time signatures at score start and at mid-score time signature changes.
     *
     * Walks measures in order, emitting a [ComputedTimeSignature] for each staff
     * whenever the effective time signature differs from the previous measure. Change
     * events are placed at the preceding barline time (same convention as key sigs).
     */
    private fun computeTimeSignatures(barlines: List<ComputedBarline>): List<ComputedTimeSignature> {
        if (!score.showTimeSignatures) return emptyList()
        val timeSignatures = mutableListOf<ComputedTimeSignature>()
        val barlineTimeByMeasure = barlines.associate { it.measureNumber to it.time }
        val sortedMeasures = score.measures.sortedBy { it.key }.map { it.value }

        for ((trackId, _) in score.staffTracks) {
            timeSignatures.add(ComputedTimeSignature(
                time = TimeCode.ZERO,
                staffTrackId = trackId,
                timeSignature = score.defaultTimeSignature,
                isInitial = true
            ))

            var prevTimeSig = score.defaultTimeSignature
            for (measure in sortedMeasures) {
                val currentTimeSig = measure.timeSignature
                if (currentTimeSig != prevTimeSig) {
                    val changeTime = barlineTimeByMeasure[measure.number - 1]
                        ?: TimeCode.of(measure.number, Fraction.ZERO)
                    timeSignatures.add(ComputedTimeSignature(
                        time = changeTime,
                        staffTrackId = trackId,
                        timeSignature = currentTimeSig,
                        isInitial = false
                    ))
                    prevTimeSig = currentTimeSig
                }
            }
        }

        return timeSignatures
    }
}
