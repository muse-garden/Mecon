package com.mecon.renderer.layout

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.layout.TimeCodeArithmetic.absoluteTicksTo
import com.mecon.renderer.layout.TimeCodeArithmetic.isSamePosition
import com.mecon.renderer.layout.TimeCodeArithmetic.ticksTo
import com.mecon.renderer.layout.TimeCodeArithmetic.toAbsoluteTicks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeCodeArithmeticTest {

    @Test
    fun testMeasure1StartIsZeroTicks() {
        val timeCode = TimeCode.of(1, Fraction.ZERO)
        assertEquals(0L, timeCode.toAbsoluteTicks())
    }

    @Test
    fun testMeasure2StartIs4096Ticks() {
        val timeCode = TimeCode.of(2, Fraction.ZERO)
        assertEquals(4096L, timeCode.toAbsoluteTicks())
    }

    @Test
    fun testHalfBeatInMeasure1() {
        // Half of a whole note = 2048 ticks
        val timeCode = TimeCode.of(1, Fraction.HALF)
        assertEquals(2048L, timeCode.toAbsoluteTicks())
    }

    @Test
    fun testQuarterBeatInMeasure1() {
        // Quarter of a whole note = 1024 ticks
        val timeCode = TimeCode.of(1, Fraction.QUARTER)
        assertEquals(1024L, timeCode.toAbsoluteTicks())
    }

    @Test
    fun testMeasure3Beat2() {
        // Measure 3, beat 1/2 of whole = (3-1) * 4096 + 2048 = 10240
        val timeCode = TimeCode.of(3, Fraction.HALF)
        assertEquals(10240L, timeCode.toAbsoluteTicks())
    }

    @Test
    fun testTicksToSameTime() {
        val t1 = TimeCode.of(1, Fraction.QUARTER)
        val t2 = TimeCode.of(1, Fraction.QUARTER)
        assertEquals(0L, t1.ticksTo(t2))
    }

    @Test
    fun testTicksToLaterTime() {
        val t1 = TimeCode.of(1, Fraction.ZERO)
        val t2 = TimeCode.of(1, Fraction.QUARTER)
        assertEquals(1024L, t1.ticksTo(t2))
    }

    @Test
    fun testTicksToEarlierTime() {
        val t1 = TimeCode.of(1, Fraction.QUARTER)
        val t2 = TimeCode.of(1, Fraction.ZERO)
        assertEquals(-1024L, t1.ticksTo(t2))
    }

    @Test
    fun testAbsoluteTicksTo() {
        val t1 = TimeCode.of(1, Fraction.QUARTER)
        val t2 = TimeCode.of(1, Fraction.ZERO)
        assertEquals(1024L, t1.absoluteTicksTo(t2))
    }

    @Test
    fun testIsSamePosition() {
        val t1 = TimeCode.of(1, Fraction(2, 4))
        val t2 = TimeCode.of(1, Fraction.HALF)
        assertTrue(t1.isSamePosition(t2))
    }

    @Test
    fun testIsDifferentPosition() {
        val t1 = TimeCode.of(1, Fraction.QUARTER)
        val t2 = TimeCode.of(1, Fraction.HALF)
        assertFalse(t1.isSamePosition(t2))
    }

    @Test
    fun testCrossbarMeasureTicks() {
        // From measure 1 beat 3/4 to measure 2 beat 1/4
        val t1 = TimeCode.of(1, Fraction(3, 4))
        val t2 = TimeCode.of(2, Fraction.QUARTER)

        // t1: (1-1)*4096 + 3072 = 3072
        // t2: (2-1)*4096 + 1024 = 5120
        // diff: 5120 - 3072 = 2048
        assertEquals(2048L, t1.ticksTo(t2))
    }
}
