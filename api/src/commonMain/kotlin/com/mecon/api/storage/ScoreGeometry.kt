package com.mecon.api.storage

import com.mecon.api.primitive.EventId
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.Serializable

/**
 * Persisted render-geometry overlay for slurs, tuplets, articulation marks and staff
 * attachments (dynamics / hairpins / 8va·8vb brackets).
 *
 * When present, an entry is the **source of truth** for that symbol's geometry:
 * the renderer re-anchors it instead of laying it out from scratch. An **absent**
 * entry means "auto-lay-out this symbol" — so a score without a [ScoreGeometry]
 * (`StorageScore.geometry == null`) renders exactly as before.
 *
 * All coordinates are in **staff spaces** (plain [Float] — this layer can't
 * depend on the renderer's `StaffSpace`). They live in deliberately **stable,
 * anchor-relative frames** so most edits don't invalidate them:
 *  - **Articulation** marks: glyph origin offset from the event's **time-slot X**
 *    and the **staff midline** (rarely needs to move when neighbours change).
 *  - **Slur** endpoints: offset from the **anchored notehead** (so they follow
 *    the note when it is moved / transposed); the curve shape is stored as apex /
 *    damping parameters, which are stable across small layout changes.
 *  - **Attachment** spans (hairpin / 8va / 8vb): both endpoints offset from their
 *    own **time-slot X** (so the span follows the notes it brackets) and the
 *    **staff midline**. A point attachment (dynamic letter) stores only its start.
 *
 * Keys are stable ids: articulation by voice [EventId]; slur by
 * [com.mecon.api.computed.ComputedSlur.slurId] (= [events.StorageSlurEvent.id] or
 * a deterministic derived id on the legacy counts path); attachment by
 * [com.mecon.api.computed.ComputedStaffAttachment.id] (= the storage attachment id).
 */
@Serializable
data class ScoreGeometry(
    val articulations: Map<EventId, ArticulationGeometry> = emptyMap(),
    /** Tie geometry grouped by source voice-event id; chord pitches are distinguished inside the list. */
    val ties: Map<EventId, List<TieGeometry>> = emptyMap(),
    val slurs: Map<EventId, SlurGeometry> = emptyMap(),
    val attachments: Map<EventId, AttachmentGeometry> = emptyMap(),
    /** Beam geometry keyed by the stable computed beam-group id. */
    val beams: Map<String, BeamGeometry> = emptyMap(),
    /** Tuplet placement keyed by the stable id of the span's first voice event. */
    val tuplets: Map<EventId, TupletGeometry> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = articulations.isEmpty() && ties.isEmpty() && slurs.isEmpty() &&
            attachments.isEmpty() && beams.isEmpty() && tuplets.isEmpty()

    /**
     * Drop the named entries, returning the overlay the renderer should consume
     * for an **incremental** frame: stale entries are removed so the affected
     * symbols fall back to fresh auto-layout (reshape), while every other entry
     * stays present and resolves anchor-relative (the notes it follows may have
     * moved). Returns `this` unchanged when nothing is dropped.
     */
    fun without(
        staleArticulations: Set<EventId>,
        staleSlurs: Set<EventId>,
        staleAttachments: Set<EventId> = emptySet(),
        staleBeams: Set<String> = emptySet(),
        staleTies: Set<EventId> = emptySet(),
    ): ScoreGeometry {
        if (staleArticulations.isEmpty() && staleSlurs.isEmpty() && staleAttachments.isEmpty() &&
            staleBeams.isEmpty() && staleTies.isEmpty()
        ) return this
        return ScoreGeometry(
            articulations = if (staleArticulations.isEmpty()) articulations
            else articulations.filterKeys { it !in staleArticulations },
            ties = if (staleTies.isEmpty()) ties else {
                val builder = ((ties as? PersistentMap<EventId, List<TieGeometry>>)
                    ?: ties.toPersistentMap()).builder()
                for (id in staleTies) {
                    val geometries = ties[id] ?: continue
                    val directives = geometries.mapNotNull { geometry ->
                        when {
                            geometry.manuallyAdjusted -> geometry
                            geometry.directionLocked -> geometry.copy(directionOnly = true)
                            else -> null
                        }
                    }
                    if (directives.isEmpty()) builder.remove(id) else builder[id] = directives
                }
                builder.build()
            },
            slurs = if (staleSlurs.isEmpty()) slurs else {
                val builder = ((slurs as? PersistentMap<EventId, SlurGeometry>)
                    ?: slurs.toPersistentMap()).builder()
                for (id in staleSlurs) {
                    val geometry = slurs[id] ?: continue
                    when {
                        geometry.manuallyAdjusted -> Unit
                        geometry.directionOnly || geometry.directionLocked ->
                            builder[id] = geometry.copy(directionOnly = true)
                        else -> builder.remove(id)
                    }
                }
                builder.build()
            },
            attachments = if (staleAttachments.isEmpty()) attachments
            else attachments.filterKeys { it !in staleAttachments },
            beams = if (staleBeams.isEmpty()) beams else beams.filterKeys { it !in staleBeams },
            tuplets = tuplets,
        )
    }

    companion object {
        val EMPTY = ScoreGeometry()
    }
}

/**
 * Persisted side of a tuplet bracket / slur / number.
 *
 * Auto-captured geometry remains unlocked so changes to the group's stems can choose a new side.
 * An inspector edit locks the side and turns it into an explicit engraving directive.
 */
@Serializable
data class TupletGeometry(
    val above: Boolean,
    val directionLocked: Boolean = false,
)

/**
 * Persisted geometry for one tie from a specific pitch of a voice event.
 *
 * Both endpoints are relative to their anchored notehead centres. A let-ring has no target pitch,
 * but still stores its synthetic end relative to the source anchor.
 */
@Serializable
data class TieGeometry(
    val sourcePitchIndex: Int,
    val targetPitchIndex: Int? = null,
    val startDx: Float,
    val startDy: Float,
    val endDx: Float,
    val endDy: Float,
    val above: Boolean,
    val minApex: Float,
    val maxApex: Float,
    val slopeDamping: Float = 1f,
    val middleStraightening: Float = 0f,
    /** Keep the user's direction while rerunning automatic anchors and collision avoidance. */
    val directionOnly: Boolean = false,
    /** Direction came from the user or an imported MusicXML placement/orientation. */
    val directionLocked: Boolean = false,
    /** Curve height/shape was directly adjusted by the user or imported from MusicXML Bézier data. */
    val manuallyAdjusted: Boolean = false,
    /** Keep automatic notehead endpoints but apply the stored direction / curve shape. */
    val autoEndpoints: Boolean = false,
)

/** Persisted placement of all articulation marks on one voice event. */
@Serializable
data class ArticulationGeometry(
    val marks: List<MarkOffset>,
)

/**
 * One placed articulation glyph, as a stable offset.
 *
 * @property index  Index into the event's `articulations` list.
 * @property above  True = drawn above the note.
 * @property dx     Glyph origin X minus the event's time-slot X (staff spaces).
 * @property dy     Glyph origin Y minus the staff midline (staff spaces).
 */
@Serializable
data class MarkOffset(
    val index: Int,
    val above: Boolean,
    val dx: Float,
    val dy: Float,
)

/**
 * Persisted geometry for one phrasing slur.
 *
 * Endpoints are stored relative to their anchored notehead so they follow the
 * notes; the shape parameters mirror the renderer's `SlurLayout`. Cross-system
 * (multi-stub) slurs are not persisted in Phase 1 — they fall back to auto.
 *
 * @property startDx / startDy  Start endpoint offset from the start notehead anchor.
 * @property endDx / endDy      End endpoint offset from the end notehead anchor.
 * @property above              Bow direction (true = above the staff).
 * @property directionOnly      Ignore captured coordinates and rerun automatic placement using [above].
 */
@Serializable
data class SlurGeometry(
    val startPitchIndex: Int,
    val endPitchIndex: Int,
    val startDx: Float,
    val startDy: Float,
    val endDx: Float,
    val endDy: Float,
    val above: Boolean,
    val minApex: Float,
    val maxApex: Float,
    val slopeDamping: Float,
    val middleStraightening: Float,
    val directionOnly: Boolean = false,
    /** Direction came from the user or an imported MusicXML placement. */
    val directionLocked: Boolean = false,
    /** Curve height/shape was directly adjusted by the user or imported from MusicXML Bézier data. */
    val manuallyAdjusted: Boolean = false,
    /** Keep automatic notehead/stem endpoints but apply the stored direction / curve shape. */
    val autoEndpoints: Boolean = false,
) {
    companion object {
        /**
         * Bounds, in staff spaces, that a user-dragged apex is held within. Owned here so platform
         * adapters and the shared editing session cannot drift to different limits.
         */
        const val MIN_APEX: Float = 0.25f
        const val MAX_APEX: Float = 8f
    }
}

/**
 * Persisted geometry for one staff attachment — a dynamic letter (point) or a
 * spanning symbol (hairpin / 8va / 8vb).
 *
 * Both endpoints are stored relative to **their own time-slot X** and the
 * **staff midline**, so the symbol follows the notes it brackets when they move.
 * A **point** attachment (dynamic) leaves the end fields `null`; a **span**
 * stores both. The drawn *kind* (wedge vs. bracket vs. text+dashed line, label,
 * hook) is re-derived from the [com.mecon.api.computed.ComputedStaffAttachment]
 * at resolve time, so only the positions live here.
 *
 * The end Y is kept independently from the start Y even though auto-layout draws
 * a hairpin **horizontal** today (`startDy == endDy`): recording both reserves the
 * slot for a later manual, non-horizontal adjustment without a format change.
 *
 * @property startDx Start X minus the start onset's time-slot X (staff spaces).
 * @property startDy Start Y minus the staff midline (staff spaces).
 * @property endDx   End X minus the end onset's time-slot X; `null` for a point.
 * @property endDy   End Y minus the staff midline; `null` for a point.
 * @property spread  Wedge opening (full mouth height) for a hairpin; 0 otherwise.
 */
@Serializable
data class AttachmentGeometry(
    val startDx: Float,
    val startDy: Float,
    val endDx: Float? = null,
    val endDy: Float? = null,
    val spread: Float = 0f,
    /** User owns endpoint Y; renderer continues to auto-layout X from the musical anchors. */
    val manuallyAdjustedY: Boolean = false,
) {
    /** True when this is a spanning symbol (both endpoints present). */
    val isSpan: Boolean get() = endDx != null && endDy != null
}

/**
 * Persisted endpoint geometry for one primary beam group.
 *
 * Coordinates are staff-space offsets from the owning staff midline. For a
 * cross-staff group, [crossStaffBase] selects the reference line,
 * [crossStaffOffset] positions the beam baseline from it, and the endpoint
 * offsets are measured from that baseline; this keeps the beam stable when
 * the inter-staff distance changes.
 */
@Serializable
data class BeamGeometry(
    val startDy: Float,
    val endDy: Float,
    val crossStaffBase: CrossStaffBeamBase? = null,
    val crossStaffOffset: Float? = null,
    /** Upper staff index of the concrete gap used by [CrossStaffBeamBase.BETWEEN_STAFFS]. */
    val betweenStaffUpperIndex: Int? = null,
    /** Lower staff index of the concrete gap used by [CrossStaffBeamBase.BETWEEN_STAFFS]. */
    val betweenStaffLowerIndex: Int? = null,
    /** True only after an editor drag; automatic captures remain freely recomputable. */
    val manuallyAdjusted: Boolean = false,
)

@Serializable
enum class CrossStaffBeamBase {
    TOP_STAFF_MIDLINE,
    BOTTOM_STAFF_MIDLINE,
    BETWEEN_STAFFS,
}
