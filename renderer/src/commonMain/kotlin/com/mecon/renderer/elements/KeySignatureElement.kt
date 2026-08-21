package com.mecon.renderer.elements

import com.mecon.api.computed.CancellationNatural
import com.mecon.api.computed.ComputedKeySignature
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.engine.KeySignaturePositionComputer
import com.mecon.renderer.enums.ClefType
import com.mecon.renderer.enums.toClefType
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.computeMinimumWidth
import com.mecon.api.interaction.KeySignatureSection
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * Layout element for a key signature.
 *
 * The key signature geometry is pre-computed with Y position relative to staff center (Y=0).
 * Each accidental is positioned according to standard key signature placement rules.
 */
@Serializable
data class KeySignatureElement(
    override val time: TimeCode,
    override val staffIndex: Int,
    /** Key signature (number of sharps/flats) */
    val keySignature: KeySignature,
    /** Staff track this key signature belongs to, when known. */
    val staffTrackId: TrackId? = null,
    /** Computed key-signature time used for selection; restated line headers may differ from [time]. */
    val sectionTime: TimeCode = time,
    /** Whether this is the initial key signature (at start of system) */
    val isInitial: Boolean,
    /** The clef type (needed for correct accidental positioning) */
    val clefType: ClefType,
    /** Pre-computed geometry (relative to staff center Y=0) */
    val geometryList: List<DrawableGeometry>,
    /** X offset from time slot X position */
    override val relativeX: StaffSpace = StaffSpace.ZERO
) : LayoutElement, RenderableElement {
    override val priority: Int = LayoutElement.PRIORITY_KEY_SIGNATURE
    override val minimumWidth: StaffSpace
        get() = if (geometryList.isNotEmpty()) {
            geometryList.computeMinimumWidth()
        } else {
            // Fallback for legacy construction
            StaffSpace(abs(keySignature.fifths) * 1.0f + 0.5f)
        }

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        if (geometryList.isEmpty()) return ElementRenderOutput.EMPTY
        val drawOffset = RelativePoint(context.offset.x + relativeX, context.offset.y)
        val commands = geometryList.flatMap { it.draw(drawOffset, context.transformer) }
        val elemId = context.idGenerator()
        val element = renderElement(elemId, RenderElementType.KEY_SIGNATURE)
            .addCommands(commands)
            .build()
        val sections = mutableListOf<SectionRegistration>()
        val computed = context.computedScore.keySignatures.find {
            it.time == sectionTime && it.keySignature == keySignature &&
                (staffTrackId == null || it.staffTrackId == staffTrackId)
        } ?: staffTrackId?.let {
            ComputedKeySignature(
                time = sectionTime,
                staffTrackId = it,
                keySignature = keySignature,
                isInitial = isInitial,
                cancellationNaturals = emptyList(),
            )
        }
        if (computed != null) {
            sections.add(SectionRegistration(KeySignatureSection(computed), elemId))
        }
        val hitAreas = mutableListOf<ElementHitArea>()
        val mergedBounds = geometryList.mergedScoreRelativeBounds(drawOffset)
        if (mergedBounds != null) {
            hitAreas.add(ElementHitArea(elemId, mergedBounds))
        }
        return ElementRenderOutput(listOf(element), sections, hitAreas)
    }

    companion object {
        /** Standard spacing between accidentals in key signature */
        private val ACCIDENTAL_SPACING = StaffSpace(1.0f)
        /** Extra gap between cancellation naturals and new accidentals */
        private val CANCELLATION_GAP = StaffSpace(0.5f)

        private fun staffPositionForNatural(
            natural: CancellationNatural,
            clef: Clef,
        ): Int = KeySignaturePositionComputer.staffPosition(
            natural.noteName,
            clef,
            sharps = natural.fromSharpKey,
        )

        private fun yOffsetForStaffPosition(staffPosition: Int): StaffSpace =
            if (staffPosition == 0) StaffSpace.ZERO else StaffSpace(staffPosition * -0.5f)

        context(BravuraFont)
        fun create(
            time: TimeCode,
            staffIndex: Int,
            keySignature: KeySignature,
            isInitial: Boolean,
            clef: Clef,
            staffTrackId: TrackId? = null,
            sectionTime: TimeCode = time,
            cancellationNaturals: List<CancellationNatural> = emptyList()
        ): KeySignatureElement {
            val geometryList = mutableListOf<DrawableGeometry>()
            var xCursor = StaffSpace.ZERO

            // 1. Cancellation naturals (if any)
            if (cancellationNaturals.isNotEmpty()) {
                val naturalGlyph = SmuflGlyphs.accidentalNatural
                val naturalBbox = this@BravuraFont.getBBox(naturalGlyph)

                for (natural in cancellationNaturals) {
                    val staffPos = staffPositionForNatural(natural, clef)
                    val yOffset = yOffsetForStaffPosition(staffPos)
                    geometryList.add(
                        GlyphGeometry.fromBBox(naturalGlyph, RelativePoint(xCursor, yOffset), naturalBbox)
                    )
                    xCursor += ACCIDENTAL_SPACING
                }

                if (keySignature.fifths != 0) {
                    xCursor += CANCELLATION_GAP
                }
            }

            // 2. New key signature accidentals
            if (keySignature.fifths != 0) {
                val isSharp = keySignature.fifths > 0
                val count = abs(keySignature.fifths).coerceAtMost(7)

                val positions = KeySignaturePositionComputer.staffPositions(keySignature, clef)

                val glyph = if (isSharp) SmuflGlyphs.accidentalSharp else SmuflGlyphs.accidentalFlat
                val bbox = this@BravuraFont.getBBox(glyph)

                for ((index, staffPosition) in positions.take(count).withIndex()) {
                    // Staff positions increase upward; renderer Y increases downward.
                    val yOffset = yOffsetForStaffPosition(staffPosition)
                    val xOffset = xCursor + ACCIDENTAL_SPACING * index.toFloat()
                    geometryList.add(
                        GlyphGeometry.fromBBox(glyph, RelativePoint(xOffset, yOffset), bbox)
                    )
                }
            }

            return KeySignatureElement(
                time = time,
                staffIndex = staffIndex,
                keySignature = keySignature,
                staffTrackId = staffTrackId,
                sectionTime = sectionTime,
                isInitial = isInitial,
                clefType = clef.toClefType(),
                geometryList = geometryList
            )
        }
    }
}
