package com.mecon.api.runtime.events

import com.mecon.api.primitive.*
import com.mecon.api.runtime.HasOnset
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.events.*

/**
 * Runtime tie information for a specific pitch.
 *
 * Target resolution happens in the Computed layer ([com.mecon.core.engine.TieTargetComputer])
 * using B+ tree lookups on the voice track's [com.mecon.api.runtime.TimeIndexedList].
 */
data class RuntimeTieInfo(
    /** Which pitch in the chord (0-based index) */
    val pitchIndex: Int,
    /** True = let ring (laissez vibrer); false = resolve target via heuristic search */
    val isLetRing: Boolean
)

/**
 * Runtime pitch event - in-memory representation of a pitch event.
 *
 * This is the runtime version matching the new storage model:
 * - pitches: list of pitches (empty = rest, multiple = chord)
 * - No duration (pitch extends until next event; duration is on VoiceEvent)
 * - No rendering (rendering is on VoiceEvent)
 */
data class RuntimePitchEvent(
    val id: EventId,
    override val onset: TimeCode,
    val pitches: List<Pitch> = emptyList(),  // Empty = rest, multiple = chord
    /** Articulation marks affecting how this note/chord is played. */
    val articulations: List<com.mecon.api.storage.Articulation> = emptyList()
) : HasOnset {

    /**
     * Create from storage event.
     */
    constructor(storage: StoragePitchEvent) : this(
        id = storage.id,
        onset = storage.onset,
        pitches = storage.pitches,
        articulations = storage.articulations
    )

    /**
     * Convert to storage event.
     */
    fun toStorage(): StoragePitchEvent = StoragePitchEvent(
        id = id,
        onset = onset,
        pitches = pitches,
        articulations = articulations
    )

    /**
     * Whether this is a rest (no pitches).
     */
    val isRest: Boolean get() = pitches.isEmpty()

    /**
     * Whether this is a chord (multiple pitches).
     */
    val isChord: Boolean get() = pitches.size > 1

    /**
     * Primary pitch (lowest, used for stem direction calculation).
     */
    val primaryPitch: Pitch? get() = pitches.minByOrNull { it.midiNumber }

    companion object {
        fun create(
            onset: TimeCode,
            pitches: List<Pitch> = emptyList()
        ) = RuntimePitchEvent(
            id = EventId.generate(),
            onset = onset,
            pitches = pitches
        )

        /**
         * Create a single-pitch event (convenience).
         */
        fun single(onset: TimeCode, pitch: Pitch) = create(onset, listOf(pitch))

        /**
         * Create a chord event (convenience).
         */
        fun chord(onset: TimeCode, vararg pitches: Pitch) = create(onset, pitches.toList())

        /**
         * Create a rest event (convenience).
         */
        fun rest(onset: TimeCode) = create(onset, emptyList())
    }
}

/**
 * Runtime voice event - represents a renderable note/chord in a voice.
 *
 * VoiceEvent bridges PitchEvents (pure pitch data) to notation:
 * - onset: where this voice event appears
 * - pitchEvent: resolved reference to a single RuntimePitchEvent
 * - duration: how long this note/chord is displayed
 * - rendering: display customization
 * - ties: per-pitch tie information with resolved targets
 *
 * One-to-one relationship: Each VoiceEvent references exactly one PitchEvent.
 */
data class RuntimeVoiceEvent(
    val id: EventId,
    override val onset: TimeCode,
    val pitchEvent: RuntimePitchEvent,  // Resolved reference to a single pitch event
    val duration: Duration,
    val rendering: RenderingProps? = null,
    /** Per-pitch tie information with resolved target references */
    val ties: List<RuntimeTieInfo> = emptyList(),
    /** Tuplet span starting at this event, if any (same shape as the storage field). */
    val tupletSpan: TupletSpan? = null,
    /** Number of phrasing slurs opening at this event. See [com.mecon.api.storage.events.StorageVoiceEvent]. */
    val slurStarts: Int = 0,
    /** Number of phrasing slurs closing at this event (applied before opens). */
    val slurEnds: Int = 0,
    /**
     * Grace-note group metadata, present only on the first event of a grace
     * group. See [com.mecon.api.storage.events.GraceNoteInfo].
     */
    val graceInfo: GraceNoteInfo? = null,
) : HasOnset {

    /** Whether this event is part of a grace-note group (any position). */
    val isGrace: Boolean get() = onset.grace != null

    /** Whether this event is the first of a grace-note group (carries [graceInfo]). */
    val isGraceGroupStart: Boolean get() = graceInfo != null

    /**
     * All pitches from the referenced pitch event.
     */
    val pitches: List<Pitch> get() = pitchEvent.pitches

    /**
     * Primary pitch (lowest, for stem direction).
     */
    val primaryPitch: Pitch? get() = pitches.minByOrNull { it.midiNumber }

    /**
     * Whether this is a rest.
     */
    val isRest: Boolean get() = pitchEvent.isRest

    /**
     * Whether this is a chord.
     */
    val isChord: Boolean get() = pitchEvent.isChord

    /**
     * Calculate end time.
     */
    val endTime: TimeCode get() = onset + duration.toFraction()

    /**
     * Check if this event overlaps with a time range.
     */
    fun overlaps(range: TimeRange): Boolean =
        onset <= range.end && endTime >= range.start

    /**
     * Get tie info for a specific pitch index, if any.
     */
    fun getTieForPitch(pitchIndex: Int): RuntimeTieInfo? =
        ties.find { it.pitchIndex == pitchIndex }

    /**
     * Check if a specific pitch is tied.
     */
    fun isPitchTied(pitchIndex: Int): Boolean =
        ties.any { it.pitchIndex == pitchIndex }

    /**
     * Check if any pitch has a let-ring tie.
     */
    val hasLetRing: Boolean get() = ties.any { it.isLetRing }

    companion object {
        fun create(
            onset: TimeCode,
            pitchEvent: RuntimePitchEvent,
            duration: Duration,
            rendering: RenderingProps? = null,
            ties: List<RuntimeTieInfo> = emptyList(),
            tupletSpan: TupletSpan? = null,
            slurStarts: Int = 0,
            slurEnds: Int = 0,
            graceInfo: GraceNoteInfo? = null,
        ) = RuntimeVoiceEvent(
            id = EventId.generate(),
            onset = onset,
            pitchEvent = pitchEvent,
            duration = duration,
            rendering = rendering,
            ties = ties,
            tupletSpan = tupletSpan,
            slurStarts = slurStarts,
            slurEnds = slurEnds,
            graceInfo = graceInfo,
        )
    }
}

/**
 * Base interface for all customized plugin events in the runtime model.
 */
interface RuntimePluginEvent<T : StoragePluginEvent> : HasOnset {
    val id: EventId
    override val onset: TimeCode
    val storageEvent: T // Reference to the original storage event
}
