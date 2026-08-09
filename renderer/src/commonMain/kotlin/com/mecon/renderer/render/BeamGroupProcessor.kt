package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.api.computed.BeamGroupId
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.CrossStaffBeamBase
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.NoteScale
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.*
import com.mecon.renderer.layout.stem.BeamLayoutComputer
import com.mecon.renderer.layout.stem.BeamNoteInput
import com.mecon.renderer.layout.stem.BeamLayoutResult
import com.mecon.renderer.smufl.BravuraFont

/**
 * Processes beam groups: computes stem adjustments and collects beam render data.
 *
 * This processor handles the beam group computation pass that:
 * 1. Identifies beam groups from voice event layouts
 * 2. Computes unified stem tip positions for each group
 * 3. Returns stem adjustments and beam render data for the beam renderer
 */
internal class BeamGroupProcessor(
    private val config: RenderLayoutConfig
) {
    /**
     * Data collected for rendering a single beam group.
     */
    data class BeamGroupRenderData(
        val beamNoteInfos: List<BeamNoteInfo>,
        val stemDirection: StemDirection,
        val staffIndex: Int,
        val noteScale: NoteScale = NoteScale.NORMAL,
        /**
         * Measure of the beam group (beams never cross a barline, so all notes share it).
         * Lets a measure-windowed splice regenerate only the affected beams. -1 if unknown.
         */
        val measureNumber: Int = -1,
        val geometry: BeamGeometry? = null,
    )

    /**
     * Result of beam group processing.
     */
    data class BeamProcessingResult(
        val stemAdjustments: Map<EventId, StaffSpace>,
        val beamGroupRenderData: List<BeamGroupRenderData>,
        /** Direction overrides produced when a manually moved beam crosses its notes. */
        val stemDirections: Map<EventId, StemDirection> = emptyMap(),
    )

    /**
     * Process all beam groups in the layout result.
     *
     * @param layoutResult The unified layout result
     * @param staffLayoutByIndex Staff layouts indexed by staff index
     * @param measureFilter When non-null, only groups whose measure falls in this range are processed;
     *   the rest are skipped. Beams never cross a barline (a group is wholly in one measure) and each
     *   group's [BeamGroupRenderData]/[stemAdjustments] only feed elements in that same measure — which
     *   the caller ([FullScoreRenderer]) already restricts to this window (stems via `voiceFilter`, beam
     *   render data via the same measure check). So a per-page / per-system render (streaming, Phase 3)
     *   can skip the geometry work for out-of-window groups without changing the emitted output. Groups
     *   with an unknown measure (-1) are always processed.
     * @param groupIds Optional pre-windowed group directory. Paginated incremental rendering already
     *   knows which groups occur in the regenerated systems; supplying it avoids enumerating the full
     *   score's beam-group index merely to reject almost every group by [measureFilter].
     * @return Processing result containing stem adjustments and beam render data
     */
    context(BravuraFont)
    fun processBeamGroups(
        layoutResult: UnifiedLayoutResult,
        staffLayoutByIndex: Map<Int, StaffLayoutInfo>,
        measureFilter: IntRange? = null,
        groupIds: Iterable<BeamGroupId>? = null,
        geometry: Map<String, BeamGeometry> = emptyMap(),
    ): BeamProcessingResult {
        val stemAdjustments = mutableMapOf<EventId, StaffSpace>()
        val stemDirections = mutableMapOf<EventId, StemDirection>()
        val beamComputer = BeamLayoutComputer(config.beamLayoutConfig)
        val beamGroupRenderData = mutableListOf<BeamGroupRenderData>()

        // Global pass: enumerate beam groups (via the pre-built index) and render each in
        // isolation. The per-group logic lives in renderBeamGroup so editing can re-render a
        // single group (found from an edited note's BeamGroupId) without touching the rest.
        for (groupId in groupIds ?: layoutResult.voiceEventLayouts.beamGroupIds()) {
            val notesInGroup = layoutResult.voiceEventLayouts.forBeamGroup(groupId)
            if (measureFilter != null) {
                val groupMeasure = notesInGroup.firstOrNull()?.measureNumber ?: -1
                if (groupMeasure != -1 && groupMeasure !in measureFilter) continue
            }
            val data = renderBeamGroup(
                notesInGroup, layoutResult, staffLayoutByIndex, beamComputer, stemAdjustments,
                geometry[groupId.value], stemDirections
            )
            if (data != null) beamGroupRenderData.add(data)
        }

        return BeamProcessingResult(stemAdjustments, beamGroupRenderData, stemDirections)
    }

    /**
     * Render a single beam group: resolve its notes' stem tips, write the per-note stem
     * adjustments into [stemAdjustments], and return the data needed to draw the beam.
     *
     * Pure with respect to the rest of the score — it only reads the given [notesInGroup]
     * and looks their positions up via [layoutResult] / [staffLayoutByIndex]. Returns null
     * if the group has fewer than two resolvable notes (no beam to draw).
     *
     * @param notesInGroup The voice-event layouts that share one beam group, in time order.
     * @param stemAdjustments Out-param: adjusted stem-tip Y per event id (also consumed by stem rendering).
     */
    context(BravuraFont)
    fun renderBeamGroup(
        notesInGroup: List<VoiceEventLayout>,
        layoutResult: UnifiedLayoutResult,
        staffLayoutByIndex: Map<Int, StaffLayoutInfo>,
        beamComputer: BeamLayoutComputer,
        stemAdjustments: MutableMap<EventId, StaffSpace>,
        storedGeometry: BeamGeometry? = null,
        stemDirections: MutableMap<EventId, StemDirection> = mutableMapOf(),
    ): BeamGroupRenderData? {
        if (notesInGroup.size < 2) return null

        // Determine direction (all notes in group share direction)
        val layoutStemDirection = notesInGroup.first().stem?.direction ?: StemDirection.UP

        // Derive scale from the first NoteElement in the group so any note type
        // (grace, cue, ossia) is handled without special-casing here.
        val firstVoiceLayout = notesInGroup.first()
        val firstSlot = layoutResult.timeSlotMap.atTime(firstVoiceLayout.time)
        val noteScale = firstSlot?.noteByEvent(firstVoiceLayout.eventId)
            ?.noteScale ?: NoteScale.NORMAL
        val scale = noteScale.value

        // Cross-staff beam: notes span more than one staff, so the beam runs between the
        // staves (flat baseline at the midpoint of the two staff centers) and each note's
        // stem reaches it from its own staff. Per-note stem directions were already resolved
        // toward the beam (interleaved) in StemDirectionResolver.
        val groupStaffIndices = notesInGroup.map { it.staffIndex }.distinct()
        if (groupStaffIndices.size > 1) {
            return buildCrossStaffBeam(
                notesInGroup, groupStaffIndices, layoutResult, staffLayoutByIndex, noteScale, stemAdjustments,
                storedGeometry
            )
        }

        // A manual beam crossing the average notehead centre is an explicit direction change.
        // Derive this every render from persisted geometry, so no transient layout result can
        // restore the old direction after mouse-up.
        val stemDirection = if (storedGeometry?.manuallyAdjusted == true) {
            val centers = notesInGroup.mapNotNull { voiceLayout ->
                val noteheads = listOfNotNull(voiceLayout.primary as? NoteheadLayout) + voiceLayout.chordNotes
                noteheads.takeIf { it.isNotEmpty() }?.map { it.relativeY.value }?.average()?.toFloat()
            }
            resolveManualBeamDirection(storedGeometry, centers, layoutStemDirection)
        } else layoutStemDirection

        // Build note info list (used for both stem adjustment and beam rendering)
        val noteInfos = notesInGroup.mapNotNull { voiceLayout ->
            val stem = voiceLayout.stem ?: return@mapNotNull null
            val slot = layoutResult.timeSlotMap.atTime(voiceLayout.time) ?: return@mapNotNull null
            val noteEvent = slot.noteByEvent(voiceLayout.eventId)
            val noteRelativeX = noteEvent?.relativeX ?: StaffSpace.ZERO
            // Collapsed (fully-hidden) staff ⇒ null ⇒ skip; no flat-stack fallback (see FullScoreRenderer).
            val staffLayout = layoutResult.staffForSystem(slot.systemIndex, voiceLayout.staffIndex)
                ?: return@mapNotNull null

            val stemRelativeX = if (stemDirection == stem.direction) stem.relativeX
            else stem.oppositeRelativeX ?: StaffSpace(-stem.relativeX.value)
            val x = slot.x + noteRelativeX + stemRelativeX

            // Default stem tip Y (from individual stem calculation) and the notehead
            // nearest to the beam — for UP this is the top notehead (smallest Y),
            // for DOWN the bottom notehead (largest Y). stem.topY/bottomY give the
            // outer endpoints of the stem; the near notehead is one ideal-stem-length
            // away from the tip on the staff side.
            val idealStemSpan = (config.stemLength + config.beamedStemExtension) * scale
            val noteheads = listOfNotNull(voiceLayout.primary as? NoteheadLayout) + voiceLayout.chordNotes
            val topNoteheadY = noteheads.minOfOrNull { staffLayout.centerY.value + it.relativeY.value }
                ?: (staffLayout.centerY + stem.topY).value
            val bottomNoteheadY = noteheads.maxOfOrNull { staffLayout.centerY.value + it.relativeY.value }
                ?: (staffLayout.centerY + stem.bottomY).value
            val defaultTipY: StaffSpace
            val nearNoteheadY: StaffSpace
            if (stemDirection == StemDirection.UP) {
                nearNoteheadY = StaffSpace(topNoteheadY)
                defaultTipY = nearNoteheadY - idealStemSpan
            } else {
                nearNoteheadY = StaffSpace(bottomNoteheadY)
                defaultTipY = nearNoteheadY + idealStemSpan
            }

            val beamInfo = voiceLayout.beamInfo ?: return@mapNotNull null

            BeamNoteInput(
                id = voiceLayout.eventId,
                x = x,
                defaultStemTipY = defaultTipY,
                nearNoteheadY = nearNoteheadY,
                isRest = false
            ) to BeamNoteInfo(
                x = x,
                stemTipY = defaultTipY, // Will be updated after beam computation
                beamInfo = com.mecon.api.computed.BeamInfo(
                    groupId = beamInfo.groupId,
                    totalBeamCount = beamInfo.totalBeamCount,
                    beamsLeft = beamInfo.beamsLeft,
                    beamsRight = beamInfo.beamsRight
                ),
                eventId = voiceLayout.eventId,
                trackId = voiceLayout.trackId
            )
        }

        val inputs = noteInfos.map { it.first }

        // Use the largest beam count in the group to size the minimum stem clearance.
        // The stem tip sits at the outer beam center; inner beams stack toward the notehead.
        // To guarantee `minimumFreeLength` of clearance between the notehead and the nearest
        // beam edge, the stem must be long enough to cover the inner beam stack plus the
        // half-thickness of the outermost beam.
        val maxBeamCount = notesInGroup.maxOf { it.beamInfo?.totalBeamCount ?: 1 }
        val beamThickness = config.engravingDefaults.beamThickness.value * scale
        val beamSpacing = config.engravingDefaults.beamSpacing.value * scale
        val innerStack = (maxBeamCount - 1).coerceAtLeast(0) * (beamThickness + beamSpacing)
        val minStemLength = config.beamLayoutConfig.minimumFreeLength(maxBeamCount) * scale +
            innerStack + beamThickness / 2f

        // Compute beam layout and apply stem adjustments
        val computed = beamComputer.compute(inputs, stemDirection, minStemLength)
        val storedResult = storedGeometry?.let { stored ->
            val first = inputs.minByOrNull { it.x.value } ?: return@let computed
            val last = inputs.maxByOrNull { it.x.value } ?: return@let computed
            val slope = if (last.x.value == first.x.value) 0f
            else (stored.endDy - stored.startDy) / (last.x.value - first.x.value)
            BeamLayoutResult(
                startX = first.x,
                startY = StaffSpace(first.defaultStemTipY.value - first.defaultStemTipY.value + stored.startDy +
                    (layoutResult.staffForSystem(
                        layoutResult.timeSlotMap.atTime(notesInGroup.first().time)?.systemIndex ?: 0,
                        notesInGroup.first().staffIndex
                    )?.centerY?.value ?: 0f)),
                endX = last.x,
                endY = StaffSpace(first.defaultStemTipY.value - first.defaultStemTipY.value + stored.startDy +
                    (layoutResult.staffForSystem(
                        layoutResult.timeSlotMap.atTime(notesInGroup.first().time)?.systemIndex ?: 0,
                        notesInGroup.first().staffIndex
                    )?.centerY?.value ?: 0f) + slope * (last.x.value - first.x.value)),
                slope = slope,
            )
        }
        // Preserve manual slope and position. If it is too close to the notes, translate the
        // whole line just far enough outward; never replace it with unrelated auto geometry.
        val result = storedResult?.let {
            adjustManualBeamForClearance(it, inputs, stemDirection, minStemLength)
        } ?: computed

        if (storedGeometry?.manuallyAdjusted == true) {
            notesInGroup.forEach { stemDirections[it.eventId] = stemDirection }
        }

        val beamNoteInfos = if (result != null) {
            // Apply adjusted stem tips
            noteInfos.map { (input, info) ->
                val adjustedY = result.yAt(input.x)
                stemAdjustments[input.id as EventId] = adjustedY
                info.copy(stemTipY = adjustedY)
            }.sortedBy { it.x }
        } else {
            noteInfos.map { it.second }.sortedBy { it.x }
        }

        if (beamNoteInfos.size < 2) return null
        val groupStaffIndex = notesInGroup.first().staffIndex
        return BeamGroupRenderData(
            beamNoteInfos, stemDirection, groupStaffIndex, noteScale,
            measureNumber = notesInGroup.first().measureNumber,
            geometry = result?.let {
                val center = layoutResult.staffForSystem(
                    layoutResult.timeSlotMap.atTime(notesInGroup.first().time)?.systemIndex ?: 0,
                    notesInGroup.first().staffIndex
                )?.centerY?.value ?: 0f
                BeamGeometry(
                    it.startY.value - center,
                    it.endY.value - center,
                    manuallyAdjusted = storedGeometry?.manuallyAdjusted == true,
                )
            }
        )
    }

    /**
     * Build render data for a beam group that spans more than one staff.
     *
     * The beam is a flat baseline at the midpoint between the highest and lowest involved
     * staves' center lines; every note's stem tip is pinned to that baseline (recorded in
     * [stemAdjustments]) and reaches it from the note's own staff. Returns null if fewer
     * than two notes resolve.
     */
    context(BravuraFont)
    private fun buildCrossStaffBeam(
        notesInGroup: List<VoiceEventLayout>,
        staffIndices: List<Int>,
        layoutResult: UnifiedLayoutResult,
        staffLayoutByIndex: Map<Int, StaffLayoutInfo>,
        noteScale: NoteScale,
        stemAdjustments: MutableMap<EventId, StaffSpace>,
        storedGeometry: BeamGeometry? = null,
    ): BeamGroupRenderData? {
        val upperIdx = staffIndices.min()
        val lowerIdx = staffIndices.max()
        // Cross-staff beams stay within one system; resolve Y from that system.
        val sysIndex = layoutResult.timeSlotMap.atTime(notesInGroup.first().time)?.systemIndex ?: 0
        val staffCenters = (upperIdx..lowerIdx).mapNotNull { staffIndex ->
            // Collapsed (fully-hidden) staff ⇒ null ⇒ skip; no flat-stack fallback (see FullScoreRenderer).
            val staff = layoutResult.staffForSystem(sysIndex, staffIndex)
            staff?.let { staffIndex to it.centerY }
        }.toMap()
        val upperStaffY = staffCenters[upperIdx] ?: return null
        val lowerStaffY = staffCenters[lowerIdx] ?: return null
        val sortedStaffIndices = staffCenters.keys.sorted()
        val defaultPairStart = ((sortedStaffIndices.size - 1) / 2).coerceAtMost(sortedStaffIndices.lastIndex - 1)
        val defaultUpperIndex = sortedStaffIndices[defaultPairStart]
        val defaultLowerIndex = sortedStaffIndices[defaultPairStart + 1]
        val storedPairIsValid = storedGeometry?.let { stored ->
            sortedStaffIndices.zipWithNext().any { (upper, lower) ->
                stored.betweenStaffUpperIndex == upper && stored.betweenStaffLowerIndex == lower
            }
        } == true
        val effectiveGeometry = (storedGeometry ?: BeamGeometry(0f, 0f)).let { stored ->
            when (stored.crossStaffBase) {
                CrossStaffBeamBase.TOP_STAFF_MIDLINE,
                CrossStaffBeamBase.BOTTOM_STAFF_MIDLINE -> stored.copy(
                    crossStaffOffset = stored.crossStaffOffset ?: 0f,
                    betweenStaffUpperIndex = null,
                    betweenStaffLowerIndex = null,
                )
                CrossStaffBeamBase.BETWEEN_STAFFS -> stored.copy(
                    crossStaffOffset = stored.crossStaffOffset ?: 0f,
                    betweenStaffUpperIndex = if (storedPairIsValid) stored.betweenStaffUpperIndex else defaultUpperIndex,
                    betweenStaffLowerIndex = if (storedPairIsValid) stored.betweenStaffLowerIndex else defaultLowerIndex,
                )
                null -> stored.copy(
                    crossStaffBase = CrossStaffBeamBase.BETWEEN_STAFFS,
                    crossStaffOffset = stored.crossStaffOffset ?: 0f,
                    betweenStaffUpperIndex = defaultUpperIndex,
                    betweenStaffLowerIndex = defaultLowerIndex,
                )
            }
        }
        val baseY = when (effectiveGeometry.crossStaffBase ?: CrossStaffBeamBase.BETWEEN_STAFFS) {
            CrossStaffBeamBase.TOP_STAFF_MIDLINE -> upperStaffY.value
            CrossStaffBeamBase.BOTTOM_STAFF_MIDLINE -> lowerStaffY.value
            CrossStaffBeamBase.BETWEEN_STAFFS -> {
                val pairUpper = effectiveGeometry.betweenStaffUpperIndex
                    ?.let(staffCenters::get) ?: staffCenters.getValue(defaultUpperIndex)
                val pairLower = effectiveGeometry.betweenStaffLowerIndex
                    ?.let(staffCenters::get) ?: staffCenters.getValue(defaultLowerIndex)
                (pairUpper.value + pairLower.value) / 2f
            }
        }
        val beamBaselineY = StaffSpace(baseY + (effectiveGeometry.crossStaffOffset ?: 0f))

        val infos = notesInGroup.mapNotNull { voiceLayout ->
            val beamInfo = voiceLayout.beamInfo ?: return@mapNotNull null
            val slot = layoutResult.timeSlotMap.atTime(voiceLayout.time) ?: return@mapNotNull null
            val noteEvent = slot.noteByEvent(voiceLayout.eventId)
            val noteRelativeX = noteEvent?.relativeX ?: StaffSpace.ZERO
            val stemRelX = voiceLayout.stem?.relativeX ?: StaffSpace.ZERO
            val x = slot.x + noteRelativeX + stemRelX

            BeamNoteInfo(
                x = x,
                stemTipY = beamBaselineY,
                beamInfo = com.mecon.api.computed.BeamInfo(
                    groupId = beamInfo.groupId,
                    totalBeamCount = beamInfo.totalBeamCount,
                    beamsLeft = beamInfo.beamsLeft,
                    beamsRight = beamInfo.beamsRight
                ),
                eventId = voiceLayout.eventId,
                trackId = voiceLayout.trackId
            )
        }.sortedBy { it.x }

        if (infos.size < 2) return null
        val firstX = infos.first().x.value
        val lastX = infos.last().x.value
        val slope = if (lastX == firstX) 0f else {
            (effectiveGeometry.endDy - effectiveGeometry.startDy) / (lastX - firstX)
        }
        val startY = beamBaselineY.value + effectiveGeometry.startDy
        val adjustedInfos = infos.map { info ->
            val adjustedY = StaffSpace(startY + slope * (info.x.value - firstX))
            info.eventId?.let { stemAdjustments[it] = adjustedY }
            info.copy(stemTipY = adjustedY)
        }
        if (effectiveGeometry.manuallyAdjusted) {
            val maxBeamCount = notesInGroup.maxOf { it.beamInfo?.totalBeamCount ?: 1 }
            val beamThickness = config.engravingDefaults.beamThickness.value * noteScale.value
            val beamSpacing = config.engravingDefaults.beamSpacing.value * noteScale.value
            val innerStack = (maxBeamCount - 1).coerceAtLeast(0) * (beamThickness + beamSpacing)
            val minStemLength = config.beamLayoutConfig.minimumFreeLength(maxBeamCount) * noteScale.value +
                innerStack + beamThickness / 2f
            val idealStemSpan = ((config.stemLength + config.beamedStemExtension) * noteScale.value).value
            val hasClearance = notesInGroup.zip(adjustedInfos).all { (voiceLayout, info) ->
                val stem = voiceLayout.stem ?: return@all true
                val slot = layoutResult.timeSlotMap.atTime(voiceLayout.time) ?: return@all true
                val staff = layoutResult.staffForSystem(slot.systemIndex, voiceLayout.staffIndex)
                    ?: return@all true
                val nearNoteheadY = when (stem.direction) {
                    StemDirection.UP -> staff.centerY.value + stem.topY.value + idealStemSpan
                    StemDirection.DOWN -> staff.centerY.value + stem.bottomY.value - idealStemSpan
                }
                when (stem.direction) {
                    StemDirection.UP -> nearNoteheadY - info.stemTipY.value >= minStemLength
                    StemDirection.DOWN -> info.stemTipY.value - nearNoteheadY >= minStemLength
                }
            }
            if (!hasClearance) {
                return buildCrossStaffBeam(
                    notesInGroup,
                    staffIndices,
                    layoutResult,
                    staffLayoutByIndex,
                    noteScale,
                    stemAdjustments,
                    storedGeometry = null,
                )
            }
        }
        // Beam-level stacking direction is irrelevant for single-beam cross-staff groups.
        return BeamGroupRenderData(
            adjustedInfos, StemDirection.DOWN, upperIdx, noteScale,
            measureNumber = notesInGroup.first().measureNumber,
            geometry = effectiveGeometry,
        )
    }
}

/** Resolve the side of a persisted manual beam without relying on transient stem layout. */
internal fun resolveManualBeamDirection(
    geometry: BeamGeometry,
    noteheadCenters: List<Float>,
    fallback: StemDirection,
): StemDirection {
    if (noteheadCenters.isEmpty()) return fallback
    val beamCenter = (geometry.startDy + geometry.endDy) / 2f
    val noteCenter = noteheadCenters.average().toFloat()
    return if (beamCenter <= noteCenter) StemDirection.UP else StemDirection.DOWN
}

/** Translate a manual beam outward without changing either endpoint delta or slope. */
internal fun adjustManualBeamForClearance(
    stored: BeamLayoutResult,
    inputs: List<BeamNoteInput>,
    direction: StemDirection,
    minStemLength: Float,
): BeamLayoutResult {
    if (inputs.isEmpty()) return stored
    val correction = when (direction) {
        StemDirection.UP -> inputs.minOf { input ->
            input.nearNoteheadY.value - minStemLength - stored.yAt(input.x).value
        }.coerceAtMost(0f)
        StemDirection.DOWN -> inputs.maxOf { input ->
            input.nearNoteheadY.value + minStemLength - stored.yAt(input.x).value
        }.coerceAtLeast(0f)
    }
    return stored.copy(
        startY = StaffSpace(stored.startY.value + correction),
        endY = StaffSpace(stored.endY.value + correction),
    )
}
