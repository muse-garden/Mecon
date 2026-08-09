package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TrackId
import com.mecon.theory.ChordArity
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.SearchConfig
import com.mecon.theory.SearchBackend
import com.mecon.theory.SlotWindow
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget
import com.mecon.theory.NaturalTriads
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonicPatternAndTextureTest {
    private val cMajor = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)

    @Test
    fun matcherAndRuntimeConstraintsSharePatternSteps() {
        val pattern = HarmonicPatterns.AUTHENTIC_CADENCE
        val v7 = TextbookSeventhTarget(
            DominantSeventhRules.seventhChordInKey(cMajor, 5),
            TextbookSeventhPosition.ROOT_POSITION,
        )
        val tonic = TextbookSeventhTarget(
            DominantSeventhRules.tonicTriadInKey(cMajor),
            TextbookSeventhPosition.ROOT_POSITION,
        )

        assertEquals(PatternCompletion.PARTIAL, pattern.matcher().stateFor(listOf(v7)).completion)
        assertEquals(PatternCompletion.COMPLETE, pattern.matcher().stateFor(listOf(v7, tonic)).completion)
        val runtimeSelectors = pattern.constraintsAt(0).map {
            ((it.expr as ConstraintExpr.Atom).predicate as ConstraintPredicate.TargetMatches)
                .requirement.selector
        }
        assertTrue(runtimeSelectors.zip(listOf(v7, tonic)).all { (selector, target) ->
            selector.matches(target)
        })
    }

    @Test
    fun externalSustainedVoiceAllowsEitherThirdOrFifthOmissionInSeventhChord() {
        val target = TextbookSeventhTarget(
            DominantSeventhRules.seventhChordInKey(cMajor, 5),
            TextbookSeventhPosition.ROOT_POSITION,
        )
        assertEquals(ChordArity.SEVENTH, target.arity)

        listOf(
            listOf("C5", "F4", "B3", "G2"), // omit fifth
            listOf("C5", "F4", "D3", "G2"), // omit third
        ).forEach { pitches ->
            val voiceIds = listOf(
                TrackId("solver-soprano"),
                TrackId("solver-alto"),
                TrackId("solver-tenor"),
                TrackId("solver-bass"),
            )
            val program = ConstraintProgram(
                key = cMajor,
                slotDomains = listOf(SlotDomain(listOf(target))),
                texturePlan = HarmonicTexturePlan(
                    listOf(
                        VoiceParticipationSpan(
                            window = SlotWindow(0, 0),
                            voiceId = voiceIds.first(),
                            participation = HarmonicVoiceParticipation.Sustained(Pitch.fromName("C5")),
                        )
                    )
                ),
                pitchPins = voiceIds.zip(pitches).map { (voiceId, pitch) ->
                    VoicePitchPin(0, voiceId, Pitch.fromName(pitch))
                },
                writingRulePreset = WritingRulePreset.NONE,
                ruleModules = emptyList(),
                includeDerivedTextbookConstraints = false,
                searchConfig = SearchConfig(
                    maxResults = 1,
                    beamWidth = 64,
                    // DP state declarations currently cover fixed natural triads only.
                    backend = SearchBackend.GREEDY_DFS,
                ),
            )

            val solution = ConstraintProgramSolver.solve(program)
            assertTrue(solution.isNotEmpty(), "Expected sustained-tone voicing $pitches to be writable")
            assertEquals(PitchClass.C, solution.single().voicings.single().soprano.pitchClass)
        }
    }

    @Test
    fun externalBassPedalDoesNotChangeStructuralInversion() {
        val tonic = NaturalTriads.inKey(cMajor).first { it.degree == 1 }
        val firstInversion = TextbookTriadTarget(tonic, TextbookTriadPosition.FIRST_INVERSION)
        val voiceIds = listOf(
            TrackId("solver-soprano"),
            TrackId("solver-alto"),
            TrackId("solver-tenor"),
            TrackId("solver-bass"),
        )
        val pitches = listOf("C5", "G4", "E3", "F2")
        val program = ConstraintProgram(
            key = cMajor,
            slotDomains = listOf(SlotDomain(listOf(firstInversion))),
            texturePlan = HarmonicTexturePlan(
                listOf(
                    VoiceParticipationSpan(
                        SlotWindow(0, 0),
                        voiceIds.last(),
                        HarmonicVoiceParticipation.Sustained(Pitch.fromName("F2")),
                    )
                )
            ),
            pitchPins = voiceIds.zip(pitches).map { (voiceId, pitch) ->
                VoicePitchPin(0, voiceId, Pitch.fromName(pitch))
            },
            writingRulePreset = WritingRulePreset.NONE,
            ruleModules = emptyList(),
            includeDerivedTextbookConstraints = false,
        )

        assertTrue(ConstraintProgramSolver.solve(program).isNotEmpty())
        assertEquals(1, firstInversion.inversion)
    }
}
