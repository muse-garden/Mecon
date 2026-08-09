package com.mecon.renderer.elements

import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.computeMinimumWidth
import com.mecon.api.interaction.TimeSignatureSection
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlinx.serialization.Serializable

/**
 * Layout element for a time signature.
 *
 * The time signature geometry is pre-computed with Y position relative to staff center (Y=0).
 * Numerator is positioned above center, denominator below.
 */
@Serializable
data class TimeSignatureElement(
    override val time: TimeCode,
    override val staffIndex: Int,
    /** Time signature */
    val timeSignature: TimeSignature,
    /** Whether this is the initial time signature (at start of system) */
    val isInitial: Boolean,
    /** Pre-computed geometry (relative to staff center Y=0) */
    val geometryList: List<DrawableGeometry>,
    /** X offset from time slot X position */
    override val relativeX: StaffSpace = StaffSpace.ZERO
) : LayoutElement, RenderableElement {
    override val priority: Int = LayoutElement.PRIORITY_TIME_SIGNATURE
    override val minimumWidth: StaffSpace
        get() = if (geometryList.isNotEmpty()) {
            geometryList.computeMinimumWidth()
        } else {
            // Fallback for legacy construction
            StaffSpace(2.5f)
        }

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        if (geometryList.isEmpty()) return ElementRenderOutput.EMPTY
        val drawOffset = RelativePoint(context.offset.x + relativeX, context.offset.y)
        val commands = geometryList.flatMap { it.draw(drawOffset, context.transformer) }
        val elemId = context.idGenerator()
        val element = renderElement(elemId, RenderElementType.TIME_SIGNATURE)
            .addCommands(commands)
            .build()
        val sections = mutableListOf<SectionRegistration>()
        val computed = context.computedScore.timeSignatures.find {
            it.time == time && it.timeSignature == timeSignature
        }
        if (computed != null) {
            sections.add(SectionRegistration(TimeSignatureSection(computed), elemId))
        }
        val hitAreas = mutableListOf<ElementHitArea>()
        val mergedBounds = geometryList.mergedScoreRelativeBounds(drawOffset)
        if (mergedBounds != null) {
            hitAreas.add(ElementHitArea(elemId, mergedBounds))
        }
        return ElementRenderOutput(listOf(element), sections, hitAreas)
    }

    companion object {
        /**
         * Create a TimeSignatureElement with pre-computed geometry.
         *
         * @param time Time code for this time signature
         * @param staffIndex Staff index
         * @param timeSignature The time signature
         * @param isInitial Whether this is the initial time signature
         */
        context(BravuraFont)
        fun create(
            time: TimeCode,
            staffIndex: Int,
            timeSignature: TimeSignature,
            isInitial: Boolean
        ): TimeSignatureElement {
            val geometryList = mutableListOf<DrawableGeometry>()

            // Get digit glyphs for numerator
            val numeratorDigits = getDigitGlyphs(timeSignature.numerator)
            val denominatorDigits = getDigitGlyphs(timeSignature.denominator)

            // Position numerator above center (Y = -1 staff space)
            val numeratorY = StaffSpace(-1f)
            numeratorDigits.forEachIndexed { index, glyph ->
                val bbox = this@BravuraFont.getBBox(glyph)
                val position = RelativePoint(StaffSpace(index.toFloat()), numeratorY)
                geometryList.add(GlyphGeometry.fromBBox(glyph, position, bbox))
            }

            // Position denominator below center (Y = 1 staff space)
            val denominatorY = StaffSpace(1f)
            denominatorDigits.forEachIndexed { index, glyph ->
                val bbox = this@BravuraFont.getBBox(glyph)
                val position = RelativePoint(StaffSpace(index.toFloat()), denominatorY)
                geometryList.add(GlyphGeometry.fromBBox(glyph, position, bbox))
            }

            return TimeSignatureElement(
                time = time,
                staffIndex = staffIndex,
                timeSignature = timeSignature,
                isInitial = isInitial,
                geometryList = geometryList
            )
        }

        /**
         * Convert a number to a list of digit glyphs.
         */
        private fun getDigitGlyphs(number: Int): List<GlyphInfo> {
            if (number == 0) return listOf(SmuflGlyphs.timeSig0)

            val digits = mutableListOf<GlyphInfo>()
            var n = number
            while (n > 0) {
                digits.add(0, SmuflGlyphs.timeSigDigit(n % 10))
                n /= 10
            }
            return digits
        }
    }
}
