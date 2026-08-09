package com.mecon.renderer.elements

import com.mecon.api.computed.BeamInfo
import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.StemDirection as StorageStemDirection
import com.mecon.api.storage.ArpeggioType
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.renderer.geometry.NoteScale
import com.mecon.renderer.layout.NoteBodyElementBuilder
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.RestLayout
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.DrawEllipse
import com.mecon.renderer.render.DrawGlyph
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.LineGeometry
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.smufl.SmuflGlyphs
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Layout element for a voice event (note, chord, or rest).
 *
 * The geometry for note body elements (noteheads/rests, accidentals, dots, ledger lines)
 * is pre-computed at creation time in [noteBody]. This provides:
 * - Accurate [minimumWidth] for layout calculations
 * - Drawable geometry via [geometryList] and [draw] method
 * - Stem attachment points for both directions
 *
 * Stem, flag, and beam geometry are computed separately by VoiceEventLayoutBuilder
 * once the stem direction is resolved.
 */
@Serializable
data class NoteElement(
    override val time: TimeCode,
    override val staffIndex: Int,
    /** The event ID */
    val eventId: EventId,
    /** Track ID (staff track this note renders on — the target staff for cross-staff notes). */
    val trackId: TrackId,
    /** Owning voice-track ID; null only for legacy or synthetic layout elements. */
    val voiceTrackId: TrackId? = null,
    /**
     * Track ID of the note's home staff, used to group cross-staff events into one
     * proportional-layout voice. Null = same as [trackId].
     */
    val voiceGroupTrackId: TrackId? = null,
    /** Duration of the event */
    val duration: Duration,
    /** Measure number */
    val measureNumber: Int,
    /** Computed pitch data for all pitches in this event */
    val pitchData: List<ComputedPitchData>,
    /** Whether this is a rest */
    val isRest: Boolean,
    /** Colored placeholder at the start of an otherwise hidden small-note input region. */
    val smallNoteRegionMarker: Boolean = false,
    /**
     * Display staff position override for a rest (from RenderingProps.restStaffPosition);
     * null means use the rest type's default. Ignored for notes/chords.
     */
    val restStaffPosition: Int? = null,
    /** User-specified stem direction (from RenderingProps), null means auto */
    val userStemDirection: StorageStemDirection? = null,
    /** Voice number within the staff (1-based) */
    val voiceNumber: Int = 1,
    /** Cross-staff render offset (0 = home staff; <0 = borrowed up, >0 = borrowed down). */
    val crossStaffOffset: Int = 0,
    /** Beam info (null if not beamed) */
    val beamInfo: BeamInfo?,
    /** Resolved stem direction (from layout context) */
    val resolvedStemDirection: StemDirection? = null,
    /** Pre-computed geometry for note body elements */
    val noteBody: NoteBodyElement = NoteBodyElement.EMPTY,
    /** X offset from time slot X position */
    override val relativeX: StaffSpace = StaffSpace.ZERO,
    /** Visual scale applied to glyphs, stem, beam, and flag (< 1 for grace/cue notes). */
    val noteScale: NoteScale = NoteScale.NORMAL,
    /** Computed source fact: vertical arpeggiation form for this chord. */
    val arpeggioType: ArpeggioType? = null,
    /**
     * Extra left overhang injected by the layout engine to reserve space for a
     * grace-note cluster that precedes this principal.  Not stored — recomputed
     * each layout pass.
     */
    @Transient val extraLeftOverhang: StaffSpace = StaffSpace.ZERO,
    /**
     * Extra slot width reserved by the same-time multi-voice solver. The solver stores each voice's
     * local offset in [relativeX] and fills each event's width to the complete staff-local cluster
     * width here, so proportional spacing includes the expanded cluster. Not stored — recomputed
     * with every affected time slot.
     */
    @Transient val multiVoiceWidthExtension: StaffSpace = StaffSpace.ZERO,
) : LayoutElement, RenderableElement {
    override val priority: Int = LayoutElement.PRIORITY_NOTE

    /** Minimum width computed from note body geometry */
    override val minimumWidth: StaffSpace
        get() = if (noteBody.noteheads.isNotEmpty() || isRest) {
            noteBody.width + multiVoiceWidthExtension
        } else {
            StaffSpace(2.5f) // Fallback for legacy construction
        }

    override val leftOverhang: StaffSpace
        get() {
            val bodyOverhang = if (noteBody.leftExtent < StaffSpace.ZERO) -noteBody.leftExtent else StaffSpace.ZERO
            val arpeggioOverhang = if (arpeggioType != null) bodyOverhang + StaffSpace(1.25f) else bodyOverhang
            return maxOf(extraLeftOverhang, arpeggioOverhang)
        }

    /** Stem attachment point for stem-up (at lowest notehead, right side) */
    val stemUpAttachment: RelativePoint get() = noteBody.stemUpAttachment

    /** Stem attachment point for stem-down (at highest notehead, left side) */
    val stemDownAttachment: RelativePoint get() = noteBody.stemDownAttachment

    /** Primary staff position (for vertical centering calculations) */
    val primaryStaffPosition: Int? get() = pitchData.minByOrNull { it.midiPitch }?.staffPosition

    /** Highest staff position */
    val highestStaffPosition: Int? get() = pitchData.maxByOrNull { it.staffPosition }?.staffPosition

    /** Lowest staff position */
    val lowestStaffPosition: Int? get() = pitchData.minByOrNull { it.staffPosition }?.staffPosition

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        if (noteBody.geometryList.isEmpty()) return ElementRenderOutput.EMPTY
        val drawOffset = RelativePoint(context.offset.x + relativeX, context.offset.y)
        val computedEvent = context.computedScore.getComputedEvent(eventId)

        val elements = mutableListOf<com.mecon.renderer.render.RenderElement>()
        val sections = mutableListOf<SectionRegistration>()
        val hitAreas = mutableListOf<ElementHitArea>()

        if (isRest) {
            // Rest: single RenderElement for the whole rest glyph
            val commands = noteBody.geometryList
                .flatMap { it.draw(drawOffset, context.transformer) }
                .map { command ->
                    if (!smallNoteRegionMarker) command else when (command) {
                        is DrawGlyph -> command.copy(color = SMALL_NOTE_REGION_MARKER_COLOR)
                        is DrawEllipse -> command.copy(
                            fillColor = command.fillColor?.let { SMALL_NOTE_REGION_MARKER_COLOR },
                            strokeColor = command.strokeColor?.let { SMALL_NOTE_REGION_MARKER_COLOR },
                        )
                        else -> command
                    }
                }
            val elemId = context.idGenerator()
            val element = renderElement(elemId, RenderElementType.REST)
                .addCommands(commands)
                .eventId(eventId)
                .trackId(trackId)
                .measureNumber(measureNumber)
                .staffIndex(staffIndex)
                .metadata(
                    "staffPosition",
                    (restStaffPosition ?: RestLayout.defaultRestStaffPosition(duration)).toString(),
                )
                .build()
            elements.add(element)
            if (computedEvent != null) {
                sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
            }
            val mergedBounds = noteBody.geometryList.mergedScoreRelativeBounds(drawOffset)
            if (mergedBounds != null) {
                hitAreas.add(ElementHitArea(elemId, mergedBounds))
            }
        } else {
            // Each notehead as independent RenderElement
            for (noteheadInfo in noteBody.noteheads) {
                val commands = noteheadInfo.geometry.draw(drawOffset, context.transformer)
                val elemId = context.idGenerator()
                val element = renderElement(elemId, RenderElementType.NOTEHEAD)
                    .addCommands(commands)
                    .eventId(eventId)
                    .trackId(trackId)
                    .measureNumber(measureNumber)
                    .staffIndex(staffIndex)
                    .metadata("pitchIndex", noteheadInfo.pitchIndex.toString())
                    .build()
                elements.add(element)
                if (computedEvent != null) {
                    sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
                    sections.add(SectionRegistration(
                        VoiceNoteSection(computedEvent, noteheadInfo.pitchIndex), elemId
                    ))
                }
                hitAreas.add(ElementHitArea(elemId, noteheadInfo.geometry.scoreRelativeBounds(drawOffset)))
            }

            // Each accidental as independent RenderElement (linked to its notehead's pitch)
            for (accInfo in noteBody.accidentals) {
                val commands = accInfo.geometry.draw(drawOffset, context.transformer)
                val elemId = context.idGenerator()
                val element = renderElement(elemId, RenderElementType.ACCIDENTAL)
                    .addCommands(commands)
                    .eventId(eventId)
                    .trackId(trackId)
                    .measureNumber(measureNumber)
                    .staffIndex(staffIndex)
                    .metadata("pitchIndex", accInfo.pitchIndex.toString())
                    .build()
                elements.add(element)
                if (computedEvent != null) {
                    sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
                    sections.add(SectionRegistration(
                        VoiceNoteSection(computedEvent, accInfo.pitchIndex), elemId
                    ))
                }
                hitAreas.add(ElementHitArea(elemId, accInfo.geometry.scoreRelativeBounds(drawOffset)))
            }

            // Each dot as independent RenderElement (linked to its notehead's pitch)
            for (dotInfo in noteBody.dots) {
                val commands = dotInfo.geometry.draw(drawOffset, context.transformer)
                val elemId = context.idGenerator()
                val element = renderElement(elemId, RenderElementType.DOT)
                    .addCommands(commands)
                    .eventId(eventId)
                    .trackId(trackId)
                    .measureNumber(measureNumber)
                    .staffIndex(staffIndex)
                    .metadata("pitchIndex", dotInfo.pitchIndex.toString())
                    .build()
                elements.add(element)
                if (computedEvent != null) {
                    sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
                    sections.add(SectionRegistration(
                        VoiceNoteSection(computedEvent, dotInfo.pitchIndex), elemId
                    ))
                }
                hitAreas.add(ElementHitArea(elemId, dotInfo.geometry.scoreRelativeBounds(drawOffset)))
            }

            // Ledger lines as a single RenderElement
            if (noteBody.ledgerLineGeometries.isNotEmpty()) {
                val commands = noteBody.ledgerLineGeometries.flatMap { it.draw(drawOffset, context.transformer) }
                val elemId = context.idGenerator()
                val element = renderElement(elemId, RenderElementType.LEDGER_LINE)
                    .addCommands(commands)
                    .eventId(eventId)
                    .trackId(trackId)
                    .measureNumber(measureNumber)
                    .staffIndex(staffIndex)
                    .build()
                elements.add(element)
                if (computedEvent != null) {
                    sections.add(SectionRegistration(VoiceEventSection(computedEvent), elemId))
                }
                @Suppress("UNCHECKED_CAST")
                val ledgerGeometries = noteBody.ledgerLineGeometries as List<com.mecon.renderer.geometry.DrawableGeometry>
                val mergedBounds = ledgerGeometries.mergedScoreRelativeBounds(drawOffset)
                if (mergedBounds != null) {
                    hitAreas.add(ElementHitArea(elemId, mergedBounds))
                }
            }

            if (arpeggioType != null && noteBody.noteheads.size >= 2) {
                val noteBounds = noteBody.noteheads.map { it.geometry.bounds }
                val top = noteBounds.minOf { it.top }
                val bottom = noteBounds.maxOf { it.bottom }
                val x = noteBody.leftExtent - StaffSpace(0.75f)
                val geometries = mutableListOf<com.mecon.renderer.geometry.DrawableGeometry>()
                if (arpeggioType == ArpeggioType.NON_ARPEGGIATE) {
                    val thickness = StaffSpace(0.10f)
                    val hook = StaffSpace(0.45f)
                    geometries += LineGeometry.vertical(x, top, bottom, thickness)
                    geometries += LineGeometry.horizontal(top, x, x + hook, thickness)
                    geometries += LineGeometry.horizontal(bottom, x, x + hook, thickness)
                } else {
                    val segment = SmuflGlyphs.arpeggiato
                    val segmentBox = this@BravuraFont.getBBox(segment)
                    if (segmentBox != null) {
                        val advance = segmentBox.height.takeIf { it.value > 0.05f } ?: StaffSpace(0.8f)
                        var y = top
                        while (y < bottom) {
                            geometries += GlyphGeometry.fromBBox(
                                segment,
                                RelativePoint(x - segmentBox.southWest.x, y + segmentBox.northEast.y),
                                segmentBox,
                            )
                            y += advance
                        }
                    }
                    val arrowGlyph = when (arpeggioType) {
                        ArpeggioType.UP -> SmuflGlyphs.wiggleArpeggiatoUpArrow
                        ArpeggioType.DOWN -> SmuflGlyphs.wiggleArpeggiatoDownArrow
                        else -> null
                    }
                    arrowGlyph?.let { arrow ->
                        this@BravuraFont.getBBox(arrow)?.let { box ->
                            val edgeY = if (arpeggioType == ArpeggioType.UP) top else bottom - box.height
                            geometries += GlyphGeometry.fromBBox(
                                arrow,
                                RelativePoint(x - box.southWest.x, edgeY + box.northEast.y),
                                box,
                            )
                        }
                    }
                }
                val commands = geometries.flatMap { it.draw(drawOffset, context.transformer) }
                if (commands.isNotEmpty()) {
                    val elemId = context.idGenerator()
                    elements += renderElement(elemId, RenderElementType.ORNAMENT)
                        .addCommands(commands)
                        .eventId(eventId)
                        .trackId(trackId)
                        .measureNumber(measureNumber)
                        .staffIndex(staffIndex)
                        .build()
                    if (computedEvent != null) {
                        sections += SectionRegistration(VoiceEventSection(computedEvent), elemId)
                    }
                    geometries.mergedScoreRelativeBounds(drawOffset)?.let {
                        hitAreas += ElementHitArea(elemId, it)
                    }
                }
            }
        }

        return ElementRenderOutput(elements, sections, hitAreas)
    }

    companion object {
        /**
         * Create from a ComputedVoiceEvent with pre-computed geometry.
         *
         * This is the preferred factory method. It computes note body geometry
         * at creation time for accurate layout width calculations.
         *
         * @param event The computed voice event
         * @param staffIndex The staff index within the system
         * @param trackId The staff track ID
         * @param voiceNumber The voice number within the staff (1-based)
         * @param config Layout configuration
         */
        context(BravuraFont)
        fun create(
            event: ComputedVoiceEvent,
            staffIndex: Int,
            trackId: TrackId,
            voiceNumber: Int = 1,
            config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT,
            resolvedStemDirection: StemDirection? = null,
            voiceTrackId: TrackId? = null,
            voiceGroupTrackId: TrackId? = null
        ): NoteElement {
            val scale = event.rendering?.scale
                ?: if (event.isGrace) config.graceNoteScale else 1f
            val noteScale = NoteScale(scale)
            val builder = NoteBodyElementBuilder(config, scale = scale)
            val smallNoteRegionMarker =
                event.isRest &&
                    event.rendering?.hidden == true &&
                    event.tupletInfo?.smallNotes == true

            val noteBody = if (event.isRest && event.rendering?.hidden == true && !smallNoteRegionMarker) {
                NoteBodyElement.EMPTY
            } else if (event.isRest) {
                builder.buildRestElement(event.duration, event.rendering?.restStaffPosition)
            } else {
                builder.buildNoteGeometry(
                    event.pitchData,
                    event.duration,
                    resolvedStemDirection
                )
            }

            return NoteElement(
                time = event.onset,
                staffIndex = staffIndex,
                eventId = event.id,
                trackId = trackId,
                voiceTrackId = voiceTrackId,
                voiceGroupTrackId = voiceGroupTrackId,
                duration = event.duration,
                measureNumber = event.measurePosition.measure,
                pitchData = event.pitchData,
                isRest = event.isRest,
                smallNoteRegionMarker = smallNoteRegionMarker,
                restStaffPosition = event.rendering?.restStaffPosition,
                userStemDirection = event.rendering?.stemDirection,
                voiceNumber = voiceNumber,
                crossStaffOffset = event.rendering?.crossStaffOffset ?: 0,
                beamInfo = event.beamInfo,
                resolvedStemDirection = resolvedStemDirection,
                noteBody = noteBody,
                noteScale = noteScale,
                arpeggioType = event.rendering?.arpeggio,
            )
        }

        private val SMALL_NOTE_REGION_MARKER_COLOR = RenderColor.rgb(70, 140, 215)
    }
}
