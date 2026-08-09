package com.mecon.api.computed

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.events.StoragePluginEvent

/**
 * Computed score - contains runtime score plus all computed fields.
 *
 * This is a simplified implementation without incremental updates.
 * When the runtime score changes, create a new ComputedScore via fromRuntime().
 *
 * Note: In the refactored model, computed events are based on VoiceEvents
 * (the rendered notes) rather than PitchEvents (raw pitch data).
 */
data class ComputedScore(
    val runtime: RuntimeScore,
    val computedEvents: ComputedEventStore,
    /** Barlines at measure boundaries and start/end of score */
    val barlines: List<ComputedBarline> = emptyList(),
    val voltaEndings: List<ComputedVoltaEnding> = emptyList(),
    val navigationMarks: List<ComputedNavigationMark> = emptyList(),
    /** Clefs at the start of staves and at clef changes */
    val clefs: List<ComputedClef> = emptyList(),
    /** Key signatures at the start of staves and at key changes */
    val keySignatures: List<ComputedKeySignature> = emptyList(),
    /** Time signatures at the start and at time signature changes */
    val timeSignatures: List<ComputedTimeSignature> = emptyList(),
    /** Resolved phrasing slurs (per-voice, with nesting depth). */
    val slurs: List<ComputedSlur> = emptyList(),
    /** Staff attachments (dynamics, hairpins) resolved to display-order staves. */
    val staffAttachments: List<ComputedStaffAttachment> = emptyList(),
    /** All tempo keyframes, including editor-only hidden points. */
    val tempoKeyframes: List<ComputedTempoKeyframe> = emptyList(),
    /** plugin tracks with computed data */
    val pluginTracks: Map<TrackId, com.mecon.api.computed.tracks.ComputedPluginTrack<out com.mecon.api.storage.events.StoragePluginEvent>> = emptyMap(),
    /**
     * Per-system staff header: brackets, instrument/group labels, and resolved
     * barline connectivity. For now there is exactly one system, so this is a
     * single value (a per-system list would be appropriate when multi-system
     * layout is implemented).
     */
    val staffHeader: ComputedStaffHeader = ComputedStaffHeader.EMPTY
) {
    /**
     * Get a computed event by ID.
     */
    fun getComputedEvent(eventId: EventId): ComputedVoiceEvent? =
        computedEvents[eventId]

    /**
     * Get a computed plugin track by ID.
     */
    fun getPluginTrack(id: TrackId): com.mecon.api.computed.tracks.ComputedPluginTrack<*>? = pluginTracks[id]

    /**
     * Update a computed plugin track.
     */
    fun updatePluginTrack(trackId: TrackId, updatedTrack: com.mecon.api.computed.tracks.ComputedPluginTrack<*>): ComputedScore =
        copy(pluginTracks = pluginTracks + (trackId to updatedTrack))

    /**
     * Get all events in a measure.
     */
    fun eventsInMeasure(measureNumber: Int): List<ComputedVoiceEvent> =
        computedEvents.values.filter { it.measurePosition.measure == measureNumber }

    /**
     * Events whose onset measure lies in [range], onset-ordered — an O(log N + window) B+ tree range
     * query over [computedEvents] instead of a whole-score scan. Used by the windowed render passes (the
     * paginated splice's spanner regeneration, live-geometry fold) so a per-note edit touches only its
     * window's events rather than every event in the score.
     *
     * The query end is `range.last + 1` (the store's [ComputedEventStore.range] is inclusive), so it may
     * include events on the next measure's downbeat; callers keep their own `onset.measure in range` guard
     * to stay exact. An empty [range] yields no events.
     */
    fun eventsInMeasureRange(range: IntRange): List<ComputedVoiceEvent> =
        if (range.isEmpty()) emptyList()
        else computedEvents.range(
            // A grace before beat 0 compares below TimeCode.ofMeasure(range.first), although its onset
            // still belongs to range.first. Start one measure earlier, then retain by onset measure; this
            // keeps the B+ query grace-safe without falling back to an all-events scan.
            TimeCode.ofMeasure((range.first - 1).coerceAtLeast(0)),
            TimeCode.ofMeasure(range.last + 1),
        ).filter { it.onset.measure in range }

    /**
     * Get all events sorted by onset.
     */
    fun allEventsSorted(): List<ComputedVoiceEvent> =
        computedEvents.values.sortedBy { it.onset }

    /**
     * Get all barlines sorted by time.
     */
    fun allBarlinesSorted(): List<ComputedBarline> =
        barlines.sortedBy { it.time }

    /**
     * Get barline at a specific time.
     */
    fun barlineAtTime(time: TimeCode): ComputedBarline? =
        barlines.find { it.time == time }

    /**
     * Get all clefs sorted by time.
     */
    fun allClefsSorted(): List<ComputedClef> =
        clefs.sortedBy { it.time }

    /**
     * Get all key signatures sorted by time.
     */
    fun allKeySignaturesSorted(): List<ComputedKeySignature> =
        keySignatures.sortedBy { it.time }

    /**
     * Get all time signatures sorted by time.
     */
    fun allTimeSignaturesSorted(): List<ComputedTimeSignature> =
        timeSignatures.sortedBy { it.time }

    /**
     * Get beam groups.
     */
    fun getBeamGroups(): Map<BeamGroupId, List<ComputedVoiceEvent>> =
        computedEvents.values
            .filter { it.beamInfo != null }
            .groupBy { it.beamInfo!!.groupId }

    /**
     * Represents a tie chain for a specific pitch across multiple events.
     */
    data class PitchTieChain(
        /** The MIDI pitch number being tied */
        val midiPitch: Int,
        /** Sequence of (event, pitchIndex) pairs in the chain */
        val entries: List<Pair<ComputedVoiceEvent, Int>>,
        /** True if the chain ends with a let-ring */
        val endsWithLetRing: Boolean
    )

    /**
     * Get tied chains tracking individual pitches through tie sequences.
     *
     * Supports partial chord ties (e.g., only top note of chord tied)
     * and let-ring ties.
     */
    fun getTiedChains(): List<PitchTieChain> {
        val chains = mutableListOf<PitchTieChain>()
        val visited = mutableSetOf<Pair<EventId, Int>>() // (eventId, pitchIndex)

        for (event in computedEvents.values) {
            for ((pitchIndex, pitchData) in event.pitchData.withIndex()) {
                val key = event.id to pitchIndex
                if (key in visited) continue
                if (pitchData.tieTarget == null) continue

                // Start of a pitch tie chain
                val entries = mutableListOf<Pair<ComputedVoiceEvent, Int>>()
                var currentEvent: ComputedVoiceEvent? = event
                var currentPitchIndex = pitchIndex
                var endsWithLetRing = false

                while (currentEvent != null) {
                    val currentKey = currentEvent.id to currentPitchIndex
                    if (currentKey in visited) break

                    visited.add(currentKey)
                    entries.add(currentEvent to currentPitchIndex)

                    val currentPitchData = currentEvent.pitchData.getOrNull(currentPitchIndex)
                    val tieTarget = currentPitchData?.tieTarget

                    when {
                        tieTarget == null -> break
                        tieTarget.isLetRing -> {
                            endsWithLetRing = true
                            break
                        }
                        else -> {
                            currentEvent = tieTarget.targetEventId?.let { computedEvents[it] }
                            currentPitchIndex = tieTarget.targetPitchIndex ?: 0
                        }
                    }
                }

                if (entries.size > 1 || endsWithLetRing) {
                    chains.add(
                        PitchTieChain(
                            midiPitch = pitchData.midiPitch,
                            entries = entries,
                            endsWithLetRing = endsWithLetRing
                        )
                    )
                }
            }
        }

        return chains
    }
}

/**
 * Returns all storage events of type [T] from plugin tracks whose [trackType] matches.
 *
 * Encapsulates the boilerplate of filtering tracks, flattening events, and casting the
 * underlying storage event. Plugin code passes the track-type string and the concrete
 * storage-event class; the framework handles the rest.
 *
 * Example:
 * ```kotlin
 * val chords = computedScore.pluginEventsOf<StorageChordEvent>(StorageChordEvent.TRACK_TYPE)
 * ```
 */
inline fun <reified T : com.mecon.api.storage.events.StoragePluginEvent> ComputedScore.pluginEventsOf(
    trackType: String
): List<T> =
    pluginTracks.values
        .filter { it.type == trackType }
        .flatMap { it.events.toList() }
        .mapNotNull { it.runtimeEvent.storageEvent as? T }

/**
 * Returns the first computed plugin track whose [type] matches [trackType], cast to
 * [com.mecon.api.computed.tracks.ComputedPluginTrack<T>].
 *
 * Complements [pluginEventsOf] for callers that need direct track access (e.g., adjacent-event
 * queries via [com.mecon.api.runtime.TimeIndexedList]).
 */
@Suppress("UNCHECKED_CAST")
fun <T : com.mecon.api.storage.events.StoragePluginEvent> ComputedScore.pluginTrackOf(
    trackType: String
): com.mecon.api.computed.tracks.ComputedPluginTrack<T>? =
    pluginTracks.values.firstOrNull { it.type == trackType }
        as? com.mecon.api.computed.tracks.ComputedPluginTrack<T>
