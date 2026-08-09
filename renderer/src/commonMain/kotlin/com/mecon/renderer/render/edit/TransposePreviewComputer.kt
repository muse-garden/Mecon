package com.mecon.renderer.render.edit

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.DiatonicTranspose
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.tracks.RuntimeStaffTrack
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.StaffPitchContext
import com.mecon.renderer.elements.FlagElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativeLine
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.NoteBodyElementBuilder
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.VoiceEventLayoutBuilder
import com.mecon.renderer.layout.stem.StemDirectionResolver
import com.mecon.renderer.layout.stem.StemResolutionInput
import com.mecon.renderer.layout.stem.VoiceContext
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont

/**
 * A live drag-to-transpose preview: the moved notes re-engraved at their new staff positions, in
 * absolute (global) render coordinates ready to draw over the score. The originals are hidden by the
 * caller (a `hidden` style override on each moved event), so only this preview is seen while dragging.
 *
 * Two colour layers, drawn base-then-moved: [baseCommands] is the part that should keep its normal
 * (black) colour — the unmoved noteheads, stem, flag and ledgers of a partially-moved chord — and
 * [movedCommands] is the notes actually being dragged, drawn in the selection colour on top. For a
 * whole-event move everything is in [movedCommands] (the whole note is the selection).
 *
 * [anchor] locates the owning page in paginated mode (same role as `GhostNote.anchor`).
 */
data class TransposePreview(
    val baseCommands: List<RenderCommand>,
    val movedCommands: List<RenderCommand>,
    val anchor: AbsolutePoint,
)

/**
 * Re-engraves already-placed notes at a new pitch for the drag-to-transpose preview, using the very
 * same body / stem / flag builders that engrave committed notes (mirrors [GhostNoteComputer]). The X
 * of each note is kept (only the pitch — vertical position — changes), so the surrounding layout is
 * untouched until the drag is released and the real edit recomputes. Beamed notes are previewed
 * un-beamed (stem + flag); the commit's recompute restores correct beaming.
 *
 * Pure and stateless: reads only its arguments and allocates fresh geometry, so it is safe to call
 * from the pointer-event coroutine on every drag step.
 */
context(BravuraFont)
class TransposePreviewComputer(private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT) {
    private data class PreviewEventContext(
        val transformer: com.mecon.renderer.render.CoordinateTransformer,
        val event: com.mecon.api.computed.ComputedVoiceEvent,
        val eventId: EventId,
        val staffTrack: RuntimeStaffTrack,
        val staffIndex: Int,
        val centerY: StaffSpace,
        val originLeftRel: StaffSpace,
    )

    private data class PreviewPitchEdit(
        val stepDelta: Int,
        val key: KeySignature,
        val clef: Clef,
        val octaveShiftDiatonicOffset: Int,
        val movedSet: Set<Int>,
        val beamTip: BeamTip?,
    )

    private val noteBodyBuilder = NoteBodyElementBuilder(config)
    private val voiceLayoutBuilder = VoiceEventLayoutBuilder(config)
    private val stemResolver = StemDirectionResolver(
        voiceConfig = config.voiceStemConfig,
        beamConfig = config.beamLayoutConfig
    )

    /**
     * Build the preview for [targets] (event id → moved pitch indices; null = whole event) shifted by
     * [stepDelta] diatonic steps. Returns null when nothing could be previewed (no resolvable target).
     */
    fun compute(
        result: RenderResult,
        runtime: RuntimeScore,
        computed: ComputedScore,
        targets: Map<EventId, Set<Int>?>,
        stepDelta: Int,
    ): TransposePreview? {
        if (stepDelta == 0 || targets.isEmpty()) return null
        // Clamp the delta so no moved pitch leaves the MIDI range — keeps the preview in lock-step with
        // the commit (`NoteEditEngine.transpose` clamps identically), so a drag past the top/bottom just
        // pins the notes at the edge instead of showing (then committing) an unplayable pitch.
        val delta = DiatonicTranspose.clampDelta(movedPitchKeys(runtime, computed, targets), stepDelta)
        if (delta == 0) return null

        val transformer = result.transformerSnapshot
        val base = mutableListOf<RenderCommand>()   // unmoved parts → keep black
        val moved = mutableListOf<RenderCommand>()  // dragged notes → selection colour
        var firstAnchor: AbsolutePoint? = null

        for ((eventId, movedIndices) in targets) {
            val event = computed.getComputedEvent(eventId) ?: continue
            if (event.isRest || event.pitchData.isEmpty()) continue

            // Locate the event on screen via its notehead element (absolute geometry).
            val noteheadEl = result.elementsForEvent(eventId)
                .firstOrNull { it.type == RenderElementType.NOTEHEAD } ?: continue
            val nhBox = noteheadEl.hitBox
            val centerPoint = transformer.toRelative(
                AbsolutePoint(
                    Pixels(nhBox.origin.x.value + nhBox.width.value / 2f),
                    Pixels(nhBox.origin.y.value + nhBox.height.value / 2f),
                )
            )
            val staffHit = result.spatialIndex.staffAt(centerPoint) ?: continue
            val staffTrack = runtime.orderedStaffs().getOrNull(staffHit.staffIndex) ?: continue
            val homeStaff = homeStaffForEvent(runtime, eventId) ?: staffTrack
            // Original notehead left edge (relative) — the preview keeps the same X, only Y/pitch move.
            val originLeftRel = transformer.toRelative(
                AbsolutePoint(Pixels(nhBox.origin.x.value), Pixels(0f))
            ).x

            val movedSet = movedIndices?.takeIf { it.isNotEmpty() }
                ?.filter { it in event.pitchData.indices }?.toSet()
                ?: event.pitchData.indices.toSet()

            // Beamed note: keep the existing beam (it doesn't move in a vertical drag) and reconnect the
            // moved note's stem to it, rather than previewing the note un-beamed. The beam tip is the far
            // endpoint of the original stem (the end touching the beam), read from the displayed render.
            val beamTip = if (event.beamInfo != null) beamTipOf(result, eventId, nhBox.origin.y.value + nhBox.height.value / 2f) else null

            val anchor = appendEvent(
                base = base,
                moved = moved,
                context = PreviewEventContext(
                    transformer,
                    event,
                    eventId,
                    staffTrack,
                    staffHit.staffIndex,
                    staffHit.centerY,
                    originLeftRel,
                ),
                edit = PreviewPitchEdit(
                    delta,
                    runtime.getKeySignatureAt(event.onset.measure),
                    StaffPitchContext.effectiveClef(event.onset, staffTrack),
                    StaffPitchContext.octaveShiftDiatonicOffset(event.onset, homeStaff),
                    movedSet,
                    beamTip,
                ),
            )
            if (firstAnchor == null) firstAnchor = anchor
        }

        val anchor = firstAnchor ?: return null
        if (base.isEmpty() && moved.isEmpty()) return null
        return TransposePreview(base, moved, anchor)
    }

    /**
     * Re-engrave [event]'s full note body with the pitches in [movedSet] shifted by [stepDelta], and
     * route its commands into [base] (normal/black) or [moved] (drag colour): moved noteheads (and their
     * accidentals/dots) go to [moved]; everything else (unmoved noteheads, stem, flag, ledgers) goes to
     * [base] — except a whole-event move (every pitch moved), where the stem/flag/ledgers go to [moved]
     * too so the whole note shows as the selection. Returns the draw anchor.
     */
    private fun appendEvent(
        base: MutableList<RenderCommand>,
        moved: MutableList<RenderCommand>,
        context: PreviewEventContext,
        edit: PreviewPitchEdit,
    ): AbsolutePoint {
        val (transformer, event, eventId, staffTrack, staffIndex, centerY, originLeftRel) = context
        val (stepDelta, key, clef, octaveShiftDiatonicOffset, movedSet, beamTip) = edit
        val wholeEvent = movedSet.size == event.pitchData.size
        // Keep event.pitchData order (not sorted) so each notehead's pitchIndex still indexes the
        // original chord — that's how we tell which noteheads are the moved ones. The builder sorts
        // internally for stacking, so vertical placement is still correct.
        val newPitchData = event.pitchData.mapIndexed { i, pd ->
            if (i in movedSet) transposedPitchData(pd, stepDelta, clef, octaveShiftDiatonicOffset, key) else pd
        }

        val stemDir = stemResolver.resolveIndividualDirection(
            StemResolutionInput(
                eventId = eventId,
                pitchData = newPitchData,
                beamInfo = null,
                userStemDirection = null,
                voiceContext = VoiceContext(
                    voiceNumber = 1,
                    measureNumber = event.onset.measure,
                    hasMultipleVoices = false,
                    staffIndex = staffIndex,
                ),
            )
        )
        val body = noteBodyBuilder.buildNoteGeometry(newPitchData, event.duration, stemDir)
        val newNoteheadMinX = body.noteheads.minOfOrNull { it.geometry.bounds.origin.x.value } ?: 0f
        val drawOffset = RelativePoint(originLeftRel - StaffSpace(newNoteheadMinX), centerY)

        // Per-pitch parts route by whether that pitch moved; structural parts route by whole-vs-partial.
        for (nh in body.noteheads)
            (if (nh.pitchIndex in movedSet) moved else base) += nh.geometry.draw(drawOffset, transformer)
        for (acc in body.accidentals)
            (if (acc.pitchIndex in movedSet) moved else base) += acc.geometry.draw(drawOffset, transformer)
        for (dot in body.dots)
            (if (dot.pitchIndex in movedSet) moved else base) += dot.geometry.draw(drawOffset, transformer)
        val structural = if (wholeEvent) moved else base
        for (ledger in body.ledgerLineGeometries) structural += ledger.draw(drawOffset, transformer)

        if (beamTip != null) {
            // Beamed: keep the existing beam and run the stem from the new notehead up/down to it. No
            // flag (it's beamed). The beam itself is left in the rendered score (not hidden / redrawn).
            val attach = if (beamTip.stemUp) body.stemUpAttachment else body.stemDownAttachment
            val stemLine = RelativeLine.vertical(
                x = drawOffset.x + attach.x,
                startY = drawOffset.y + attach.y,
                endY = beamTip.relativeY,
                thickness = config.engravingDefaults.stemThickness,
            )
            val absStem = transformer.toAbsolute(stemLine)
            structural += DrawLine(
                start = absStem.start,
                end = absStem.end,
                thickness = absStem.thickness,
                color = RenderColor.BLACK,
                bounds = RenderHelpers.calculateLineBounds(absStem),
            )
            return transformer.toAbsolute(drawOffset)
        }

        // Un-beamed: re-engrave the stem + flag at their computed length.
        val noteElement = NoteElement(
            time = event.onset,
            staffIndex = staffIndex,
            eventId = eventId,
            trackId = staffTrack.id,
            duration = event.duration,
            measureNumber = event.onset.measure,
            pitchData = newPitchData,
            isRest = false,
            beamInfo = null,
            resolvedStemDirection = stemDir,
            noteBody = body,
        )
        val layout = voiceLayoutBuilder.buildLayout(
            noteElement, staffIndex, staffTrack.id, event.onset.measure, stemDir
        )
        val stem = layout.stem
        if (stem != null) {
            val stemLine = RelativeLine.vertical(
                x = drawOffset.x + stem.relativeX,
                startY = drawOffset.y + stem.topY,
                endY = drawOffset.y + stem.bottomY,
                thickness = config.engravingDefaults.stemThickness,
            )
            val absStem = transformer.toAbsolute(stemLine)
            structural += DrawLine(
                start = absStem.start,
                end = absStem.end,
                thickness = absStem.thickness,
                color = RenderColor.BLACK,
                bounds = RenderHelpers.calculateLineBounds(absStem),
            )
            val flag = layout.flag
            if (flag != null) {
                val glyph = FlagElement.getFlagGlyph(flag.flagCount, stem.direction)
                val flagPos = transformer.toAbsolute(
                    RelativePoint(drawOffset.x + flag.relativeX, drawOffset.y + flag.relativeY)
                )
                val fontSize = transformer.toPixels(StaffSpace(4f))
                structural += RenderHelpers.createGlyphCommand(glyph, flagPos, fontSize)
            }
        }
        return transformer.toAbsolute(drawOffset)
    }

    /** The beam endpoint a moved beamed note's stem should reconnect to (preserved from the render). */
    private data class BeamTip(val relativeY: StaffSpace, val stemUp: Boolean)

    /**
     * The beam tip for [eventId] from the displayed render: the far endpoint of the event's original
     * stem (the end touching the beam, i.e. the one farther from the notehead centre [noteheadCenterYAbs]).
     * Null when the event has no stem element. The beam doesn't move in a vertical drag, so reusing it
     * keeps the beam exactly where it is while the moved note's stem stretches to reach it.
     */
    private fun beamTipOf(result: RenderResult, eventId: EventId, noteheadCenterYAbs: Float): BeamTip? {
        val stemEl = result.elementsForEvent(eventId)
            .firstOrNull { it.type == RenderElementType.STEM } ?: return null
        val line = stemEl.commands.firstOrNull() as? DrawLine ?: return null
        val startY = line.start.y.value
        val endY = line.end.y.value
        val beamEndY: Float
        val noteEndY: Float
        if (kotlin.math.abs(startY - noteheadCenterYAbs) >= kotlin.math.abs(endY - noteheadCenterYAbs)) {
            beamEndY = startY; noteEndY = endY
        } else {
            beamEndY = endY; noteEndY = startY
        }
        val relativeY = result.transformerSnapshot.toRelative(AbsolutePoint(line.start.x, Pixels(beamEndY))).y
        return BeamTip(relativeY, stemUp = beamEndY < noteEndY)
    }

    /** Every (pitch, key) the [targets] would move — used to clamp the delta into MIDI range. */
    private fun movedPitchKeys(
        runtime: RuntimeScore,
        computed: ComputedScore,
        targets: Map<EventId, Set<Int>?>,
    ): List<Pair<Pitch, KeySignature>> {
        val out = ArrayList<Pair<Pitch, KeySignature>>()
        for ((eventId, movedIndices) in targets) {
            val event = computed.getComputedEvent(eventId) ?: continue
            if (event.isRest) continue
            val key = runtime.getKeySignatureAt(event.onset.measure)
            val indices = movedIndices?.takeIf { it.isNotEmpty() }
                ?.filter { it in event.pitchData.indices } ?: event.pitchData.indices.toList()
            indices.forEach { out.add(event.pitchData[it].pitch to key) }
        }
        return out
    }

    private fun homeStaffForEvent(runtime: RuntimeScore, eventId: EventId): RuntimeStaffTrack? =
        runtime.staffTracks.values.firstOrNull { staff ->
            staff.voiceTracks.any { voice -> voice.events.any { it.id == eventId } }
        }

    /** Respell [pd] up/down [stepDelta] diatonic steps using [key]'s default accidental (no temp accidental). */
    private fun transposedPitchData(
        pd: ComputedPitchData,
        stepDelta: Int,
        clef: Clef,
        octaveShiftDiatonicOffset: Int,
        key: KeySignature,
    ): ComputedPitchData {
        val newPitch = DiatonicTranspose.spell(key, pd.pitch.diatonicSteps + stepDelta)
        val staffPos = StaffPitchContext.staffPosition(newPitch, clef, octaveShiftDiatonicOffset)
        return ComputedPitchData(
            pitch = newPitch,
            midiPitch = newPitch.midiNumber,
            staffPosition = staffPos,
            effectiveAccidental = null,     // temporary accidental dropped; key default needs no glyph
            needsLedgerLine = staffPos > 5 || staffPos < -5,
        )
    }
}
