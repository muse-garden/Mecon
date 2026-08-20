package com.mecon.mobile

import com.mecon.features.scoreediting.ScoreEditingFrame
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.interaction.VoiceArticulationSection
import com.mecon.api.interaction.VoiceBeamSection
import com.mecon.api.interaction.VoiceSlurSection
import com.mecon.api.interaction.VoiceTieSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.BarlineSection
import com.mecon.api.interaction.VoltaEndingSection
import com.mecon.api.interaction.NavigationMarkSection
import com.mecon.api.interaction.LayoutBreakSection
import com.mecon.api.interaction.HiddenStaffSection
import com.mecon.api.interaction.ClefSection
import com.mecon.api.interaction.KeySignatureSection
import com.mecon.api.interaction.TimeSignatureSection
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.PageGeometry
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.RenderEngine
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.RenderElement
import com.mecon.renderer.render.edit.GhostNote
import com.mecon.renderer.render.edit.GhostClef
import com.mecon.renderer.render.edit.GhostExpressionSpan
import com.mecon.renderer.render.edit.GhostKeySignature
import com.mecon.renderer.render.edit.GhostTimeSignature
import com.mecon.renderer.render.edit.ExpressionSpanKind
import com.mecon.renderer.smufl.BravuraFont

/**
 * Platform-neutral engraving owner for mobile shells.
 *
 * Android, iOS and Harmony only replay [RenderResult] commands and map pointer coordinates back
 * through that same immutable result. They do not reproduce layout or music rules locally.
 */
class MobileScoreRenderSession(
    private val font: BravuraFont,
    config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT.copy(padEmptyMeasures = true),
) {
    private val engine = with(font) { RenderEngine(config) }

    /** CPU-heavy: callers must invoke this on a serial background worker. */
    fun render(
        frame: ScoreEditingFrame,
        lineWidth: StaffSpace = StaffSpace(48f),
    ): RenderResult {
        val result = engine.render(
            score = frame.runtimeScore,
            pageWidth = lineWidth,
            pageGeometry = PageGeometry.continuous(lineWidth),
        )
        return result.copy(capturedGeometry = engine.captureGeometry())
    }

    /** Resolves a pointer against the exact displayed frame and returns renderer-owned ghost geometry. */
    fun computeNoteGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        duration: Duration,
        restMode: Boolean,
        accidental: Accidental? = null,
        voiceNumber: Int = 1,
    ): GhostNote? = with(font) {
        engine.computeGhost(
            result = result,
            runtime = frame.runtimeScore,
            point = point,
            duration = duration,
            accidental = accidental,
            restMode = restMode,
            voiceNumber = voiceNumber,
        )
    }

    fun computeClefGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        clef: Clef,
    ): GhostClef? = with(font) {
        engine.computeClefGhost(result, frame.runtimeScore, point, clef)
    }

    fun computeTimeSignatureGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        timeSignature: TimeSignature,
    ): GhostTimeSignature? = with(font) {
        engine.computeTimeSignatureGhost(result, frame.runtimeScore, point, timeSignature)
    }

    fun computeKeySignatureGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        point: AbsolutePoint,
        keySignature: KeySignature,
    ): GhostKeySignature? = with(font) {
        engine.computeKeySignatureGhost(result, frame.runtimeScore, point, keySignature)
    }

    fun computeExpressionSpanGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        staffTrackId: TrackId,
        start: TimeCode,
        end: TimeCode,
        kind: ExpressionSpanKind,
    ): GhostExpressionSpan? = with(font) {
        engine.computeExpressionSpanGhost(result, frame.runtimeScore, staffTrackId, start, end, kind)
    }

    /**
     * Creates the first visible S-family draft from one semantic anchor. A quarter note is the
     * preferred default; the closest renderable slot on the same system is used when that exact
     * time is unavailable. This keeps time-axis and system-boundary policy out of platform shells.
     */
    fun computeDefaultExpressionSpanGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        staffTrackId: TrackId,
        start: TimeCode,
        kind: ExpressionSpanKind,
    ): GhostExpressionSpan? = with(font) {
        val timeMap = result.scoreTimeMap ?: return@with null
        val startAbsolute = timeMap.absolute(start)
        val preferred = startAbsolute + Fraction.QUARTER
        result.timeCodePositions.keys.asSequence()
            .filter { it > start }
            .sortedWith(
                compareBy<TimeCode> {
                    val delta = timeMap.absolute(it) - preferred
                    if (delta.isNegative) -delta else delta
                }.thenBy { it },
            )
            .mapNotNull { end ->
                engine.computeExpressionSpanGhost(result, frame.runtimeScore, staffTrackId, start, end, kind)
            }
            .firstOrNull()
    }

    fun computePointSymbolGhost(
        frame: ScoreEditingFrame,
        result: RenderResult,
        staffTrackId: TrackId,
        onset: TimeCode,
        kind: com.mecon.renderer.render.edit.PointSymbolKind,
    ): com.mecon.renderer.render.edit.GhostPointSymbol? = with(font) {
        engine.computePointSymbolGhost(result, frame.runtimeScore, staffTrackId, onset, kind)
    }

    /**
     * Converts renderer hit metadata into a stable shared-session selection target. Pixel geometry
     * ends here; callers dispatch only IDs and pitch indices. Noteheads win over their enclosing
     * event section so a chord can later grow pitch-level transforms without changing this API.
     */
    fun selectionTargetAt(result: RenderResult, point: AbsolutePoint): ScoreSelectionTarget? {
        val sections = result.hitTest(point).elements.asReversed().flatMap { it.sections }
        return selectionTargetForSections(result, sections)
    }

    /** Ordered semantic candidates for ambiguous touch hits; platforms present rather than guess. */
    fun selectionCandidatesAt(result: RenderResult, point: AbsolutePoint): List<ScoreSelectionTarget> =
        mergeRendererTargets(result.hitTest(point).elements.asReversed()
            .mapNotNull { element -> selectionTargetForSections(result, element.sections) }
        )

    /**
     * Resolves a marquee against the exact displayed render generation. Event hits are merged by
     * stable event/voice identity and their pitch indices are unioned; overlapping renderer
     * fragments therefore never create duplicate selection targets.
     */
    fun selectionTargetsInRegion(result: RenderResult, rect: AbsoluteRect): List<ScoreSelectionTarget> {
        val raw = result.hitTestRegion(rect).mapNotNull { element ->
            selectionTargetForSections(result, element.sections)
        }
        return mergeRendererTargets(raw)
    }

    /** Renderer elements that visually represent one semantic target in this exact generation. */
    fun elementsForSelection(result: RenderResult, target: ScoreSelectionTarget): List<RenderElement> {
        fun ids(sectionId: EventSectionId): List<com.mecon.renderer.render.RenderElementId> =
            result.sectionIndex.elementsForSectionId(sectionId).elementIds
        val elementIds = when (target) {
            is ScoreSelectionTarget.Event -> target.pitchIndices?.flatMap { index ->
                ids(EventSectionId.voiceNote(target.eventId, index))
            } ?: ids(EventSectionId.voiceEvent(target.eventId))
            is ScoreSelectionTarget.Clef -> target.staffTrackId?.let {
                ids(EventSectionId.clef(it, target.onset))
            }.orEmpty()
            is ScoreSelectionTarget.KeySignature -> target.staffTrackId?.let {
                ids(EventSectionId.keySignature(it, target.onset))
            }.orEmpty()
            is ScoreSelectionTarget.TimeSignature -> target.staffTrackId?.let {
                ids(EventSectionId.timeSignature(it, target.onset))
            }.orEmpty()
            is ScoreSelectionTarget.Barline -> target.onset?.let {
                ids(EventSectionId.barline(target.boundaryMeasure, it))
            }.orEmpty()
            is ScoreSelectionTarget.VoltaEnding ->
                ids(EventSectionId.volta(target.startMeasure, target.endMeasure, target.numbers))
            is ScoreSelectionTarget.NavigationMark ->
                ids(EventSectionId.navigationMark(target.boundaryMeasure, target.mark))
            is ScoreSelectionTarget.Slur ->
                ids(EventSectionId.voiceSlur(target.startEventId, target.endEventId, 0, target.slurId))
            is ScoreSelectionTarget.Tie ->
                ids(EventSectionId.voiceTie(target.sourceEventId, target.sourcePitchIndex))
            is ScoreSelectionTarget.Beam -> ids(EventSectionId.voiceBeam(target.groupId))
            is ScoreSelectionTarget.Articulation -> target.articulationIndex?.let {
                ids(EventSectionId.voiceArticulation(target.eventId, it))
            } ?: result.sectionIndex.allOfType<VoiceArticulationSection>()
                .filter { it.event.id == target.eventId }
                .flatMap { ids(it.id) }
            is ScoreSelectionTarget.Attachment -> ids(EventSectionId.staffAttachment(target.attachmentId))
            is ScoreSelectionTarget.LayoutBreak -> listOf(LayoutBreakKind.SYSTEM, LayoutBreakKind.PAGE)
                .flatMap { ids(EventSectionId.layoutBreak(target.beforeMeasure, it)) }
            is ScoreSelectionTarget.StaffVisibility -> result.sectionIndex.allOfType<HiddenStaffSection>()
                .filter {
                    target.staffTrackId in it.staffTrackIds && it.range.from == target.startMeasure &&
                        it.range.to == target.endMeasure
                }
                .flatMap { ids(it.id) }
        }
        return elementIds.distinct().mapNotNull(result::elementById)
    }

    private fun mergeRendererTargets(raw: List<ScoreSelectionTarget>): List<ScoreSelectionTarget> {
        val events = linkedMapOf<Pair<com.mecon.api.primitive.EventId, TrackId?>, ScoreSelectionTarget.Event>()
        val others = linkedSetOf<ScoreSelectionTarget>()
        raw.forEach { target ->
            if (target is ScoreSelectionTarget.Event) {
                val key = target.eventId to target.voiceTrackId
                val existing = events[key]
                val existingPitches = existing?.pitchIndices
                val targetPitches = target.pitchIndices
                events[key] = if (existing == null) target else existing.copy(
                    pitchIndices = when {
                        existingPitches == null -> targetPitches
                        targetPitches == null -> existingPitches
                        else -> existingPitches + targetPitches
                    },
                )
            } else {
                others += target
            }
        }
        return events.values + others
    }

    private fun selectionTargetForSections(
        result: RenderResult,
        sections: List<com.mecon.api.interaction.EventSection>,
    ): ScoreSelectionTarget? {
        sections.filterIsInstance<VoiceArticulationSection>().firstOrNull()?.let { articulation ->
            if (articulation.articulation == com.mecon.api.storage.Articulation.FERMATA) {
                articulation.event.fermata?.let { fermata ->
                    return ScoreSelectionTarget.Attachment(attachmentId = fermata.id)
                }
            }
            return ScoreSelectionTarget.Articulation(
                eventId = articulation.event.id,
                articulationIndex = articulation.index,
                voiceTrackId = result.eventVoiceTrackIds[articulation.event.id],
            )
        }
        sections.filterIsInstance<VoiceSlurSection>().firstOrNull()?.let { slur ->
            val slurId = slur.slurId ?: return@let
            val voiceId = result.eventVoiceTrackIds[slur.startEvent.id] ?: return@let
            return ScoreSelectionTarget.Slur(slurId, voiceId, slur.startEvent.id, slur.endEvent.id)
        }
        sections.filterIsInstance<VoiceTieSection>().firstOrNull()?.let { tie ->
            return ScoreSelectionTarget.Tie(
                sourceEventId = tie.sourceEvent.id,
                sourcePitchIndex = tie.sourcePitchIndex,
                voiceTrackId = result.eventVoiceTrackIds[tie.sourceEvent.id],
                targetEventId = tie.targetEvent?.id,
            )
        }
        sections.filterIsInstance<VoiceBeamSection>().firstOrNull()?.let { beam ->
            return ScoreSelectionTarget.Beam(beam.groupId.value)
        }
        sections.filterIsInstance<StaffAttachmentSection>().firstOrNull()?.let { attachment ->
            return ScoreSelectionTarget.Attachment(
                attachmentId = attachment.attachment.id,
                staffTrackId = attachment.attachment.staffTrackId,
            )
        }
        sections.filterIsInstance<VoltaEndingSection>().firstOrNull()?.let { volta ->
            return ScoreSelectionTarget.VoltaEnding(
                volta.ending.startMeasure,
                volta.ending.endMeasure,
                volta.ending.numbers,
            )
        }
        sections.filterIsInstance<NavigationMarkSection>().firstOrNull()?.let { navigation ->
            return ScoreSelectionTarget.NavigationMark(
                navigation.navigation.boundaryMeasure,
                navigation.navigation.mark,
                navigation.navigation.time,
            )
        }
        sections.filterIsInstance<BarlineSection>().firstOrNull()?.let { barline ->
            return ScoreSelectionTarget.Barline(barline.barline.measureNumber, barline.barline.time)
        }
        sections.filterIsInstance<LayoutBreakSection>().firstOrNull()?.let { layoutBreak ->
            return ScoreSelectionTarget.LayoutBreak(layoutBreak.beforeMeasure)
        }
        sections.filterIsInstance<HiddenStaffSection>().firstOrNull()?.let { hidden ->
            val staffId = hidden.staffTrackIds.firstOrNull() ?: return@let
            return ScoreSelectionTarget.StaffVisibility(staffId, hidden.range.from, hidden.range.to)
        }
        sections.filterIsInstance<ClefSection>().firstOrNull()?.let { clef ->
            return ScoreSelectionTarget.Clef(clef.clef.staffTrackId, clef.clef.time)
        }
        sections.filterIsInstance<KeySignatureSection>().firstOrNull()?.let { key ->
            return ScoreSelectionTarget.KeySignature(key.keySignature.staffTrackId, key.keySignature.time)
        }
        sections.filterIsInstance<TimeSignatureSection>().firstOrNull()?.let { meter ->
            return ScoreSelectionTarget.TimeSignature(meter.timeSignature.staffTrackId, meter.timeSignature.time)
        }
        sections.filterIsInstance<VoiceNoteSection>().firstOrNull()?.let { note ->
            return ScoreSelectionTarget.Event(
                eventId = note.event.id,
                voiceTrackId = result.eventVoiceTrackIds[note.event.id],
                pitchIndices = setOf(note.pitchIndex),
            )
        }
        val event = sections.filterIsInstance<VoiceEventSection>().firstOrNull()?.event ?: return null
        return ScoreSelectionTarget.Event(
            eventId = event.id,
            voiceTrackId = result.eventVoiceTrackIds[event.id],
        )
    }
}
