package com.mecon.desktop.ui.components.inspector

import androidx.compose.runtime.Composable
import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.EventSection
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.ScoreGeometry
import com.mecon.api.storage.events.GraceTimeSource
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoTransition
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.api.primitive.Accidental
import com.mecon.api.storage.events.TrillPlaybackMode

internal data class SelectionInspectorContext(
    val selection: Set<EventSection>,
    val runtimeScore: RuntimeScore?,
    val computedScore: ComputedScore?,
    val runtimeGeometry: ScoreGeometry?,
    val renderedGeometry: ScoreGeometry?,
    val maxMeasure: Int,
)

internal data class TempoPropertiesActions(
    val changeBpm: (EventId, Float) -> Unit = { _, _ -> },
    val changeDisplayStyle: (EventId, TempoDisplayStyle) -> Unit = { _, _ -> },
    val changeTransition: (EventId, TempoTransition) -> Unit = { _, _ -> },
)

internal data class PerformancePropertiesActions(
    val changeAmount: (EventId, Fraction) -> Unit = { _, _ -> },
)

internal data class OrnamentPropertiesActions(
    val changeUpperAccidental: (EventId, Accidental?) -> Unit = { _, _ -> },
    val changeLowerAccidental: (EventId, Accidental?) -> Unit = { _, _ -> },
    val changeElementDuration: (EventId, Fraction) -> Unit = { _, _ -> },
    val changeOscillations: (EventId, Int) -> Unit = { _, _ -> },
    val changeTrillPlaybackMode: (EventId, TrillPlaybackMode) -> Unit = { _, _ -> },
)

internal data class GraceGroupPropertiesActions(
    val changeGroup: (EventId, Duration, GraceTimeSource) -> Unit = { _, _, _ -> },
)

internal data class BarlinePropertiesActions(
    val changeRepeatCount: (Int, Int) -> Unit = { _, _ -> },
)

internal data class StaffVisibilityPropertiesActions(
    val revealStaff: (List<TrackId>, MeasureRange) -> Unit = { _, _ -> },
)

internal data class CurvePropertiesActions(
    val changeSlurDirection: (Boolean) -> Unit = {},
    val changeTieDirection: (Boolean) -> Unit = {},
)

internal data class SelectionInspectorActions(
    val delete: () -> Unit = {},
    val tempo: TempoPropertiesActions = TempoPropertiesActions(),
    val performance: PerformancePropertiesActions = PerformancePropertiesActions(),
    val graceGroup: GraceGroupPropertiesActions = GraceGroupPropertiesActions(),
    val barline: BarlinePropertiesActions = BarlinePropertiesActions(),
    val staffVisibility: StaffVisibilityPropertiesActions = StaffVisibilityPropertiesActions(),
    val curves: CurvePropertiesActions = CurvePropertiesActions(),
    val ornaments: OrnamentPropertiesActions = OrnamentPropertiesActions(),
)

internal enum class InspectorDeletePolicy {
    DEFAULT,
    DENY,
}

internal interface SelectionPropertyContributor {
    val exclusive: Boolean get() = false

    fun isApplicable(context: SelectionInspectorContext): Boolean

    fun deletePolicy(context: SelectionInspectorContext): InspectorDeletePolicy =
        InspectorDeletePolicy.DEFAULT

    @Composable
    fun Content(context: SelectionInspectorContext)
}
