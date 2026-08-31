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
import com.mecon.renderer.render.spatial.SystemNode

internal data class AuditionTarget(
    val event: ComputedVoiceEvent,
    val soundingPitchIndices: Set<Int>?,
    val transposedPitchIndices: Set<Int>? = soundingPitchIndices,
)

internal fun EventSection.auditionTarget(): AuditionTarget? = when (this) {
    is VoiceNoteSection -> event.takeUnless { it.isRest }
        ?.let { AuditionTarget(it, setOf(pitchIndex)) }
    is VoiceStemSection -> event.takeUnless { it.isRest }
        ?.let { AuditionTarget(it, null) }
    else -> null
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
 * system one row beyond the one visually under the pointer. In horizontal pagination the owning
 * page column also participates, because systems on adjacent pages can share the same displayed Y.
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
): Int? = nearestDisplayedSystem(
    result, raw, offset, scale, density, paginated, pages, slots,
)

/**
 * Resolve an analysis-range drag row by containment in the whole laid-out system range.
 *
 * Tonal-region lanes live above the five-line core. A row changes only after the pointer actually
 * enters another system's complete occupied band; while it is in the gap, [preferredSystemIndex]
 * remains selected. Overlapping bands also prefer the current row. Other score handles continue to
 * use nearest-core semantics through [nearestDisplayedSystemByStaffCore].
 */
internal fun nearestDisplayedSystemByFullRange(
    result: RenderResult,
    raw: Offset,
    offset: Offset,
    scale: Float,
    density: Float,
    paginated: Boolean,
    pages: List<com.mecon.renderer.render.RenderPage>,
    slots: List<Offset>,
    preferredSystemIndex: Int? = null,
): Int? {
    val candidates = result.spatialIndex.allSystems().mapNotNull { system ->
        fun displayedDesignY(relativeY: Float): Float? {
            val globalY = result.transformerSnapshot.toAbsolute(
                RelativePoint(StaffSpace.ZERO, StaffSpace(relativeY))
            ).y.value
            val designY = if (paginated) {
                globalToDesign(0f, globalY, pages, slots)?.y
            } else {
                globalY
            }
            return designY?.let { it * density * scale + offset.y }
        }

        val rawTop = displayedDesignY(system.topY.value) ?: return@mapNotNull null
        val rawBottom = displayedDesignY(system.bottomY.value) ?: return@mapNotNull null
        if (raw.y !in rawTop..rawBottom) return@mapNotNull null

        if (paginated) {
            val centerGlobalY = result.transformerSnapshot.toAbsolute(
                RelativePoint(
                    StaffSpace.ZERO,
                    StaffSpace((system.topY.value + system.bottomY.value) / 2f),
                )
            ).y.value
            val pageIndex = pages.indices.firstOrNull { index ->
                centerGlobalY in pages[index].contentOffsetY.value..(
                    pages[index].contentOffsetY.value + pages[index].height.value
                )
            } ?: return@mapNotNull null
            val rawLeft = slots[pageIndex].x * density * scale + offset.x
            val rawRight = (slots[pageIndex].x + pages[pageIndex].width.value) *
                density * scale + offset.x
            if (raw.x !in rawLeft..rawRight) return@mapNotNull null
        }

        val rawCenter = (rawTop + rawBottom) / 2f
        system to kotlin.math.abs(raw.y - rawCenter)
    }

    return candidates.firstOrNull { it.first.systemIndex == preferredSystemIndex }
        ?.first
        ?.systemIndex
        ?: candidates.minByOrNull { it.second }?.first?.systemIndex
        ?: preferredSystemIndex?.takeIf { preferred ->
            result.spatialIndex.allSystems().any { it.systemIndex == preferred }
        }
        ?: nearestDisplayedSystemByStaffCore(
            result, raw, offset, scale, density, paginated, pages, slots,
        )
}

private fun nearestDisplayedSystem(
    result: RenderResult,
    raw: Offset,
    offset: Offset,
    scale: Float,
    density: Float,
    paginated: Boolean,
    pages: List<com.mecon.renderer.render.RenderPage>,
    slots: List<Offset>,
): Int? {
    fun distance(system: SystemNode): Float {
        val rangeTop = system.staffRegions.minOfOrNull { it.centerY.value - 2f }
            ?: system.topY.value
        val rangeBottom = system.staffRegions.maxOfOrNull { it.centerY.value + 2f }
            ?: system.bottomY.value
        fun displayedDesign(relativeY: Float): Offset? {
            val absoluteY = result.transformerSnapshot.toAbsolute(
                RelativePoint(StaffSpace.ZERO, StaffSpace(relativeY))
            ).y.value
            return if (paginated) {
                globalToDesign(0f, absoluteY, pages, slots)
            } else {
                Offset(0f, absoluteY)
            }
        }
        val designTop = displayedDesign(rangeTop)
            ?: return Float.POSITIVE_INFINITY
        val designBottom = displayedDesign(rangeBottom)
            ?: return Float.POSITIVE_INFINITY
        val rawTop = designTop.y * density * scale + offset.y
        val rawBottom = designBottom.y * density * scale + offset.y
        val dy = when {
            raw.y < rawTop -> rawTop - raw.y
            raw.y > rawBottom -> raw.y - rawBottom
            else -> 0f
        }
        if (!paginated) return dy

        // Two pages may be arranged side by side, so their systems can have the same displayed Y.
        // Include the owning page's horizontal band to keep a pointer on page 2 from resolving to
        // the same-height system on page 1.
        val rangeGlobalY = result.transformerSnapshot.toAbsolute(
            RelativePoint(StaffSpace.ZERO, StaffSpace(rangeTop))
        ).y.value
        val pageIndex = pages.indices.firstOrNull { index ->
            rangeGlobalY in pages[index].contentOffsetY.value..(
                pages[index].contentOffsetY.value + pages[index].height.value
            )
        } ?: return Float.POSITIVE_INFINITY
        val rawLeft = slots[pageIndex].x * density * scale + offset.x
        val rawRight = (slots[pageIndex].x + pages[pageIndex].width.value) *
            density * scale + offset.x
        val dx = when {
            raw.x < rawLeft -> rawLeft - raw.x
            raw.x > rawRight -> raw.x - rawRight
            else -> 0f
        }
        return dx * dx + dy * dy
    }

    return result.spatialIndex.allSystems().minByOrNull(::distance)?.systemIndex
}

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
