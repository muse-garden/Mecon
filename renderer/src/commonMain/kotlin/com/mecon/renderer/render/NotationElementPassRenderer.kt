package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.BarlineSection
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.elements.ElementRenderContext
import com.mecon.renderer.elements.ElementRenderOutput
import com.mecon.renderer.elements.FlagElement
import com.mecon.renderer.elements.RenderableElement
import com.mecon.renderer.elements.SectionRegistration
import com.mecon.renderer.elements.StemElement
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.NoteheadLayout
import com.mecon.renderer.layout.RestLayout
import com.mecon.renderer.layout.StaffKind
import com.mecon.renderer.layout.StaffLayoutInfo
import com.mecon.renderer.layout.SystemLayout
import com.mecon.renderer.layout.UnifiedLayoutResult
import com.mecon.renderer.layout.UnifiedTimeSlot
import com.mecon.renderer.layout.VoiceEventLayout
import com.mecon.renderer.smufl.BravuraFont

/** Renders notation passes shared by full render and incremental splice windows. */
context(BravuraFont)
internal class NotationElementPassRenderer(
    private val config: RenderLayoutConfig,
    private val transformer: CoordinateTransformer,
    private val systemRenderer: SystemRenderer,
) {
    fun renderTimeSlotElements(
        layoutResult: UnifiedLayoutResult,
        computedScore: ComputedScore,
        systemByIndex: Map<Int, SystemLayout>,
        staffFor: (Int, Int) -> StaffLayoutInfo?,
        nextId: () -> RenderElementId,
        collect: (ElementRenderOutput, Int, Int) -> Unit,
        slotFilter: (UnifiedTimeSlot) -> Boolean = { true },
        includeBarlines: Boolean = true,
        includeNonBarlines: Boolean = true,
    ) {
        // The score-opening barline of a multi-staff system is drawn without staff connectivity (the
        // system bracket/brace closes the left edge instead). Identify it by **time**, not by a running
        // "first seen" flag: under a partial [slotFilter] (per-page / per-system streaming) the earliest
        // barline may be filtered out, and a running flag would then mis-mark the first *surviving*
        // barline of a later page as a system start — cutting its staff connection. A time comparison is
        // position-independent, so it stays correct under any filter while matching the full-render result
        // (the earliest barline is the first one iterated in time order).
        val firstBarlineTime = firstMultiStaffBarlineTime(layoutResult, systemByIndex)
        for (slot in layoutResult.timeSlotMap.all()) {
            if (!slotFilter(slot)) continue
            val system = systemByIndex[slot.systemIndex]
            val notationStaffs = (system?.staffLayouts ?: layoutResult.staffLayouts)
                .filter { it.kind == StaffKind.NOTATION }
            val staffByIndexForSlot = notationStaffs.associateBy { it.staffIndex }
            for (event in slot.events) {
                when (event) {
                    is BarlineElement -> {
                        if (!includeBarlines) continue
                        if (event.time in layoutResult.suppressedBarlineTimes) continue
                        if (notationStaffs.size >= 2) {
                            val isSystemStart = event.time == firstBarlineTime
                            val computedBarline = computedScore.barlines.find { it.time == event.time }
                            val connectivity = if (isSystemStart) emptyList()
                                else computedBarline?.connectedStaffRanges ?: emptyList()
                            val renderElements = systemRenderer.renderSystemBarline(
                                barlineElement = event,
                                slotX = slot.x,
                                firstStaffTopY = notationStaffs.first().topY,
                                lastStaffBottomY = notationStaffs.last().bottomY,
                                connectedRanges = connectivity,
                                staffByIndex = staffByIndexForSlot,
                                idGenerator = nextId
                            )
                            collect(
                                ElementRenderOutput(
                                    renderElements = renderElements,
                                    // Multi-staff barlines are emitted by SystemRenderer instead of
                                    // BarlineElement.render(), so mirror its section ownership here.
                                    // Without this registration the barline can be selected by the
                                    // boundary hit test, but selection styles cannot reach its element.
                                    sectionRegistrations = computedBarline?.let { barline ->
                                        renderElements.map { SectionRegistration(BarlineSection(barline), it.id) }
                                    }.orEmpty(),
                                    hitAreas = emptyList()
                                ),
                                notationStaffs.firstOrNull()?.staffIndex ?: 0,
                                slot.systemIndex,
                            )
                        } else {
                            for (staffLayout in notationStaffs) {
                                val ctx = ElementRenderContext(
                                    offset = RelativePoint(slot.x, staffLayout.centerY),
                                    transformer = transformer,
                                    idGenerator = nextId,
                                    computedScore = computedScore
                                )
                                collect(event.render(ctx), staffLayout.staffIndex, slot.systemIndex)
                            }
                        }
                    }
                    else -> {
                        if (!includeNonBarlines) continue
                        // A clef change landing on a system start (>0) is drawn by that line's restated
                        // header (and as the previous line's courtesy clef), so skip the in-stream body
                        // clef here to avoid the clef appearing twice at the break.
                        if (event is ClefElement && event.time in layoutResult.suppressedClefTimes) continue
                        val staffLayout = staffFor(slot.systemIndex, event.staffIndex) ?: continue
                        val ctx = ElementRenderContext(
                            offset = RelativePoint(slot.x, staffLayout.centerY),
                            transformer = transformer,
                            idGenerator = nextId,
                            computedScore = computedScore
                        )
                        collect((event as RenderableElement).render(ctx), staffLayout.staffIndex, slot.systemIndex)
                    }
                }
            }
        }
    }

    /**
     * Time of the earliest non-suppressed barline that falls in a multi-staff (≥2 notation staves)
     * system — the score-opening barline, drawn without staff connectivity. Returns null when the score
     * has no multi-staff barline (single-staff scores never take the connected-barline branch). Iterates
     * in time order and short-circuits at the first match, so it is effectively O(1).
     */
    private fun firstMultiStaffBarlineTime(
        layoutResult: UnifiedLayoutResult,
        systemByIndex: Map<Int, SystemLayout>,
    ): com.mecon.api.primitive.TimeCode? {
        for (slot in layoutResult.timeSlotMap.all()) {
            val system = systemByIndex[slot.systemIndex]
            val notationStaffCount = (system?.staffLayouts ?: layoutResult.staffLayouts)
                .count { it.kind == StaffKind.NOTATION }
            if (notationStaffCount < 2) continue
            val barline = slot.events.firstOrNull {
                it is BarlineElement && it.time !in layoutResult.suppressedBarlineTimes
            } as BarlineElement?
            if (barline != null) return barline.time
        }
        return null
    }

    fun renderStemFlagElements(
        layoutResult: UnifiedLayoutResult,
        beamProcessingResult: BeamGroupProcessor.BeamProcessingResult,
        staffFor: (Int, Int) -> StaffLayoutInfo?,
        dummyCtx: ElementRenderContext,
        collect: (ElementRenderOutput, Int, Int) -> Unit,
        voiceFilter: (VoiceEventLayout) -> Boolean = { true },
        /**
         * Optional pre-windowed source. Incremental callers already own time-ordered per-measure
         * chunks, so they need not scan every voice layout merely to apply [voiceFilter].
         */
        voiceLayouts: Iterable<VoiceEventLayout> = layoutResult.voiceEventLayouts.all(),
    ) {
        for (voiceLayout in voiceLayouts) {
            if (voiceLayout.primary is RestLayout) continue
            if (!voiceFilter(voiceLayout)) continue

            val slot = layoutResult.timeSlotMap.atTime(voiceLayout.time) ?: continue
            val noteEvent = slot.noteByEvent(voiceLayout.eventId)
            val noteRelativeX = noteEvent?.relativeX ?: StaffSpace.ZERO
            val noteScale = noteEvent?.noteScale?.value ?: 1f
            val staffLayout = staffFor(slot.systemIndex, voiceLayout.staffIndex) ?: continue

            val stemLayout = voiceLayout.stem
            if (stemLayout != null) {
                val effectiveDirection = beamProcessingResult.stemDirections[voiceLayout.eventId]
                    ?: stemLayout.direction
                val effectiveRelativeX = if (effectiveDirection == stemLayout.direction) stemLayout.relativeX
                else stemLayout.oppositeRelativeX ?: StaffSpace(-stemLayout.relativeX.value)
                val stemX = slot.x + noteRelativeX + effectiveRelativeX

                var stemTopY = staffLayout.centerY + stemLayout.topY
                var stemBottomY = staffLayout.centerY + stemLayout.bottomY

                if (effectiveDirection != stemLayout.direction) {
                    val noteheads = listOfNotNull(voiceLayout.primary as? NoteheadLayout) + voiceLayout.chordNotes
                    val topNoteY = noteheads.minOfOrNull { staffLayout.centerY.value + it.relativeY.value }
                        ?: stemTopY.value
                    val bottomNoteY = noteheads.maxOfOrNull { staffLayout.centerY.value + it.relativeY.value }
                        ?: stemBottomY.value
                    if (effectiveDirection == StemDirection.UP) stemBottomY = StaffSpace(bottomNoteY)
                    else stemTopY = StaffSpace(topNoteY)
                }

                val adjustedTipY = beamProcessingResult.stemAdjustments[voiceLayout.eventId]
                if (adjustedTipY != null) {
                    if (effectiveDirection == StemDirection.UP) {
                        stemTopY = adjustedTipY
                    } else {
                        stemBottomY = adjustedTipY
                    }
                }

                val stemElement = StemElement(
                    eventId = voiceLayout.eventId,
                    trackId = voiceLayout.trackId,
                    measureNumber = voiceLayout.measureNumber,
                    staffIndex = voiceLayout.staffIndex,
                    stemX = stemX,
                    topY = stemTopY,
                    bottomY = stemBottomY,
                    direction = effectiveDirection,
                    thickness = config.engravingDefaults.stemThickness * noteScale
                )
                collect(stemElement.render(dummyCtx), voiceLayout.staffIndex, slot.systemIndex)

                val flagLayout = voiceLayout.flag
                if (flagLayout != null && voiceLayout.beamInfo == null) {
                    val flagX = slot.x + noteRelativeX + flagLayout.relativeX
                    val flagY = staffLayout.centerY + flagLayout.relativeY

                    val flagElement = FlagElement(
                        eventId = voiceLayout.eventId,
                        trackId = voiceLayout.trackId,
                        measureNumber = voiceLayout.measureNumber,
                        staffIndex = voiceLayout.staffIndex,
                        flagX = flagX,
                        flagY = flagY,
                        flagCount = flagLayout.flagCount,
                        direction = stemLayout.direction,
                        scale = noteScale,
                    )
                    collect(flagElement.render(dummyCtx), voiceLayout.staffIndex, slot.systemIndex)
                }
            }
        }
    }
}
