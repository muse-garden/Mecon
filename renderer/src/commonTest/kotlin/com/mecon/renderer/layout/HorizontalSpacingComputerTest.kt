package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.renderer.geometry.StaffSpace
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class HorizontalSpacingComputerTest {

    private val computer = HorizontalSpacingComputer.default()

    @Test
    fun testLongerNotesGetMoreSpace() {
        val wholeWidth = computer.widthForDuration(Duration.WHOLE)
        val halfWidth = computer.widthForDuration(Duration.HALF)
        val quarterWidth = computer.widthForDuration(Duration.QUARTER)

        assertTrue(wholeWidth > halfWidth, "Whole note should be wider than half note")
        assertTrue(halfWidth > quarterWidth, "Half note should be wider than quarter note")
        // Quarter and shorter notes may be clamped to minimum spacing
        assertTrue(quarterWidth >= StaffSpace(1.0f), "Quarter note should have reasonable width")
    }

    @Test
    fun testDottedNotesAreWider() {
        // Use a config with smaller minimum to see the actual computed widths
        val config = RenderLayoutConfig(
            minimumNoteSpacing = StaffSpace(0.5f),
            baseSpacingUnit = StaffSpace(1.0f),
            spacingRatio = 1.6f
        )
        val computer = HorizontalSpacingComputer(config)

        val quarterWidth = computer.widthForDuration(Duration.QUARTER)
        val dottedQuarterWidth = computer.widthForDuration(Duration.DOTTED_QUARTER)

        assertTrue(
            dottedQuarterWidth > quarterWidth,
            "Dotted quarter ($dottedQuarterWidth) should be wider than quarter ($quarterWidth)"
        )
    }

    @Test
    fun testMinimumSpacing() {
        val config = RenderLayoutConfig(minimumNoteSpacing = StaffSpace(1.5f))
        val computer = HorizontalSpacingComputer(config)

        // Very short notes should still get minimum spacing
        val sixtyFourthWidth = computer.widthForDuration(Duration(DurationBase.SIXTY_FOURTH))

        assertTrue(
            sixtyFourthWidth >= config.minimumNoteSpacing,
            "Width should be at least minimum spacing"
        )
    }

    @Test
    fun testLogarithmicScaling() {
        // The relationship should be logarithmic, not linear
        val wholeWidth = computer.widthForDuration(Duration.WHOLE)
        val quarterWidth = computer.widthForDuration(Duration.QUARTER)

        // Whole note is 4x the duration of quarter, but shouldn't be 4x the width
        val ratio = wholeWidth.value / quarterWidth.value
        assertTrue(ratio < 4f, "Spacing should be sublinear (ratio: $ratio)")
        assertTrue(ratio > 1f, "Whole note should still be wider")
    }
}
