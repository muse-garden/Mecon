package com.mecon.api.storage.tracks

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The range a [StorageControllerTrack] governs.
 *
 * - [staffIds] empty → the whole score.
 * - [voiceNumbers] empty → all voices within the covered staves.
 */
@Serializable
data class ControllerScope(
    val staffIds: List<TrackId> = emptyList(),
    val voiceNumbers: List<Int> = emptyList(),
) {
    val isWholeScore: Boolean get() = staffIds.isEmpty()
}

/**
 * Kind of transition a controller event records.
 */
@Serializable
enum class ControllerEventType {
    /** Switch to a fixed dynamic level. */
    SET_DYNAMIC,
    /** Begin a crescendo / diminuendo ramp. */
    RAMP_START,
    /** End a crescendo / diminuendo ramp. */
    RAMP_END,
}

/**
 * A single transition on a controller track at a point in time.
 *
 * The first version stores only the symbolic intent — there is no synthesised
 * playback effect yet, so [level] / [hairpin] may be null ("blank" events that
 * merely mark where a dynamic change occurs).
 */
@Serializable
data class StorageControllerEvent(
    val id: EventId,
    val onset: TimeCode,
    val type: ControllerEventType,
    /** Target level for [ControllerEventType.SET_DYNAMIC] / [ControllerEventType.RAMP_START]. */
    val level: DynamicLevel? = null,
    /** Ramp direction for [ControllerEventType.RAMP_START]. */
    val hairpin: HairpinType? = null,
)

/**
 * A controller track ties on-score expressive marks (dynamics, hairpins) to a
 * timeline of playback transitions over a [scope] of staves / voices.
 *
 * Staff-track attachments ([com.mecon.api.storage.events.StorageStaffAttachment])
 * reference the controller events generated for them. A fixed dynamic produces a
 * single [ControllerEventType.SET_DYNAMIC] event; a hairpin produces a
 * [ControllerEventType.RAMP_START] / [ControllerEventType.RAMP_END] pair.
 */
@Serializable
@SerialName("controller")
data class StorageControllerTrack(
    override val id: TrackId,
    override val name: String = "Controller",
    val scope: ControllerScope = ControllerScope(),
    val events: List<StorageControllerEvent> = emptyList(),
) : StorageTrack {
    fun addEvent(event: StorageControllerEvent): StorageControllerTrack =
        copy(events = events + event)

    fun findEvent(eventId: EventId): StorageControllerEvent? =
        events.find { it.id == eventId }

    companion object {
        fun create(
            name: String = "Controller",
            scope: ControllerScope = ControllerScope(),
        ) = StorageControllerTrack(
            id = TrackId.generate(),
            name = name,
            scope = scope,
        )
    }
}
