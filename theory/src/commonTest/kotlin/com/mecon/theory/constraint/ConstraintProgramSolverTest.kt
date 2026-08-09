package com.mecon.theory.constraint

import com.mecon.theory.Key
import com.mecon.theory.ChordArity
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.NaturalTriads
import com.mecon.api.primitive.Pitch
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.RequirementMode
import com.mecon.theory.RuleId
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleProfile
import com.mecon.theory.RuleRequirement
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.standardFourPartWritingVoices
import com.mecon.theory.toFixedVoiceScore
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstraintProgramSolverTest {

    private val cMajor: Key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)

    private fun triad(degree: Int): NaturalTriad =
        NaturalTriads.inKey(cMajor).first { it.degree == degree }

    private fun rootSlot(degree: Int): SlotDomain =
        SlotDomain(listOf(TextbookTriadTarget(triad(degree), TextbookTriadPosition.ROOT_POSITION)))

    @Test
    fun solvesBasicRootPositionProgression() {
        val program = ConstraintProgram.fromRequirements(
            key = cMajor,
            slotDomains = listOf(rootSlot(1), rootSlot(5), rootSlot(1)),
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(

            ),
        )
        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty(), "I-V-I 应有候选")
        assertEquals(
            listOf(1, 5, 1),
            solutions.first().voicings.map { it.target.degree },
        )
    }

    @Test
    fun solvesMixedTriadAndSeventhProgram() {
        // 混合 arity：I(三和弦) → V7(七和弦，省五) → I(三和弦)。三和弦槽走三章规则，七和弦槽走七和弦章规则，
        // V7→I transition 走七和弦解决规则；候选逐槽 target arity 一致，省五约束在生成期收窄。
        val v7 = DominantSeventhRules.seventhChordInKey(cMajor, 5)
        val program = ConstraintProgram.fromRequirements(
            key = cMajor,
            slotDomains = listOf(
                rootSlot(1),
                SlotDomain(
                    targets = listOf(
                        TextbookSeventhTarget(v7, TextbookSeventhPosition.ROOT_POSITION)
                    ),
                ),
                rootSlot(1),
            ),
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                toneCompleteness = listOf(
                    ToneCompletenessRequirement(
                        window = SlotWindow(1, 1),
                        requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
                        omittedTones = setOf(ChordTone.FIFTH),
                        selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                    )
                ),
                searchConfig = SearchConfig(maxResults = 4, beamWidth = 128),

            ),
        )
        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty(), "I-V7-I 混合应有候选")
        val voicings = solutions.first().voicings
        assertEquals(ChordArity.TRIAD, voicings[0].target.arity, "第 0 槽应为三和弦 target")
        assertEquals(ChordArity.SEVENTH, voicings[1].target.arity, "第 1 槽应为七和弦 target")
        assertEquals(ChordArity.TRIAD, voicings[2].target.arity, "第 2 槽应为三和弦 target")

        val v7Voicing = voicings[1]
        val fifthPc = v7.chord.pitchClasses[2]
        assertTrue(
            listOf(v7Voicing.soprano, v7Voicing.alto, v7Voicing.tenor, v7Voicing.bass)
                .none { it.pitchClass == fifthPc },
            "OMIT_FIFTH 收窄后 V7 不应含五音",
        )
    }

    @Test
    fun orKeepsPrefixWhileFutureBranchIsUndetermined() {
        val futureRootDoubling = ConstraintExpr.Atom(
            ConstraintPredicate.ToneDoubled(
                DoublingRequirement(slot = 1, tone = ChordTone.ROOT, required = true)
            )
        )
        val impossibleFirstBranch = ConstraintExpr.Atom(
            ConstraintPredicate.ToneCompleteness(
                ToneCompletenessRequirement(
                    window = SlotWindow(0, 0),
                    omittedTones = setOf(ChordTone.ROOT),
                )
            )
        )
        val program = ConstraintProgram(
            key = cMajor,
            slotDomains = listOf(rootSlot(1), rootSlot(1)),
            constraints = listOf(
                Constraint(
                    ConstraintExpr.Or(
                        listOf(
                            ConstraintBranch(impossibleFirstBranch),
                            ConstraintBranch(futureRootDoubling),
                        )
                    )
                )
            ),
            searchConfig = SearchConfig(maxResults = 2, beamWidth = 96),
        )

        assertTrue(
            ConstraintProgramSolver.solve(program).isNotEmpty(),
            "Or 的未来分支未落定时不得把前缀误剪掉",
        )
    }

    @Test
    fun targetMatchKeepsPrefixWhileLaterWindowSlotCanMatch() {
        val program = ConstraintProgram(
            key = cMajor,
            slotDomains = listOf(rootSlot(1), rootSlot(5), rootSlot(1)),
            constraints = listOf(
                Constraint(
                    expr = ConstraintExpr.Atom(
                        ConstraintPredicate.TargetMatches(
                            TargetFeatureBonusRequirement(
                                window = SlotWindow(0, 1),
                                selector = TargetSelector(degrees = setOf(5)),
                                ruleId = RuleId("test.future-target-match"),
                                message = "窗口内须出现 V。",
                                bonus = 0.0,
                            )
                        )
                    ),
                    modality = ConstraintModality.Require,
                )
            ),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 64),
        )

        assertTrue(
            ConstraintProgramSolver.solve(program).isNotEmpty(),
            "较早槽未命中时，只要窗口内未来槽仍可命中就不得剪掉前缀。",
        )
    }

    @Test
    fun preferredNeighborRequirementIsNotProjectedAsHardRelation() {
        val preferredPreparation = ChordToneNeighborRequirement(
            window = SlotWindow(0, 1),
            sourceSlot = 1,
            sourceTone = ChordTone.SEVENTH,
            direction = ChordToneNeighborDirection.PREVIOUS,
            candidateScaleDegrees = setOf(4),
            allowedDiatonicStepDeltas = setOf(0),
            sourceSelector = TargetSelector(
                degrees = setOf(5),
                arities = setOf(ChordArity.SEVENTH),
            ),
        )
        val program = ConstraintProgram(
            key = cMajor,
            slotDomains = listOf(rootSlot(1), rootSlot(5)),
            constraints = listOf(
                Constraint(
                    expr = ConstraintExpr.Atom(ConstraintPredicate.NeighborTone(preferredPreparation)),
                    modality = ConstraintModality.Prefer(),
                ),
                Constraint(
                    expr = ConstraintExpr.Atom(ConstraintPredicate.NeighborTone(preferredPreparation)),
                    modality = ConstraintModality.Annotate,
                ),
            ),
        )

        assertTrue(
            program.chordToneNeighbors.isEmpty(),
            "Prefer/Annotate 邻接规则只参与评分与 finding，不能投影成早期硬剪枝。",
        )
    }

    @Test
    fun constraintScopeSkipsExpressionWhenSelectorDoesNotMatch() {
        val program = ConstraintProgram(
            key = cMajor,
            slotDomains = listOf(rootSlot(1)),
            constraints = listOf(
                Constraint(
                    expr = ConstraintExpr.Atom(
                        ConstraintPredicate.ToneCompleteness(
                            ToneCompletenessRequirement(
                                window = SlotWindow(0, 0),
                                omittedTones = setOf(ChordTone.ROOT),
                            )
                        )
                    ),
                    scope = ConstraintScope(selector = TargetSelector(degrees = setOf(5))),
                )
            ),
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 64),
        )

        assertTrue(ConstraintProgramSolver.solve(program).isNotEmpty())
    }

    @Test
    fun mixedArityDoesNotCollapseTriadContextAcrossSeventhSlot() {
        // 回归 M2：checkScore 不能再把异 arity 槽过滤掉后，把 I-I64-I 误看成连续三和弦上下文。
        val v7 = DominantSeventhRules.seventhChordInKey(cMajor, 5)
        val voices = standardFourPartWritingVoices()
        val frames = listOf(
            frame(0, TextbookTriadTarget(triad(1), TextbookTriadPosition.SECOND_INVERSION), 72, 64, 60, 55),
            frame(1, TextbookSeventhTarget(v7, TextbookSeventhPosition.ROOT_POSITION), 71, 65, 62, 55),
            frame(2, TextbookTriadTarget(triad(5), TextbookTriadPosition.ROOT_POSITION), 71, 62, 59, 55),
        )
        val state = FixedVoiceWritingState(frames)
        val findings = ChordRuleDispatcher(defaultChordRuleModules(cMajor, slotCount = frames.size)).checkScore(
            FixedVoiceScoreRuleContext(
                fixedVoiceScore = state.toFixedVoiceScore(voices),
                state = state,
            )
        )

        assertTrue(
            findings.none { it.ruleId == SecondInversionTriadRules.CADENTIAL_SIX_FOUR },
            "七和弦隔开的 I64-V 不应误命中终止四六上下文 finding",
        )
    }

    private fun frame(
        slotIndex: Int,
        target: ChordTarget,
        soprano: Int,
        alto: Int,
        tenor: Int,
        bass: Int,
    ): FixedVoiceWritingFrame<ChordTarget> {
        val voices = standardFourPartWritingVoices()
        return FixedVoiceWritingFrame(
            slotIndex = slotIndex,
            target = target,
            pitchesByVoiceId = voices.zip(listOf(soprano, alto, tenor, bass))
                .associate { (voice, midi) -> voice.id to Pitch.fromMidi(midi, preferSharps = true) },
        )
    }

    @Test
    fun ruleAtRequirementHitsIndication() {
        val program = ConstraintProgram.fromRequirements(
            key = cMajor,
            slotDomains = listOf(rootSlot(5), rootSlot(1)),
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                ruleProfile = RuleProfile(
                    id = "test",
                    requirements = listOf(
                        RuleRequirement(
                            ruleId = RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
                            mode = RequirementMode.REQUIRE_INDICATION,
                            window = SlotWindow(0, null),
                        )
                    ),
                ),

            ),
        )
        val solutions = ConstraintProgramSolver.solve(program)

        assertTrue(solutions.isNotEmpty(), "V-I + 要求四五度共同音应有候选")
        assertTrue(
            solutions.all { solution ->
                solution.breakdown.findings.any {
                    it.ruleId == RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE &&
                        it.kind == RuleFindingKind.INDICATION
                }
            },
            "满足 REQUIRE_INDICATION 的候选都应命中目标 finding",
        )
    }

    @Test
    fun futureWindowRequirementIsNotJudgedMissing() {
        // I-I 上无法产生终止四六 INDICATION：
        //   全局 requirement（window=null）→ 完整解判为缺失 → 无解；
        //   窗口起点在未来（超出进行长度）→ 生成期投影判定"尚不可裁决" → 不强制 → 有解。
        fun programWith(window: SlotWindow?): ConstraintProgram =
            ConstraintProgram.fromRequirements(
                key = cMajor,
                slotDomains = listOf(rootSlot(1), rootSlot(1)),
                    configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
                    ruleProfile = RuleProfile(
                        id = "test",
                        requirements = listOf(
                            RuleRequirement(
                                ruleId = SecondInversionTriadRules.CADENTIAL_SIX_FOUR,
                                mode = RequirementMode.REQUIRE_INDICATION,
                                window = window,
                            )
                        ),
                    ),

                ),
            )

        assertTrue(
            ConstraintProgramSolver.solve(programWith(window = null)).isEmpty(),
            "全局要求无法满足的规则 → 无解",
        )
        assertTrue(
            ConstraintProgramSolver.solve(programWith(window = SlotWindow(5, null))).isNotEmpty(),
            "窗口在未来（尚不可裁决）→ 不误判缺失，仍有解",
        )
    }
}
