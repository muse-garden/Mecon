package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchoenbergMinorSubdominantChapterTest {
    private val key = Key.major(PitchClass.C)
    private val search = SearchConfig(
        maxResults = 1,
        beamWidth = 512,
    )

    @Test
    fun derivesTriadsAndSeventhsFromTonicAndSubdominantNaturalMinor() {
        val chords = SchoenbergMinorSubdominantChapter.borrowedChords(key)
        val triads = chords.filter { it.arity == ChordArity.TRIAD }
        val sevenths = chords.filter { it.arity == ChordArity.SEVENTH }

        assertEquals(10, triads.size)
        assertEquals(10, sevenths.size)
        assertTrue(triads.any { it.degree == 2 && it.rootAlteration == -1 && it.quality == ChordQuality.MAJOR })
        assertTrue(triads.any { it.degree == 6 && it.rootAlteration == -1 && it.quality == ChordQuality.MAJOR })
        assertTrue(triads.any { it.degree == 5 && it.rootAlteration == 0 && it.quality == ChordQuality.DIMINISHED })
        assertTrue(sevenths.any { it.degree == 3 && it.rootAlteration == -1 && it.quality == ChordQuality.MAJOR7 })
    }

    @Test
    fun independentConnectionEnumeratesCuratedChromaticVoiceLeading() {
        val progressions = SchoenbergMinorSubdominantChapter.enumerateConnections(key)

        assertTrue(progressions.isNotEmpty())
        val seventh = progressions.firstOrNull { it.slots[1].arity == ChordArity.SEVENTH }
        assertTrue(seventh != null)
        (progressions.take(3) + seventh).distinct().forEach { progression ->
            assertTrue(
                ConstraintProgramSolver.solve(
                    SchoenbergMinorSubdominantChapter.connectionProgram(
                        key = key,
                        progression = progression,
                        searchConfig = search,
                    )
                ).isNotEmpty(),
                "$progression should admit a four-part realization",
            )
        }
    }

    @Test
    fun neapolitanExerciseCoversSixFourAndDirectDominantCadences() {
        val progressions = SchoenbergMinorSubdominantChapter.enumerateNeapolitanCadences(key)

        assertEquals(listOf(4, 3), progressions.map { it.slots.size })
        val throughSixFour = progressions.first()
        assertEquals(-1, throughSixFour.slots[0].rootAlteration)
        assertEquals(TextbookTriadPosition.FIRST_INVERSION, throughSixFour.slots[0].position)
        assertEquals(TextbookTriadPosition.SECOND_INVERSION, throughSixFour.slots[1].position)
        progressions.forEach { progression ->
            assertTrue(
                ConstraintProgramSolver.solve(
                    SchoenbergMinorSubdominantChapter.neapolitanCadenceProgram(
                        key = key,
                        progression = progression,
                        searchConfig = search,
                    )
                ).isNotEmpty(),
                "$progression should admit a four-part realization",
            )
        }
    }

    @Test
    fun analogousExerciseContainsTextbookFlatSixToFivePattern() {
        val progression = SchoenbergMinorSubdominantChapter
            .enumerateAnalogousNeapolitanConnections(key)
            .first {
                it.slots[0].degree == 6 &&
                    it.slots[0].rootAlteration == -1 &&
                    it.slots[1].degree == 5 &&
                    it.slots[2].appliedToDegree == 5
            }

        assertEquals(TextbookTriadPosition.FIRST_INVERSION, progression.slots[0].position)
        assertEquals(TextbookTriadPosition.SECOND_INVERSION, progression.slots[1].position)
        assertTrue(
            ConstraintProgramSolver.solve(
                SchoenbergMinorSubdominantChapter.analogousNeapolitanProgram(
                    key = key,
                    progression = progression,
                    searchConfig = search,
                )
            ).isNotEmpty(),
            "$progression should admit a four-part realization",
        )
    }

    @Test
    fun neapolitanRelationAlsoUsesTheStudiedMinorKeyVocabulary() {
        val minor = Key.fromKeySignatureFifths(0, KeySignatureMode.MINOR)
        val progressions = SchoenbergMinorSubdominantChapter.enumerateNeapolitanCadences(minor)

        assertEquals(listOf(4, 3), progressions.map { it.slots.size })
        assertTrue(progressions.all { it.slots.first().rootAlteration == -1 })
        progressions.forEach { progression ->
            assertTrue(
                ConstraintProgramSolver.solve(
                    SchoenbergMinorSubdominantChapter.neapolitanCadenceProgram(
                        key = minor,
                        progression = progression,
                        searchConfig = search,
                    )
                ).isNotEmpty(),
                "$progression should admit a minor-key realization",
            )
        }
    }

    @Test
    fun analogousRelationIsIndependentFromTheActiveKeyVocabularyFilter() {
        assertTrue(
            AnalogousNeapolitanRelation.allows(
                sourceRoot = PitchClass(9),
                localTonicRoot = PitchClass(8),
                sourceIsMajorSixth = true,
                localTonicIsSixFour = true,
                appliedDominantTargetsLocalTonic = true,
            ),
            "A major-sixth chord a semitone above a chromatic local tonic is the same relation",
        )
        assertTrue(
            SchoenbergProgressionVocabularyFilters.None.allows(
                key,
                SchoenbergMinorSubdominantChapter.enumerateAnalogousNeapolitanConnections(key).first(),
            )
        )
    }

    @Test
    fun curriculumRegistersThreeMajorKeyIndependentExercises() {
        val ids = setOf(
            SchoenbergCommonToneExercises.MINOR_SUBDOMINANT_CONNECTION_EXERCISE_ID,
            SchoenbergCommonToneExercises.NEAPOLITAN_CADENCE_EXERCISE_ID,
            SchoenbergCommonToneExercises.ANALOGOUS_NEAPOLITAN_EXERCISE_ID,
        )
        ids.forEach { exerciseId ->
            val descriptor = SchoenbergCommonToneExercises.descriptorForExercise(exerciseId)
            assertEquals(SchoenbergExerciseGroup.INDEPENDENT, descriptor.group)
            assertEquals(SchoenbergCommonToneExercises.MAJOR_BRANCH_RULE_ID, descriptor.parentId)
            assertTrue(exerciseId in SchoenbergChapterRegistry.registeredExerciseIds)
        }
    }
}
