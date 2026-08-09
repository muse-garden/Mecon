package com.mecon.desktop.uikit.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircleOfFifthsPickerTest {
    @Test
    fun enharmonicMajorKeysShareOneOfTwelveCircleSlots() {
        val pairs = listOf(
            FifthsKey(-7, FifthsKeyMode.MAJOR) to FifthsKey(5, FifthsKeyMode.MAJOR),
            FifthsKey(-6, FifthsKeyMode.MAJOR) to FifthsKey(6, FifthsKeyMode.MAJOR),
            FifthsKey(-5, FifthsKeyMode.MAJOR) to FifthsKey(7, FifthsKeyMode.MAJOR),
        )

        pairs.forEach { (flatKey, sharpKey) ->
            assertEquals(flatKey.circleSlot, sharpKey.circleSlot)
            assertTrue(flatKey.isEnharmonicWith(sharpKey))
        }
        assertEquals(listOf(5, -7), fifthsForCircleSlot(5))
        assertEquals(listOf(6, -6), fifthsForCircleSlot(6))
        assertEquals(listOf(7, -5), fifthsForCircleSlot(7))
    }

    @Test
    fun relativeMinorSharesSignatureSlotButIsNotTheSameTonalKey() {
        val cMajor = FifthsKey(0, FifthsKeyMode.MAJOR)
        val aMinor = FifthsKey(0, FifthsKeyMode.MINOR)

        assertEquals(cMajor.circleSlot, aMinor.circleSlot)
        assertFalse(cMajor.isEnharmonicWith(aMinor))
    }
}
