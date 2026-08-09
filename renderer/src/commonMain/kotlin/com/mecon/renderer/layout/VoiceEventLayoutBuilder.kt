package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.elements.NoteheadElement
import com.mecon.renderer.enums.NoteheadType
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.EngravingDefaults

/**
 * Builds VoiceEventLayout from NoteElement.
 *
 * This builder focuses on stem, flag, and beam layout. Note body elements
 * (noteheads, accidentals, dots, ledger lines) are pre-computed in
 * NoteElement.noteBody and rendered directly from there.
 *
 * All positions are relative to:
 * - X: The time code X position (usually 0, can be negative for accidentals)
 * - Y: The staff center line (0 = middle line)
 *
 * The coordinate system follows standard notation:
 * - Y increases downward (so negative Y is up)
 * - Staff position 0 = middle line (B4 in treble clef)
 * - Each staff position step = 0.5 staff spaces
 */
context(BravuraFont)
class VoiceEventLayoutBuilder(
    private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT
) {

    /**
     * Build a complete layout for a voice event.
     *
     * Note body geometry (noteheads, accidentals, dots, ledger lines) is
     * pre-computed in NoteElement.noteBody. This method builds:
     * - Primary element (NoteheadLayout/RestLayout) for stem attachment reference
     * - Stem, flag layouts
     * - Beam info
     *
     * The primary and chordNotes layouts are still needed for backward
     * compatibility with NoteRenderer and stem/flag positioning.
     *
     * @param event The note layout event with pre-computed geometry
     * @param staffIndex The staff index within the system
     * @param trackId The staff track ID
     * @param measureNumber The measure number
     * @param stemDirection The resolved stem direction (from StemDirectionResolver)
     * @return Complete VoiceEventLayout with all components
     */
    fun buildLayout(
        event: NoteElement,
        staffIndex: Int,
        trackId: TrackId,
        measureNumber: Int,
        stemDirection: StemDirection = StemDirection.UP
    ): VoiceEventLayout {
        return if (event.isRest) {
            buildRestLayout(event, staffIndex, trackId, measureNumber)
        } else {
            buildNoteLayout(event, staffIndex, trackId, measureNumber, stemDirection)
        }
    }

    /**
     * Build layout for a rest event.
     */
    private fun buildRestLayout(
        event: NoteElement,
        staffIndex: Int,
        trackId: TrackId,
        measureNumber: Int
    ): VoiceEventLayout {
        val restType = restTypeFromDuration(event.duration)
        val staffPosition = event.restStaffPosition ?: RestLayout.defaultRestStaffPosition(restType)
        val relativeY = RestLayout.relativeY(staffPosition)

        val primary = RestLayout(
            eventId = event.eventId,
            trackId = trackId,
            relativeX = StaffSpace.ZERO,
            relativeY = relativeY,
            staffIndex = staffIndex,
            restType = restType
        )

        return VoiceEventLayout(
            eventId = event.eventId,
            trackId = trackId,
            voiceTrackId = event.voiceTrackId ?: trackId,
            time = event.time,
            staffIndex = staffIndex,
            measureNumber = measureNumber,
            crossStaffOffset = event.crossStaffOffset,
            primary = primary
        )
    }

    /**
     * Build layout for a note/chord event.
     *
     * Uses pre-computed stem attachment points from NoteElement.noteBody
     * for precise stem positioning.
     *
     * @param event The note layout event with pre-computed geometry
     * @param staffIndex The staff index within the system
     * @param trackId The staff track ID
     * @param measureNumber The measure number
     * @param stemDirection The resolved stem direction (from StemDirectionResolver)
     */
    private fun buildNoteLayout(
        event: NoteElement,
        staffIndex: Int,
        trackId: TrackId,
        measureNumber: Int,
        stemDirection: StemDirection
    ): VoiceEventLayout {
        val noteheadType = noteheadTypeFromDuration(event.duration)

        // Sort pitches by staff position for proper ordering
        val sortedPitchData = event.pitchData.sortedBy { it.staffPosition }

        // Primary notehead is the note that determines stem attachment
        // For stem up: lowest note (bottom)
        // For stem down: highest note (top)
        val primaryPitchData = when (stemDirection) {
            StemDirection.UP -> sortedPitchData.first()
            StemDirection.DOWN -> sortedPitchData.last()
        }

        val primaryLayout = buildNoteheadLayout(
            pitchData = primaryPitchData,
            eventId = event.eventId,
            trackId = trackId,
            staffIndex = staffIndex,
            noteheadType = noteheadType
        )

        // Chord notes (all notes except primary) - needed for backward compatibility
        val chordNotes = sortedPitchData
            .filter { it != primaryPitchData }
            .map { pitchData ->
                buildNoteheadLayout(
                    pitchData = pitchData,
                    eventId = event.eventId,
                    trackId = trackId,
                    staffIndex = staffIndex,
                    noteheadType = noteheadType
                )
            }

        // Stem (only for notes that need stems)
        // Use pre-computed stem attachment from NoteElement.noteBody
        val stem = if (needsStem(event.duration)) {
            buildStemLayoutFromAttachment(
                event = event,
                sortedPitchData = sortedPitchData,
                direction = stemDirection,
                isBeamed = event.beamInfo != null
            )
        } else null

        // Flag (only for unbeamed flagged notes)
        val flag = if (stem != null && needsFlags(event.duration) && event.beamInfo == null) {
            buildFlagLayout(stem, event.duration)
        } else null

        // Ledger lines - needed for extent calculations
        val ledgerLines = buildLedgerLineLayouts(
            allNotes = listOf(primaryLayout) + chordNotes
        )

        // Beam info
        val beamInfo = event.beamInfo?.let { beam ->
            BeamLayoutInfo(
                groupId = beam.groupId,
                totalBeamCount = beam.totalBeamCount,
                beamsLeft = beam.beamsLeft,
                beamsRight = beam.beamsRight
            )
        }

        return VoiceEventLayout(
            eventId = event.eventId,
            trackId = trackId,
            voiceTrackId = event.voiceTrackId ?: trackId,
            time = event.time,
            staffIndex = staffIndex,
            measureNumber = measureNumber,
            crossStaffOffset = event.crossStaffOffset,
            primary = primaryLayout,
            chordNotes = chordNotes,
            stem = stem,
            flag = flag,
            ledgerLines = ledgerLines,
            beamInfo = beamInfo
        )
    }

    /**
     * Build a single notehead layout from pitch data.
     */
    private fun buildNoteheadLayout(
        pitchData: ComputedPitchData,
        eventId: com.mecon.api.primitive.EventId,
        trackId: TrackId,
        staffIndex: Int,
        noteheadType: NoteheadType
    ): NoteheadLayout {
        val staffPosition = pitchData.staffPosition
        val relativeY = staffPositionToRelativeY(staffPosition)
        val ledgerCount = NoteheadLayout.ledgerLineCountFromStaffPosition(staffPosition)

        return NoteheadLayout(
            eventId = eventId,
            trackId = trackId,
            relativeX = StaffSpace.ZERO,  // Noteheads are at the time code X
            relativeY = relativeY,
            staffIndex = staffIndex,
            noteheadType = noteheadType,
            staffPosition = staffPosition,
            accidental = pitchData.effectiveAccidental,
            needsLedgerLines = ledgerCount != 0,
            ledgerLineCount = ledgerCount
        )
    }

    /**
     * Build stem layout using pre-computed attachment points from NoteElement.
     *
     * This uses stemUpAttachment/stemDownAttachment from NoteElement.noteBody,
     * which were computed by NoteBodyElementBuilder with SMuFL anchor points.
     */
    private fun buildStemLayoutFromAttachment(
        event: NoteElement,
        sortedPitchData: List<ComputedPitchData>,
        direction: StemDirection,
        isBeamed: Boolean
    ): StemLayout {
        // Get pre-computed stem attachment point (X coordinate only)
        val attachment = when (direction) {
            StemDirection.UP -> event.stemUpAttachment
            StemDirection.DOWN -> event.stemDownAttachment
        }

        // Find the extent of all notes
        val topNoteY = sortedPitchData.minOf { staffPositionToRelativeY(it.staffPosition).value }
        val bottomNoteY = sortedPitchData.maxOf { staffPositionToRelativeY(it.staffPosition).value }

        // Calculate stem length (scaled for grace notes)
        val scale = event.noteScale.value
        val baseStemLength = config.stemLength * scale
        val extensionForBeam = if (isBeamed) config.beamedStemExtension * scale else StaffSpace.ZERO

        val (topY, bottomY) = when (direction) {
            StemDirection.UP -> {
                // Stem goes up from bottom note, extends above top note
                // Bottom: at lowest note
                // Top: extend above highest note by stem length
                val stemBottom = StaffSpace(bottomNoteY)
                val tipY = StaffSpace(topNoteY) - baseStemLength - extensionForBeam
                tipY to stemBottom
            }
            StemDirection.DOWN -> {
                // Stem goes down from top note, extends below bottom note
                // Top: at highest note
                // Bottom: extend below lowest note by stem length
                val stemTop = StaffSpace(topNoteY)
                val tipY = StaffSpace(bottomNoteY) + baseStemLength + extensionForBeam
                stemTop to tipY
            }
        }

        return StemLayout(
            relativeX = attachment.x,
            topY = topY,
            bottomY = bottomY,
            direction = direction,
            oppositeRelativeX = when (direction) {
                StemDirection.UP -> event.stemDownAttachment.x
                StemDirection.DOWN -> event.stemUpAttachment.x
            },
        )
    }

    /**
     * Build flag layout.
     */
    private fun buildFlagLayout(stem: StemLayout, duration: Duration): FlagLayout {
        val flagCount = flagCountFromDuration(duration)
        if (flagCount == 0) error("Called buildFlagLayout for non-flagged duration")

        return FlagLayout(
            relativeX = stem.relativeX,
            relativeY = stem.tipY,
            flagCount = flagCount,
            direction = stem.direction
        )
    }

    /**
     * Build ledger line layouts.
     */
    private fun buildLedgerLineLayouts(
        allNotes: List<NoteheadLayout>
    ): List<LedgerLineLayout> {
        val notesNeedingLedgers = allNotes.filter { it.needsLedgerLines }
        if (notesNeedingLedgers.isEmpty()) return emptyList()

        val layouts = mutableListOf<LedgerLineLayout>()
        // Notehead origin is at left edge (X=0), so ledger line extends from -extension to width+extension
        // All notes in a chord should have the same type, use the first one
        val noteheadWidth = NoteheadElement.defaultWidth(allNotes.first().noteheadType)
        val ledgerExtension = EngravingDefaults.BRAVURA.legerLineExtension
        val ledgerWidth = noteheadWidth + ledgerExtension * 2
        val ledgerX = -ledgerExtension

        // Find the range of ledger lines needed
        val minStaffPos = notesNeedingLedgers.minOf { it.staffPosition }
        val maxStaffPos = notesNeedingLedgers.maxOf { it.staffPosition }

        // Lines above staff (position > 4 for 5-line staff)
        if (maxStaffPos > 4) {
            for (pos in 6..maxStaffPos step 2) {
                layouts.add(
                    LedgerLineLayout(
                        relativeX = ledgerX,
                        relativeY = staffPositionToRelativeY(pos),
                        width = ledgerWidth,
                        staffPosition = pos
                    )
                )
            }
        }

        // Lines below staff (position < -4 for 5-line staff)
        if (minStaffPos < -4) {
            for (pos in -6 downTo minStaffPos step 2) {
                layouts.add(
                    LedgerLineLayout(
                        relativeX = ledgerX,
                        relativeY = staffPositionToRelativeY(pos),
                        width = ledgerWidth,
                        staffPosition = pos
                    )
                )
            }
        }

        return layouts
    }

    // ========== Utility Functions ==========

    /**
     * Check if duration needs a stem.
     */
    private fun needsStem(duration: Duration): Boolean =
        duration.base != DurationBase.WHOLE &&
        duration.base != DurationBase.BREVE &&
        duration.base != DurationBase.LONGA &&
        duration.base != DurationBase.MAXIMA

    /**
     * Check if duration needs flags (unbeamed).
     */
    private fun needsFlags(duration: Duration): Boolean =
        duration.base.ordinal >= DurationBase.EIGHTH.ordinal

    /**
     * Get flag count from duration.
     */
    private fun flagCountFromDuration(duration: Duration): Int = when (duration.base) {
        DurationBase.EIGHTH -> 1
        DurationBase.SIXTEENTH -> 2
        DurationBase.THIRTY_SECOND -> 3
        DurationBase.SIXTY_FOURTH -> 4
        DurationBase.ONE_TWENTY_EIGHTH -> 5
        else -> 0
    }

    companion object {
        /**
         * Create a builder with default configuration.
         */
        context(BravuraFont)
        fun default(): VoiceEventLayoutBuilder =
            VoiceEventLayoutBuilder()
    }
}
