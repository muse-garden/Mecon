package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.StaffSpace

/**
 * A non-spacing editor marker anchored only after system/page layout is final.
 * Future editor entries can add another implementation without entering proportional layout.
 */
sealed interface PostLayoutMarker {
    val systemIndex: Int
    val anchorMeasure: Int
}

/** Hidden tempo keyframe shown as a coloured dot in editor mode only. */
data class TempoKeyframeMarker(
    override val systemIndex: Int,
    override val anchorMeasure: Int,
    val x: StaffSpace,
    val keyframe: ComputedTempoKeyframe,
) : PostLayoutMarker

data class LayoutBreakMarker(
    override val systemIndex: Int,
    /** First measure on the far side of the boundary. */
    val beforeMeasure: Int,
    val kind: LayoutBreakKind,
) : PostLayoutMarker {
    override val anchorMeasure: Int get() = beforeMeasure - 1
}

/**
 * A run of one or more consecutive staves that are fully hidden on a system (line): they have collapsed
 * out of that system's [SystemLayout.staffLayouts], so a single dashed line is drawn across the line in
 * the gap they vacated. [staffIndices] are the collapsed notation staff indices (ascending); the painter
 * derives the gap Y from the system's *present* staves (the ones just above/below the run), so the marker
 * survives vertical/page shifts without recomputation — mirroring [LayoutBreakMarker].
 */
data class HiddenStaffMarker(
    override val systemIndex: Int,
    val staffIndices: List<Int>,
    val staffTrackIds: List<TrackId>,
    val fromMeasure: Int,
    val toMeasure: Int,
) : PostLayoutMarker {
    override val anchorMeasure: Int get() = fromMeasure
}
