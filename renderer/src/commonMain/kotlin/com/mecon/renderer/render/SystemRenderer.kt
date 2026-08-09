package com.mecon.renderer.render

import com.mecon.api.computed.StaffIndexRange
import com.mecon.api.render.RenderColor
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.NavigationMark
import com.mecon.renderer.elements.*
import com.mecon.renderer.geometry.*
import com.mecon.renderer.layout.*
import com.mecon.renderer.layout.RenderConstants
import com.mecon.renderer.smufl.BravuraFont
import com.mecon.renderer.smufl.GlyphInfo
import com.mecon.renderer.smufl.SmuflGlyphs

/**
 * Renderer for system-level elements: staves, brackets, labels, system barlines.
 */
context(BravuraFont)
class SystemRenderer(
    private val config: RenderLayoutConfig,
    private val transformer: CoordinateTransformer
) {
    fun renderStaffLines(
        staffCenterY: StaffSpace,
        startX: StaffSpace,
        endX: StaffSpace,
        trackId: com.mecon.api.primitive.TrackId,
        idGenerator: () -> RenderElementId,
        lineCount: Int = 5
    ): List<RenderElement> {
        val halfHeight = StaffSpace((lineCount - 1) / 2.0f)
        val topY = staffCenterY - halfHeight
        val width = endX - startX

        val staffElement = StaffElement(
            origin = RelativePoint(startX, topY),
            width = width,
            lineCount = lineCount,
            lineThickness = config.engravingDefaults.staffLineThickness
        )

        val commands = staffElement.toLines().map { line ->
            val absLine = transformer.toAbsolute(line)
            DrawLine(
                start = absLine.start, end = absLine.end,
                thickness = absLine.thickness,
                color = RenderColor.BLACK,
                bounds = RenderHelpers.calculateLineBounds(absLine)
            )
        }

        val bounds = transformer.toAbsolute(RelativeRect(
            RelativePoint(startX, topY), width, StaffSpace((lineCount - 1).toFloat())
        ))

        return listOf(
            renderElement(idGenerator(), RenderElementType.STAFF)
                .addCommands(commands).hitBox(bounds).trackId(trackId).build()
        )
    }

    /**
     * Render system barline segments derived from barline connectivity ranges.
     */
    fun renderSystemBarline(
        barlineElement: BarlineElement,
        slotX: StaffSpace,
        firstStaffTopY: StaffSpace,
        lastStaffBottomY: StaffSpace,
        connectedRanges: List<StaffIndexRange>,
        staffByIndex: Map<Int, StaffLayoutInfo>,
        idGenerator: () -> RenderElementId
    ): List<RenderElement> {
        val drawOffset = RelativePoint(slotX + barlineElement.relativeX, StaffSpace.ZERO)
        val lineGeoms = barlineElement.geometryList.filterIsInstance<LineGeometry>()
        val glyphGeoms = barlineElement.geometryList.filterIsInstance<GlyphGeometry>()
        if (lineGeoms.isEmpty() && glyphGeoms.isEmpty()) return emptyList()

        val segments: List<Pair<StaffSpace, StaffSpace>> = if (connectedRanges.isEmpty()) {
            listOf(firstStaffTopY to lastStaffBottomY)
        } else {
            connectedRanges.mapNotNull { range ->
                val top = staffByIndex[range.first] ?: return@mapNotNull null
                val bot = staffByIndex[range.last] ?: return@mapNotNull null
                top.topY to bot.bottomY
            }
        }

        val commands = mutableListOf<RenderCommand>()
        for ((segTop, segBottom) in segments) {
            for (geom in lineGeoms) {
                val systemLine = LineGeometry.vertical(
                    geom.start.x,
                    segTop,
                    segBottom,
                    geom.thickness,
                    dashIntervals = geom.dashIntervals,
                    cap = geom.cap,
                )
                commands.addAll(systemLine.draw(drawOffset, transformer))
            }
        }
        // Repeat dots are staff-local even when the barline rules connect several
        // staves. Render one pair on every visible notation staff.
        for (staff in staffByIndex.values.sortedBy { it.staffIndex }) {
            val staffOffset = RelativePoint(
                slotX + barlineElement.relativeX,
                staff.centerY,
            )
            for (geom in glyphGeoms) commands.addAll(geom.draw(staffOffset, transformer))
        }
        if (commands.isEmpty()) return emptyList()

        return listOf(
            renderElement(idGenerator(), RenderElementType.BARLINE)
                .addCommands(commands)
                .measureNumber(barlineElement.measureNumber)
                .metadata("barlineTime", barlineElement.time.format())
                .build()
        )
    }

    /**
     * Render a single vertical rule at [x] spanning [topY]..[bottomY].
     */
    private fun renderVerticalRule(
        x: StaffSpace,
        topY: StaffSpace,
        bottomY: StaffSpace,
        idGenerator: () -> RenderElementId
    ): List<RenderElement> {
        val geom = LineGeometry.vertical(x, topY, bottomY, config.engravingDefaults.thinBarlineThickness)
        val commands = geom.draw(RelativePoint.ZERO, transformer)
        if (commands.isEmpty()) return emptyList()
        return listOf(
            renderElement(idGenerator(), RenderElementType.BARLINE).addCommands(commands).build()
        )
    }

    /**
     * Render a single vertical barline at [x] spanning [topY]..[bottomY]. Used to
     * draw the closing barline at the right edge of a justified system, standing in
     * for the next system's suppressed measure-opening barline.
     */
    fun renderClosingBarline(
        type: com.mecon.api.primitive.BarlineType,
        measureNumber: Int,
        x: StaffSpace,
        topY: StaffSpace,
        bottomY: StaffSpace,
        staffByIndex: Map<Int, StaffLayoutInfo>,
        idGenerator: () -> RenderElementId
    ): List<RenderElement> {
        val element = BarlineElement.create(
            com.mecon.api.primitive.TimeCode.ZERO,
            type,
            measureNumber,
        )
        return renderSystemBarline(
            barlineElement = element,
            // A system's lineEndX is its right edge; keep multi-rule and repeat
            // barlines inside that edge rather than clipping their thick rule/dots.
            slotX = x - element.minimumWidth,
            firstStaffTopY = topY,
            lastStaffBottomY = bottomY,
            connectedRanges = emptyList(),
            staffByIndex = staffByIndex,
            idGenerator = idGenerator,
        )
    }

    /**
     * Render the system-start vertical rule at the left edge ([x]) of a system,
     * spanning [topY]..[bottomY]. This is a decorative rule that begins every system
     * (it is *not* a measure barline); the first system shows it via its in-stream
     * initial barline, so this is drawn for later systems to match.
     */
    fun renderSystemStartLine(
        type: com.mecon.api.primitive.BarlineType,
        measureNumber: Int,
        x: StaffSpace,
        topY: StaffSpace,
        bottomY: StaffSpace,
        staffByIndex: Map<Int, StaffLayoutInfo>,
        idGenerator: () -> RenderElementId
    ): List<RenderElement> = renderSystemBarline(
        barlineElement = BarlineElement.create(
            com.mecon.api.primitive.TimeCode.ZERO,
            type,
            measureNumber,
        ),
        // At a system start the glyph grows into the line, to the right.
        slotX = x,
        firstStaffTopY = topY,
        lastStaffBottomY = bottomY,
        connectedRanges = emptyList(),
        staffByIndex = staffByIndex,
        idGenerator = idGenerator,
    )

    /**
     * Render a header bracket using SMuFL glyphs and the Bravura brace alternates.
     *
     * [PlacedBracket.x] is the RIGHT edge of the bracket column. Brackets are drawn
     * so their rightmost extent is flush with this right edge.
     */
    fun renderHeaderBracket(
        bracket: StaffHeaderLayoutComputer.PlacedBracket,
        idGenerator: () -> RenderElementId
    ): List<RenderElement> {
        if (bracket.style == BracketStyle.NONE) return emptyList()
        val commands = mutableListOf<RenderCommand>()

        when (bracket.style) {
            BracketStyle.SQUARE -> commands.addAll(renderSquareBracket(bracket))
            BracketStyle.BRACE  -> commands.addAll(renderBraceGlyph(bracket))
            BracketStyle.SUB_BRACKET -> commands.addAll(renderSubBracket(bracket))
            BracketStyle.NONE -> Unit
        }

        if (commands.isEmpty()) return emptyList()
        val type = if (bracket.style == BracketStyle.BRACE)
            RenderElementType.SYSTEM_BRACE else RenderElementType.SYSTEM_BRACKET

        return listOf(
            renderElement(idGenerator(), type).addCommands(commands).build()
        )
    }

    /**
     * Render a header label (instrument or group name) as left-aligned text
     * centered vertically on the spanned staff range.
     */
    fun renderHeaderLabel(
        label: StaffHeaderLayoutComputer.PlacedLabel,
        idGenerator: () -> RenderElementId
    ): List<RenderElement> {
        if (label.text.isEmpty()) return emptyList()
        val pos = transformer.toAbsolute(RelativePoint(label.x, label.centerY))
        val fontSizePixels = transformer.toPixels(StaffHeaderLayoutComputer.LABEL_FONT_SIZE)
        val topLeftY = Pixels(pos.y.value - fontSizePixels.value * 0.5f)
        val widthGuess = Pixels(label.text.length * fontSizePixels.value * APPROX_CHAR_WIDTH_RATIO)
        val alignment = if (label.rightAligned) TextAlignment.RIGHT else TextAlignment.LEFT
        val boundsOriginX = if (label.rightAligned) Pixels(pos.x.value - widthGuess.value) else pos.x
        val bounds = AbsoluteRect(
            origin = AbsolutePoint(boundsOriginX, topLeftY),
            width = widthGuess,
            height = fontSizePixels
        )
        val cmd = DrawText(
            position = AbsolutePoint(pos.x, topLeftY),
            text = label.text,
            fontFamily = "Arial",
            fontSize = fontSizePixels,
            color = RenderColor.BLACK,
            alignment = alignment,
            bounds = bounds
        )
        return listOf(
            renderElement(idGenerator(), RenderElementType.TEXT_ANNOTATION)
                .addCommands(listOf(cmd)).hitBox(bounds).build()
        )
    }

    fun renderNavigationMark(
        x: StaffSpace,
        y: StaffSpace,
        mark: NavigationMark,
        staffIndex: Int,
        idGenerator: () -> RenderElementId,
    ): List<RenderElement> {
        val text = when (mark) {
            NavigationMark.SEGNO,
            NavigationMark.CODA -> ""
            NavigationMark.TO_CODA -> "To Coda"
            NavigationMark.FINE -> "Fine"
            NavigationMark.DA_CAPO -> "D.C."
            NavigationMark.DAL_SEGNO -> "D.S."
            NavigationMark.DA_CAPO_AL_FINE -> "D.C. al Fine"
            NavigationMark.DAL_SEGNO_AL_FINE -> "D.S. al Fine"
            NavigationMark.DA_CAPO_AL_CODA -> "D.C. al Coda"
            NavigationMark.DAL_SEGNO_AL_CODA -> "D.S. al Coda"
        }
        val pos = transformer.toAbsolute(RelativePoint(x, y))
        val isSign = mark == NavigationMark.SEGNO || mark == NavigationMark.CODA
        val fontSize = transformer.toPixels(StaffSpace(
            when {
                isSign -> 3.0f
                mark in setOf(NavigationMark.DA_CAPO, NavigationMark.DAL_SEGNO, NavigationMark.FINE) -> 1.8f
                else -> 1.55f
            }
        ))
        val commands = if (isSign) {
            val glyph = if (mark == NavigationMark.SEGNO) SmuflGlyphs.segno else SmuflGlyphs.coda
            val scale = fontSize / 4f
            val bbox = this@BravuraFont.getBBox(glyph)
            val origin = if (bbox != null) {
                AbsolutePoint(
                    pos.x - scale * ((bbox.southWest.x.value + bbox.northEast.x.value) / 2f),
                    pos.y + scale * bbox.southWest.y.value,
                )
            } else {
                AbsolutePoint(pos.x - fontSize * 0.4f, pos.y)
            }
            listOf(RenderHelpers.createGlyphCommand(
                glyph = glyph,
                origin = origin,
                fontSize = fontSize,
            ))
        } else {
            buildList<RenderCommand> {
                val width = Pixels(text.length * fontSize.value * 0.68f)
                val textBounds = AbsoluteRect(
                    AbsolutePoint(pos.x - width / 2f, pos.y - fontSize * 1.15f),
                    width,
                    fontSize * 1.15f,
                )
                add(DrawText(
                    // Compose renders DrawText.position as the top-left, not a baseline.
                    // Keep the complete text box above the requested bottom edge.
                    position = AbsolutePoint(pos.x, pos.y - fontSize * 1.15f),
                    text = text,
                    fontFamily = "serif",
                    fontSize = fontSize,
                    fontWeight = FontWeight.BOLD,
                    fontStyle = FontStyle.ITALIC,
                    alignment = TextAlignment.CENTER,
                    bounds = textBounds,
                ))
                if (mark == NavigationMark.TO_CODA) {
                    add(RenderHelpers.createGlyphCommand(
                        glyph = SmuflGlyphs.coda,
                        origin = AbsolutePoint(pos.x + width / 2f + fontSize * 0.2f, pos.y),
                        fontSize = Pixels(fontSize.value * 1.5f),
                    ))
                }
            }
        }
        val bounds = RenderHelpers.mergeBounds(commands.map { it.bounds })
        return listOf(
            renderElement(idGenerator(), RenderElementType.NAVIGATION_MARK)
                .addCommands(commands)
                .hitBox(bounds)
                .staffIndex(staffIndex)
                .build()
        )
    }

    // ---- Private bracket rendering ----

    /**
     * SQUARE bracket: U+E003 (top) + thick vertical bar + U+E004 (bottom).
     *
     * The bracketTop/bracketBottom glyphs have their bar stub at x=0 (SW.x=0) and
     * the horizontal arm/cap extending rightward to NE.x≈1.876 SS into the staff area.
     * Anchor so bar center = bracket.x (staff left edge): anchorX = bracket.x - thickness/2.
     */
    private fun renderSquareBracket(bracket: StaffHeaderLayoutComputer.PlacedBracket): List<RenderCommand> {
        val commands = mutableListOf<RenderCommand>()
        val thickness = bracket.thickness
        val serifFontSize = transformer.toPixels(StaffSpace(4f))  // natural Bravura size (1em = 4 SS)

        // Bar stub is at glyph x=0; center it at (bracket.x - gap) for visual spacing from staff.
        val gap = RenderConstants.SQUARE_BRACKET_STAFF_GAP
        val barCenterX = bracket.x - gap
        val anchorX = barCenterX - thickness * 0.5f

        val topPos = transformer.toAbsolute(RelativePoint(anchorX, bracket.topY))
        commands.add(RenderHelpers.createGlyphCommand(SmuflGlyphs.bracketTop, topPos, serifFontSize))

        val botPos = transformer.toAbsolute(RelativePoint(anchorX, bracket.bottomY))
        commands.add(RenderHelpers.createGlyphCommand(SmuflGlyphs.bracketBottom, botPos, serifFontSize))

        // Main vertical bar centered at barCenterX, spanning topY to bottomY.
        val vertLine = LineGeometry.vertical(barCenterX, bracket.topY, bracket.bottomY, thickness)
        commands.addAll(vertLine.draw(RelativePoint.ZERO, transformer))

        return commands
    }

    /**
     * BRACE: choose a SMuFL brace alternate for the target span, then scale it
     * proportionally in both dimensions. The standard brace is 1em high; the
     * alternate thresholds correspond to roughly 1, 2, 3, 4–9 and 10+ staff
     * heights, which keeps a large brace from becoming excessively wide/bold.
     *
     * The glyph's visual content is entirely to the RIGHT of its SMuFL origin.
     * The anchor is shifted LEFT so the brace's right edge is clear of the staff.
     */
    private fun renderBraceGlyph(bracket: StaffHeaderLayoutComputer.PlacedBracket): List<RenderCommand> {
        val heightSS = (bracket.bottomY - bracket.topY).value.coerceAtLeast(0f)
        val glyph = braceGlyphFor(heightSS)
        val fontSize = transformer.toPixels(StaffSpace(BRACE_EM_HEIGHT_STAFF_SPACES))
        val pixelsPerStaffSpace = transformer.toPixels(StaffSpace.ONE).value
        val bbox = getBBox(glyph)
        val glyphHeightSS = bbox?.height?.value?.takeIf { it > 0f }
            ?: BRACE_EM_HEIGHT_STAFF_SPACES
        val proportionalScale = heightSS / glyphHeightSS

        // Brace right edge in glyph = NE.x at natural scale. Apply the same
        // scale as Y because the glyph is intentionally scaled proportionally.
        val rightEdgeSS = bbox?.northEast?.x?.value ?: DEFAULT_BRACE_RIGHT_EDGE_SS
        val braceRightPx = rightEdgeSS * pixelsPerStaffSpace * proportionalScale
        val gapPx = transformer.toPixels(RenderConstants.BRACE_STAFF_GAP).value

        // Place glyph so its right edge is (bracket.x - gap) — clear of the staff.
        val bracketXPx = transformer.toAbsolute(RelativePoint(bracket.x, bracket.bottomY)).x.value
        val anchorX = AbsolutePoint(
            x = Pixels(bracketXPx - braceRightPx - gapPx),
            y = transformer.toAbsolute(RelativePoint(bracket.x, bracket.bottomY)).y
        )
        return listOf(
            RenderHelpers.createGlyphCommand(
                glyph = glyph,
                origin = anchorX,
                fontSize = fontSize,
                scaleX = proportionalScale,
                scaleY = proportionalScale
            )
        )
    }

    /**
     * Select the alternate using the actual geometric span, rather than only
     * the number of staff indices. Two staves can be separated by a large
     * vertical gap, and that is exactly when the narrower alternates matter.
     */
    private fun braceGlyphFor(heightSS: Float): GlyphInfo {
        val spanInEm = heightSS / BRACE_EM_HEIGHT_STAFF_SPACES
        return when {
            spanInEm <= 1.5f -> SmuflGlyphs.braceSmall
            spanInEm <= 2.5f -> SmuflGlyphs.brace
            spanInEm <= 3.5f -> SmuflGlyphs.braceLarge
            spanInEm < 10f -> SmuflGlyphs.braceLarger
            else -> SmuflGlyphs.braceFlat
        }
    }

    /**
     * SUB_BRACKET: a thin rule with short fixed-size arms.
     *
     * The SMuFL U+E005 glyph is only a cap. Scaling that single glyph to the full
     * group height also scales its endpoint horizontally, producing enormous black
     * wedges on orchestral systems. Draw the invariant geometry directly instead.
     */
    private fun renderSubBracket(bracket: StaffHeaderLayoutComputer.PlacedBracket): List<RenderCommand> {
        val gap = RenderConstants.SQUARE_BRACKET_STAFF_GAP
        val barCenterX = bracket.x - gap
        val armEndX = barCenterX + StaffSpace(0.55f)
        return buildList {
            addAll(LineGeometry.vertical(barCenterX, bracket.topY, bracket.bottomY, bracket.thickness)
                .draw(RelativePoint.ZERO, transformer))
            addAll(LineGeometry.horizontal(bracket.topY, barCenterX, armEndX, bracket.thickness)
                .draw(RelativePoint.ZERO, transformer))
            addAll(LineGeometry.horizontal(bracket.bottomY, barCenterX, armEndX, bracket.thickness)
                .draw(RelativePoint.ZERO, transformer))
        }
    }

    companion object {
        private const val APPROX_CHAR_WIDTH_RATIO: Float = 0.6f
        private const val BRACE_EM_HEIGHT_STAFF_SPACES: Float = 4f
        private const val DEFAULT_BRACE_RIGHT_EDGE_SS: Float = 0.328f
    }
}
