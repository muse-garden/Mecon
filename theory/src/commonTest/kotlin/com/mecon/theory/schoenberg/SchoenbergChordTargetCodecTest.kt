package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.constraint.ChordSelectionTargetCatalog
import com.mecon.theory.constraint.ChordTarget
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The codec feeds symbolic chords straight into chapter policies that compare them structurally,
 * so it has to spell them exactly the way the chapters build their own vocabulary.
 */
class SchoenbergChordTargetCodecTest {
    private val key = ModulationKey(0, KeySignatureMode.MAJOR)

    @Test
    fun seventhChordInversionsLiveOnTheSeventhPositionAxisOnly() {
        val targets = ChordSelectionTargetCatalog.targets(key)
        TextbookSeventhPosition.entries.forEach { position ->
            val inversion = TextbookSeventhPosition.entries.indexOf(position)
            val target = assertNotNull(
                targets.firstOrNull {
                    it.arity == ChordArity.SEVENTH && it.degree == 5 && it.inversion == inversion
                },
                "Expected a dominant seventh in inversion $inversion",
            )

            val symbolic = target.toSchoenbergSymbolicChord()

            assertEquals(position, symbolic.seventhPosition)
            assertEquals(
                TextbookTriadPosition.ROOT_POSITION,
                symbolic.position,
                "A seventh chord must not also claim a triad inversion",
            )
        }
    }

    @Test
    fun convertedChordsMatchTheChapterVocabularyStructurally() {
        val vocabulary = SchoenbergIntegratedTechTree.vocabulary(
            key = Key.major(com.mecon.api.primitive.PitchClass.C),
            treatmentIds = SchoenbergHarmonicTreatments.integratedDiatonicTreatments,
        ).toSet()
        val converted = ChordSelectionTargetCatalog.targets(key)
            .filter { it.arity == ChordArity.SEVENTH }
            .map(ChordTarget::toSchoenbergSymbolicChord)

        assertTrue(converted.isNotEmpty())
        assertTrue(
            converted.any { it in vocabulary },
            "No converted seventh chord matched the chapter vocabulary; " +
                "sample=${converted.first()}",
        )
        assertTrue(
            converted.none {
                it.arity == ChordArity.SEVENTH &&
                    it.position != TextbookTriadPosition.ROOT_POSITION
            },
        )
    }
}
