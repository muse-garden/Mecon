package com.mecon.renderer.elements

import com.mecon.api.interaction.VoiceTupletSection
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.events.TupletDisplayStyle
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.RelativeLine
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.SlurCurveBuilder
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.TupletLayout
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.DrawPath
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.renderElement
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Renders one tuplet group — bracket / slur / number — from a pre-resolved
 * [TupletLayout].
 *
 * Geometry was fully resolved earlier by
 * [com.mecon.renderer.render.TupletLayoutComputer], so this element only
 * translates the chosen [TupletDisplayStyle] into draw commands. The numeric
 * label is drawn as SMuFL tuplet glyphs (U+E880–U+E889) so it matches the
 * engraving of the rest of the score.
 */
data class TupletElement(
    val tupletLayout: TupletLayout,
    private val config: RenderLayoutConfig
) : RenderableElement {

    context(BravuraFont)
    override fun render(context: ElementRenderContext): ElementRenderOutput {
        if (tupletLayout.displayStyle == TupletDisplayStyle.NONE) {
            return ElementRenderOutput.EMPTY
        }

        val commands = mutableListOf<RenderCommand>()
        val relativeBoundsList = mutableListOf<RelativeRect>()

        val startX = tupletLayout.start.x
        val endX = tupletLayout.end.x
        val startY = tupletLayout.start.y
        val endY = tupletLayout.end.y
        val midX = (startX + endX) / 2f
        val midY = StaffSpace((startY.value + endY.value) * 0.5f)
        val spanX = (endX.value - startX.value).let { if (kotlin.math.abs(it) < 0.0001f) 1f else it }
        fun lineY(x: StaffSpace): StaffSpace {
            val t = ((x.value - startX.value) / spanX).coerceIn(0f, 1f)
            return StaffSpace(startY.value + (endY.value - startY.value) * t)
        }

        // ABOVE → push number further up (negative Y); BELOW → further down.
        val numberSign = if (tupletLayout.direction == SlurDirection.ABOVE) -1f else 1f
        val numberGlyphs = resolveNumberGlyphs(tupletLayout.numberText)
        val numberWidth = numberGlyphsWidth(numberGlyphs)

        when (tupletLayout.displayStyle) {
            TupletDisplayStyle.NONE -> Unit

            TupletDisplayStyle.NUMBER_ONLY -> {
                val numberY = midY + StaffSpace(NUMBER_ONLY_OFFSET.value * numberSign)
                val glyphBounds = addNumberGlyphs(commands, midX, numberY, numberGlyphs, context)
                relativeBoundsList.addAll(glyphBounds)
            }

            TupletDisplayStyle.BRACKET_AND_NUMBER -> {
                // Hooks point toward the staff (opposite of bracket side).
                val hookSign = if (tupletLayout.direction == SlurDirection.ABOVE) 1f else -1f
                val startHookEndY = startY + StaffSpace(HOOK_LENGTH.value * hookSign)
                val endHookEndY = endY + StaffSpace(HOOK_LENGTH.value * hookSign)
                val bracketThickness = config.engravingDefaults.tupletBracketThickness

                addLine(commands,
                    RelativePoint(startX, startY),
                    RelativePoint(startX, startHookEndY),
                    bracketThickness, context)
                addLine(commands,
                    RelativePoint(endX, endY),
                    RelativePoint(endX, endHookEndY),
                    bracketThickness, context)

                val numberY = midY + StaffSpace(BRACKET_NUMBER_OFFSET.value * numberSign)

                // Slanted segments left/right of the number gap. Sized to the actual
                // glyph footprint so the bracket meets the digits cleanly.
                val numberHalfWidth = StaffSpace(numberWidth.value * 0.5f)
                val gapStart = midX - numberHalfWidth - GAP_PADDING
                val gapEnd = midX + numberHalfWidth + GAP_PADDING
                if (gapStart > startX) {
                    addLine(commands,
                        RelativePoint(startX, startY),
                        RelativePoint(gapStart, lineY(gapStart)),
                        bracketThickness, context)
                }
                if (gapEnd < endX) {
                    addLine(commands,
                        RelativePoint(gapEnd, lineY(gapEnd)),
                        RelativePoint(endX, endY),
                        bracketThickness, context)
                }
                val glyphBounds = addNumberGlyphs(commands, midX, numberY, numberGlyphs, context)
                relativeBoundsList.addAll(glyphBounds)

                val minY = minOf(startY.value, endY.value, startHookEndY.value, endHookEndY.value)
                val maxY = maxOf(startY.value, endY.value, startHookEndY.value, endHookEndY.value)
                relativeBoundsList.add(RelativeRect(
                    origin = RelativePoint(startX, StaffSpace(minY)),
                    width = endX - startX,
                    height = StaffSpace(maxY - minY)
                ))
            }

            TupletDisplayStyle.SLUR_AND_NUMBER -> {
                val midpointThickness = config.engravingDefaults.slurMidpointThickness
                val relativePath = SlurCurveBuilder.buildLensPath(
                    start = tupletLayout.start,
                    end = tupletLayout.end,
                    direction = tupletLayout.direction,
                    midpointThickness = midpointThickness,
                    maxHeight = SLUR_MAX_HEIGHT
                )
                val relativeSlurBounds = SlurCurveBuilder.lensBounds(
                    start = tupletLayout.start,
                    end = tupletLayout.end,
                    direction = tupletLayout.direction,
                    midpointThickness = midpointThickness,
                    maxHeight = SLUR_MAX_HEIGHT
                )
                commands.add(DrawPath(
                    path = context.transformer.toAbsolute(relativePath),
                    fillColor = RenderColor.BLACK,
                    strokeColor = null,
                    bounds = context.transformer.toAbsolute(relativeSlurBounds)
                ))
                relativeBoundsList.add(relativeSlurBounds)

                val numberY = midY + StaffSpace(SLUR_NUMBER_OFFSET.value * numberSign)
                val glyphBounds = addNumberGlyphs(commands, midX, numberY, numberGlyphs, context)
                relativeBoundsList.addAll(glyphBounds)
            }
        }

        val elemId = context.idGenerator()
        val element = renderElement(elemId, RenderElementType.TUPLET_BRACKET)
            .addCommands(commands)
            .eventId(tupletLayout.startEventId)
            .trackId(tupletLayout.trackId)
            .measureNumber(tupletLayout.measureNumber)
            .staffIndex(tupletLayout.staffIndex)
            .build()

        val sections = mutableListOf<SectionRegistration>()
        val startEvent = context.computedScore.getComputedEvent(tupletLayout.startEventId)
        if (startEvent != null) {
            sections.add(SectionRegistration(VoiceTupletSection(startEvent), elemId))
        }

        val mergedBounds = mergeRelativeBounds(relativeBoundsList)
        val hitAreas = if (mergedBounds != null)
            listOf(ElementHitArea(elemId, mergedBounds))
        else emptyList()

        return ElementRenderOutput(
            renderElements = listOf(element),
            sectionRegistrations = sections,
            hitAreas = hitAreas
        )
    }

    private fun addLine(
        commands: MutableList<RenderCommand>,
        start: RelativePoint,
        end: RelativePoint,
        thickness: StaffSpace,
        context: ElementRenderContext
    ) {
        val absLine = context.transformer.toAbsolute(RelativeLine(start, end, thickness))
        commands.add(DrawLine(
            start = absLine.start,
            end = absLine.end,
            thickness = absLine.thickness,
            color = RenderColor.BLACK,
            bounds = RenderHelpers.calculateLineBounds(absLine)
        ))
    }

    private fun resolveNumberGlyphs(text: String): List<GlyphInfo> = text.mapNotNull { ch ->
        ch.digitToIntOrNull()?.let { SmuflGlyphs.tupletDigit(it) }
    }

    context(BravuraFont)
    private fun numberGlyphsWidth(glyphs: List<GlyphInfo>): StaffSpace {
        if (glyphs.isEmpty()) return StaffSpace.ZERO
        var total = 0f
        for (g in glyphs) {
            total += this@BravuraFont.getAdvanceWidth(g).value
        }
        return StaffSpace(total * NUMBER_SCALE)
    }

    /**
     * Lay out the tuplet number using SMuFL tuplet digit glyphs (U+E880–U+E889),
     * horizontally centered on [midX] and vertically centered on [centerY]
     * (via the glyph bbox midline).
     */
    context(BravuraFont)
    private fun addNumberGlyphs(
        commands: MutableList<RenderCommand>,
        midX: StaffSpace,
        centerY: StaffSpace,
        glyphs: List<GlyphInfo>,
        context: ElementRenderContext
    ): List<RelativeRect> {
        if (glyphs.isEmpty()) return emptyList()
        val total = numberGlyphsWidth(glyphs)
        // SMuFL fonts use 1 em = 4 staff spaces; multiply by NUMBER_SCALE to shrink/grow the digit.
        val fontSize = context.transformer.toPixels(StaffSpace(4f * NUMBER_SCALE))
        var cursor = midX - total / 2f
        val bounds = mutableListOf<RelativeRect>()
        for (glyph in glyphs) {
            val bbox = this@BravuraFont.getBBox(glyph)
            val advance = StaffSpace(this@BravuraFont.getAdvanceWidth(glyph).value * NUMBER_SCALE)
            // SMuFL Y is up; render Y is down. Glyph occupies render-Y range
            // [position.y - bbox.northEast.y, position.y - bbox.southWest.y]. To
            // center the bbox on centerY we offset position.y by the bbox midline,
            // pre-multiplied by NUMBER_SCALE so it tracks the rendered glyph size.
            val verticalAdjust = if (bbox != null)
                StaffSpace((bbox.northEast.y.value + bbox.southWest.y.value) * 0.5f * NUMBER_SCALE)
            else StaffSpace.ZERO
            val absOrigin = context.transformer.toAbsolute(
                RelativePoint(cursor, centerY + verticalAdjust)
            )
            val command = RenderHelpers.createGlyphCommand(glyph, absOrigin, fontSize)
            commands.add(command)
            // Track approximate relative bounds for hit-testing.
            if (bbox != null) {
                bounds.add(RelativeRect(
                    origin = RelativePoint(
                        cursor + StaffSpace(bbox.southWest.x.value * NUMBER_SCALE),
                        centerY + verticalAdjust - StaffSpace(bbox.northEast.y.value * NUMBER_SCALE)
                    ),
                    width = StaffSpace(bbox.width.value * NUMBER_SCALE),
                    height = StaffSpace(bbox.height.value * NUMBER_SCALE)
                ))
            }
            cursor += advance
        }
        return bounds
    }

    private fun mergeRelativeBounds(rects: List<RelativeRect>): RelativeRect? {
        if (rects.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (r in rects) {
            minX = minOf(minX, r.origin.x.value)
            minY = minOf(minY, r.origin.y.value)
            maxX = maxOf(maxX, r.origin.x.value + r.width.value)
            maxY = maxOf(maxY, r.origin.y.value + r.height.value)
        }
        return RelativeRect(
            origin = RelativePoint(StaffSpace(minX), StaffSpace(minY)),
            width = StaffSpace(maxX - minX),
            height = StaffSpace(maxY - minY)
        )
    }

    companion object {
        /** Length of the perpendicular hook at each bracket end. */
        val HOOK_LENGTH = StaffSpace(0.5f)
        /** Extra padding on each side of the number when breaking the bracket. */
        val GAP_PADDING = StaffSpace(0.2f)
        /**
         * Additional offset of the digit beyond the bracket baseline, in the same
         * direction as [TupletLayout.direction]. Positive pushes the digit *away*
         * from the notes; negative would tuck it toward them. Tunable so engravers
         * can taste-balance the gap between the bracket line and the number.
         */
        val BRACKET_NUMBER_OFFSET = StaffSpace(0f)
        /** Offset of the digit beyond the slur apex (NUMBER side of SLUR_AND_NUMBER). */
        val SLUR_NUMBER_OFFSET = StaffSpace(1.7f)
        /**
         * Cap on the tuplet slur's nominal apex height. The lens apex sits at
         * `(nominalHeight + halfGap) * 0.75` beyond the baseline midpoint, so a
         * cap of ~1.2 ss keeps the apex roughly 1 ss away from the baseline —
         * leaving headroom for the digit, which is centered at [SLUR_NUMBER_OFFSET].
         * Without this cap, the default 4 ss cap lets the slur swallow the digit
         * on wide tuplets.
         */
        val SLUR_MAX_HEIGHT = StaffSpace(1.2f)
        /** Offset of the digit beyond the (invisible) baseline for NUMBER_ONLY. */
        val NUMBER_ONLY_OFFSET = StaffSpace(0.4f)
        /**
         * Visual scale of the tuplet digit glyph. 1.0 = native SMuFL size (1 em = 4 staff
         * spaces); 0.7 is a typical engraving scale for tuplet numbers — smaller than the
         * surrounding noteheads so the bracket reads as the dominant shape.
         */
        const val NUMBER_SCALE = 0.7f
    }
}
