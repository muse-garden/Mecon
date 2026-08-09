package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedStaffBracket
import com.mecon.api.computed.ComputedStaffHeader
import com.mecon.api.computed.ComputedStaffLabel
import com.mecon.api.computed.StaffIndexRange
import com.mecon.api.computed.StaffLabelPlacement
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.BracketStyle
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertTrue

class StaffHeaderLayoutComputerTest {
    @Test
    fun outerBracketIsRightOfNestedBracketInEngraving() {
        val header = ComputedStaffHeader(
            brackets = listOf(
                ComputedStaffBracket(BracketStyle.SQUARE, StaffIndexRange(0, 3), depth = 1, sourceId = "outer"),
                ComputedStaffBracket(BracketStyle.SUB_BRACKET, StaffIndexRange(0, 1), depth = 2, sourceId = "inner")
            ),
            labels = emptyList(),
            barlineConnectivity = emptyList()
        )
        val staffs = (0..3).map { index ->
            val center = StaffSpace(index * 10f)
            StaffLayoutInfo(
                trackId = TrackId("staff-$index"),
                staffIndex = index,
                partIndex = index,
                centerY = center,
                topY = center - StaffSpace(2f),
                bottomY = center + StaffSpace(2f),
                contentTopY = center - StaffSpace(2f),
                contentBottomY = center + StaffSpace(2f),
                clef = Clef.TREBLE
            )
        }

        val result = StaffHeaderLayoutComputer(RenderLayoutConfig.DEFAULT).compute(header, staffs)
        val outer = result.brackets.single { it.sourceId == "outer" }
        val inner = result.brackets.single { it.sourceId == "inner" }
        assertTrue(outer.x.value > inner.x.value, "engraving places the outer bracket closer to the staves")
    }

    @Test
    fun playerNumberIsPlacedBeforeEveryBrace() {
        val header = ComputedStaffHeader(
            brackets = listOf(
                ComputedStaffBracket(BracketStyle.BRACE, StaffIndexRange(0, 1), depth = 1, sourceId = "brace"),
            ),
            labels = listOf(
                ComputedStaffLabel(
                    text = "1,3",
                    abbreviation = null,
                    staffRange = StaffIndexRange(0, 0),
                    depth = 0,
                    sourceId = "players",
                    placement = StaffLabelPlacement.BEFORE_BRACKETS,
                ),
            ),
            barlineConnectivity = emptyList(),
        )
        val staffs = (0..1).map { index ->
            val center = StaffSpace(index * 10f)
            StaffLayoutInfo(
                trackId = TrackId("staff-$index"),
                staffIndex = index,
                partIndex = 0,
                centerY = center,
                topY = center - StaffSpace(2f),
                bottomY = center + StaffSpace(2f),
                contentTopY = center - StaffSpace(2f),
                contentBottomY = center + StaffSpace(2f),
                clef = Clef.TREBLE,
            )
        }

        val result = StaffHeaderLayoutComputer(RenderLayoutConfig.DEFAULT).compute(header, staffs)
        assertTrue(result.labels.single().x.value < result.brackets.single().x.value)
        assertTrue(result.totalWidth.value >= result.brackets.single().x.value)
    }
}
