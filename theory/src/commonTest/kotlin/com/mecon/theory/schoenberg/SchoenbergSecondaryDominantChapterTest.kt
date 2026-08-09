package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.SecondaryHarmonyFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchoenbergSecondaryDominantChapterTest {
    @Test
    fun sharedCatalogContainsFunctionalAndModalTypesButDoesNotTonicizeDegreeSeven() {
        val types = SchoenbergSecondaryDominantChapter.harmonyTypes(Key.major(PitchClass.C))

        assertTrue(types.any { it.tonicizedDegree == 1 })
        assertTrue(types.none { it.tonicizedDegree == 7 })
        assertNotNull(
            types.firstOrNull {
                it.family == SecondaryHarmonyFamily.SECONDARY_DOMINANT &&
                    it.tonicizedDegree == 5 &&
                    it.rootDegree == 2 &&
                    it.rootAlteration == 0 &&
                    it.quality == ChordQuality.MAJOR
            },
            "C major must expose D major as V/V",
        )
        assertNotNull(
            types.firstOrNull {
                it.family == SecondaryHarmonyFamily.SECONDARY_LEADING &&
                    it.tonicizedDegree == 5 &&
                    it.rootDegree == 4 &&
                    it.rootAlteration == 1 &&
                    it.quality == ChordQuality.DIMINISHED
            },
            "C major must expose F-sharp diminished as vii°/V",
        )
        assertTrue(
            types.any {
                it.family == SecondaryHarmonyFamily.MODAL_AUGMENTED &&
                    it.quality == ChordQuality.AUGMENTED
            }
        )
        assertTrue(
            types.any {
                it.family == SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT &&
                    it.tonicizedDegree == 1 &&
                    it.rootDegree == 5 &&
                    it.modalOrigins == setOf(Mode.DORIAN, Mode.LYDIAN) &&
                    it.quality == ChordQuality.MINOR
            },
            "Dorian and lowered-seventh Lydian must derive one shared 5-b7-2 color sonority",
        )
        assertTrue(
            types.none {
                it.family == SecondaryHarmonyFamily.SECONDARY_DOMINANT &&
                    it.tonicizedDegree == 4 &&
                    it.quality == ChordQuality.MAJOR7 &&
                    Mode.LYDIAN in it.modalOrigins
            },
            "Lydian must lower degree seven instead of emitting an unaltered major seventh",
        )
    }

    @Test
    fun enumerationUsesAuthenticAndDeceptiveAppliedResolutions() {
        val progressions = SchoenbergSecondaryDominantChapter.enumerate(Key.major(PitchClass.C))

        assertTrue(progressions.isNotEmpty())
        assertTrue(progressions.all { progression ->
            progression.kind == SchoenbergConnectionKind.SECONDARY_FUNCTION &&
                progression.slots.single { it.secondaryFamily != null }.appliedToDegree != 7
        })
        assertTrue(progressions.any { progression ->
            val applied = progression.slots[1]
            progression.slots[2].degree == applied.appliedToDegree
        })
        assertTrue(progressions.any { progression ->
            val applied = progression.slots[1]
            progression.slots[2].degree != applied.appliedToDegree
        })
    }

    @Test
    fun concreteHarmonyChoiceFiltersEnumerationBeforeProgressionSelection() {
        val key = Key.major(PitchClass.C)
        listOf(key, Key.minor(PitchClass.A)).forEach { exerciseKey ->
            val choices = SchoenbergSecondaryDominantChapter.harmonyChoices(exerciseKey)
            val expectedFamilies = if (exerciseKey.mode == Mode.AEOLIAN) {
                SecondaryHarmonyFamily.entries.toSet() -
                    SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT
            } else {
                SecondaryHarmonyFamily.entries.toSet()
            }
            assertEquals(expectedFamilies, choices.map { it.chord.secondaryFamily }.toSet())
            val choicesWithoutProgressions = choices.filter {
                SchoenbergSecondaryDominantChapter.enumerate(exerciseKey, it.id).isEmpty()
            }
            assertTrue(
                choicesWithoutProgressions.isEmpty(),
                "Every listed concrete chord must offer progressions: ${choicesWithoutProgressions.map { it.id }}",
            )
        }
        val choices = SchoenbergSecondaryDominantChapter.harmonyChoices(key)
        val selected = choices.first {
            it.chord.secondaryFamily == SecondaryHarmonyFamily.MODAL_DESCENDING_DOMINANT
        }
        val progressions = SchoenbergSecondaryDominantChapter.enumerate(key, selected.id)

        assertTrue(progressions.isNotEmpty())
        assertTrue(progressions.all { progression ->
            progression.slots.single { it.secondaryFamily != null }.transitionToken() ==
                selected.chord.transitionToken()
        })
    }

    @Test
    fun everyDisplayedSecondaryFamilyHasARealizableRepresentative() {
        val key = Key.major(PitchClass.C)
        val choices = SchoenbergSecondaryDominantChapter.harmonyChoices(key)

        SecondaryHarmonyFamily.entries.forEach { family ->
            val choice = choices.first { it.chord.secondaryFamily == family }
            val programs = SchoenbergSecondaryDominantChapter.enumerate(key, choice.id)
                .take(8)
                .map { progression ->
                    SchoenbergSecondaryDominantChapter.program(
                        key = key,
                        progression = progression,
                        searchConfig = com.mecon.theory.SearchConfig(maxResults = 1, beamWidth = 256),
                    )
                }
            assertTrue(
                ConstraintProgramSolver.solveFirstFeasible(programs, maxProgramAttempts = programs.size)
                    .isNotEmpty(),
                "$family must have a realizable displayed chord and progression",
            )
        }
    }

    @Test
    fun secondaryLeadingSeventhsKeepModalProvenanceWithoutChangingQuality() {
        val types = SchoenbergSecondaryDominantChapter.harmonyTypes(Key.minor(PitchClass.A))
        val leadingSeventhOfFive = types.first {
            it.family == SecondaryHarmonyFamily.SECONDARY_LEADING &&
                it.tonicizedDegree == 5 &&
                it.arity == ChordArity.SEVENTH
        }

        assertEquals(setOf(Mode.PHRYGIAN), leadingSeventhOfFive.modalOrigins)
        assertEquals(ChordQuality.HALF_DIMINISHED7, leadingSeventhOfFive.quality)
    }

    @Test
    fun everySecondaryLeadingSeventhIsHalfDiminishedForEveryTargetDegree() {
        listOf(
            Key.major(PitchClass.C),
            Key.minor(PitchClass.A),
        ).forEach { key ->
            val leadingSevenths = SchoenbergSecondaryDominantChapter.harmonyTypes(key)
                .filter {
                    it.family == SecondaryHarmonyFamily.SECONDARY_LEADING &&
                        it.arity == ChordArity.SEVENTH
                }

            assertEquals((1..6).toSet(), leadingSevenths.map { it.tonicizedDegree }.toSet())
            assertTrue(
                leadingSevenths.all { it.quality == ChordQuality.HALF_DIMINISHED7 },
                "$key must construct every secondary leading seventh as half-diminished",
            )
        }
    }

    @Test
    fun minorAppliedDominantUsesLocalLeadingToneInsteadOfGlobalRaisedTurn() {
        val key = Key.minor(PitchClass.A)
        val progression = SchoenbergSecondaryDominantChapter.enumerate(key)
            .first {
                val chord = it.slots[1]
                chord.secondaryFamily == SecondaryHarmonyFamily.SECONDARY_DOMINANT &&
                    chord.appliedToDegree == 5 &&
                    chord.arity == ChordArity.TRIAD &&
                    it.slots[2].degree == 5
            }
        val applied = progression.slots[1]

        assertEquals(2, applied.degree)
        assertEquals(ChordQuality.MAJOR, applied.quality)
        val program = SchoenbergSecondaryDominantChapter.program(key, progression)
        assertTrue(
            program.constraints.any {
                it.ruleId == SchoenbergSecondaryDominantChapter.LEADING_TONE_RULE_ID
            }
        )
        assertTrue(
            ConstraintProgramSolver.solve(program).isNotEmpty(),
            "B-D#-F# in a minor must allow D# to resolve to E rather than forcing D# to E#",
        )
    }
}
