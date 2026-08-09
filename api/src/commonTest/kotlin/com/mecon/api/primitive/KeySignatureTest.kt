package com.mecon.api.primitive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeySignatureTest {

    @Test
    fun majorByFifthsPreservesEnharmonicFlatKeys() {
        val keys = (-7..7).associateWith(KeySignature::majorByFifths)

        assertEquals("Db", keys.getValue(-5).displayName)
        assertEquals(-5, keys.getValue(-5).fifths)
        assertEquals(5, keys.getValue(-5).accidentals().size)

        assertEquals("Gb", keys.getValue(-6).displayName)
        assertEquals(-6, keys.getValue(-6).fifths)
        assertEquals(6, keys.getValue(-6).accidentals().size)

        assertEquals("Cb", keys.getValue(-7).displayName)
        assertEquals(-7, keys.getValue(-7).fifths)
        assertEquals(7, keys.getValue(-7).accidentals().size)
    }

    @Test
    fun majorByFifthsKeepsSharpKeysCompatibleWithLegacyConstruction() {
        val cSharp = KeySignature.majorByFifths(7)
        val legacyCSharp = KeySignature(PitchClass(1), Mode.MAJOR)

        assertEquals("C#", cSharp.displayName)
        assertEquals(7, cSharp.fifths)
        assertNull(cSharp.fifthsOverride)
        assertEquals(legacyCSharp, cSharp)
    }

    @Test
    fun legacyEnharmonicPitchClassStillInfersSharpSideWithoutOverride() {
        val legacyDbPitchClass = KeySignature(PitchClass(1), Mode.MAJOR)

        assertEquals("C#", legacyDbPitchClass.displayName)
        assertEquals(7, legacyDbPitchClass.fifths)
    }
}
