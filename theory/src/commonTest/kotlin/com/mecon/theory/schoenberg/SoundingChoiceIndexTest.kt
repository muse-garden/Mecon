package com.mecon.theory.schoenberg

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The off-key projection looks chords up by a rotated 12-bit pitch-class mask instead of scanning
 * the catalog. This pins that shortcut to the linear scan it replaced, for every catalog chord at
 * every transposition.
 */
class SoundingChoiceIndexTest {
    @Test
    fun maskLookupMatchesALinearScanAtEveryTransposition() {
        listOf(
            ModulationKey(0, KeySignatureMode.MAJOR),
            ModulationKey(-3, KeySignatureMode.MINOR),
        ).forEach { key ->
            val catalog = ChordSelectionCatalog.choices(key)
            val index = SoundingChoiceIndex.of(catalog)

            catalog.forEach { source ->
                (0..11).forEach { semitones ->
                    val transposed = source.pitchClasses.map { (it + semitones).mod(12) }.toSet()
                    assertEquals(
                        catalog.filter { it.pitchClasses == transposed },
                        index.matching(source.pitchClasses.pitchClassMask().rotatedBySemitones(semitones)),
                        "$key ${source.functionalSymbol} +$semitones",
                    )
                }
            }
        }
    }

    @Test
    fun rotationWrapsAroundTheTwelveNoteCircle() {
        val mask = setOf(0, 4, 7).pitchClassMask()

        assertEquals(setOf(0, 4, 7), maskPitchClasses(mask))
        assertEquals(setOf(1, 5, 8), maskPitchClasses(mask.rotatedBySemitones(1)))
        assertEquals(setOf(11, 3, 6), maskPitchClasses(mask.rotatedBySemitones(11)))
        assertEquals(mask, mask.rotatedBySemitones(12))
        assertEquals(mask, mask.rotatedBySemitones(-12))
        assertEquals(mask.rotatedBySemitones(11), mask.rotatedBySemitones(-1))
    }

    private fun maskPitchClasses(mask: Int): Set<Int> =
        (0..11).filterTo(mutableSetOf()) { (mask shr it) and 1 == 1 }
}
