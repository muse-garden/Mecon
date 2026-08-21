package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.renderer.elements.*
import com.mecon.renderer.enums.toClefType
import com.mecon.core.engine.StaffPitchContext
import com.mecon.renderer.layout.stem.StemDirectionResolver
import com.mecon.renderer.layout.stem.StemResolutionInput
import com.mecon.renderer.layout.stem.VoiceContext
import com.mecon.renderer.smufl.BravuraFont

/**
 * Collects all events from a computed score and resolves stem directions.
 *
 * This class handles:
 * - Collecting note events with voice context
 * - Resolving stem directions via [StemDirectionResolver]
 * - Collecting barlines, clefs, key signatures, and time signatures
 */
context(BravuraFont)
internal class EventCollector(
    private val stemDirectionResolver: StemDirectionResolver,
    private val config: RenderLayoutConfig
) {
    /**
     * Data needed for note creation before stem resolution.
     */
    private data class NoteData(
        val event: ComputedVoiceEvent,
        val staffInfo: StaffInfo,
        /** Home staff track of the note's voice; used to group cross-staff notes for spacing. */
        val homeTrackId: TrackId,
        /** Owning voice track; keeps same-staff voices distinct during spanner layout. */
        val voiceTrackId: TrackId,
        val voiceNumber: Int,
        val voiceContext: VoiceContext
    )

    /**
     * Collect all events from the computed score, resolving stem directions before creation.
     *
     * ## Incremental window-only collection ([windowOnly])
     *
     * Building a [NoteElement] runs [com.mecon.renderer.layout.NoteBodyElementBuilder] glyph-metric work
     * per note, which dominates collection on long scores. When [windowOnly] is supplied (the incremental
     * layout path), **only** the events and notation elements whose measure lies in the window are built
     * and returned; everything outside is omitted — the caller reconstructs it by reusing the cached slot
     * map verbatim (see [UnifiedLayoutComputer]'s base-map splice). This removes collection's whole-score
     * glyph work and the reassembly of the out-of-window notes into the output.
     *
     * Windowing uses [ComputedScore.eventsInMeasureRange], whose B+ range deliberately starts one measure
     * early and then filters by onset measure so a grace before the first downbeat is retained. Multi-voice
     * detection stays exact — a window measure's voices are all in-window — and clef
     * state for key-signature positioning is seeded from the clef changes *before* the window (below), so
     * a key signature inside the window is spelled against the correct clef. Correctness of the omitted
     * remainder is the caller's splice invariant: an edit confined to the window cannot change an
     * out-of-window slot, so the cached slot equals a fresh build.
     *
     * @param windowOnly Measures to collect; everything else is omitted for the caller to reuse from
     *   cache. Null ⇒ full collect (every event/element built).
     * @return List of all layout events (window-only when [windowOnly] is set)
     */
    fun collectAllEventsWithResolvedStems(
        computed: ComputedScore,
        runtime: RuntimeScore,
        voiceToStaff: Map<TrackId, TrackId>,
        staffTracks: Map<TrackId, StaffInfo>,
        windowOnly: IntRange? = null
    ): List<LayoutElement> {
        val events = mutableListOf<LayoutElement>()
        fun inWindow(measure: Int) = windowOnly == null || measure in windowOnly

        // Build voice number mapping: voiceTrackId -> voice number within staff
        val voiceNumberMap = buildVoiceNumberMap(runtime, voiceToStaff)

        // Index staves by display order so a cross-staff offset can resolve a render target.
        val staffInfoByIndex = staffTracks.values.associateBy { it.staffIndex }
        val maxStaffIndex = staffInfoByIndex.keys.maxOrNull() ?: 0

        // The staff a note actually renders on, honoring RenderingProps.crossStaffOffset.
        fun effectiveStaff(homeStaff: StaffInfo, event: ComputedVoiceEvent): StaffInfo {
            val offset = event.rendering?.crossStaffOffset ?: 0
            if (offset == 0) return homeStaff
            val target = (homeStaff.staffIndex + offset).coerceIn(0, maxStaffIndex)
            return staffInfoByIndex[target] ?: homeStaff
        }

        // Build multi-voice tracking: (staffTrackId, measureNumber) -> set of voice track IDs.
        // A borrowed note is counted on its *target* staff, so the target staff is seen as
        // multi-voice during the overlap and its native notes pick voice-based stem directions.
        val measureVoices = mutableMapOf<Pair<TrackId, Int>, MutableSet<TrackId>>()

        // Resolve an event's voice track: synthesized events (e.g. implicit rests) carry it directly;
        // normal events are looked up via a one-time eventId → voiceTrackId index. Building the index
        // once is O(N); the previous per-event scan of every voice track made collection O(N²) (it ran
        // for every event in both passes below — the dominant cost of collection on long scores). The
        // index (and both passes) are restricted to the window when [windowOnly] is set — by onset
        // measure, iterating the full tree (grace-safe), so it holds every id the window queries.
        val voiceTrackByEventId: Map<EventId, TrackId> = buildMap {
            for ((voiceTrackId, voiceTrack) in runtime.voiceTracks) {
                val candidates = if (windowOnly == null) voiceTrack.events else voiceTrack.eventsInRange(
                    TimeCode.ofMeasure((windowOnly.first - 1).coerceAtLeast(0)),
                    TimeCode.ofMeasure(windowOnly.last + 1),
                )
                for (e in candidates) if (inWindow(e.onset.measure)) put(e.id, voiceTrackId)
            }
        }
        fun voiceTrackFor(event: ComputedVoiceEvent): TrackId? =
            event.originVoiceTrackId ?: voiceTrackByEventId[event.id]

        // The persistent event store is already onset-ordered. Incremental collection reads only the
        // affected measure window through its grace-safe B+ range instead of sorting/scanning the score.
        val sortedEvents = if (windowOnly != null) computed.eventsInMeasureRange(windowOnly)
            else computed.allEventsSorted()

        // First pass: identify which measures have multiple voices (window measures only when windowed —
        // an out-of-window measure's stems are not re-resolved, so its voice set is not needed).
        for (event in sortedEvents) {
            if (!inWindow(event.measurePosition.measure)) continue
            val voiceTrackId = voiceTrackFor(event)
                ?: continue
            val staffTrackId = voiceToStaff[voiceTrackId] ?: continue
            val homeStaff = staffTracks[staffTrackId] ?: continue
            val renderStaff = effectiveStaff(homeStaff, event)
            val measureKey = renderStaff.trackId to event.measurePosition.measure
            measureVoices.getOrPut(measureKey) { mutableSetOf() }.add(voiceTrackId)
        }

        val pendingNotes = mutableListOf<NoteData>()

        // Second pass: collect note data and build stem resolution inputs (window notes only when windowed)
        for (event in sortedEvents) {
            if (!inWindow(event.measurePosition.measure)) continue
            val voiceTrackId = voiceTrackFor(event)
                ?: continue
            val staffTrackId = voiceToStaff[voiceTrackId] ?: continue
            val homeStaff = staffTracks[staffTrackId] ?: continue
            val staffInfo = effectiveStaff(homeStaff, event)

            val voiceNumber = voiceNumberMap[voiceTrackId] ?: 1
            val measureKey = staffInfo.trackId to event.measurePosition.measure
            val hasMultipleVoices = (measureVoices[measureKey]?.size ?: 1) > 1

            val context = VoiceContext(
                voiceNumber = voiceNumber,
                measureNumber = event.measurePosition.measure,
                hasMultipleVoices = hasMultipleVoices,
                staffIndex = staffInfo.staffIndex,
                crossStaffOffset = event.rendering?.crossStaffOffset ?: 0
            )

            pendingNotes.add(NoteData(event, staffInfo, homeStaff.trackId, voiceTrackId, voiceNumber, context))
        }

        // Resolve stem directions
        val resolutionInputs = pendingNotes.map { data ->
            StemResolutionInput(
                eventId = data.event.id,
                pitchData = data.event.pitchData,
                beamInfo = data.event.beamInfo,
                userStemDirection = data.event.rendering?.stemDirection,
                voiceContext = data.voiceContext
            )
        }

        val resolvedDirections = stemDirectionResolver.resolve(resolutionInputs)

        // Create NoteElements with resolved directions
        for (data in pendingNotes) {
            val direction = resolvedDirections[data.event.id]
            events.add(
                NoteElement.create(
                    event = data.event,
                    staffIndex = data.staffInfo.staffIndex,
                    trackId = data.staffInfo.trackId,
                    voiceNumber = data.voiceNumber,
                    config = config,
                    resolvedStemDirection = direction,
                    voiceTrackId = data.voiceTrackId,
                    voiceGroupTrackId = data.homeTrackId
                )
            )
        }
        // Notation elements (barlines / clefs / key / time signatures). Each is keyed to the measure it
        // sits in via `time.measure`; when windowed, only those in-window are emitted (the rest are reused
        // from the cached slot map). Clef *state* is still advanced over every clef (below) so an
        // in-window key signature is spelled against the same clef the full collect would use.
        val barlineTimes = computed.allBarlinesSorted().map { it.time }.toSet()

        // Collect barlines from computed score - use factory method
        for (barline in computed.allBarlinesSorted()) {
            if (!inWindow(barline.time.measure)) continue
            events.add(
                BarlineElement.create(
                    time = barline.time,
                    type = barline.type,
                    measureNumber = barline.measureNumber
                )
            )
        }

        // One immutable time sequence per staff. Key signatures must query the clef in force at
        // their own onset; advancing a mutable "current clef" over a separate pass incorrectly
        // positioned every earlier key signature with the score's final clef.
        val staffPitchTimelines = staffTracks.mapValues { (trackId, info) ->
            StaffPitchContext.timeline(
                initialClef = info.clef,
                changes = computed.clefs
                    .filter { it.staffTrackId == trackId && !it.isInitial }
                    .map { it.time to it.clef },
            )
        }

        // Collect clefs from computed score - use factory method.
        for (clef in computed.allClefsSorted()) {
            val staffInfo = staffTracks[clef.staffTrackId] ?: continue
            val clefType = clef.clef.toClefType()

            if (!inWindow(clef.time.measure)) continue
            val isInlineClefChange = !clef.isInitial &&
                clef.time !in barlineTimes &&
                (clef.time.beat ?: Fraction.ZERO) != Fraction.ZERO
            val scale = if (isInlineClefChange) {
                RenderConstants.INLINE_CLEF_CHANGE_SCALE
            } else {
                1f
            }

            events.add(
                ClefElement.create(
                    time = clef.time,
                    staffIndex = staffInfo.staffIndex,
                    clef = clef.clef,
                    isInitial = clef.isInitial,
                    staffTrackId = clef.staffTrackId,
                    scale = scale
                )
            )
        }

        // Collect key signatures from computed score - use factory method
        for (keySignature in computed.allKeySignaturesSorted()) {
            if (!inWindow(keySignature.time.measure)) continue
            val staffInfo = staffTracks[keySignature.staffTrackId] ?: continue
            val clef = staffPitchTimelines[keySignature.staffTrackId]
                ?.at(keySignature.time)
                ?.clef
                ?: staffInfo.clef

            events.add(
                KeySignatureElement.create(
                    time = keySignature.time,
                    staffIndex = staffInfo.staffIndex,
                    keySignature = keySignature.keySignature,
                    isInitial = keySignature.isInitial,
                    clef = clef,
                    staffTrackId = keySignature.staffTrackId,
                    cancellationNaturals = keySignature.cancellationNaturals
                )
            )
        }

        // Collect time signatures from computed score - use factory method
        for (timeSignature in computed.allTimeSignaturesSorted()) {
            if (!inWindow(timeSignature.time.measure)) continue
            val staffInfo = staffTracks[timeSignature.staffTrackId] ?: continue
            events.add(
                TimeSignatureElement.create(
                    time = timeSignature.time,
                    staffIndex = staffInfo.staffIndex,
                    timeSignature = timeSignature.timeSignature,
                    isInitial = timeSignature.isInitial
                )
            )
        }

        return events
    }

    /**
     * Build a mapping of voice track IDs to voice numbers within their staff.
     *
     * Voice numbers are 1-based within each staff. The first voice encountered
     * in each staff gets number 1, the second gets number 2, etc.
     */
    fun buildVoiceNumberMap(
        runtime: RuntimeScore,
        voiceToStaff: Map<TrackId, TrackId>
    ): Map<TrackId, Int> {
        val result = mutableMapOf<TrackId, Int>()
        val staffVoiceCounters = mutableMapOf<TrackId, Int>()

        for (staff in runtime.staffTracks.values) {
            for (voice in staff.voiceTracks) {
                val staffTrackId = voiceToStaff[voice.id] ?: staff.id
                val voiceNumber = (staffVoiceCounters[staffTrackId] ?: 0) + 1
                staffVoiceCounters[staffTrackId] = voiceNumber
                result[voice.id] = voiceNumber
            }
        }

        return result
    }
}
