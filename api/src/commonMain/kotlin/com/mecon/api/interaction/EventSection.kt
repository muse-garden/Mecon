package com.mecon.api.interaction

import com.mecon.api.computed.*
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.MeasureRange

/**
 * Fine-grained picking types for score elements.
 *
 * Each EventSection identifies a specific sub-part of a rendered score element,
 * enabling precise interaction (e.g., clicking a specific notehead in a chord
 * vs. the stem vs. the beam group).
 *
 * EventSections are built during rendering by [SectionIndexBuilder] and stored
 * in [SectionIndex] as part of [com.mecon.renderer.render.RenderResult].
 *
 * Each section has a stable numeric [id] used by indexes and style lookups. [sectionId] is generated
 * only for diagnostics and UI display.
 */
sealed interface EventSection {
    /** Stable numeric identity used by all runtime queries. */
    val id: EventSectionId

    /** Human-readable identifier for diagnostics and UI display only. */
    val sectionId: String
}

/**
 * A specific notehead within a voice event (chord).
 *
 * @property event The computed voice event
 * @property pitchIndex Index into [event.pitchData] identifying which pitch
 */
data class VoiceNoteSection(
    val event: ComputedVoiceEvent,
    val pitchIndex: Int
) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceNote(event.id, pitchIndex)
    override val sectionId: String get() = "${event.id.value}:notehead:$pitchIndex"
    val pitchData: ComputedPitchData get() = event.pitchData[pitchIndex]
}

/**
 * The stem of a voice event.
 */
data class VoiceStemSection(val event: ComputedVoiceEvent) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceStem(event.id)
    override val sectionId: String get() = "${event.id.value}:stem"
}

/**
 * The flag of a voice event (unbeamed 8th notes and shorter).
 */
data class VoiceFlagSection(val event: ComputedVoiceEvent) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceFlag(event.id)
    override val sectionId: String get() = "${event.id.value}:flag"
}

/**
 * A beam group connecting multiple notes.
 *
 * @property events All voice events in this beam group
 * @property groupId Beam group identifier
 */
data class VoiceBeamSection(
    val events: List<ComputedVoiceEvent>,
    val groupId: BeamGroupId
) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceBeam(groupId.value)
    override val sectionId: String get() = "beam:${groupId.value}"
}

/**
 * An entire voice event (notehead + stem + flag + accidentals + dots + ledger lines).
 */
data class VoiceEventSection(val event: ComputedVoiceEvent) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceEvent(event.id)
    override val sectionId: String get() = "${event.id.value}:event"
}

/**
 * A single articulation mark on a voice event.
 *
 * @property event The computed voice event
 * @property index Index into [event.articulations] identifying which mark
 */
data class VoiceArticulationSection(
    val event: ComputedVoiceEvent,
    val index: Int,
    /** Displayed mark kind; lets adapters distinguish a projected fermata from stored articulations. */
    val articulation: com.mecon.api.storage.Articulation? = null,
) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceArticulation(event.id, index)
    override val sectionId: String get() = "${event.id.value}:artic:$index"
}

/**
 * The tuplet bracket / slur / number attached to a tuplet's starting event.
 */
data class VoiceTupletSection(val startEvent: ComputedVoiceEvent) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceTuplet(startEvent.id)
    override val sectionId: String get() = "${startEvent.id.value}:tuplet"
}

/** A tie beginning at one pitch of a voice event. */
data class VoiceTieSection(
    val sourceEvent: ComputedVoiceEvent,
    val sourcePitchIndex: Int,
    val targetEvent: ComputedVoiceEvent? = null,
) : EventSection {
    override val id: EventSectionId = EventSectionId.voiceTie(sourceEvent.id, sourcePitchIndex)
    override val sectionId: String get() = "tie:${sourceEvent.id.value}:$sourcePitchIndex"
}

/**
 * A phrasing slur identified by its start event. Nested slurs sharing a start
 * event are disambiguated by [nestingLevel].
 */
data class VoiceSlurSection(
    val startEvent: ComputedVoiceEvent,
    val endEvent: ComputedVoiceEvent,
    val nestingLevel: Int,
    val slurId: com.mecon.api.primitive.EventId? = null,
) : EventSection {
    override val id: EventSectionId =
        EventSectionId.voiceSlur(startEvent.id, endEvent.id, nestingLevel, slurId)
    override val sectionId: String get() =
        "slur:${startEvent.id.value}->${endEvent.id.value}:$nestingLevel"
}

/**
 * A staff attachment (dynamic mark or hairpin).
 */
data class StaffAttachmentSection(val attachment: ComputedStaffAttachment) : EventSection {
    override val id: EventSectionId = EventSectionId.staffAttachment(attachment.id)
    override val sectionId: String get() = "attachment:${attachment.id.value}"
}

/**
 * A barline.
 */
enum class BarlineVisualPlacement { INLINE, SYSTEM_END, SYSTEM_START }

data class BarlineSection(
    val barline: ComputedBarline,
    val systemIndex: Int? = null,
    val visualPlacement: BarlineVisualPlacement = BarlineVisualPlacement.INLINE,
) : EventSection {
    override val id: EventSectionId = EventSectionId.barline(barline.measureNumber, barline.time)
    override val sectionId: String get() = "barline:m${barline.measureNumber}:${barline.time.format()}"
}

/** A selectable first/second-ending bracket. */
data class VoltaEndingSection(
    val ending: ComputedVoltaEnding,
) : EventSection {
    override val id: EventSectionId =
        EventSectionId.volta(ending.startMeasure, ending.endMeasure, ending.numbers)
    override val sectionId: String =
        "volta:${ending.numbers.sorted().joinToString(",")}:m${ending.startMeasure}-${ending.endMeasure}"
}

/** A selectable score-navigation sign or jump instruction. */
data class NavigationMarkSection(
    val navigation: com.mecon.api.computed.ComputedNavigationMark,
) : EventSection {
    override val id: EventSectionId =
        EventSectionId.navigationMark(navigation.boundaryMeasure, navigation.mark)
    override val sectionId: String =
        "navigation:${navigation.mark.name.lowercase()}:m${navigation.boundaryMeasure}"
}

/** A non-spacing editor entry for a forced system/page boundary. */
enum class LayoutBreakKind { SYSTEM, PAGE }

data class LayoutBreakSection(
    /** The first measure after the forced boundary. */
    val beforeMeasure: Int,
    val kind: LayoutBreakKind,
) : EventSection {
    override val id: EventSectionId = EventSectionId.layoutBreak(beforeMeasure, kind)
    override val sectionId: String get() = "layout-break:${kind.name.lowercase()}:m$beforeMeasure"
}

/**
 * A fully-hidden staff span on one system (line), rendered as a collapsed dashed
 * line (see `HiddenStaffMarker`). Consecutive hidden staves merge into one marker,
 * so [staffTrackIds] may list several staves. Selecting it drives the show-staff
 * actions (this line / all following lines) in the context menu and property panel.
 */
data class HiddenStaffSection(
    val systemIndex: Int,
    val staffTrackIds: List<TrackId>,
    val range: MeasureRange,
) : EventSection {
    override val id: EventSectionId =
        EventSectionId.hiddenStaff(systemIndex, staffTrackIds, range.from, range.to)
    override val sectionId: String get() =
        "hidden-staff:sys$systemIndex:${staffTrackIds.joinToString("+") { it.value }}:m${range.from}-${range.to}"
}

/**
 * A complete measure on one staff, selected by clicking the staff background.
 *
 * It is intentionally an interaction-only section: the score model remains
 * unchanged, while the desktop editor expands this into that staff's voice
 * events for copy/cut/delete and paints the cell background itself.
 */
data class MeasureStaffSection(
    val staffTrackId: TrackId,
    val measureNumber: Int,
) : EventSection {
    override val id: EventSectionId = EventSectionId.measureStaff(staffTrackId, measureNumber)
    override val sectionId: String get() = "measure:${staffTrackId.value}:$measureNumber"
}

/**
 * A clef.
 */
data class ClefSection(val clef: ComputedClef) : EventSection {
    override val id: EventSectionId = EventSectionId.clef(clef.staffTrackId, clef.time)
    override val sectionId: String get() = "clef:${clef.staffTrackId.value}:${clef.time.format()}"
}

/**
 * A key signature.
 */
data class KeySignatureSection(val keySignature: ComputedKeySignature) : EventSection {
    override val id: EventSectionId = EventSectionId.keySignature(keySignature.staffTrackId, keySignature.time)
    override val sectionId: String get() = "keysig:${keySignature.staffTrackId.value}:${keySignature.time.format()}"
}

/**
 * A time signature.
 */
data class TimeSignatureSection(val timeSignature: ComputedTimeSignature) : EventSection {
    override val id: EventSectionId = EventSectionId.timeSignature(timeSignature.staffTrackId, timeSignature.time)
    override val sectionId: String get() = "timesig:${timeSignature.staffTrackId.value}:${timeSignature.time.format()}"
}
