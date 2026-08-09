package com.mecon.theory

import com.mecon.api.primitive.IntervalQuality
import com.mecon.api.primitive.Pitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpelledIntervalTest {
    @Test
    fun distinguishesAugmentedThirdFromPerfectFourth() {
        val augmentedThird = SpelledInterval.between(Pitch.fromName("C4"), Pitch.fromName("E#4"))
        val perfectFourth = SpelledInterval.between(Pitch.fromName("C4"), Pitch.fromName("F4"))

        assertEquals(3, augmentedThird.number)
        assertEquals(SpelledIntervalBase.MAJOR, augmentedThird.base)
        assertEquals(1, augmentedThird.offset)
        assertEquals(IntervalQuality.AUGMENTED, augmentedThird.quality)
        assertEquals(4, perfectFourth.number)
        assertEquals(SpelledIntervalBase.PERFECT, perfectFourth.base)
        assertEquals(0, perfectFourth.offset)
        assertTrue(augmentedThird.isEnharmonicallyEquivalentTo(perfectFourth))
    }

    @Test
    fun diminishedSeventhIsMinorSeventhWithNegativeOffset() {
        val diminishedSeventh = SpelledInterval.between(Pitch.fromName("C4"), Pitch.fromName("Bbb4"))

        assertEquals(7, diminishedSeventh.number)
        assertEquals(SpelledIntervalBase.MINOR, diminishedSeventh.base)
        assertEquals(-1, diminishedSeventh.offset)
        assertEquals(IntervalQuality.DIMINISHED, diminishedSeventh.quality)
        assertEquals(9, diminishedSeventh.semitones)
    }

    @Test
    fun transposesWithSpelling() {
        val augmentedThird = SpelledInterval(
            number = 3,
            base = SpelledIntervalBase.MAJOR,
            offset = 1,
        )

        assertEquals(Pitch.fromName("E#4"), augmentedThird.transpose(Pitch.fromName("C4")))
    }
}

