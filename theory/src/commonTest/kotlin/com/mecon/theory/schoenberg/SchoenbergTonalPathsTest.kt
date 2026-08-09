package com.mecon.theory.schoenberg

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import kotlin.test.Test
import kotlin.test.assertEquals

class SchoenbergTonalPathsTest {
    private val cMajor = ModulationKey(0, KeySignatureMode.MAJOR)

    @Test
    fun resolvesThreeAndFourSharpGoldPathsFromCMajor() {
        val three = SchoenbergTonalPathResolver.resolve(SchoenbergDistantTonalPaths.THREE_SHARPS, cMajor)
        val four = SchoenbergTonalPathResolver.resolve(SchoenbergDistantTonalPaths.FOUR_SHARPS, cMajor)

        assertEquals(listOf("C", "A", "A"), three.nodes.map { it.key.displayName })
        assertEquals(
            listOf(KeySignatureMode.MAJOR, KeySignatureMode.MINOR, KeySignatureMode.MAJOR),
            three.nodes.map { it.key.mode },
        )
        assertEquals(listOf("C", "G", "E", "E"), four.nodes.map { it.key.displayName })
        assertEquals(3, three.fifthsDelta)
        assertEquals(4, four.fifthsDelta)
    }

    @Test
    fun resolvesFlatAndSimplifiedSharpPathsToSameDestinationDistance() {
        val paths = listOf(
            SchoenbergDistantTonalPaths.FOUR_SHARPS_APPLIED,
            SchoenbergDistantTonalPaths.FOUR_SHARPS_BORROWED,
            SchoenbergDistantTonalPaths.THREE_FLATS,
            SchoenbergDistantTonalPaths.FOUR_FLATS,
        ).map { SchoenbergTonalPathResolver.resolve(it, cMajor) }

        assertEquals(listOf(4, 4, -3, -4), paths.map { it.fifthsDelta })
        assertEquals(listOf("E", "E", "C", "F"), paths.map { it.target.key.displayName })
        assertEquals(
            listOf(
                KeySignatureMode.MAJOR,
                KeySignatureMode.MAJOR,
                KeySignatureMode.MINOR,
                KeySignatureMode.MINOR,
            ),
            paths.map { it.target.key.mode },
        )
    }
}
