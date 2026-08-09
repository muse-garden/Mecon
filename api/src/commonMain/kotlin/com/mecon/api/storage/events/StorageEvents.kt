package com.mecon.api.storage.events

import com.mecon.api.primitive.*
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.RenderingProps
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base interface for all storage events.
 */
@Serializable
sealed interface StorageEvent {
    val id: EventId
}

/**
 * Tie information for a specific pitch within a VoiceEvent.
 *
 * If [isLetRing] is false (default), the target event is resolved at runtime
 * by searching forward in the track:
 * 1. The immediately next VoiceEvent in the track — connects if it has the same pitch.
 * 2. Otherwise, if the next event is in a different measure or absent, the first event
 *    at the start of the following measure (beat = 0) that has the same pitch.
 * 3. No tie if neither candidate is found.
 *
 * 🚧 Grace-note rule (not yet implemented): when the source note is a grace note,
 * search forward from it through all grace notes and the principal note it decorates
 * (inclusive), connecting to the first one with the same pitch.
 */
@Serializable
data class TieInfo(
    /** Which pitch in the chord (0-based index into pitches list) */
    val pitchIndex: Int,
    /** True = let ring (laissez vibrer); false = resolve target by heuristic search */
    val isLetRing: Boolean = false
)

/**
 * Where a grace-note group steals its musical time from.
 *
 * Grace notes do not extend the underlying meter — they borrow time either
 * from the preceding note or from the principal note they decorate.
 */
@Serializable
enum class GraceTimeSource {
    /** Shorten the previous note by [GraceNoteInfo.totalDuration]; the principal
     *  starts on its written onset. */
    PREVIOUS,
    /** Delay the principal by [GraceNoteInfo.totalDuration]; the previous note
     *  retains its full written duration. */
    PRINCIPAL,
}

/**
 * Metadata attached to the **first** VoiceEvent of a grace-note group.
 *
 * A grace group is the contiguous run of VoiceEvents whose [TimeCode.grace] is
 * non-null at the same `(measure, beat)`. Their grace components evenly divide
 * the half-open window `[-1, 0)` (e.g. three graces → `-1, -2/3, -1/3`), so
 * ordering is structural and independent of actual sounded duration.
 *
 * - [totalDuration] is the musical time the entire group occupies.
 * - [stealFrom] decides which neighbour donates that time during playback.
 *
 * Only the first event in the group carries this; the rest are recognised by
 * having `onset.grace != null` and no `graceInfo`.
 */
@Serializable
data class GraceNoteInfo(
    val totalDuration: Duration,
    val stealFrom: GraceTimeSource,
)

/**
 * Display style for a tuplet bracket / number.
 */
@Serializable
enum class TupletDisplayStyle {
    /** Render nothing — the tuplet exists only in timing data. */
    NONE,
    /** Render only the count number above/below the group. */
    NUMBER_ONLY,
    /** Render an angled bracket + the count number. */
    BRACKET_AND_NUMBER,
    /** Render a slur (curved bracket) + the count number. */
    SLUR_AND_NUMBER,
}

/**
 * Tuplet span attached to the VoiceEvent that **starts** a tuplet group.
 *
 * - [endTimeCode] is exclusive: the first TimeCode that is no longer part of the
 *   tuplet (e.g. for a beat-one triplet in 4/4 it is the onset of beat two).
 * - [count] is N as in "N-tuplet" (e.g. 3 for a triplet, 5 for a quintuplet).
 * - [beatUnit] is the editor's notion of "one tuplet beat" (e.g. EIGHTH for
 *   a quarter-note triplet's underlying eighth). Renderer ignores it — each
 *   note's own [Duration] still governs how it draws.
 * - [displayStyle] picks the bracket/number rendering.
 */
@Serializable
data class TupletSpan(
    val endTimeCode: TimeCode,
    val count: Int,
    val beatUnit: DurationBase,
    val displayStyle: TupletDisplayStyle = TupletDisplayStyle.BRACKET_AND_NUMBER,
    /** Hidden-bracket, metered small-note input region (Chopin-style). */
    val smallNotes: Boolean = false,
) {
    init {
        require(count > 1) { "Tuplet count must be > 1, got $count" }
    }
}

/**
 * Storage pitch event - represents a pitch point in time.
 *
 * In the new design, PitchEvent is a pure pitch data container:
 * - Contains one or more pitches (chords use multiple pitches)
 * - Empty pitches list = rest
 * - No duration (the pitch extends until the next PitchEvent)
 * - No rendering props (those belong to VoiceEvent)
 *
 * Duration and rendering are handled by VoiceEvent which references PitchEvents.
 * This allows the same pitch to be rendered with different durations (e.g., tied notes).
 *
 * [articulations] (staccato, accent, …) live here because they describe how the
 * note is *played* — musical information shared by every VoiceEvent that points
 * at this pitch. The purely typographic choice of which side to draw them on is
 * carried separately by [com.mecon.api.storage.RenderingProps.articulationPlacement].
 */
@Serializable
@SerialName("pitch")
data class StoragePitchEvent(
    override val id: EventId,
    val onset: TimeCode,
    val pitches: List<Pitch> = emptyList(),  // Empty = rest, multiple = chord
    /** Articulation marks affecting how this note/chord is played. */
    val articulations: List<Articulation> = emptyList()
) : StorageEvent {
    companion object {
        fun create(
            onset: TimeCode,
            pitches: List<Pitch> = emptyList(),
            articulations: List<Articulation> = emptyList()
        ) = StoragePitchEvent(
            id = EventId.generate(),
            onset = onset,
            pitches = pitches,
            articulations = articulations
        )

        /**
         * Create a single-pitch event (convenience).
         */
        fun single(
            onset: TimeCode,
            pitch: Pitch,
            articulations: List<Articulation> = emptyList()
        ) = create(onset, listOf(pitch), articulations)

        /**
         * Create a chord event (convenience).
         */
        fun chord(
            onset: TimeCode,
            vararg pitches: Pitch
        ) = create(onset, pitches.toList())

        /**
         * Create a rest event (convenience).
         */
        fun rest(onset: TimeCode) = create(onset, emptyList())
    }

    /**
     * Whether this is a rest (no pitches).
     */
    val isRest: Boolean get() = pitches.isEmpty()

    /**
     * Whether this is a chord (multiple pitches).
     */
    val isChord: Boolean get() = pitches.size > 1
}

/**
 * Voice event - represents a renderable note or chord at a specific time.
 *
 * VoiceEvent bridges PitchEvents (pure pitch data) to notation:
 * - onset: where this voice event appears in the score
 * - pitchEventId: reference to a single PitchEvent
 * - duration: how long this note/chord is displayed
 * - rendering: display customization (stem direction, accidentals, etc.)
 *
 * Key design decisions:
 * - **Tied notes**: One PitchEvent + multiple VoiceEvents
 *   (same pitch rendered as multiple note symbols connected by ties)
 * - **Chords**: One VoiceEvent references one PitchEvent with multiple pitches
 * - **Rests**: VoiceEvent references a PitchEvent with empty pitches list
 * - **One-to-one relationship**: Each VoiceEvent references exactly one PitchEvent
 */
@Serializable
@SerialName("voiceEvent")
data class StorageVoiceEvent(
    override val id: EventId,
    val onset: TimeCode,
    val pitchEventId: EventId,  // Reference to a single PitchEvent
    val duration: Duration,
    val rendering: RenderingProps? = null,
    /** Per-pitch tie information. Each entry ties a specific pitch to another VoiceEvent. */
    val ties: List<TieInfo> = emptyList(),
    /**
     * Tuplet span starting at this event, if any. Only the first event in a
     * tuplet group carries this — subsequent events inside the tuplet have
     * `tupletSpan = null` and are discovered by [TupletSpan.endTimeCode].
     */
    val tupletSpan: TupletSpan? = null,
    /**
     * Number of phrasing slurs that **open** at this event.
     *
     * Slurs are not standalone events — they live as start/end markers on the
     * VoiceEvents they connect, matched into pairs by a LIFO stack walk over
     * the voice track. An event can both close inner slurs and open new ones
     * simultaneously (e.g. LilyPond's `\)\(` pattern), which is why these
     * fields are counts rather than booleans.
     */
    val slurStarts: Int = 0,
    /** Number of phrasing slurs that **close** at this event. Closes are
     *  applied before opens, so the inner-most still-open slurs are popped. */
    val slurEnds: Int = 0,
    /**
     * Grace-note group metadata. Only set on the **first** event of a grace
     * group; subsequent grace events in the same group are identified purely
     * by `onset.grace != null`. See [GraceNoteInfo].
     */
    val graceInfo: GraceNoteInfo? = null,
) : StorageEvent {
    init {
        require(slurStarts >= 0) { "slurStarts must be non-negative, got $slurStarts" }
        require(slurEnds >= 0) { "slurEnds must be non-negative, got $slurEnds" }
    }

    companion object {
        fun create(
            onset: TimeCode,
            pitchEventId: EventId,
            duration: Duration,
            rendering: RenderingProps? = null,
            ties: List<TieInfo> = emptyList(),
            tupletSpan: TupletSpan? = null,
            slurStarts: Int = 0,
            slurEnds: Int = 0,
            graceInfo: GraceNoteInfo? = null,
        ) = StorageVoiceEvent(
            id = EventId.generate(),
            onset = onset,
            pitchEventId = pitchEventId,
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
 * Phrasing slur connecting two VoiceEvents, promoted to a first-class storage
 * object so it carries a **stable [id]** — the key for persisted geometry
 * ([com.mecon.api.storage.ScoreGeometry]) and the handle for future manual
 * editing. Lives on [com.mecon.api.storage.tracks.StorageVoiceTrack.slurs].
 *
 * Legacy scores instead encode slurs as [StorageVoiceEvent.slurStarts] /
 * [StorageVoiceEvent.slurEnds] counts. Those are still honoured at load time
 * (see `RuntimeScore.fromStorage`): when [StorageVoiceTrack.slurs] is empty the
 * counts are walked LIFO and synthesised into equivalent slurs with a
 * deterministic derived id. New / saved scores write explicit slurs.
 */
@Serializable
data class StorageSlurEvent(
    val id: EventId,
    val startEventId: EventId,
    val endEventId: EventId,
) {
    companion object {
        fun create(startEventId: EventId, endEventId: EventId) =
            StorageSlurEvent(EventId.generate(), startEventId, endEventId)
    }
}

/**
 * Text event - text annotation.
 */
@Serializable
@SerialName("text")
data class StorageTextEvent(
    override val id: EventId,
    val onset: TimeCode,
    val text: String,
    val textType: TextType = TextType.EXPRESSION,
    val rendering: RenderingProps? = null
) : StorageEvent

enum class TextType {
    EXPRESSION,   // Expressive text (italics)
    TECHNIQUE,    // Technique instruction
    LYRICS,       // Lyrics
    REHEARSAL     // Rehearsal mark
}

/**
 * Base interface for all customized plugin events stored in plugin tracks.
 */
@Serializable
abstract class StoragePluginEvent : StorageEvent {
    abstract val onset: TimeCode
}

/** Plugin event whose visual/analysis impact covers a finite half-open interval. */
interface StoragePluginIntervalEvent {
    val endOnset: TimeCode
}

/**
 * Marker for an event whose analysis state remains in force after its explicit
 * interval, so edits must invalidate rendering from its onset to the score end.
 */
interface StoragePluginForwardAffectingEvent
