package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedStaffHeader
import com.mecon.api.computed.ComputedStaffBracket
import com.mecon.api.computed.ComputedStaffLabel
import com.mecon.api.computed.StaffIndexRange
import com.mecon.api.computed.StaffLabelPlacement
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.renderer.geometry.StaffSpace

/**
 * Computes left-margin geometry for staff brackets and instrument/group labels.
 *
 * ## Visual layout (left → right, away from staff → adjacent to staff)
 *
 *   `[largest-range label] … [smallest-range label] | [innermost bracket] … [outermost bracket] | staves`
 *
 * Key rules (per user specification):
 *  - ALL labels are to the LEFT of ALL brackets.
 *  - Labels are ordered by staff-range size, DESCENDING (largest spans leftmost).
 *  - Brackets are ordered by nesting depth, DESCENDING (deepest/innermost leftmost,
 *    shallowest/outermost rightmost — adjacent to the staves). This intentionally
 *    mirrors the editor, where large ranges are placed on the left for manipulation.
 *  - Brackets at the same depth share one column.
 *
 * [computeWidth] is cheap and used during layout to reserve the horizontal slot
 * before staff Y positions are known. [compute] runs after [StaffLayoutComputer]
 * and produces concrete [PlacedLabel] / [PlacedBracket] geometry for the renderer.
 */
class StaffHeaderLayoutComputer(
    private val config: RenderLayoutConfig
) {
    data class PlacedLabel(
        val text: String,
        val abbreviation: String?,
        /** Right edge when [rightAligned] is true; left edge otherwise. */
        val x: StaffSpace,
        val centerY: StaffSpace,
        val staffRange: StaffIndexRange,
        val depth: Int,
        val sourceId: String,
        val rightAligned: Boolean = false
    )

    data class PlacedBracket(
        val style: BracketStyle,
        /** Right edge of the bracket's visual body (adjacent to what's on its right). */
        val x: StaffSpace,
        val topY: StaffSpace,
        val bottomY: StaffSpace,
        val thickness: StaffSpace,
        val staffRange: StaffIndexRange,
        val depth: Int,
        val sourceId: String
    )

    data class Result(
        val labels: List<PlacedLabel>,
        val brackets: List<PlacedBracket>,
        val totalWidth: StaffSpace
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList(), StaffSpace.ZERO)
        }
    }

    fun computeWidth(header: ComputedStaffHeader): StaffSpace {
        if (header.brackets.isEmpty() && header.labels.isEmpty()) return StaffSpace.ZERO
        val leftLabels = header.labels.filter { it.placement == StaffLabelPlacement.LEFT_OF_BRACKETS }
        val playerLabels = header.labels.filter { it.placement == StaffLabelPlacement.BEFORE_BRACKETS }
        val labelCols = labelColumnWidths(leftLabels)
        val bracketCols = bracketColumnSizes(header.brackets)
        var w = StaffSpace.ZERO
        for (width in labelCols) w += width + LABEL_PADDING
        for (width in bracketCols) w += width
        if (playerLabels.isNotEmpty()) {
            w += playerLabels.maxOf { estimateLabelWidth(it.text) } + PLAYER_LABEL_PADDING
        }
        return w
    }

    fun compute(
        header: ComputedStaffHeader,
        staffLayouts: List<StaffLayoutInfo>,
        startX: StaffSpace = StaffSpace.ZERO
    ): Result {
        if (header.brackets.isEmpty() && header.labels.isEmpty()) return Result.EMPTY

        val staffByIndex = staffLayouts
            .filter { it.kind == StaffKind.NOTATION }
            .associateBy { it.staffIndex }
        if (staffByIndex.isEmpty()) return Result.EMPTY

        val labels = mutableListOf<PlacedLabel>()
        val brackets = mutableListOf<PlacedBracket>()
        val leftLabels = header.labels.filter { it.placement == StaffLabelPlacement.LEFT_OF_BRACKETS }
        val playerLabels = header.labels.filter { it.placement == StaffLabelPlacement.BEFORE_BRACKETS }

        // ---- Label section ----
        // Column 0 = rightmost (adjacent to brackets), column N = leftmost.
        // A label gets column 0 if it has no sub-labels within its staff range;
        // otherwise column = max(sub-label columns) + 1.
        // Column 0 labels are right-aligned; others are left-aligned.
        val colMap = computeColumnIndices(leftLabels)
        val maxCol = if (leftLabels.isEmpty()) -1 else (colMap.values.maxOrNull() ?: 0)
        val colWidths = (0..maxCol).map { col ->
            leftLabels.filter { colMap[it] == col }
                .maxOfOrNull { estimateLabelWidth(it.text) } ?: StaffSpace.ZERO
        }

        // Lay out columns left-to-right: leftmost first (maxCol), rightmost last (0)
        var xCursor = startX
        val colLeftX = mutableMapOf<Int, StaffSpace>()
        val colRightX = mutableMapOf<Int, StaffSpace>()
        for (col in maxCol downTo 0) {
            colLeftX[col] = xCursor
            colRightX[col] = xCursor + colWidths[col]
            xCursor += colWidths[col] + LABEL_PADDING
        }

        for (lbl in leftLabels) {
            val col = colMap[lbl] ?: 0
            val cy = centerYFor(lbl.staffRange, staffByIndex) ?: continue
            val isRightmost = (col == 0)
            labels.add(PlacedLabel(
                text = lbl.text,
                abbreviation = lbl.abbreviation,
                x = if (isRightmost) colRightX[0]!! else colLeftX[col]!!,
                centerY = cy,
                staffRange = lbl.staffRange,
                depth = lbl.depth,
                sourceId = lbl.sourceId,
                rightAligned = isRightmost
            ))
        }

        if (playerLabels.isNotEmpty()) {
            val width = playerLabels.maxOf { estimateLabelWidth(it.text) }
            for (lbl in playerLabels) {
                val cy = centerYFor(lbl.staffRange, staffByIndex) ?: continue
                labels.add(
                    PlacedLabel(
                        text = lbl.text,
                        abbreviation = lbl.abbreviation,
                        x = xCursor,
                        centerY = cy,
                        staffRange = lbl.staffRange,
                        depth = lbl.depth,
                        sourceId = lbl.sourceId,
                        rightAligned = false,
                    )
                )
            }
            xCursor += width + PLAYER_LABEL_PADDING
        }

        var x = xCursor  // bracket columns start here

        // ---- Bracket section (right side, innermost left → outermost adjacent to staves) ----
        val bracketGroups = header.brackets
            .groupBy { it.depth }
            .entries
            .sortedByDescending { it.key }

        for ((_, group) in bracketGroups) {
            val colWidth = group.maxOf { bracketWidthFor(it.style) }
            val rightEdge = x + colWidth
            for (br in group) {
                val topInfo = staffByIndex[br.staffRange.first] ?: continue
                val botInfo = staffByIndex[br.staffRange.last] ?: continue
                val topY = topInfo.topY - bracketOverhangFor(br.style)
                val botY = botInfo.bottomY + bracketOverhangFor(br.style)
                brackets.add(PlacedBracket(
                    style = br.style,
                    x = rightEdge,   // right edge of this column
                    topY = topY,
                    bottomY = botY,
                    thickness = bracketThicknessFor(br.style),
                    staffRange = br.staffRange,
                    depth = br.depth,
                    sourceId = br.sourceId
                ))
            }
            x = rightEdge
        }

        return Result(labels, brackets, x)
    }

    // ---- Helpers ----

    /** Column index per label: 0 = rightmost (no sub-labels), N = N levels from rightmost. */
    private fun computeColumnIndices(labels: List<ComputedStaffLabel>): Map<ComputedStaffLabel, Int> {
        val result = mutableMapOf<ComputedStaffLabel, Int>()
        var changed = true
        while (changed) {
            changed = false
            for (label in labels) {
                if (label in result) continue
                val subLabels = labels.filter { other ->
                    other.sourceId != label.sourceId &&
                    other.staffRange.first >= label.staffRange.first &&
                    other.staffRange.last <= label.staffRange.last &&
                    other.staffRange.span < label.staffRange.span
                }
                if (subLabels.isEmpty() || subLabels.all { it in result }) {
                    result[label] = if (subLabels.isEmpty()) 0
                                    else subLabels.maxOf { result[it]!! } + 1
                    changed = true
                }
            }
        }
        for (label in labels) if (label !in result) result[label] = 0
        return result
    }

    private fun labelColumnWidths(labels: List<ComputedStaffLabel>): List<StaffSpace> {
        if (labels.isEmpty()) return emptyList()
        val colMap = computeColumnIndices(labels)
        val maxCol = colMap.values.maxOrNull() ?: 0
        return (0..maxCol).map { col ->
            labels.filter { colMap[it] == col }.maxOfOrNull { estimateLabelWidth(it.text) } ?: StaffSpace.ZERO
        }
    }

    private fun bracketColumnSizes(brackets: List<ComputedStaffBracket>): List<StaffSpace> =
        brackets.groupBy { it.depth }
            .entries.sortedByDescending { it.key }
            .map { (_, g) -> g.maxOf { bracketWidthFor(it.style) } }

    private fun centerYFor(range: StaffIndexRange, byIdx: Map<Int, StaffLayoutInfo>): StaffSpace? {
        val first = byIdx[range.first] ?: return null
        val last = byIdx[range.last] ?: return null
        return StaffSpace((first.centerY.value + last.centerY.value) * 0.5f)
    }

    private fun estimateLabelWidth(text: String): StaffSpace =
        StaffSpace(text.length * LABEL_CHAR_WIDTH)

    fun bracketWidthFor(style: BracketStyle): StaffSpace = when (style) {
        BracketStyle.NONE -> StaffSpace.ZERO
        // Column width = space to the LEFT of the stave/next-column needed for the bar.
        // Bar center is at bracket.x - SQUARE_BRACKET_STAFF_GAP (0.5 SS);
        // bar left edge is at bracket.x - 0.5 - thickness/2 ≈ bracket.x - 0.75.
        // The bracketTop arm/serif extends RIGHTWARD (into the stave area) and is free.
        BracketStyle.SQUARE -> StaffSpace(0.75f)
        // Brace uses proportional scaling and may select a narrower alternate for
        // large spans; keep a conservative column so its natural small-span form
        // and the staff gap do not collide with the next header column.
        BracketStyle.BRACE -> StaffSpace(1.4f)
        BracketStyle.SUB_BRACKET -> StaffSpace(0.8f)
    }

    private fun bracketThicknessFor(style: BracketStyle): StaffSpace = when (style) {
        BracketStyle.NONE -> StaffSpace.ZERO
        BracketStyle.SQUARE -> config.engravingDefaultsOrFallback.bracketThickness
        BracketStyle.BRACE -> config.engravingDefaultsOrFallback.bracketThickness
        BracketStyle.SUB_BRACKET -> config.engravingDefaultsOrFallback.subBracketThickness
    }

    fun bracketOverhangFor(style: BracketStyle): StaffSpace = when (style) {
        // SQUARE: bracketTop/bracketBottom glyphs extend beyond the staff on their own.
        BracketStyle.SQUARE -> StaffSpace.ZERO
        BracketStyle.BRACE -> StaffSpace.ZERO
        else -> StaffSpace.ZERO
    }

    companion object {
        const val LABEL_CHAR_WIDTH: Float = 1.0f
        val LABEL_FONT_SIZE: StaffSpace = StaffSpace(1.6f)
        val LABEL_PADDING: StaffSpace = StaffSpace(0.5f)
        val PLAYER_LABEL_PADDING: StaffSpace = StaffSpace(0.35f)
        val EDGE_PADDING: StaffSpace = StaffSpace(0.5f)
    }
}

private val StaffIndexRange.span: Int get() = last - first

private val RenderLayoutConfig.engravingDefaultsOrFallback: com.mecon.renderer.smufl.EngravingDefaults
    get() = com.mecon.renderer.smufl.EngravingDefaults.BRAVURA
