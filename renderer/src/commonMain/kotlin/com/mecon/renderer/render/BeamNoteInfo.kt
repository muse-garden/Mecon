package com.mecon.renderer.render

import com.mecon.api.computed.BeamInfo
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.StaffSpace

/**
 * Information about a note in a beam group for rendering.
 */
data class BeamNoteInfo(
    /** X position of the note */
    val x: StaffSpace,
    /** Y position of the stem tip */
    val stemTipY: StaffSpace,
    /** Beam information from computed layer */
    val beamInfo: BeamInfo,
    /** Event ID for hit testing */
    val eventId: EventId? = null,
    /** Track ID */
    val trackId: TrackId? = null
)
