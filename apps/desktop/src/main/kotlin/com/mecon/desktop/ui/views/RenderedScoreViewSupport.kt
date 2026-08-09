package com.mecon.desktop.ui.views

import com.mecon.desktop.voiceTrackIdOf
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import com.mecon.desktop.uikit.components.MeconDropdownItem
import com.mecon.desktop.uikit.components.MeconDropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DiatonicTranspose
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.PageArrangement
import com.mecon.api.storage.tracks.MeasureRange
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.uikit.i18n.i18n
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.api.render.RenderColor
import com.mecon.api.interaction.*
import com.mecon.renderer.geometry.*
import com.mecon.renderer.render.*

internal data class AuditionTarget(
    val event: ComputedVoiceEvent,
    val soundingPitchIndices: Set<Int>?,
    val transposedPitchIndices: Set<Int>? = soundingPitchIndices,
)

/**
 * In-progress drag state, shared by note-transpose and rest-move gestures: both hide their originals,
 * show a re-engraved preview, and hold it until the committed re-render lands (the lifecycle below is
 * kind-agnostic). [transposeTargets] drives a note transpose; [restMove] (non-null) drives a rest move.
 */
internal data class TransposeDragState(
    val previewTargets: Map<EventId, Set<Int>?>,
    val transposeTargets: List<NoteEditEngine.TransposeTarget>,
    /** The glyph that started the drag; unrelated selected events are never added to its audition. */
    val auditionTarget: AuditionTarget? = null,
    /** Last effective (range-clamped) delta sounded, so pinned edge pitches are not retriggered. */
    val auditionStepDelta: Int = 0,
    /** Non-null when this drag moves rest(s) rather than transposing notes. */
    val restMove: RestMoveInfo? = null,
    /** For a transpose: diatonic step delta. For a rest move: staff-position step delta (+up). */
    val stepDelta: Int = 0,
    /** Current re-engraved preview (two colour layers), or null when there is nothing to show
     *  (delta 0, or clamped to 0 at the MIDI-range edge). Null ⇒ the originals stay visible. */
    val preview: com.mecon.renderer.render.edit.TransposePreview? = null,
    /** True after the mouse is released: the preview is held (originals still hidden, interaction
     *  blocked) until the committed re-render lands, so the score never flashes the pre-drag notes. */
    val committing: Boolean = false,
    /** Render frame displayed at mouse-up. Captured before invoking the edit callback so a fast commit
     *  cannot race ahead of the effect that waits for its replacement. Compared by identity. */
    val commitBaseline: RenderResult? = null,
    /** Monotonic mouse-up time used only by the opt-in hand-off performance probe. */
    val commitStartedAtNanos: Long = 0L,
)

internal fun EventSection.auditionTarget(): AuditionTarget? = when (this) {
    is VoiceNoteSection -> event.takeUnless { it.isRest }
        ?.let { AuditionTarget(it, setOf(pitchIndex)) }
    is VoiceStemSection -> event.takeUnless { it.isRest }
        ?.let { AuditionTarget(it, null) }
    else -> null
}

/** A drag edits one event: sound its whole resulting chord, transposing only the moved pitches. */
internal fun EventSection.dragAuditionTarget(
    targets: List<NoteEditEngine.TransposeTarget>,
): AuditionTarget? {
    val event = when (this) {
        is VoiceNoteSection -> event
        is VoiceEventSection -> event
        else -> return null
    }.takeUnless { it.isRest } ?: return null
    val target = targets.firstOrNull { it.eventId == event.id } ?: return null
    return AuditionTarget(event, soundingPitchIndices = null, transposedPitchIndices = target.pitchIndices)
}

/** Mirrors the renderer/edit engine clamp so drag audition follows the pitch actually on screen. */
internal fun clampTransposeDelta(
    runtime: RuntimeScore,
    computed: ComputedScore,
    targets: Map<EventId, Set<Int>?>,
    requested: Int,
): Int {
    val moved = buildList {
        for ((eventId, pitchIndices) in targets) {
            val event = computed.getComputedEvent(eventId) ?: continue
            val key = runtime.getKeySignatureAt(event.onset.measure)
            val indices = pitchIndices?.takeIf { it.isNotEmpty() }
                ?.filter { it in event.pitchData.indices }
                ?: event.pitchData.indices.toList()
            indices.forEach { add(event.pitchData[it].pitch to key) }
        }
    }
    return DiatonicTranspose.clampDelta(moved, requested)
}

/** The non-rest computed event a section refers to, or null for rests / non-note sections. */
internal fun EventSection.movableEvent(): ComputedVoiceEvent? = when (this) {
    is VoiceNoteSection -> event.takeUnless { it.isRest }
    is VoiceEventSection -> event.takeUnless { it.isRest }
    else -> null
}

/** The rest computed event a section refers to (whole-event selection of a rest), or null otherwise. */
internal fun EventSection.restEvent(): ComputedVoiceEvent? = when (this) {
    is VoiceEventSection -> event.takeIf { it.isRest }
    else -> null
}

/**
 * Aggregate the rest events in [sections] into per-rest move info: each rest's current effective
 * staff position (override, else type default) and its type default. Mirrors [buildTransposeTargets]
 * for notes. Non-rest / non-event sections are skipped.
 */
internal fun buildRestMoveInfo(
    sections: Set<EventSection>,
    runtime: RuntimeScore?,
): RestMoveInfo {
    if (runtime == null) return RestMoveInfo(emptyList())
    val targets = LinkedHashMap<EventId, RestTargetInfo>()
    for (section in sections) {
        val event = section.restEvent() ?: continue
        if (event.id in targets) continue
        val voiceTrackId = runtime.voiceTrackIdOf(event.id) ?: continue
        val default = com.mecon.renderer.layout.RestLayout.defaultRestStaffPosition(event.duration)
        val start = event.rendering?.restStaffPosition ?: default
        targets[event.id] = RestTargetInfo(voiceTrackId, event.id, start, default)
    }
    return RestMoveInfo(targets.values.toList())
}

/**
 * The sections that should move when a transpose drag grabs [picked]:
 * - [picked] is already part of the selection → the whole selection moves;
 * - shift held → the selection plus the grabbed note;
 * - otherwise → just the grabbed note.
 */
internal fun resolveMoveSections(
    picked: EventSection,
    selection: Set<EventSection>,
    shiftHeld: Boolean,
): Set<EventSection> = when {
    picked in selection -> selection
    shiftHeld -> selection + picked
    else -> setOf(picked)
}

/**
 * Aggregate [sections] into transpose targets, one per note event: a whole-event section moves every
 * pitch (`pitchIndices = null`); individual notehead sections move only their pitch. Mirrors
 * `App.buildDeletions`. Rests and non-note sections are skipped.
 */
internal fun buildTransposeTargets(
    sections: Set<EventSection>,
    runtime: RuntimeScore?,
): List<NoteEditEngine.TransposeTarget> {
    if (runtime == null) return emptyList()
    class Acc { var whole = false; val pitches = mutableSetOf<Int>() }
    val byEvent = LinkedHashMap<EventId, Acc>()
    for (section in sections) {
        when (section) {
            is VoiceNoteSection -> if (!section.event.isRest)
                byEvent.getOrPut(section.event.id) { Acc() }.pitches.add(section.pitchIndex)
            is VoiceEventSection -> if (!section.event.isRest)
                byEvent.getOrPut(section.event.id) { Acc() }.whole = true
            else -> {}
        }
    }
    return byEvent.mapNotNull { (eventId, acc) ->
        val voiceTrackId = runtime.voiceTrackIdOf(eventId) ?: return@mapNotNull null
        NoteEditEngine.TransposeTarget(
            voiceTrackId = voiceTrackId,
            eventId = eventId,
            pitchIndices = if (acc.whole) null else acc.pitches,
        )
    }
}

/**
 * Map a raw pointer position to the absolute (global) render point the hit index uses, undoing pan
 * [offset], [scale] and [density]; in paginated mode the design point is mapped through the page grid
 * ([designToGlobal]). Returns null when the point lies outside every page (paginated only).
 */
internal fun rawToAbsolutePoint(
    raw: Offset,
    offset: Offset,
    scale: Float,
    density: Float,
    paginated: Boolean,
    pages: List<com.mecon.renderer.render.RenderPage>,
    slots: List<Offset>,
): AbsolutePoint? {
    val designX = (raw.x - offset.x) / scale / density
    val designY = (raw.y - offset.y) / scale / density
    return if (paginated) designToGlobal(Offset(designX, designY), pages, slots)
    else AbsolutePoint(Pixels(designX), Pixels(designY))
}

/**
 * Resolve the visual system nearest to the raw pointer from its five-line staff cores.
 *
 * Do not use SystemNode.topY/bottomY here: those bands expand to include ledger
 * notes and attachments and can overlap the next system, making a dragged span
 * or navigation mark snap to a barline on the wrong row. Compare in the final
 * displayed coordinate space as well: converting a paginated pointer to global
 * score Y and back through a changing page/system layout can otherwise select a
 * system one row beyond the one visually under the pointer.
 */
internal fun nearestDisplayedSystemByStaffCore(
    result: RenderResult,
    raw: Offset,
    offset: Offset,
    scale: Float,
    density: Float,
    paginated: Boolean,
    pages: List<com.mecon.renderer.render.RenderPage>,
    slots: List<Offset>,
): Int? =
    result.spatialIndex.allSystems().minByOrNull { system ->
        val coreTop = system.staffRegions.minOfOrNull { it.centerY.value - 2f }
            ?: system.topY.value
        val coreBottom = system.staffRegions.maxOfOrNull { it.centerY.value + 2f }
            ?: system.bottomY.value
        fun displayedRawY(relativeY: Float): Float? {
            val globalY = result.transformerSnapshot.toAbsolute(
                RelativePoint(StaffSpace.ZERO, StaffSpace(relativeY))
            ).y.value
            val designY = if (paginated) {
                globalToDesign(0f, globalY, pages, slots)?.y
            } else globalY
            return designY?.let { it * density * scale + offset.y }
        }
        val rawTop = displayedRawY(coreTop) ?: return@minByOrNull Float.POSITIVE_INFINITY
        val rawBottom = displayedRawY(coreBottom) ?: return@minByOrNull Float.POSITIVE_INFINITY
        when {
            raw.y < rawTop -> rawTop - raw.y
            raw.y > rawBottom -> raw.y - rawBottom
            else -> 0f
        }
    }?.systemIndex

/**
 * Navigation marks use the top staff's upper line minus 0.85 spaces as their
 * default bottom-edge anchor (matching StructuralElementRenderer).
 */
internal fun navigationSystemAnchorY(result: RenderResult, systemIndex: Int): Float? {
    val system = result.spatialIndex.allSystems().firstOrNull {
        it.systemIndex == systemIndex
    } ?: return null
    val topStaffLine = system.staffRegions.minOfOrNull { it.centerY.value - 2f } ?: return null
    return topStaffLine - 0.85f
}

/** One staff's reveal options in the hidden-staff context menu. */
internal data class HiddenStaffMenuOption(
    val staffName: String,
    val staffId: TrackId,
    /** The measure span of the line (system) the click landed on, for the "this line" action. */
    val lineRange: MeasureRange,
    /** Non-null when the click landed on a grey (partially-hidden) cell → enables "reveal this region". */
    val partialMeasure: Int?,
)

/**
 * Resolve the reveal-staff menu for a right click at [point]: a hidden dashed line (fully-hidden staves)
 * lists each collapsed staff; a grey cell (partial hide) lists that one staff plus a "reveal this region"
 * action. Null when the click is not over any hidden area.
 */
internal fun buildHiddenStaffMenu(
    result: RenderResult,
    score: RuntimeScore?,
    point: AbsolutePoint,
): List<HiddenStaffMenuOption>? {
    score ?: return null
    fun name(staff: com.mecon.api.runtime.tracks.RuntimeStaffTrack): String =
        staff.staffLabel?.takeIf { it.isNotBlank() } ?: staff.name

    val hiddenSection = result.hitTest(point).allSections()
        .filterIsInstance<HiddenStaffSection>().firstOrNull()
    if (hiddenSection != null) {
        return hiddenSection.staffTrackIds.mapNotNull { id ->
            val staff = score.staffTracks[id] ?: return@mapNotNull null
            HiddenStaffMenuOption(name(staff), id, hiddenSection.range, partialMeasure = null)
        }.ifEmpty { null }
    }

    val cell = result.measureStaffAt(point) ?: return null
    val staffId = score.orderedStaffs().getOrNull(cell.second)?.id ?: return null
    val staff = score.staffTracks[staffId] ?: return null
    if (!staff.isHidden(cell.first)) return null
    val systemIndex = result.measureBounds.firstOrNull { it.measureNumber == cell.first }?.systemIndex
    val lineMeasures = result.measureBounds.filter { it.systemIndex == systemIndex }.map { it.measureNumber }
    val lineRange = if (lineMeasures.isEmpty()) MeasureRange(cell.first, cell.first)
    else MeasureRange(lineMeasures.min(), lineMeasures.max())
    return listOf(HiddenStaffMenuOption(name(staff), staffId, lineRange, partialMeasure = cell.first))
}

/** Two-level reveal menu: level 1 = staff name, level 2 = this line / all following (+ this region for grey). */
@Composable
internal fun HiddenStaffContextMenu(
    options: List<HiddenStaffMenuOption>,
    maxMeasure: Int,
    onReveal: (List<TrackId>, MeasureRange) -> Unit,
    onDismiss: () -> Unit,
) {
    MeconDropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        options.forEach { opt ->
            if (options.size > 1) {
                Text(
                    opt.staffName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MeconColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            MeconDropdownItem(
                label = i18n("menu.showStaffThisLine"),
                onClick = { onReveal(listOf(opt.staffId), opt.lineRange) },
            )
            MeconDropdownItem(
                label = i18n("menu.showStaffFollowing"),
                onClick = {
                    onReveal(
                        listOf(opt.staffId),
                        MeasureRange(opt.lineRange.from, maxMeasure.coerceAtLeast(opt.lineRange.from)),
                    )
                },
            )
            if (opt.partialMeasure != null) {
                MeconDropdownItem(
                    label = i18n("menu.revealRegion"),
                    onClick = { onReveal(listOf(opt.staffId), MeasureRange(opt.partialMeasure, opt.partialMeasure)) },
                )
            }
        }
    }
}

@Composable
internal fun HiddenStaffMenuHost(
    options: List<HiddenStaffMenuOption>?,
    position: Offset,
    maxMeasure: Int,
    onReveal: (List<TrackId>, MeasureRange) -> Unit,
    onDismiss: () -> Unit,
) {
    options ?: return
    Box(modifier = Modifier.offset {
        IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }) {
        HiddenStaffContextMenu(
            options = options,
            maxMeasure = maxMeasure,
            onReveal = onReveal,
            onDismiss = onDismiss,
        )
    }
}

/** Gap (design px) between adjacent pages when laid out. */
internal const val PAGE_GAP_DESIGN = 24f

/**
 * Compute the design-space top-left slot for each page, in the chosen [arrangement]:
 * VERTICAL stacks pages top-to-bottom, HORIZONTAL lays them left-to-right, each separated
 * by [PAGE_GAP_DESIGN].
 */
internal fun pageSlotOffsets(
    pages: List<com.mecon.renderer.render.RenderPage>,
    arrangement: PageArrangement
): List<Offset> {
    val slots = ArrayList<Offset>(pages.size)
    var cursor = 0f
    for (p in pages) {
        when (arrangement) {
            PageArrangement.VERTICAL -> {
                slots.add(Offset(0f, cursor))
                cursor += p.height.value + PAGE_GAP_DESIGN
            }
            PageArrangement.HORIZONTAL -> {
                slots.add(Offset(cursor, 0f))
                cursor += p.width.value + PAGE_GAP_DESIGN
            }
        }
    }
    return slots
}

/**
 * Map a canvas design-space point (in the laid-out page grid) back to the global score
 * coordinate used by the hit-test index. Returns null when the point lies outside every page.
 * Page-local X equals global X (only Y was shifted during page slicing), so X maps directly.
 */
internal fun designToGlobal(
    point: Offset,
    pages: List<com.mecon.renderer.render.RenderPage>,
    slots: List<Offset>
): AbsolutePoint? {
    for (i in pages.indices) {
        val s = slots[i]
        val p = pages[i]
        if (point.x >= s.x && point.x <= s.x + p.width.value &&
            point.y >= s.y && point.y <= s.y + p.height.value
        ) {
            val localX = point.x - s.x
            val localY = point.y - s.y
            return AbsolutePoint(Pixels(localX), Pixels(localY + p.contentOffsetY.value))
        }
    }
    return null
}

/**
 * Map a global score point to canvas design-space (the laid-out page grid). Returns null when
 * no page owns the point's global Y band. Inverse of [designToGlobal].
 */
internal fun globalToDesign(
    globalX: Float,
    globalY: Float,
    pages: List<com.mecon.renderer.render.RenderPage>,
    slots: List<Offset>
): Offset? {
    for (i in pages.indices) {
        val p = pages[i]
        val top = p.contentOffsetY.value
        val bottom = top + p.height.value
        if (globalY in top..bottom) {
            val s = slots[i]
            return Offset(s.x + globalX, s.y + (globalY - top))
        }
    }
    return null
}

private fun Color.toRenderColor(): RenderColor = RenderColor.rgb(
    (red * 255f).roundToInt(),
    (green * 255f).roundToInt(),
    (blue * 255f).roundToInt(),
)

internal fun voiceSelectionRenderColor(voiceNumber: Int): RenderColor =
    MeconColors.voiceSelectionColor(voiceNumber).toRenderColor()

private fun RuntimeScore.voiceNumberOf(event: ComputedVoiceEvent): Int? =
    event.originVoiceTrackId?.let { voiceTracks[it]?.voiceNumber }
        ?: voiceTracks.values.firstOrNull { voice ->
            voice.events.toList().any { it.id == event.id }
        }?.voiceNumber

internal fun EventSection.voiceNumber(score: RuntimeScore?): Int? = when (this) {
    is VoiceNoteSection -> score?.voiceNumberOf(event)
    is VoiceStemSection -> score?.voiceNumberOf(event)
    is VoiceFlagSection -> score?.voiceNumberOf(event)
    is VoiceBeamSection -> events.firstOrNull()?.let { score?.voiceNumberOf(it) }
    is VoiceEventSection -> score?.voiceNumberOf(event)
    is VoiceArticulationSection -> score?.voiceNumberOf(event)
    is VoiceTupletSection -> score?.voiceNumberOf(startEvent)
    is VoiceTieSection -> score?.voiceNumberOf(sourceEvent)
    is VoiceSlurSection -> score?.voiceNumberOf(startEvent)
    is StaffAttachmentSection -> attachment.voiceNumber
    else -> null
}
