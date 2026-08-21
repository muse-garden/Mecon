package com.mecon.renderer.elements

import com.mecon.api.computed.ComputedClef
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.enums.getReferenceLine
import com.mecon.renderer.enums.toClefType
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderConstants
import com.mecon.api.interaction.ClefSection
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont
import kotlinx.serialization.Serializable

/**
 * Layout element for a clef.
 *
 * The clef geometry is pre-computed with Y position relative to staff center (Y=0).
 */
@Serializable
data class ClefElement(
    override val time: TimeCode,
    override val staffIndex: Int,
    /** Clef type */
    val clef: Clef,
    /** Staff track this clef belongs to, when known. */
    val staffTrackId: TrackId? = null,
    /** Computed clef time used for selection; restated line headers may differ from [time]. */
    val sectionTime: TimeCode = time,
    /** Whether this is the initial clef (at start of system) */
    val isInitial: Boolean,
    /** Pre-computed geometry (relative to staff center Y=0) */
    val geometryList: List<DrawableGeometry>,
    /** X offset from time slot X position */
    override val relativeX: StaffSpace = StaffSpace.ZERO
) : LayoutElement, RenderableElement {
    override val priority: Int
        get() = if (isInitial) LayoutElement.PRIORITY_CLEF else LayoutElement.PRIORITY_CLEF_CHANGE
    override val minimumWidth: StaffSpace
        get() = geometryList.computeUnscaledMinimumWidth()
            ?: if (isInitial) RenderConstants.INITIAL_CLEF_WIDTH else RenderConstants.CLEF_CHANGE_WIDTH

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        if (geometryList.isEmpty()) return ElementRenderOutput.EMPTY
        val drawOffset = RelativePoint(context.offset.x + relativeX, context.offset.y)
        val commands = geometryList.flatMap { it.draw(drawOffset, context.transformer) }
        val elemId = context.idGenerator()
        val element = renderElement(elemId, RenderElementType.CLEF)
            .addCommands(commands)
            .build()
        val sections = mutableListOf<SectionRegistration>()
        val computed = context.computedScore.clefs.find {
            it.time == sectionTime && it.clef == clef && (staffTrackId == null || it.staffTrackId == staffTrackId)
        } ?: staffTrackId?.let {
            ComputedClef(
                time = sectionTime,
                staffTrackId = it,
                clef = clef,
                isInitial = isInitial,
            )
        }
        if (computed != null) {
            sections.add(SectionRegistration(ClefSection(computed), elemId))
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
         * Create a ClefElement with pre-computed geometry.
         *
         * @param time Time code for this clef
         * @param staffIndex Staff index
         * @param clef The clef type
         * @param isInitial Whether this is the initial clef at system start
         */
        context(BravuraFont)
        fun create(
            time: TimeCode,
            staffIndex: Int,
            clef: Clef,
            isInitial: Boolean,
            staffTrackId: TrackId? = null,
            sectionTime: TimeCode = time,
            scale: Float = 1f
        ): ClefElement {
            val clefType = clef.toClefType()
            val glyph = clefType.glyph
            val bbox = this@BravuraFont.getBBox(glyph)

            // Y offset from staff center based on reference line
            // Reference line is 1-5 (bottom to top), staff center is line 3
            // Y positive is down, so higher lines need negative Y offset
            val referenceLine = clefType.getReferenceLine()
            // Line 3 = Y=0 (staff center), Line 1 = Y=2, Line 5 = Y=-2
            val yOffset = StaffSpace((3 - referenceLine).toFloat())

            // X offset for left BBox extent (ensure glyph doesn't extend left of x=0)
            val xOffset = if (bbox != null && bbox.southWest.x.value < 0) {
                StaffSpace(-bbox.southWest.x.value * scale)
            } else {
                StaffSpace.ZERO
            }

            val position = RelativePoint(xOffset, yOffset)
            val glyphGeometry = GlyphGeometry.fromBBox(glyph, position, bbox, scale)

            return ClefElement(
                time = time,
                staffIndex = staffIndex,
                clef = clef,
                staffTrackId = staffTrackId,
                sectionTime = sectionTime,
                isInitial = isInitial,
                geometryList = listOf(glyphGeometry)
            )
        }
    }
}

private fun List<DrawableGeometry>.computeUnscaledMinimumWidth(): StaffSpace? {
    if (isEmpty()) return null
    return maxOf { geometry ->
        if (geometry is GlyphGeometry && geometry.scale != 0f) {
            StaffSpace(geometry.bounds.right.value / geometry.scale)
        } else {
            geometry.bounds.right
        }
    }
}
