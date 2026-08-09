package com.mecon.theory.textbook

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.AdjacentVoiceUnisonRule
import com.mecon.theory.FixedVoiceScore
import com.mecon.theory.FixedVoiceScoreEvent
import com.mecon.theory.FixedVoiceTransition
import com.mecon.theory.FixedVoiceVerticality
import com.mecon.theory.RuleDiagnostic
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity
import com.mecon.theory.SpelledInterval
import com.mecon.theory.SpelledIntervalBase
import com.mecon.theory.VoiceLeadingAnalysis
import com.mecon.theory.VoiceMotionDirection
import com.mecon.theory.VoicePairMotion
import com.mecon.theory.VoicePairMotionKind
import com.mecon.theory.VoiceRange
import com.mecon.theory.VoiceRangeProfile
import com.mecon.theory.constraint.LayeredDpRuleStateDeclaration
import kotlin.math.abs

object FourPartTextbookRules {
    val OUTER_VOICE_CROSSING = RuleId("textbook.four-part.outer-voice-crossing")
    val UPPER_VOICE_SPACING = RuleId("textbook.four-part.upper-voice-spacing")
    val VOICE_RANGE = RuleId("textbook.four-part.voice-range")
    val PARALLEL_FIFTH = RuleId("textbook.four-part.parallel-fifth")
    val PARALLEL_OCTAVE = RuleId("textbook.four-part.parallel-octave")
    val UNEQUAL_FIFTH = RuleId("textbook.four-part.unequal-fifth")
    val HIDDEN_FIFTH = RuleId("textbook.four-part.hidden-fifth")
    val HIDDEN_OCTAVE = RuleId("textbook.four-part.hidden-octave")

    /** Every rule this object can emit; the DP declaration completeness guard reads it. */
    val ALL_RULE_IDS: Set<RuleId> = setOf(
        OUTER_VOICE_CROSSING,
        UPPER_VOICE_SPACING,
        VOICE_RANGE,
        PARALLEL_FIFTH,
        PARALLEL_OCTAVE,
        UNEQUAL_FIFTH,
        HIDDEN_FIFTH,
        HIDDEN_OCTAVE,
    )

    /**
     * DP 状态声明。纵向规则（交错 / 间距 / 音域）只读当前 verticality；其余全部只读
     * [FixedVoiceTransition.pairMotions]，即前后两帧，因此完成本层后只需保留最近一帧。
     * 没有任何规则需要两帧或完整路径——这一面比 FREE_CLASSICAL（连续跳进要两帧）更简单。
     */
    internal fun dpStateDeclarations(): List<LayeredDpRuleStateDeclaration> = listOf(
        LayeredDpRuleStateDeclaration(OUTER_VOICE_CROSSING),
        LayeredDpRuleStateDeclaration(UPPER_VOICE_SPACING),
        LayeredDpRuleStateDeclaration(VOICE_RANGE),
        LayeredDpRuleStateDeclaration(PARALLEL_FIFTH, recentFrames = 1),
        LayeredDpRuleStateDeclaration(PARALLEL_OCTAVE, recentFrames = 1),
        LayeredDpRuleStateDeclaration(UNEQUAL_FIFTH, recentFrames = 1),
        LayeredDpRuleStateDeclaration(HIDDEN_FIFTH, recentFrames = 1),
        LayeredDpRuleStateDeclaration(HIDDEN_OCTAVE, recentFrames = 1),
    )

    fun checkFixedVoiceScore(
        score: FixedVoiceScore,
        rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    ): List<RuleDiagnostic<EventId>> =
        checkFixedVoiceScoreFindings(score, rangeProfile).map { it.toDiagnostic() }

    fun checkFixedVoiceScoreFindings(
        score: FixedVoiceScore,
        rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    ): List<RuleFinding<EventId>> =
        buildList {
            val verticalities = VoiceLeadingAnalysis.verticalities(score)
            val motions = VoiceLeadingAnalysis.transitions(score).flatMap { it.pairMotions }
            addAll(verticalities.flatMap(AdjacentVoiceUnisonRule::check))
            addAll(checkOuterVoiceCrossing(verticalities))
            addAll(checkUpperVoiceSpacing(verticalities))
            addAll(checkRanges(score.eventsByVoice.values.flatten(), rangeProfile))
            addAll(checkParallelPerfectConsonances(motions))
            addAll(checkUnequalFifths(motions))
            addAll(checkHiddenPerfectConsonances(motions))
        }.distinctBy { finding ->
            finding.ruleId to finding.anchors
        }

    fun checkFixedVoiceTransition(
        transition: FixedVoiceTransition,
        rangeProfile: VoiceRangeProfile = VoiceRangeProfile.humanFourPart(),
    ): List<RuleFinding<EventId>> =
        buildList {
            addAll(AdjacentVoiceUnisonRule.check(transition.current))
            addAll(checkOuterVoiceCrossing(listOf(transition.current)))
            addAll(checkUpperVoiceSpacing(listOf(transition.current)))
            addAll(checkRanges(transition.current.notes, rangeProfile))
            addAll(checkParallelPerfectConsonances(transition.pairMotions))
            addAll(checkUnequalFifths(transition.pairMotions))
            addAll(checkHiddenPerfectConsonances(transition.pairMotions))
        }.distinctBy { finding ->
            finding.ruleId to finding.anchors
        }

    private fun checkOuterVoiceCrossing(
        verticalities: List<FixedVoiceVerticality>,
    ): List<RuleFinding<EventId>> =
        verticalities.flatMap { verticality ->
            VoiceLeadingAnalysis.outerBoundaryCrossings(verticality).map { crossing ->
                RuleFinding(
                    ruleId = OUTER_VOICE_CROSSING,
                    kind = RuleFindingKind.VIOLATION,
                    severity = RuleSeverity.HARD,
                    message = "禁止任何声部交错至高音声部上方，或低音声部下方。",
                    anchors = anchorsOf(crossing.upper, crossing.lower),
                )
            }
        }

    private fun checkUpperVoiceSpacing(
        verticalities: List<FixedVoiceVerticality>,
    ): List<RuleFinding<EventId>> =
        verticalities.flatMap { verticality ->
            VoiceLeadingAnalysis.nonLowAdjacentSpacingViolations(verticality).map { spacing ->
                RuleFinding(
                    ruleId = UPPER_VOICE_SPACING,
                    kind = RuleFindingKind.VIOLATION,
                    severity = RuleSeverity.HARD,
                    message = "除低音声部外，相邻声部间距离应保持在八度以内。",
                    anchors = anchorsOf(spacing.upper, spacing.lower),
                )
            }
        }

    private fun checkParallelPerfectConsonances(
        motions: List<VoicePairMotion>,
    ): List<RuleFinding<EventId>> =
        motions.flatMap { motion ->
            val octaveDisplacedParallel = motion.isOctaveDisplacedParallelMotion()
            if (motion.kind != VoicePairMotionKind.PARALLEL && !octaveDisplacedParallel) {
                return@flatMap emptyList()
            }
            buildList {
                if (
                    motion.beforeInterval.isPerfectFifth() && motion.afterInterval.isPerfectFifth() ||
                    octaveDisplacedParallel && motion.isPerfectFifthClassBeforeAndAfter()
                ) {
                    add(
                        RuleFinding(
                            ruleId = PARALLEL_FIFTH,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.HARD,
                            message = "两声部相距纯五度时不可作平行进行。",
                            anchors = anchorsOf(motion),
                        )
                    )
                }
                if (
                    motion.beforeInterval.isPerfectOctaveFamily() && motion.afterInterval.isPerfectOctaveFamily() ||
                    octaveDisplacedParallel && motion.isPerfectOctaveClassBeforeAndAfter()
                ) {
                    add(
                        RuleFinding(
                            ruleId = PARALLEL_OCTAVE,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.HARD,
                            message = "两声部相距纯同度或纯八度时不可作平行进行。",
                            anchors = anchorsOf(motion),
                        )
                    )
                }
            }
        }

    private fun checkUnequalFifths(
        motions: List<VoicePairMotion>,
    ): List<RuleFinding<EventId>> =
        motions.mapNotNull { motion ->
            if (!motion.isSameDirectionMotion() || !motion.containsLowVoice()) return@mapNotNull null
            if (!motion.beforeInterval.isDiminishedFifth() || !motion.afterInterval.isPerfectFifth()) {
                return@mapNotNull null
            }
            RuleFinding(
                ruleId = UNEQUAL_FIFTH,
                kind = RuleFindingKind.VIOLATION,
                severity = RuleSeverity.SOFT,
                message = "避免低声部与其他声部由减五度同向进行到纯五度。",
                anchors = anchorsOf(motion),
            )
        }

    private fun checkHiddenPerfectConsonances(
        motions: List<VoicePairMotion>,
    ): List<RuleFinding<EventId>> =
        motions.flatMap { motion ->
            if (!motion.isSameDirectionMotion() || !motion.isOuterVoicePair() || !motion.sopranoLeaps()) {
                return@flatMap emptyList()
            }
            buildList {
                if (!motion.beforeInterval.isPerfectFifth() && motion.afterInterval.isPerfectFifth()) {
                    add(
                        RuleFinding(
                            ruleId = HIDDEN_FIFTH,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "外声部同向进入纯五度且高声部跳进，构成隐伏五度。",
                            anchors = anchorsOf(motion),
                        )
                    )
                }
                if (
                    !motion.beforeInterval.isPerfectOctaveFamily() &&
                    motion.afterInterval.isPerfectOctaveFamily()
                ) {
                    add(
                        RuleFinding(
                            ruleId = HIDDEN_OCTAVE,
                            kind = RuleFindingKind.VIOLATION,
                            severity = RuleSeverity.SOFT,
                            message = "外声部同向进入纯同度或纯八度且高声部跳进，构成隐伏八度。",
                            anchors = anchorsOf(motion),
                        )
                    )
                }
            }
        }

    private fun checkRanges(
        events: Iterable<FixedVoiceScoreEvent>,
        rangeProfile: VoiceRangeProfile,
    ): List<RuleFinding<EventId>> =
        events.filterNot { it.isRest }.mapNotNull { event ->
            val range = rangeProfile.rangeFor(event.voice.role) ?: return@mapNotNull null
            val pitch = event.requiredPitch()
            if (pitch in range) {
                null
            } else {
                RuleFinding(
                    ruleId = VOICE_RANGE,
                    kind = RuleFindingKind.VIOLATION,
                    severity = RuleSeverity.HARD,
                    message = "声部音域超出当前写作配置：${formatRange(range)}。",
                    anchors = anchorsOf(event),
                )
            }
        }

    private fun anchorsOf(vararg events: FixedVoiceScoreEvent): List<EventId> =
        events.map { it.id }

    private fun anchorsOf(motion: VoicePairMotion): List<EventId> =
        anchorsOf(motion.firstBefore, motion.secondBefore, motion.firstAfter, motion.secondAfter).distinct()

    private fun FixedVoiceScoreEvent.requiredPitch(): Pitch =
        pitch ?: error("Expected note event ${id} to have a pitch")

    private fun formatRange(range: VoiceRange): String =
        "${range.lowest.format()}-${range.highest.format()}"

    private fun VoicePairMotion.isSameDirectionMotion(): Boolean =
        firstDirection == secondDirection && firstDirection != VoiceMotionDirection.STATIONARY

    private fun VoicePairMotion.isOctaveDisplacedParallelMotion(): Boolean {
        if (firstDirection == VoiceMotionDirection.STATIONARY || secondDirection == VoiceMotionDirection.STATIONARY) {
            return false
        }
        if (kind == VoicePairMotionKind.PARALLEL) return false
        val first = simpleMotion(firstBefore.requiredPitch(), firstAfter.requiredPitch())
        val second = simpleMotion(secondBefore.requiredPitch(), secondAfter.requiredPitch())
        return first == second && first != SimpleVoiceMotion(0, 0)
    }

    private fun VoicePairMotion.containsLowVoice(): Boolean =
        firstBefore.voice.role.isLowVoiceRole() || secondBefore.voice.role.isLowVoiceRole()

    private fun VoicePairMotion.isOuterVoicePair(): Boolean =
        listOf(firstBefore.voice.role, secondBefore.voice.role).let { roles ->
            FixedVoiceRole.SOPRANO in roles && roles.any { it.isLowVoiceRole() }
        }

    private fun VoicePairMotion.sopranoLeaps(): Boolean {
        val before = when (FixedVoiceRole.SOPRANO) {
            firstBefore.voice.role -> firstBefore
            secondBefore.voice.role -> secondBefore
            else -> return false
        }
        val after = if (before.voice.id == firstAfter.voice.id) firstAfter else secondAfter
        return abs(after.requiredPitch().diatonicSteps - before.requiredPitch().diatonicSteps) > 1
    }

    private fun FixedVoiceRole?.isLowVoiceRole(): Boolean =
        this == FixedVoiceRole.BASS || this == FixedVoiceRole.BARITONE

    private fun SpelledInterval.isPerfectFifth(): Boolean =
        simpleNumber == 5 && base == SpelledIntervalBase.PERFECT && offset == 0

    private fun SpelledInterval.isDiminishedFifth(): Boolean =
        simpleNumber == 5 && base == SpelledIntervalBase.PERFECT && offset == -1

    private fun SpelledInterval.isPerfectOctaveFamily(): Boolean =
        simpleNumber == 1 && base == SpelledIntervalBase.PERFECT && offset == 0

    private fun VoicePairMotion.isPerfectFifthClassBeforeAndAfter(): Boolean =
        isPerfectFifthClass(firstBefore.requiredPitch(), secondBefore.requiredPitch()) &&
            isPerfectFifthClass(firstAfter.requiredPitch(), secondAfter.requiredPitch())

    private fun VoicePairMotion.isPerfectOctaveClassBeforeAndAfter(): Boolean =
        firstBefore.requiredPitch().pitchClass == secondBefore.requiredPitch().pitchClass &&
            firstAfter.requiredPitch().pitchClass == secondAfter.requiredPitch().pitchClass

    private fun isPerfectFifthClass(first: Pitch, second: Pitch): Boolean =
        abs(first.midiNumber - second.midiNumber).mod(OCTAVE_SEMITONES).let { it == 5 || it == 7 }

    private data class SimpleVoiceMotion(
        val diatonicSteps: Int,
        val semitones: Int,
    )

    private fun simpleMotion(from: Pitch, to: Pitch): SimpleVoiceMotion =
        SimpleVoiceMotion(
            diatonicSteps = normalizeModCentered(to.diatonicSteps - from.diatonicSteps, DIATONIC_STEPS_PER_OCTAVE),
            semitones = normalizeModCentered(to.midiNumber - from.midiNumber, OCTAVE_SEMITONES),
        )

    private fun normalizeModCentered(value: Int, modulus: Int): Int {
        val normalized = value.mod(modulus)
        val half = modulus / 2
        return if (normalized > half) normalized - modulus else normalized
    }

    private const val DIATONIC_STEPS_PER_OCTAVE = 7
    private const val OCTAVE_SEMITONES = 12
}
