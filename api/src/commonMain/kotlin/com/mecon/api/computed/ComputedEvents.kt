package com.mecon.api.computed

import com.mecon.api.primitive.*
import com.mecon.api.runtime.HasOnset
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.ArticulationPlacement
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.events.GraceNoteInfo
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.storage.events.TupletDisplayStyle
import com.mecon.api.storage.tracks.FermataShape
import kotlinx.serialization.Serializable

/**
 * Computed tie target information for a specific pitch.
 *
 * Contains all resolved tie information needed for rendering:
 * - Target event and pitch index (for normal ties)
 * - Let-ring flag (for laissez vibrer ties with no target)
 */
@Serializable
data class ComputedTieTarget(
    /** Target VoiceEvent ID, null for let-ring ties */
    val targetEventId: EventId?,
    /** Target pitch index within the target event, null for let-ring */
    val targetPitchIndex: Int?,
    /** True if this is a let-ring (laissez vibrer) tie */
    val isLetRing: Boolean
) {
    companion object {
        /**
         * Create a let-ring tie (no target).
         */
        fun letRing() = ComputedTieTarget(
            targetEventId = null,
            targetPitchIndex = null,
            isLetRing = true
        )

        /**
         * Create a tie to a specific pitch in another event.
         */
        fun toEvent(targetEventId: EventId, targetPitchIndex: Int) = ComputedTieTarget(
            targetEventId = targetEventId,
            targetPitchIndex = targetPitchIndex,
            isLetRing = false
        )
    }
}

/**
 * Resolved phrasing slur connecting two VoiceEvents.
 *
 * Slurs are encoded on the storage side as start/end **counts** on each
 * VoiceEvent so that nesting is unambiguous (closes pop the innermost open
 * slurs first, then opens push new ones). The Computed layer turns those
 * counts into concrete event-pair references with the nesting depth at which
 * the slur sat.
 */
@Serializable
data class ComputedSlur(
    /**
     * Stable slur identity — from the source [com.mecon.api.storage.events.StorageSlurEvent.id],
     * or a deterministic id derived from start/end/nesting on the legacy counts
     * path. Used as the key for persisted geometry ([com.mecon.api.storage.ScoreGeometry]).
     */
    val slurId: EventId,
    /** Voice event where the slur opens. */
    val startEventId: EventId,
    /** Voice event where the slur closes. */
    val endEventId: EventId,
    /** Voice track owning the slur. Used to look up voice-number-driven direction. */
    val voiceTrackId: TrackId,
    /**
     * Voice number within the parent staff (1-based; smaller = upper voice).
     * Drives default bow direction in multi-voice contexts.
     */
    val voiceNumber: Int,
    /**
     * Stack depth at the time the slur opened. 0 = outermost slur on the
     * track at that moment; >0 = sits inside that many enclosing slurs and
     * its apex is fanned outward to avoid colliding with them.
     */
    val nestingLevel: Int,
)

/**
 * Resolved tuplet span attached to the **first** ComputedVoiceEvent in a tuplet.
 *
 * The ComputeEngine resolves the storage-layer [com.mecon.api.storage.events.TupletSpan.endTimeCode]
 * to a concrete last-member event ID, and decides whether to demote the
 * display style to [TupletDisplayStyle.NUMBER_ONLY] when the group is beamed
 * together (conventional engraving omits the bracket on beamed tuplets).
 */
@Serializable
data class ComputedTupletInfo(
    /** First event in the tuplet (== owning ComputedVoiceEvent.id). */
    val startEventId: EventId,
    /** Last event whose onset is inside the tuplet span. */
    val endEventId: EventId,
    /** N as in "N-tuplet" — what gets printed above/below the bracket. */
    val count: Int,
    /** Effective style after any beamed-group demotion. */
    val displayStyle: TupletDisplayStyle,
    /** Whether this span is an occupied-time small-note input region. */
    val smallNotes: Boolean = false,
)

/**
 * Computed data for a single pitch within a voice event.
 *
 * When a VoiceEvent contains multiple pitches (chord), each pitch
 * has its own computed properties (staff position, accidental, tie info, etc.).
 */
@Serializable
data class ComputedPitchData(
    val pitch: Pitch,

    /** MIDI pitch after transposition */
    val midiPitch: Int,

    /**
     * Staff position (0 = middle line, positive = above, negative = below).
     *
     * Staff line positions for a standard 5-line staff:
     * - 4 = top line (F5 in treble clef)
     * - 2 = 4th line
     * - 0 = middle line (B4 in treble clef)
     * - -2 = 2nd line
     * - -4 = bottom line (E4 in treble clef)
     *
     * Notes at positions > 5 or < -5 require ledger lines.
     */
    val staffPosition: Int,

    /** Effective accidental to display (null = none needed) */
    val effectiveAccidental: Accidental?,

    /** Whether this note needs a ledger line */
    val needsLedgerLine: Boolean,

    /** Per-pitch tie target (null = no tie for this pitch) */
    val tieTarget: ComputedTieTarget? = null
)

/**
 * Computed voice event - contains all derived fields for a rendered note/chord.
 *
 * In the new model, VoiceEvent is the primary rendered entity:
 * - It references one or more PitchEvents
 * - It has duration and rendering info
 * - Computed properties are calculated per voice event
 *
 * For chords, each pitch has its own ComputedPitchData.
 */
@Serializable
data class ComputedVoiceEvent(
    // === Source fields (from RuntimeVoiceEvent) ===
    val id: EventId,
    override val onset: TimeCode,
    val duration: Duration,
    val rendering: RenderingProps? = null,

    /** Computed data for each pitch in this event */
    val pitchData: List<ComputedPitchData>,

    // === Derived fields (event-level) ===

    /** Position within measure */
    val measurePosition: MeasurePosition,

    /** Whether this is a rest */
    val isRest: Boolean,

    // === Context-dependent fields ===

    /** Beam group info (null = not beamed) */
    val beamInfo: BeamInfo?,

    /** Resolved tuplet span starting at this event, if any. */
    val tupletInfo: ComputedTupletInfo? = null,

    /**
     * Grace-note group metadata, present only on the first event of a grace
     * group. Subsequent grace events are recognised by `onset.grace != null`.
     */
    val graceInfo: GraceNoteInfo? = null,

    /**
     * Articulation marks for this note/chord (from the referenced PitchEvent).
     * The Computed layer just surfaces *which* marks exist; the Renderer decides
     * side, stacking and coordinates.
     */
    val articulations: List<Articulation> = emptyList(),

    /** Which side to draw the articulations on (from the VoiceEvent's RenderingProps). */
    val articulationPlacement: ArticulationPlacement = ArticulationPlacement.AUTO,

    /**
     * Score-wide fermata resolved to this voice event. The event is the final
     * event strictly before the fermata's stored after-time.
     */
    val fermata: ComputedFermata? = null,

    /**
     * Voice track this event belongs to, set only for events that are *synthesized*
     * by the compute layer and therefore have no backing [RuntimeVoiceEvent] (e.g.
     * implicit whole-measure rests in empty measures). For normal events this is
     * null and the renderer resolves the voice track from the runtime score.
     */
    val originVoiceTrackId: TrackId? = null,
) : HasOnset {

    /** Whether this event is part of a grace-note group (any position). */
    val isGrace: Boolean get() = onset.grace != null
    /**
     * Check if any pitch in this event has a tie.
     */
    val hasTies: Boolean get() = pitchData.any { it.tieTarget != null }

    /**
     * Check if any pitch has a let-ring tie.
     */
    val hasLetRing: Boolean get() = pitchData.any { it.tieTarget?.isLetRing == true }

    /**
     * Get all pitches that have ties.
     */
    val tiedPitches: List<ComputedPitchData> get() = pitchData.filter { it.tieTarget != null }
    /**
     * End time of this event.
     */
    val endTime: TimeCode get() = onset + duration.toFraction()

    /**
     * Whether any pitch in this event needs a ledger line.
     */
    val needsLedgerLine: Boolean get() = pitchData.any { it.needsLedgerLine }

    /**
     * All pitches in this event.
     */
    val pitches: List<Pitch> get() = pitchData.map { it.pitch }

    /**
     * Primary pitch (lowest).
     */
    val primaryPitch: Pitch? get() = pitchData.minByOrNull { it.midiPitch }?.pitch

    /**
     * Primary staff position (for stem direction).
     */
    val primaryStaffPosition: Int? get() = pitchData.minByOrNull { it.midiPitch }?.staffPosition

    companion object {
        /**
         * Create from runtime voice event with computed fields.
         */
        fun from(
            runtime: RuntimeVoiceEvent,
            pitchData: List<ComputedPitchData>,
            measurePosition: MeasurePosition,
            beamInfo: BeamInfo?,
            tupletInfo: ComputedTupletInfo? = null,
            fermata: ComputedFermata? = null,
        ) = ComputedVoiceEvent(
            id = runtime.id,
            onset = runtime.onset,
            duration = runtime.duration,
            rendering = runtime.rendering,
            pitchData = pitchData,
            measurePosition = measurePosition,
            isRest = runtime.isRest,
            beamInfo = beamInfo,
            tupletInfo = tupletInfo,
            graceInfo = runtime.graceInfo,
            articulations = runtime.pitchEvent.articulations,
            articulationPlacement = runtime.rendering?.articulationPlacement ?: ArticulationPlacement.AUTO,
            fermata = fermata,
        )
    }
}

/** A global fermata projected onto one voice event. */
@Serializable
data class ComputedFermata(
    val id: EventId,
    val afterTime: TimeCode,
    val extension: Fraction,
    val shape: FermataShape,
)

/**
 * Computed plugin event - contains all derived fields for a plugin event.
 */
interface ComputedPluginEvent<T : StoragePluginEvent> : com.mecon.api.runtime.HasOnset {
    val id: EventId
    override val onset: TimeCode
    val runtimeEvent: RuntimePluginEvent<T>
}
