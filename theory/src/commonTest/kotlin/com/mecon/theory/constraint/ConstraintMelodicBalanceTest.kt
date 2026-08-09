package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.ScoreId
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScore
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.Key
import com.mecon.theory.NaturalTriads
import com.mecon.theory.RuleId
import com.mecon.theory.standardFourPartWritingVoices
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstraintMelodicBalanceTest {
    @Test
    fun detectsUniqueExtremeAndScoresNearbyRepeatedIntervalPatterns() {
        val voices = standardFourPartWritingVoices()
        val soprano = voices.first { it.role == FixedVoiceRole.SOPRANO }
        val key = Key.major(PitchClass.C)
        val target: ChordTarget = TextbookTriadTarget(
            NaturalTriads.inKey(key).first { it.degree == 1 },
            TextbookTriadPosition.ROOT_POSITION,
        )
        val sopranoLine = listOf("C4", "D4", "E4", "C4", "D4").map(Pitch::fromName)
        val frames: List<FixedVoiceWritingFrame<ChordTarget>> = sopranoLine.mapIndexed { slot, pitch ->
            FixedVoiceWritingFrame(
                slotIndex = slot,
                target = target,
                pitchesByVoiceId = voices.associate { voice ->
                    voice.id to if (voice == soprano) pitch else Pitch.fromName(
                        when (voice.role) {
                            FixedVoiceRole.ALTO -> "G3"
                            FixedVoiceRole.TENOR -> "E3"
                            FixedVoiceRole.BASS -> "C3"
                            else -> error("Unexpected voice ${voice.role}")
                        }
                    )
                },
            )
        }
        val uniqueRule = RuleId("test.unique-extreme")
        val repetitionRule = RuleId("test.repeated-pattern")
        val program = ConstraintProgram(
            key = key,
            slotDomains = List(frames.size) { SlotDomain(listOf(target)) },
            constraints = listOf(
                globalPreference(
                    ConstraintPredicate.UniqueVoiceExtreme(
                        ChordToneVoiceFilter.SOPRANO,
                        VoiceExtreme.HIGHEST,
                    ),
                    uniqueRule,
                ),
                globalPreference(
                    ConstraintPredicate.NoRepeatedVoicePattern(
                        voiceFilter = ChordToneVoiceFilter.SOPRANO,
                        minPatternNotes = 2,
                        maxPatternNotes = 3,
                    ),
                    repetitionRule,
                ),
            ),
        )
        val context = FixedVoiceScoreRuleContext(
            fixedVoiceScore = FixedVoiceScore(
                scoreId = ScoreId("constraint-melodic-balance"),
                voices = voices,
                eventsByVoice = emptyMap(),
            ),
            state = FixedVoiceWritingState(frames = frames),
        )

        val findings = ConstraintAlgebraRuleProvider(program, voices).checkScore(context, emptyList())

        // E4 is unique, while the C-D interval occurs twice with one intervening note.
        assertTrue(findings.none { it.ruleId == uniqueRule })
        val repetition = findings.single { it.ruleId == repetitionRule }
        assertTrue(repetition.scoreDelta > 0.0)
        assertEquals(4, repetition.anchors.size)
    }

    @Test
    fun detectsSimilarChordReturnsAndRepeatedRootProgressionsAcrossInversions() {
        val voices = standardFourPartWritingVoices()
        val key = Key.major(PitchClass.C)
        val triads = NaturalTriads.inKey(key)
        val targets: List<ChordTarget> = listOf(
            TextbookTriadTarget(triads.first { it.degree == 1 }, TextbookTriadPosition.ROOT_POSITION),
            TextbookTriadTarget(triads.first { it.degree == 2 }, TextbookTriadPosition.ROOT_POSITION),
            TextbookTriadTarget(triads.first { it.degree == 1 }, TextbookTriadPosition.FIRST_INVERSION),
            TextbookTriadTarget(triads.first { it.degree == 2 }, TextbookTriadPosition.FIRST_INVERSION),
        )
        val frames = targets.mapIndexed { slot, target ->
            FixedVoiceWritingFrame(
                slotIndex = slot,
                target = target,
                pitchesByVoiceId = voices.associate { voice ->
                    voice.id to Pitch.fromName(
                        when (voice.role) {
                            FixedVoiceRole.SOPRANO -> "C4"
                            FixedVoiceRole.ALTO -> "G3"
                            FixedVoiceRole.TENOR -> "E3"
                            FixedVoiceRole.BASS -> "C3"
                            else -> error("Unexpected voice ${voice.role}")
                        }
                    )
                },
            )
        }
        val distanceRule = RuleId("test.similar-chord-distance")
        val progressionRule = RuleId("test.similar-progression")
        val program = ConstraintProgram(
            key = key,
            slotDomains = targets.map { SlotDomain(listOf(it)) },
            constraints = listOf(
                globalPreference(
                    ConstraintPredicate.MinimumSimilarChordDistance(minimumSlotDistance = 3),
                    distanceRule,
                ),
                globalPreference(
                    ConstraintPredicate.DistinctSimilarChordProgressions,
                    progressionRule,
                ),
            ),
        )
        val context = FixedVoiceScoreRuleContext(
            fixedVoiceScore = FixedVoiceScore(
                scoreId = ScoreId("constraint-harmonic-repetition"),
                voices = voices,
                eventsByVoice = emptyMap(),
            ),
            state = FixedVoiceWritingState(frames = frames),
        )

        val findings = ConstraintAlgebraRuleProvider(program, voices).checkScore(context, emptyList())

        assertTrue(findings.any { it.ruleId == distanceRule })
        assertTrue(findings.any { it.ruleId == progressionRule })
    }

    @Test
    fun prunesRepeatedSopranoCeilingBeforeTheFinalSlot() {
        val voices = standardFourPartWritingVoices()
        val soprano = voices.first { it.role == FixedVoiceRole.SOPRANO }
        val key = Key.major(PitchClass.C)
        val target: ChordTarget = TextbookTriadTarget(
            NaturalTriads.inKey(key).first { it.degree == 1 },
            TextbookTriadPosition.ROOT_POSITION,
        )
        val frames = List(2) { slot ->
            FixedVoiceWritingFrame(
                slotIndex = slot,
                target = target,
                pitchesByVoiceId = voices.associate { voice ->
                    voice.id to if (voice == soprano) Pitch.fromName("G5") else Pitch.fromName(
                        when (voice.role) {
                            FixedVoiceRole.ALTO -> "E4"
                            FixedVoiceRole.TENOR -> "G3"
                            FixedVoiceRole.BASS -> "C3"
                            else -> error("Unexpected voice ${voice.role}")
                        }
                    )
                },
            )
        }
        val ruleId = RuleId("test.early-unique-extreme")
        val program = ConstraintProgram(
            key = key,
            slotDomains = List(3) { SlotDomain(listOf(target)) },
            constraints = listOf(
                Constraint(
                    expr = ConstraintExpr.And(
                        listOf(
                            ConstraintExpr.Atom(
                                ConstraintPredicate.UniqueVoiceExtreme(
                                    ChordToneVoiceFilter.SOPRANO,
                                    VoiceExtreme.HIGHEST,
                                )
                            )
                        )
                    ),
                    modality = ConstraintModality.Require,
                    ruleId = ruleId,
                    explanation = ConstraintExplanation("ok", "repeated ceiling"),
                )
            ),
        )
        val context = FixedVoiceScoreRuleContext(
            fixedVoiceScore = FixedVoiceScore(
                scoreId = ScoreId("constraint-early-extreme"),
                voices = voices,
                eventsByVoice = emptyMap(),
            ),
            state = FixedVoiceWritingState(frames = frames),
        )

        val findings = ConstraintAlgebraRuleProvider(program, voices).checkScore(context, emptyList())

        assertTrue(findings.any { it.ruleId == ruleId })
    }

    private fun globalPreference(predicate: ConstraintPredicate, ruleId: RuleId): Constraint =
        Constraint(
            expr = ConstraintExpr.And(listOf(ConstraintExpr.Atom(predicate))),
            modality = ConstraintModality.Prefer(),
            ruleId = ruleId,
            explanation = ConstraintExplanation("ok", "repeated"),
        )
}
