package com.mecon.theory.schoenberg

import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.constraint.ConstraintProgramSolver
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneVoiceFilter
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchoenbergCadenceAndFreerDissonanceTest {
    private val major = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
    private val minor = Key.fromKeySignatureFifths(0, KeySignatureMode.MINOR)

    @Test
    fun cadenceEnumerationEndsWithSharedAuthenticCadencePolicy() {
        val progressions = SchoenbergCadenceChapter.enumerate(
            key = major,
            continuationChordCount = 8,
        )
        assertTrue(progressions.isNotEmpty())
        progressions.forEach { progression ->
            val suffix = progression.slots.takeLast(3)
            assertTrue(suffix[0].degree in setOf(2, 4, 6))
            assertTrue(suffix[1].degree == 5 && suffix[1].inversion == 0)
            assertTrue(suffix[2].degree == 1 && suffix[2].position == TextbookTriadPosition.ROOT_POSITION)
        }
    }

    @Test
    fun minorAuthenticCadenceUsesRaisedLeadingToneDominantQuality() {
        val progressions = SchoenbergCadenceChapter.enumerate(
            key = minor,
            continuationChordCount = 8,
        )

        assertTrue(progressions.isNotEmpty())
        assertTrue(
            progressions.all {
                it.slots[it.slots.lastIndex - 1].quality in
                    setOf(ChordQuality.MAJOR, ChordQuality.DOMINANT7)
            }
        )
    }

    @Test
    fun optionalDeceptiveAndCadentialSixFourAreBothStructural() {
        val options = SchoenbergCadenceOptions(
            includeDeceptiveCadence = true,
            includeCadentialSixFour = true,
        )
        val progressions = SchoenbergCadenceChapter.enumerate(
            key = major,
            continuationChordCount = 6,
            cadenceOptions = options,
        )
        assertTrue(progressions.isNotEmpty())
        progressions.forEach { progression ->
            assertTrue(progression.slots.takeLast(4)[1].isTonicSixFour())
            assertTrue(
                progression.slots.dropLast(4).zipWithNext().any { (before, after) ->
                    before.degree == 5 && after.degree in setOf(4, 6)
                }
            )
        }
        assertTrue(
            progressions.any { progression ->
                progression.slots.dropLast(4).zipWithNext().any { (before, after) ->
                    before.degree == 5 &&
                        before.arity == ChordArity.SEVENTH &&
                        after.degree in setOf(4, 6)
                }
            },
            "阻碍终止应实际允许 V7 进入 VI/IV，而不只允许 V 三和弦。",
        )
        val program = SchoenbergCadenceChapter.program(
            key = major,
            continuationChordCount = 6,
            cadenceOptions = options,
            progression = progressions.first(),
        )
        val outerLeadingTone = assertNotNull(
            program.chordToneNeighbors.firstOrNull {
                it.ruleId == SchoenbergCadenceChapter.DECEPTIVE_OUTER_LEADING_TONE_RULE_ID
            }
        )
        assertEquals(ChordTone.THIRD, outerLeadingTone.sourceTone)
        assertEquals(ChordToneVoiceFilter.OUTER, outerLeadingTone.voiceFilter)
        assertEquals(setOf(1), outerLeadingTone.candidateScaleDegrees)
        assertEquals(setOf(1), outerLeadingTone.allowedDiatonicStepDeltas)
    }

    @Test
    fun freerStageRequiresFirstInversionLeadingSubstitutionAndDropsStrictNeighbors() {
        val progressions = SchoenbergFreerDissonanceChapter.enumerate(
            key = major,
            continuationChordCount = 10,
        )
        assertTrue(progressions.isNotEmpty())
        assertTrue(
            progressions.all { progression ->
                progression.slots.dropLast(3).zipWithNext().any { (before, after) ->
                    before.degree == 7 &&
                        before.arity == ChordArity.TRIAD &&
                        before.position == TextbookTriadPosition.FIRST_INVERSION &&
                        after.degree in setOf(1, 2, 4, 6)
                }
            }
        )
        val triads = exerciseTriads(major, includeLeadingTriad = true)
        assertTrue(
            progressions.any { progression ->
                progression.slots.zipWithNext().any { (before, after) ->
                    val seventh = after.toTarget(triads).pitchClassFor(ChordTone.SEVENTH)
                    after.arity == ChordArity.SEVENTH &&
                        seventh != null &&
                        seventh !in before.toTarget(triads).sonority.pitchClasses
                }
            },
            "自由处理阶段应实际枚举到无需符号共同音预备的七和弦。",
        )

        val strict = SchoenbergIntegratedTechTree.program(
            key = major,
            continuationChordCount = 4,
            treatmentIds = setOf(
                SchoenbergHarmonicTreatments.LEADING_TRIAD,
                SchoenbergHarmonicTreatments.FIRST_INVERSION,
                SchoenbergHarmonicTreatments.DIATONIC_DOMINANT,
            ),
        )
        val freer = SchoenbergIntegratedTechTree.program(
            key = major,
            continuationChordCount = 4,
            treatmentIds = setOf(
                SchoenbergHarmonicTreatments.LEADING_TRIAD,
                SchoenbergHarmonicTreatments.FIRST_INVERSION,
                SchoenbergHarmonicTreatments.DIATONIC_DOMINANT,
            ),
            dissonanceTreatment = SchoenbergDissonanceTreatment.FREER,
        )
        assertTrue(strict.chordToneNeighbors.isNotEmpty())
        assertTrue(freer.chordToneNeighbors.isEmpty())
        assertFalse(freer.avoidDoublings.any { it.ruleId?.value?.contains("leading") == true })
    }

    @Test
    fun freerStageEnumeratesDeceptiveCadenceAtMinimumLength() {
        val progressions = SchoenbergFreerDissonanceChapter.enumerate(
            key = major,
            continuationChordCount = 8,
            cadenceOptions = SchoenbergCadenceOptions(includeDeceptiveCadence = true),
        )

        assertTrue(progressions.isNotEmpty(), "自由处理章节在最短长度也应能同时容纳阻碍终止与 VII6 替代。")
        progressions.forEach { progression ->
            val body = progression.slots.dropLast(3)
            assertTrue(
                body.zipWithNext().any { (before, after) ->
                    before.degree == 5 && after.degree in setOf(4, 6)
                }
            )
            assertTrue(
                body.zipWithNext().any { (before, after) ->
                    before.degree == 7 &&
                        before.arity == ChordArity.TRIAD &&
                        before.position == TextbookTriadPosition.FIRST_INVERSION &&
                        after.degree in setOf(1, 2, 4, 6)
                }
            )
            assertTrue(SchoenbergRootMotionAndRepetitionChapter.followsDirectionPolicy(progression.slots))
        }
        val firstSolutions = ConstraintProgramSolver.solve(
            SchoenbergFreerDissonanceChapter.program(
                key = major,
                continuationChordCount = 8,
                cadenceOptions = SchoenbergCadenceOptions(includeDeceptiveCadence = true),
                progression = progressions.first(),
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 128),
            )
        )
        assertTrue(firstSolutions.isNotEmpty(), "枚举出的首条阻碍终止进行本身必须能完成四部写作。")
    }

    @Test
    fun firstCadentialSixFourProgressionWithUnpreparedV7IsRealizable() {
        val options = SchoenbergCadenceOptions(includeCadentialSixFour = true)
        val progression = SchoenbergCadenceChapter.enumerate(
            key = major,
            continuationChordCount = 6,
            cadenceOptions = options,
        ).first()
        assertEquals(ChordArity.SEVENTH, progression.slots[progression.slots.lastIndex - 1].arity)

        val solutions = ConstraintProgramSolver.solve(
            SchoenbergCadenceChapter.program(
                key = major,
                continuationChordCount = 6,
                cadenceOptions = options,
                progression = progression,
                searchConfig = SearchConfig(maxResults = 1, beamWidth = 128),
            )
        )

        assertTrue(
            solutions.isNotEmpty(),
            "终止档允许 I64-V7；软性的 V7 七音预备偏好不得把该进行硬剪掉。",
        )
    }

    @Test
    fun enumeratedCadenceAndFreerProgressionsReachFourPartSolutions() {
        val cadenceProgressions = SchoenbergCadenceChapter.enumerate(
            key = major,
            continuationChordCount = 8,
            budget = SchoenbergIntegratedTechTree.EnumerationBudget(maxResults = 12),
        )
        val cadenceSolutions = ConstraintProgramSolver.solveFirstFeasible(
            cadenceProgressions.map {
                SchoenbergCadenceChapter.program(
                    key = major,
                    continuationChordCount = 8,
                    progression = it,
                    searchConfig = SearchConfig(maxResults = 1, beamWidth = 512),
                )
            }
        )
        assertTrue(cadenceSolutions.isNotEmpty())

        val freerProgressions = SchoenbergFreerDissonanceChapter.enumerate(
            key = major,
            continuationChordCount = 10,
            budget = SchoenbergIntegratedTechTree.EnumerationBudget(maxResults = 64),
        )
        val freerSolutions = ConstraintProgramSolver.solveFirstFeasible(
            freerProgressions.map {
                SchoenbergFreerDissonanceChapter.program(
                    key = major,
                    continuationChordCount = 10,
                    progression = it,
                    searchConfig = SearchConfig(maxResults = 1, beamWidth = 512),
                )
            }
        )
        assertTrue(freerSolutions.isNotEmpty())
    }

    @Test
    fun sixChordCadentialSixFourHasFeasibleProgressionWithinRuntimeAttemptWindow() {
        val options = SchoenbergCadenceOptions(includeCadentialSixFour = true)
        val progressions = SchoenbergCadenceChapter.enumerate(
            key = major,
            continuationChordCount = 6,
            cadenceOptions = options,
        )
        val solutions = ConstraintProgramSolver.solveFirstFeasible(
            programs = progressions.map {
                SchoenbergCadenceChapter.program(
                    key = major,
                    continuationChordCount = 6,
                    cadenceOptions = options,
                    progression = it,
                    searchConfig = SearchConfig(maxResults = 1, beamWidth = 128),
                )
            },
            maxProgramAttempts = 8,
        )
        assertTrue(solutions.isNotEmpty())
    }

    private val SchoenbergSymbolicChord.inversion: Int
        get() = seventhPosition?.ordinal ?: position.ordinal

    private fun SchoenbergSymbolicChord.isTonicSixFour(): Boolean =
        degree == 1 &&
            arity == ChordArity.TRIAD &&
            position == TextbookTriadPosition.SECOND_INVERSION
}
