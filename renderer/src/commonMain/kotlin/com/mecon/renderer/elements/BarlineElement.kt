package com.mecon.renderer.elements

import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.LineGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.geometry.computeMinimumWidth
import com.mecon.api.interaction.BarlineSection
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.LineCap
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.SmuflGlyphs
import kotlinx.serialization.Serializable

/**
 * Layout element for a barline.
 *
 * Barlines span all staves and use LineGeometry for rendering.
 * The actual vertical extent is determined at render time based on staff positions.
 */
@Serializable
data class BarlineElement(
    override val time: TimeCode,
    /** Barline type */
    val type: BarlineType,
    /** Measure number after this barline (0 for start of piece) */
    val measureNumber: Int,
    /** Pre-computed geometry (relative to x=0, needs vertical translation at render time) */
    val geometryList: List<DrawableGeometry>,
    /** X offset from time slot X position */
    override val relativeX: StaffSpace = StaffSpace.ZERO
) : LayoutElement, RenderableElement {
    override val staffIndex: Int = -1 // System-wide
    override val priority: Int = LayoutElement.PRIORITY_BARLINE
    override val minimumWidth: StaffSpace
        get() = if (geometryList.isNotEmpty()) {
            geometryList.computeMinimumWidth()
        } else {
            // Fallback for legacy construction
            when (type) {
                BarlineType.SINGLE -> StaffSpace(0.5f)
                BarlineType.DOUBLE -> StaffSpace(1.0f)
                BarlineType.FINAL, BarlineType.REVERSE_FINAL -> StaffSpace(1.5f)
                BarlineType.DASHED, BarlineType.DOTTED -> StaffSpace(0.5f)
                BarlineType.SHORT, BarlineType.TICK -> StaffSpace(0.3f)
                BarlineType.REPEAT_LEFT, BarlineType.REPEAT_RIGHT -> StaffSpace(2.0f)
                BarlineType.REPEAT_BOTH -> StaffSpace(3.0f)
            }
        }

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        if (geometryList.isEmpty()) return ElementRenderOutput.EMPTY
        val drawOffset = RelativePoint(context.offset.x + relativeX, context.offset.y)
        val commands = geometryList.flatMap { it.draw(drawOffset, context.transformer) }
        val elemId = context.idGenerator()
        val barline = context.computedScore.barlineAtTime(time)
        val builder = renderElement(elemId, RenderElementType.BARLINE)
            .addCommands(commands)
            .metadata("barlineTime", time.format())
        barline?.let { builder.measureNumber(it.measureNumber) }
        val element = builder.build()
        val sections = mutableListOf<SectionRegistration>()
        if (barline != null) {
            sections.add(SectionRegistration(BarlineSection(barline), elemId))
        }
        val hitAreas = mutableListOf<ElementHitArea>()
        val mergedBounds = geometryList.mergedScoreRelativeBounds(drawOffset)
        if (mergedBounds != null) {
            // Keep the engraving thin, but make the pointer target practical to acquire.
            val padding = StaffSpace(0.4f)
            hitAreas.add(ElementHitArea(
                elemId,
                RelativeRect(
                    RelativePoint(mergedBounds.origin.x - padding, mergedBounds.origin.y),
                    mergedBounds.width + padding * 2f,
                    mergedBounds.height,
                )
            ))
        }
        return ElementRenderOutput(listOf(element), sections, hitAreas)
    }

    companion object {
        // Standard 5-line staff: half height = 2 staff spaces
        private val HALF_STAFF_HEIGHT = StaffSpace(2f)

        /**
         * Create a BarlineElement with pre-computed geometry.
         *
         * Barline geometry uses staff-relative coordinates where Y=0 is the staff center.
         * For a standard 5-line staff, lines span from -2 to +2 staff spaces.
         * Each staff renders its own barline independently via draw().
         */
        context(com.mecon.renderer.smufl.BravuraFont)
        fun create(
            time: TimeCode,
            type: BarlineType,
            measureNumber: Int
        ): BarlineElement {
            val geometryList = computeBarlineGeometry(type)
            return BarlineElement(
                time = time,
                type = type,
                measureNumber = measureNumber,
                geometryList = geometryList
            )
        }

        /**
         * Compute geometry for a barline type.
         * Coordinates are relative to staff center (Y=0).
         * For a standard 5-line staff, vertical extent is -2 to +2 staff spaces.
         */
        context(com.mecon.renderer.smufl.BravuraFont)
        private fun computeBarlineGeometry(
            type: BarlineType
        ): List<DrawableGeometry> {
            val topY = -HALF_STAFF_HEIGHT
            val bottomY = HALF_STAFF_HEIGHT
            val defaults = this@BravuraFont.engravingDefaults

            val thin = defaults.thinBarlineThickness
            val thick = defaults.thickBarlineThickness
            val sep = defaults.barlineSeparation
            val dashed = defaults.dashedBarlineThickness

            return when (type) {
                BarlineType.SINGLE -> listOf(
                    LineGeometry.vertical(StaffSpace.ZERO, topY, bottomY, thin)
                )
                BarlineType.DOUBLE -> {
                    // Thin + Gap + Thin
                    val x1 = StaffSpace.ZERO
                    val x2 = x1 + thin / 2f + sep + thin / 2f
                    listOf(
                        LineGeometry.vertical(x1, topY, bottomY, thin),
                        LineGeometry.vertical(x2, topY, bottomY, thin)
                    )
                }
                BarlineType.FINAL -> {
                    // Thin + Gap + Thick
                    val x1 = StaffSpace.ZERO
                    val x2 = x1 + thin / 2f + sep + thick / 2f
                    listOf(
                        LineGeometry.vertical(x1, topY, bottomY, thin),
                        LineGeometry.vertical(x2, topY, bottomY, thick)
                    )
                }
                BarlineType.REVERSE_FINAL -> {
                    // Thick + Gap + Thin
                    val x1 = StaffSpace.ZERO
                    val x2 = x1 + thick / 2f + sep + thin / 2f
                    listOf(
                        LineGeometry.vertical(x1, topY, bottomY, thick),
                        LineGeometry.vertical(x2, topY, bottomY, thin)
                    )
                }
                BarlineType.DASHED -> listOf(
                    LineGeometry.vertical(
                        StaffSpace.ZERO,
                        topY,
                        bottomY,
                        dashed,
                        dashIntervals = listOf(
                            defaults.dashedBarlineDashLength,
                            defaults.dashedBarlineGapLength,
                        ),
                    )
                )
                BarlineType.DOTTED -> listOf(
                    LineGeometry.vertical(
                        StaffSpace.ZERO,
                        topY,
                        bottomY,
                        dashed,
                        dashIntervals = listOf(StaffSpace(0.04f), StaffSpace(0.46f)),
                        cap = LineCap.ROUND,
                    )
                )
                BarlineType.SHORT -> {
                    val staffHeight = bottomY - topY
                    listOf(
                        LineGeometry.vertical(StaffSpace.ZERO, topY + staffHeight * 0.25f, bottomY - staffHeight * 0.25f, thin)
                    )
                }
                BarlineType.TICK -> listOf(
                    LineGeometry.vertical(StaffSpace.ZERO, topY, topY + StaffSpace.ONE, thin)
                )
                BarlineType.REPEAT_LEFT -> {
                    // Start repeat: thick-thin-dots.
                    val thickX = StaffSpace.ZERO
                    val thinX = thickX + thick / 2f + sep + thin / 2f
                    val dotsX = thinX + thin / 2f + defaults.repeatBarlineDotSeparation
                    listOf(
                        LineGeometry.vertical(thickX, topY, bottomY, thick),
                        LineGeometry.vertical(thinX, topY, bottomY, thin),
                        repeatDots(dotsX),
                    )
                }
                BarlineType.REPEAT_RIGHT -> {
                    // End repeat: dots-thin-thick.
                    val dotsX = StaffSpace.ZERO
                    val dotWidth = this@BravuraFont.getAdvanceWidth(SmuflGlyphs.repeatDots)
                    val thinX = dotsX + dotWidth + defaults.repeatBarlineDotSeparation + thin / 2f
                    val thickX = thinX + thin / 2f + sep + thick / 2f
                    listOf(
                        repeatDots(dotsX),
                        LineGeometry.vertical(thinX, topY, bottomY, thin),
                        LineGeometry.vertical(thickX, topY, bottomY, thick),
                    )
                }
                BarlineType.REPEAT_BOTH -> {
                    // End + start repeat: dots-thin-thick-thin-dots.
                    val leftDotsX = StaffSpace.ZERO
                    val dotWidth = this@BravuraFont.getAdvanceWidth(SmuflGlyphs.repeatDots)
                    val leftThinX = leftDotsX + dotWidth + defaults.repeatBarlineDotSeparation + thin / 2f
                    val thickX = leftThinX + thin / 2f + sep + thick / 2f
                    val rightThinX = thickX + thick / 2f + sep + thin / 2f
                    val rightDotsX = rightThinX + thin / 2f + defaults.repeatBarlineDotSeparation
                    listOf(
                        repeatDots(leftDotsX),
                        LineGeometry.vertical(leftThinX, topY, bottomY, thin),
                        LineGeometry.vertical(thickX, topY, bottomY, thick),
                        LineGeometry.vertical(rightThinX, topY, bottomY, thin),
                        repeatDots(rightDotsX),
                    )
                }
            }
        }

        context(com.mecon.renderer.smufl.BravuraFont)
        private fun repeatDots(x: StaffSpace): GlyphGeometry =
            GlyphGeometry.fromBBox(
                glyph = SmuflGlyphs.repeatDots,
                // The two-dot glyph has a center around y=1.976 above its
                // baseline, so a baseline at +2 centers it on the staff.
                position = RelativePoint(x, StaffSpace(2f)),
                bbox = this@BravuraFont.getBBox(SmuflGlyphs.repeatDots),
            )
    }
}
