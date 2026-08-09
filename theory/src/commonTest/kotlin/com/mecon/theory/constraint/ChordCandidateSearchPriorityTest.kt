package com.mecon.theory.constraint

import com.mecon.api.primitive.Pitch
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.NaturalTriads
import com.mecon.theory.SearchConfig
import com.mecon.theory.standardFourPartWritingVoices
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookSeventhTarget
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChordCandidateSearchPriorityTest {
    private val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
    private val voices = standardFourPartWritingVoices()
    private val tonic = TextbookTriadTarget(
        NaturalTriads.inKey(key).first { it.degree == 1 },
        TextbookTriadPosition.ROOT_POSITION,
    )
    private val dominantSeventh = TextbookSeventhTarget(
        DominantSeventhRules.seventhChordInKey(key, 5),
        TextbookSeventhPosition.ROOT_POSITION,
    )

    @Test
    fun omissionPolicyIsAHardCandidateBoundary() {
        assertTrue(solvesPinned(tonic, listOf("C5", "E4", "C4", "C3")), "三和弦可省五音")
        assertFalse(solvesPinned(tonic, listOf("C5", "G4", "C4", "C3")), "三和弦不可省三音")

        assertTrue(solvesPinned(dominantSeventh, listOf("F5", "D5", "G3", "G2")), "七和弦可省三音")
        assertTrue(solvesPinned(dominantSeventh, listOf("F5", "B4", "G3", "G2")), "七和弦可省五音")
        assertFalse(
            solvesPinned(dominantSeventh, listOf("F5", "G4", "G3", "G2")),
            "七和弦不可同时省三音与五音",
        )
        assertFalse(
            solvesPinned(dominantSeventh, listOf("B4", "D4", "B3", "G2")),
            "七和弦不可省七音",
        )
    }

    @Test
    fun transitionPriorityWidensInDeclaredOrder() {
        val program = baseProgram(tonic, emptyList())
        val factory = ChordTargetCandidateFactory(program, voices)
        val previous = frame(tonic, listOf("C6", "G4", "E4", "C3"), -1)

        assertEquals(
            TransitionRelaxationTier.STRICT,
            factory.transitionTier(previous, frame(tonic, listOf("C6", "G4", "E4", "C3"))),
        )
        assertEquals(
            TransitionRelaxationTier.OMITTED_TONE,
            factory.transitionTier(previous, frame(tonic, listOf("C6", "E4", "E4", "C3"))),
        )
        assertEquals(
            TransitionRelaxationTier.INNER_FIFTH,
            factory.transitionTier(previous, frame(tonic, listOf("C6", "C4", "E4", "C3"))),
        )
        assertEquals(
            TransitionRelaxationTier.SOPRANO_SIXTH,
            factory.transitionTier(previous, frame(tonic, listOf("E5", "G4", "C4", "C3"))),
        )
        assertEquals(
            TransitionRelaxationTier.WIDER_LEAPS,
            factory.transitionTier(previous, frame(tonic, listOf("C5", "G4", "E4", "C3"))),
        )
    }

    /**
     * 分层 DP 为每个标签增量维护路径优先级，不再逐次比较时重扫整条路径。逐帧扩展必须与
     * [ChordTargetCandidateFactory.pathPriority] 的整段重算逐分量相等，否则前沿排序会漂移。
     */
    @Test
    fun incrementalPathPriorityMatchesFullRecompute() {
        val program = baseProgram(tonic, emptyList())
        val factory = ChordTargetCandidateFactory(program, voices)
        val boundary = frame(tonic, listOf("C5", "G4", "E4", "C3"), -1)
        val path = listOf(
            frame(tonic, listOf("C5", "G4", "E4", "C3"), 0),
            // 内声部五度、高音六度、更宽跳进与省略五音各出现一次，覆盖全部放宽层。
            frame(dominantSeventh, listOf("B4", "F4", "D4", "G2"), 1),
            frame(tonic, listOf("G5", "E4", "C4", "C3"), 2),
            frame(tonic, listOf("C5", "E4", "C4", "C3"), 3),
            frame(dominantSeventh, listOf("D5", "B4", "F4", "G3"), 4),
        )

        path.indices.forEach { end ->
            val prefix = path.take(end + 1)
            var incremental = com.mecon.theory.SearchPriority.NEUTRAL
            var previous = factory.candidateProfile(boundary)
            prefix.forEach { frame ->
                val profile = factory.candidateProfile(frame)
                incremental = factory.extendPathPriority(incremental, previous, profile)
                previous = profile
            }
            assertEquals(
                factory.pathPriority(boundary, prefix).components,
                incremental.components,
                "prefix length ${prefix.size}",
            )
        }
    }

    private fun solvesPinned(target: ChordTarget, pitches: List<String>): Boolean {
        val parsed = pitches.map(Pitch::fromName)
        val program = baseProgram(
            target,
            voices.zip(parsed).map { (voice, pitch) -> VoicePitchPin(0, voice.id, pitch) },
        )
        return ConstraintProgramSolver.solvePolyphonic(program).isNotEmpty()
    }

    private fun baseProgram(target: ChordTarget, pins: List<VoicePitchPin>): ConstraintProgram =
        ConstraintProgram(
            key = key,
            slotDomains = listOf(SlotDomain(listOf(target))),
            pitchPins = pins,
            writingRulePreset = WritingRulePreset.NONE,
            ruleModules = emptyList(),
            includeDerivedTextbookConstraints = false,
            searchConfig = SearchConfig(maxResults = 1, beamWidth = 64),
        )

    private fun frame(
        target: ChordTarget,
        pitches: List<String>,
        slotIndex: Int = 0,
    ): FixedVoiceWritingFrame<ChordTarget> =
        FixedVoiceWritingFrame(
            slotIndex = slotIndex,
            target = target,
            pitchesByVoiceId = voices.zip(pitches.map(Pitch::fromName))
                .associate { (voice, pitch) -> voice.id to pitch },
        )
}
