package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.renderer.elements.AccidentalRenderInfo
import com.mecon.renderer.elements.DotRenderInfo
import com.mecon.renderer.elements.NoteBodyElement
import com.mecon.renderer.elements.NoteheadElement
import com.mecon.renderer.elements.NoteheadRenderInfo
import com.mecon.renderer.enums.NoteheadType
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.LineGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.EngravingDefaults
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Builds NoteBodyElement for notes, chords, and rests.
 *
 * This builder creates geometry for elements "below the stem":
 * - Noteheads (for notes/chords) or rest glyphs
 * - Accidentals
 * - Augmentation dots
 * - Ledger lines
 *
 * It also computes stem attachment points for both up and down directions.
 * The stem, flag, and beam are computed separately by VoiceEventLayoutBuilder
 * once the stem direction is resolved.
 *
 * All positions are relative to:
 * - X: Time code X position (noteheads at X=0, accidentals have negative X)
 * - Y: Staff center line (Y=0 = middle line, positive Y is down)
 */
context(BravuraFont)
class NoteBodyElementBuilder(
    private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT,
    /**
     * Visual scale applied to glyph rendering and layout widths. 1.0 is normal
     * size; grace notes use [com.mecon.renderer.layout.RenderConstants.GRACE_NOTE_SCALE].
     */
    private val scale: Float = 1f
) {

    private fun noteheadWidth(type: NoteheadType): StaffSpace =
        NoteheadElement.defaultWidth(type) * scale

    /**
     * Build geometry for a note or chord.
     *
     * @param pitchData List of computed pitch data for all notes in the event
     * @param duration The duration of the event
     * @param stemDirection The stem direction (used for notehead arrangement in clusters).
     *                      If null, a default direction will be resolved from pitches.
     * @return NoteBodyElement with all drawable elements and stem attachment points
     */
    fun buildNoteGeometry(
        pitchData: List<ComputedPitchData>,
        duration: Duration,
        stemDirection: StemDirection? = null
    ): NoteBodyElement {
        if (pitchData.isEmpty()) {
            return NoteBodyElement.EMPTY
        }

        // Resolve stem direction (needed for cluster arrangement)
        val resolvedDirection = stemDirection ?: resolveDefaultStemDirection(pitchData)

        val noteheadType = noteheadTypeFromDuration(duration)

        // Sort the pitch *indices* by staff position (stable, so equal positions keep input order).
        // Working with indices — not the ComputedPitchData values — is essential: a chord may contain
        // two value-equal pitches (a unison, e.g. after dragging a note onto another), and a
        // value-keyed map / indexOf would collapse them into one. Each sortedIdx maps to a unique
        // original pitch index via [pitchIndexMap], and offsets are a list aligned with [sortedPitchData].
        val order = pitchData.indices.sortedBy { pitchData[it].staffPosition }
        val sortedPitchData = order.map { pitchData[it] }
        val pitchIndexMap = order // sortedIdx -> original pitchData index (unique even for unisons)

        // Calculate horizontal offsets for each pitch (handling seconds/clusters/unisons)
        val pitchOffsets = calculatePitchOffsets(sortedPitchData, resolvedDirection, noteheadType)

        // Build structured notehead info
        val noteheadInfos = sortedPitchData.mapIndexed { sortedIdx, pitch ->
            val offset = pitchOffsets[sortedIdx]
            val geometry = buildNoteheadGeometry(pitch, noteheadType, offset)
            NoteheadRenderInfo(
                pitchIndex = pitchIndexMap[sortedIdx],
                staffPosition = pitch.staffPosition,
                geometry = geometry
            )
        }

        // Build structured accidental info
        val accidentalInfos = buildAccidentalInfos(sortedPitchData, pitchOffsets, pitchIndexMap)

        // Build structured dot info
        val dotInfos = if (duration.dots > 0) {
            buildDotInfos(sortedPitchData, noteheadType, duration.dots, pitchOffsets, pitchIndexMap)
        } else {
            emptyList()
        }

        // Build ledger line geometries
        val ledgerGeometries = buildLedgerLineGeometries(sortedPitchData, noteheadType, pitchOffsets)

        // Calculate stem attachment points for both directions
        val (stemUpAttachment, stemDownAttachment) = calculateStemAttachmentPoints(
            sortedPitchData, noteheadType, pitchOffsets
        )

        // Calculate left and right extents
        val noteheadGeometries = noteheadInfos.map { it.geometry }
        val accidentalGeometries = accidentalInfos.map { it.geometry }
        val leftExtent = calculateLeftExtent(accidentalGeometries, noteheadGeometries)
        val rightExtent = calculateRightExtent(noteheadType, duration.dots, noteheadGeometries)

        return NoteBodyElement(
            noteheads = noteheadInfos,
            accidentals = accidentalInfos,
            dots = dotInfos,
            ledgerLineGeometries = ledgerGeometries,
            stemUpAttachment = stemUpAttachment,
            stemDownAttachment = stemDownAttachment,
            leftExtent = leftExtent,
            rightExtent = rightExtent
        )
    }

    /**
     * Build geometry for a rest.
     *
     * @param duration The duration of the rest
     * @param staffPosition Display staff position (0 = middle line, +up, -down). Null = use the
     *   type's default position ([RestLayout.defaultRestStaffPosition]).
     * @return NoteBodyElement with the rest glyph
     */
    fun buildRestElement(duration: Duration, staffPosition: Int? = null): NoteBodyElement {
        val restType = restTypeFromDuration(duration)
        val glyph = restType.glyph
        val bbox = this@BravuraFont.getBBox(glyph)

        // Rest Y position: explicit override snaps to a staff position; else the type default.
        val relativeY = RestLayout.relativeY(
            staffPosition ?: RestLayout.defaultRestStaffPosition(restType)
        )

        // X offset for left BBox extent (ensure glyph doesn't extend left of x=0)
        val xOffset = if (bbox != null && bbox.southWest.x.value < 0) {
            StaffSpace(-bbox.southWest.x.value * scale)
        } else {
            StaffSpace.ZERO
        }

        val position = RelativePoint(xOffset, relativeY)
        val glyphGeometry = GlyphGeometry.fromBBox(glyph, position, bbox, scale)

        // Calculate right extent
        val rightExtent = if (bbox != null) {
            xOffset + bbox.width * scale
        } else {
            StaffSpace(2.0f) // Fallback estimate
        }

        // Rest uses a single notehead info with pitchIndex=0
        return NoteBodyElement(
            noteheads = listOf(
                NoteheadRenderInfo(
                    pitchIndex = 0,
                    staffPosition = staffPosition ?: RestLayout.defaultRestStaffPosition(restType),
                    geometry = glyphGeometry
                )
            ),
            stemUpAttachment = RelativePoint.ZERO,
            stemDownAttachment = RelativePoint.ZERO,
            leftExtent = StaffSpace.ZERO,
            rightExtent = rightExtent
        )
    }

    /**
     * Build a single notehead glyph geometry.
     */
    private fun buildNoteheadGeometry(
        pitchData: ComputedPitchData,
        noteheadType: NoteheadType,
        xOffset: StaffSpace
    ): GlyphGeometry {
        val glyph = noteheadType.glyph
        val bbox = this@BravuraFont.getBBox(glyph)
        val relativeY = staffPositionToRelativeY(pitchData.staffPosition)

        val position = RelativePoint(xOffset, relativeY)

        return GlyphGeometry.fromBBox(glyph, position, bbox, scale)
    }

    /**
     * Build structured accidental info for all notes that need them.
     */
    private fun buildAccidentalInfos(
        sortedPitchData: List<ComputedPitchData>,
        pitchOffsets: List<StaffSpace>,
        pitchIndexMap: List<Int>
    ): List<AccidentalRenderInfo> {
        val pitchesWithAccidentals = sortedPitchData.withIndex()
            .filter { it.value.effectiveAccidental != null }
        if (pitchesWithAccidentals.isEmpty()) return emptyList()

        data class PendingAccidental(
            val sortedPitchIndex: Int,
            val glyph: GlyphInfo,
            val relativeY: StaffSpace,
            val prototype: GlyphGeometry,
        )

        // Conventional chord accidental order starts at the top and works down. Each sign is put in
        // the nearest column to the noteheads whose vertical contents it does not collide with. Thus
        // distant accidentals share a column, while seconds/unisons step successively to the left.
        val pending = pitchesWithAccidentals
            .sortedByDescending { it.value.staffPosition }
            .mapNotNull { (sortedIdx, pitch) ->
                val accidental = pitch.effectiveAccidental ?: return@mapNotNull null
                val glyph = accidentalToGlyph(accidental)
                val relativeY = staffPositionToRelativeY(pitch.staffPosition)
                PendingAccidental(
                    sortedPitchIndex = sortedIdx,
                    glyph = glyph,
                    relativeY = relativeY,
                    prototype = GlyphGeometry.fromBBox(
                        glyph,
                        RelativePoint(StaffSpace.ZERO, relativeY),
                        this@BravuraFont.getBBox(glyph),
                        scale,
                    ),
                )
            }

        val columns = mutableListOf<MutableList<PendingAccidental>>()
        val collisionPadding = config.accidentalSpacing / 2f
        for (candidate in pending) {
            val paddedBounds = candidate.prototype.bounds.expand(collisionPadding)
            val column = columns.firstOrNull { occupants ->
                occupants.none { occupant ->
                    paddedBounds.overlaps(occupant.prototype.bounds.expand(collisionPadding))
                }
            } ?: mutableListOf<PendingAccidental>().also(columns::add)
            column += candidate
        }

        // Align the actual glyph bounding boxes by their right edges. Using the font metrics rather
        // than approximate widths lets mixed flats/sharps keep a uniform gap.
        val minNoteheadX = StaffSpace(pitchOffsets.minOfOrNull { it.value } ?: 0f)
        var columnRight = minNoteheadX - config.accidentalNoteheadSpacing
        val result = mutableListOf<AccidentalRenderInfo>()
        for (column in columns) {
            for (candidate in column) {
                val positionX = columnRight - candidate.prototype.bounds.right
                val position = RelativePoint(positionX, candidate.relativeY)
                result += AccidentalRenderInfo(
                    pitchIndex = pitchIndexMap[candidate.sortedPitchIndex],
                    geometry = GlyphGeometry.fromBBox(
                        candidate.glyph,
                        position,
                        this@BravuraFont.getBBox(candidate.glyph),
                        scale,
                    ),
                )
            }
            val columnWidth = column.maxOf { it.prototype.bounds.width }
            columnRight -= columnWidth + config.accidentalSpacing
        }

        return result
    }

    /**
     * Build structured dot info for dotted notes.
     */
    private fun buildDotInfos(
        sortedPitchData: List<ComputedPitchData>,
        noteheadType: NoteheadType,
        dotCount: Int,
        pitchOffsets: List<StaffSpace>,
        pitchIndexMap: List<Int>
    ): List<DotRenderInfo> {
        if (dotCount == 0) return emptyList()

        val result = mutableListOf<DotRenderInfo>()
        val glyph = SmuflGlyphs.augmentationDot
        val bbox = this@BravuraFont.getBBox(glyph)
        val noteheadWidth = noteheadWidth(noteheadType)

        // Dots base X is determined by the rightmost notehead
        val maxNoteheadX = pitchOffsets.maxOfOrNull { it.value } ?: 0f
        val baseX = StaffSpace(maxNoteheadX) + noteheadWidth + config.noteheadDotSpacing

        for ((sortedIdx, pitch) in sortedPitchData.withIndex()) {
            val staffPosition = pitch.staffPosition
            val isOnLine = staffPosition % 2 == 0

            // Dots are placed in a space, not on a line
            // If note is on a line, move dot up to next space
            val dotY = if (isOnLine) {
                staffPositionToRelativeY(staffPosition) - StaffSpace.HALF
            } else {
                staffPositionToRelativeY(staffPosition)
            }

            for (i in 0 until dotCount) {
                val dotX = baseX + config.dotDotSpacing * i
                val position = RelativePoint(dotX, dotY)
                result.add(DotRenderInfo(
                    pitchIndex = pitchIndexMap[sortedIdx],
                    dotIndex = i,
                    geometry = GlyphGeometry.fromBBox(glyph, position, bbox, scale)
                ))
            }
        }

        return result
    }

    /**
     * Build ledger line geometries.
     */
    private fun buildLedgerLineGeometries(
        sortedPitchData: List<ComputedPitchData>,
        noteheadType: NoteheadType,
        pitchOffsets: List<StaffSpace>
    ): List<LineGeometry> {
        val pitchesNeedingLedgers = sortedPitchData.filter {
            NoteheadLayout.ledgerLineCountFromStaffPosition(it.staffPosition) != 0
        }
        if (pitchesNeedingLedgers.isEmpty()) return emptyList()

        val geometries = mutableListOf<LineGeometry>()
        val noteheadWidth = noteheadWidth(noteheadType)
        val ledgerExtension = EngravingDefaults.BRAVURA.legerLineExtension

        val minStaffPos = pitchesNeedingLedgers.minOf { it.staffPosition }
        val maxStaffPos = pitchesNeedingLedgers.maxOf { it.staffPosition }

        // Collect all staff positions that need a ledger line
        val ledgerPositions = mutableSetOf<Int>()

        if (maxStaffPos > 4) {
            for (pos in 6..maxStaffPos step 2) ledgerPositions.add(pos)
        }
        if (minStaffPos < -4) {
            for (pos in -6 downTo minStaffPos step 2) ledgerPositions.add(pos)
        }

        val ledgerThickness = EngravingDefaults.BRAVURA.staffLineThickness

        for (pos in ledgerPositions) {
            val minX = pitchOffsets.minOfOrNull { it.value } ?: 0f
            val maxX = pitchOffsets.maxOfOrNull { it.value } ?: 0f

            val startX = StaffSpace(minX) - ledgerExtension
            val endX = StaffSpace(maxX) + noteheadWidth + ledgerExtension

             geometries.add(
                LineGeometry.horizontal(
                    y = staffPositionToRelativeY(pos),
                    startX = startX,
                    endX = endX,
                    thickness = ledgerThickness
                )
            )
        }

        return geometries
    }

    /**
     * Calculate stem attachment points for both up and down directions.
     */
    private fun calculateStemAttachmentPoints(
        sortedPitchData: List<ComputedPitchData>,
        noteheadType: NoteheadType,
        pitchOffsets: List<StaffSpace>
    ): Pair<RelativePoint, RelativePoint> {
        if (sortedPitchData.isEmpty()) {
            return RelativePoint.ZERO to RelativePoint.ZERO
        }

        val glyphName = noteheadType.glyphName
        val stemThickness = config.engravingDefaults.stemThickness
        val stemOverlap = stemThickness / 2f

        val highestNote = sortedPitchData.last() // Min pos (Top)
        val lowestNote = sortedPitchData.first()   // Max pos (Bottom)

        val lowestY = staffPositionToRelativeY(lowestNote.staffPosition)
        val highestY = staffPositionToRelativeY(highestNote.staffPosition)

        // Offsets are aligned with sortedPitchData by index: first = lowest, last = highest.
        val lowestX = pitchOffsets.firstOrNull() ?: StaffSpace.ZERO
        val highestX = pitchOffsets.lastOrNull() ?: StaffSpace.ZERO

        // Stem-up attachment (at lowest note, right side).
        // SMuFL anchor coords are in unscaled staff spaces; scale them so the
        // stem connects to the scaled glyph's actual edge for grace notes.
        val stemUpAttachment = run {
            val anchor = this@BravuraFont.getStemUpAttachment(glyphName)
            if (anchor != null) {
                RelativePoint(
                    lowestX + StaffSpace(anchor.x.value * scale - stemOverlap.value),
                    lowestY + StaffSpace(-anchor.y.value * scale)
                )
            } else {
                val noteheadWidth = noteheadWidth(noteheadType)
                RelativePoint(
                    lowestX + StaffSpace(noteheadWidth.value - stemOverlap.value),
                    lowestY
                )
            }
        }

        // Stem-down attachment (at highest note, left side)
        val stemDownAttachment = run {
            val anchor = this@BravuraFont.getStemDownAttachment(glyphName)
            if (anchor != null) {
                RelativePoint(
                    highestX + StaffSpace(anchor.x.value * scale + stemOverlap.value),
                    highestY + StaffSpace(-anchor.y.value * scale)
                )
            } else {
                RelativePoint(highestX + stemOverlap, highestY)
            }
        }

        return stemUpAttachment to stemDownAttachment
    }

    /**
     * Calculate the left extent (minimum X).
     */
    private fun calculateLeftExtent(
        accidentalGeometries: List<GlyphGeometry>,
        noteheadGeometries: List<GlyphGeometry>
    ): StaffSpace {
        val accMin = if (accidentalGeometries.isNotEmpty()) accidentalGeometries.minOf { it.bounds.left } else StaffSpace.ZERO
        val noteMin = if (noteheadGeometries.isNotEmpty()) noteheadGeometries.minOf { it.bounds.left } else StaffSpace.ZERO
        return minOf(accMin, noteMin)
    }

    /**
     * Calculate the right extent (maximum X).
     */
    private fun calculateRightExtent(
        noteheadType: NoteheadType,
        dotCount: Int,
        noteheadGeometries: List<GlyphGeometry>
    ): StaffSpace {
        val noteMax = if (noteheadGeometries.isNotEmpty()) noteheadGeometries.maxOf { it.bounds.right } else StaffSpace.ZERO

        if (dotCount == 0) {
            return noteMax
        }

        // Dots are placed relative to the rightmost notehead
        val dotsExtent = noteMax +
            config.noteheadDotSpacing +
            config.dotDotSpacing * (dotCount - 1) +
            StaffSpace(0.5f)

        return dotsExtent
    }


    /**
     * Resolve default stem direction based on pitch positions.
     * Rule: If average position is above middle line, stem down. Else stem up.
     * Staff positions: < 0 is above, > 0 is below. 0 is middle.
     */
    private fun resolveDefaultStemDirection(pitchData: List<ComputedPitchData>): StemDirection {
        if (pitchData.isEmpty()) return StemDirection.UP

        val minPos = pitchData.minOf { it.staffPosition }
        val maxPos = pitchData.maxOf { it.staffPosition }

        val center = (minPos + maxPos) / 2.0

        return if (center < 0) {
            StemDirection.DOWN
        } else if (center > 0) {
            StemDirection.UP
        } else {
            StemDirection.DOWN
        }
    }

    /**
     * Calculate X offsets for noteheads to handle seconds/clusters.
     */
    private fun calculatePitchOffsets(
        sortedPitchData: List<ComputedPitchData>,
        direction: StemDirection,
        noteheadType: NoteheadType
    ): List<StaffSpace> {
        val n = sortedPitchData.size
        if (n == 0) return emptyList()

        val width = noteheadWidth(noteheadType)
        // Offsets aligned 1:1 with [sortedPitchData] by index (not keyed by pitch value), so two
        // value-equal pitches (a unison) each get their own slot and land on opposite sides of the stem.
        val offsets = MutableList(n) { StaffSpace.ZERO }

        if (direction == StemDirection.UP) {
            var previousOnLeft = true
            for (i in 0 until n) {
                if (i == 0) {
                    offsets[i] = StaffSpace.ZERO
                    previousOnLeft = true
                } else {
                    val interval = kotlin.math.abs(
                        sortedPitchData[i].staffPosition - sortedPitchData[i - 1].staffPosition
                    )
                    if (interval <= 1 && previousOnLeft) {
                        offsets[i] = width
                        previousOnLeft = false
                    } else {
                        offsets[i] = StaffSpace.ZERO
                        previousOnLeft = true
                    }
                }
            }
        } else {
            // Walk high→low (reversed); store into the reversed slot, then map back to sorted order.
            var previousOnRight = true
            var hasLeftPitch = false
            for (j in 0 until n) {
                val sortedIdx = n - 1 - j
                if (j == 0) {
                    offsets[sortedIdx] = StaffSpace.ZERO
                    previousOnRight = true
                } else {
                    val interval = kotlin.math.abs(
                        sortedPitchData[sortedIdx].staffPosition - sortedPitchData[sortedIdx + 1].staffPosition
                    )
                    if (interval <= 1 && previousOnRight) {
                        offsets[sortedIdx] = -width
                        previousOnRight = false
                        hasLeftPitch = true
                    } else {
                        offsets[sortedIdx] = StaffSpace.ZERO
                        previousOnRight = true
                    }
                }
            }
            if (hasLeftPitch) {
                for (i in 0 until n) offsets[i] = offsets[i] + width
            }
        }

        return offsets
    }

    // ========== Utility Functions ==========

    /**
     * Convert core Accidental to SMuFL glyph.
     */
    private fun accidentalToGlyph(accidental: Accidental): GlyphInfo = when (accidental) {
        Accidental.DOUBLE_FLAT -> SmuflGlyphs.accidentalDoubleFlat
        Accidental.FLAT -> SmuflGlyphs.accidentalFlat
        Accidental.NATURAL -> SmuflGlyphs.accidentalNatural
        Accidental.SHARP -> SmuflGlyphs.accidentalSharp
        Accidental.DOUBLE_SHARP -> SmuflGlyphs.accidentalDoubleSharp
    }

    companion object {
        /**
         * Create a builder with default configuration.
         */
        context(BravuraFont)
        fun default(): NoteBodyElementBuilder =
            NoteBodyElementBuilder()
    }
}
