package com.mecon.api.computed

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageTempoEvent
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoTransition

/** Resolved global tempo keyframe, anchored above the first display-order notation staff. */
data class ComputedTempoKeyframe(
    override val id: EventId,
    override val time: TimeCode,
    override val staffTrackId: TrackId,
    override val staffIndex: Int,
    override val placement: StaffAttachmentPlacement = StaffAttachmentPlacement.ABOVE,
    override val voiceNumber: Int? = null,
    val effectiveBpm: Float,
    val displayStyle: TempoDisplayStyle,
    val transitionToNext: TempoTransition,
    val nextTime: TimeCode?,
    val source: StorageTempoEvent,
) : ComputedStaffAttachment {
    val isEditorOnly: Boolean get() = displayStyle == TempoDisplayStyle.HIDDEN
    val isGradual: Boolean get() = displayStyle == TempoDisplayStyle.GRADUAL_TEXT && nextTime != null
}

