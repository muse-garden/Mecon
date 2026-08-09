package com.mecon.desktop.ui.components.inspector.performance

import com.mecon.api.primitive.Fraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerformanceAmountParserTest {
    @Test
    fun acceptsIntegerAndFractionBeatCounts() {
        assertEquals(Fraction.ONE, parsePositiveBeatFraction("1"))
        assertEquals(Fraction.ONE, parsePositiveBeatFraction("1/1"))
        assertEquals(Fraction(3, 2), parsePositiveBeatFraction(" 3 / 2 "))
    }

    @Test
    fun rejectsIncompleteOrNonPositiveValues() {
        assertNull(parsePositiveBeatFraction("1/"))
        assertNull(parsePositiveBeatFraction("0"))
        assertNull(parsePositiveBeatFraction("-1/2"))
    }
}
