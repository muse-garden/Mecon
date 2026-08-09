package com.mecon.theory

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.RootPositionTriadRules
import com.mecon.theory.textbook.SecondInversionTriadRules
import com.mecon.theory.textbook.TextbookTriadPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneMatcherTest {
    private val allPositions = setOf(
        TextbookTriadPosition.ROOT_POSITION,
        TextbookTriadPosition.FIRST_INVERSION,
        TextbookTriadPosition.SECOND_INVERSION,
    )

    @Test
    fun cadentialSixFourEnumeratesTonicSixFourDominantTonic() {
        val key = Key.major(PitchClass.C)
        val scene = RuleCatalog.scenes(SecondInversionTriadRules.CADENTIAL_SIX_FOUR).single()

        val matches = SceneMatcher.enumerate(scene, key, ChordVocabulary.fromKey(key, allPositions), windowLimit = 3)

        assertTrue(
            matches.any { match ->
                match.slots.size == 3 &&
                    match.slots[0].triad.degree == 1 &&
                    match.slots[0].position == TextbookTriadPosition.SECOND_INVERSION &&
                    match.slots[1].triad.degree == 5 &&
                    match.slots[1].position == TextbookTriadPosition.ROOT_POSITION &&
                    match.slots[2].triad.degree == 1 &&
                    match.slots[2].position == TextbookTriadPosition.ROOT_POSITION
            },
            "expected I(46)-V-I among ${matches.map { m -> m.slots.map { it.triad.degree to it.position } }}",
        )
        // 每条命中都带上和弦序列绑定说明。
        assertTrue(matches.all { it.bindings.isNotEmpty() })
    }

    @Test
    fun minorRaisedFifthToFourthDemonstrationHitsMajorDominantToSubmediant() {
        val key = Key.minor(PitchClass.A)
        val scene = RuleCatalog.scenes(RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH).single()
        assertEquals(SceneRole.DEMONSTRATION, scene.role)

        val matches = SceneMatcher.enumerate(
            scene,
            key,
            ChordVocabulary.fromKey(key, setOf(TextbookTriadPosition.ROOT_POSITION)),
            windowLimit = 2,
        )

        assertTrue(
            matches.any { match ->
                match.slots.size == 2 &&
                    match.slots[0].triad.degree == 5 &&
                    match.slots[0].triad.quality == ChordQuality.MAJOR &&
                    match.slots[1].triad.degree == 6
            },
            "expected minor V(maj)->VI among ${matches.map { m -> m.slots.map { it.triad.degree to it.triad.quality } }}",
        )
    }

    @Test
    fun fourthFifthMayProjectionMatchesApplicability() {
        val scenes = RuleCatalog.scenes(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE)

        for (from in 1..7) {
            for (to in 1..7) {
                val applies = RuleCatalog.applicability(
                    RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
                    SelectionContext(from, to),
                ).applies
                val may = SceneMatcher.appliesInContext(scenes, from, to)
                assertEquals(applies, may, "from=$from to=$to")
                assertEquals(SceneMatcher.degreeDistance(from, to) == 3, may, "from=$from to=$to")
            }
        }
    }

    @Test
    fun addingAPositionToVocabularyOnlyGrowsResults() {
        val key = Key.major(PitchClass.C)
        val scene = RuleCatalog.scenes(RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE).single()

        val rootOnly = SceneMatcher.enumerate(
            scene,
            key,
            ChordVocabulary.fromKey(key, setOf(TextbookTriadPosition.ROOT_POSITION)),
            windowLimit = 2,
        ).toSet()
        val rootPlusFirst = SceneMatcher.enumerate(
            scene,
            key,
            ChordVocabulary.fromKey(
                key,
                setOf(TextbookTriadPosition.ROOT_POSITION, TextbookTriadPosition.FIRST_INVERSION),
            ),
            windowLimit = 2,
        ).toSet()

        assertTrue(rootOnly.isNotEmpty())
        assertTrue(rootPlusFirst.containsAll(rootOnly), "expanding the vocabulary must be monotonic")
    }

    @Test
    fun passingSixFourEnumeratesOnlyStepwiseBassProgressions() {
        val key = Key.major(PitchClass.C)
        val scene = RuleCatalog.scenes(SecondInversionTriadRules.PASSING_SIX_FOUR).single()

        val matches = SceneMatcher.enumerate(scene, key, ChordVocabulary.fromKey(key, allPositions), windowLimit = 3)
        val pairs = matches.map { it.slots[0].triad.degree to it.slots[1].triad.degree }.toSet()

        // 低音同向自然音阶级进（大/小三度跨度均可）+ 首尾同和弦：每个框架和弦恰有一条经过四六，
        // 远比 RootMotion∈{1,2,3} 的松散全集收窄，又不漏掉小三度跨度（如 ii-vi⁶₄-ii⁶）。
        assertEquals(
            setOf(1 to 5, 4 to 1, 5 to 2, 2 to 6, 3 to 7, 6 to 3, 7 to 4),
            pairs,
            "unexpected passing six-four pairs: $pairs",
        )
        assertTrue(matches.all { it.slots.size == 3 })
        // 中间槽恒为第二转位，首尾恒为同一和弦。
        assertTrue(
            matches.all { m ->
                m.slots[1].position == TextbookTriadPosition.SECOND_INVERSION &&
                    m.slots[0].triad.chord.pitchClasses.toSet() == m.slots[2].triad.chord.pitchClasses.toSet()
            },
        )
    }

    @Test
    fun pedalSixFourEnumeratesOnlyHeldBassProgressions() {
        val key = Key.major(PitchClass.C)
        val scene = RuleCatalog.scenes(SecondInversionTriadRules.PEDAL_SIX_FOUR).single()

        val matches = SceneMatcher.enumerate(scene, key, ChordVocabulary.fromKey(key, allPositions), windowLimit = 3)

        // 低音保持：中间四六和弦的低音必须与首尾原位和弦的低音同音级。
        assertTrue(matches.isNotEmpty())
        assertTrue(
            matches.all { m ->
                m.slots.size == 3 &&
                    m.slots[0].bassPitchClass == m.slots[1].bassPitchClass &&
                    m.slots[1].bassPitchClass == m.slots[2].bassPitchClass
            },
            "pedal six-four must hold the bass: ${matches.map { m -> m.slots.map { it.triad.degree to it.position } }}",
        )
    }

    @Test
    fun dominantSeventhSceneEnumeratesSeventhThenTriadResolution() {
        val key = Key.major(PitchClass.C)
        val scene = RuleCatalog.scenes(DominantSeventhRules.SEVENTH_RESOLVES_DOWN).single()

        val matches = SceneMatcher.enumerate(scene, key, ChordVocabulary.fromKey(key), windowLimit = 2)

        assertTrue(
            matches.any { match ->
                match.slots.map { it.degree } == listOf(5, 1) &&
                    match.slots.map { it.arity } == listOf(ChordArity.SEVENTH, ChordArity.TRIAD) &&
                    match.slots.all { it.seventhPosition == TextbookSeventhPosition.ROOT_POSITION }
            },
            "expected V7-I symbolic resolution among ${matches.map { m -> m.slots.map { Triple(it.degree, it.arity, it.positionName) } }}",
        )
    }

    @Test
    fun voiceMotionRequiresFromToneInFirstChordAndToToneInSecond() {
        val key = Key.major(PitchClass.C)
        val vocabulary = ChordVocabulary.fromKey(key, setOf(TextbookTriadPosition.ROOT_POSITION))

        // 1 级(C)在 I 和弦音、5 级(G)在 V 和弦音 → I→V 命中。
        val holds = RuleScene(
            ruleId = RuleId("test.voice-motion.holds"),
            window = 2..2,
            facets = listOf(
                ChordPattern(listOf(SlotChordSpec(degrees = setOf(1)), SlotChordSpec(degrees = setOf(5)))),
                VoiceMotion(ScaleDegreeSpec(1), ScaleDegreeSpec(5)),
            ),
        )
        assertTrue(SceneMatcher.enumerate(holds, key, vocabulary, windowLimit = 2).isNotEmpty())

        // 2 级(D)不在 I 和弦音 → 必要条件不成立，无命中。
        val fails = RuleScene(
            ruleId = RuleId("test.voice-motion.fails"),
            window = 2..2,
            facets = listOf(
                ChordPattern(listOf(SlotChordSpec(degrees = setOf(1)), SlotChordSpec(degrees = setOf(5)))),
                VoiceMotion(ScaleDegreeSpec(2), ScaleDegreeSpec(5)),
            ),
        )
        assertTrue(SceneMatcher.enumerate(fails, key, vocabulary, windowLimit = 2).isEmpty())
    }
}
