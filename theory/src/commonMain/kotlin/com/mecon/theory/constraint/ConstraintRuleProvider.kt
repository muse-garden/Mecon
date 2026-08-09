package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScoreRuleContext
import com.mecon.theory.FixedVoiceTransitionRuleContext
import com.mecon.theory.FixedVoiceVerticalRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingRuleProvider
import com.mecon.theory.FixedVoiceWritingScorePolicy
import com.mecon.theory.Key
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleScoreIntent
import com.mecon.theory.RuleSeverity
import com.mecon.theory.solverVoiceEventId
import com.mecon.theory.solverPitchEventId
import kotlin.math.abs

/**
 * M6 单一 finding 桥。基础 textbook/module provider 先产出 Kotlin 逃生舱 finding，本 provider 再对
 * Constraint expr 求值；九类 requirement 不再各自注册 provider。
 */
internal class ConstraintCompositeRuleProvider(
    private val delegates: List<FixedVoiceWritingRuleProvider<ChordTarget>>,
    program: ConstraintProgram,
    voices: List<FixedVoice>,
) : FixedVoiceWritingRuleProvider<ChordTarget> {
    private val algebra = ConstraintAlgebraRuleProvider(program, voices)
    private val constraintOwnedRuleIds = program.constraints
        .filter { constraint -> constraint.expr.atomicPredicates().any { it !is ConstraintPredicate.RuleFound } }
        .mapNotNull { it.ruleId }
        .toSet()

    override fun checkVertical(context: FixedVoiceVerticalRuleContext<ChordTarget>): List<RuleFinding<EventId>> {
        val observed = algebra.adjustDemonstratedViolations(
            delegates.flatMap { it.checkVertical(context) }
                .filterNot { it.ruleId in constraintOwnedRuleIds },
            listOf(context.frame),
        )
        return observed + algebra.checkVertical(context)
    }

    override fun checkTransition(context: FixedVoiceTransitionRuleContext<ChordTarget>): List<RuleFinding<EventId>> {
        val observed = algebra.adjustDemonstratedViolations(
            delegates.flatMap { it.checkTransition(context) }
                .filterNot { it.ruleId in constraintOwnedRuleIds },
            listOf(context.previousFrame, context.currentFrame),
        )
        return observed + algebra.checkTransition(context)
    }

    override fun checkScore(context: FixedVoiceScoreRuleContext<ChordTarget>): List<RuleFinding<EventId>> {
        val observed = delegates.flatMap { it.checkScore(context) }
            .filterNot { it.ruleId in constraintOwnedRuleIds }
        return observed + algebra.checkScore(context, context.state.localFindings + observed)
    }
}

internal class ConstraintAlgebraRuleProvider(
    private val program: ConstraintProgram,
    private val voices: List<FixedVoice>,
) {
    private val targetOnlyEvaluationCache =
        mutableMapOf<TargetOnlyEvaluationCacheKey, ConstraintEvaluation>()

    // 约束的表达式结构在程序编译后就固定了，但 atomicPredicates() 是协程 Sequence：逐帧重算
    // 分区会主导每条转移的成本。这里按“前缀是否完整”预先分好两套分区。
    private val atomConstraints = program.constraints.filter { it.expr is ConstraintExpr.Atom }
    private val completeScorePartition = partitionScoreConstraints(complete = true)
    private val incompleteScorePartition = partitionScoreConstraints(complete = false)

    private fun partitionScoreConstraints(complete: Boolean): ScoreConstraintPartition {
        val scoreConstraints = program.constraints.filter { constraint ->
            constraint.expr !is ConstraintExpr.Atom ||
                (complete && constraint.expr.atomicPredicates().any { it.requiresGlobalEvaluation() })
        }
        val (dependent, direct) = scoreConstraints.partition { constraint ->
            constraint.expr.atomicPredicates().any { it is ConstraintPredicate.RuleFound }
        }
        val (targetOnlyDirect, voiceDependentDirect) = direct.partition { constraint ->
            constraint.expr.atomicPredicates().all { it.isTargetOnly() }
        }
        return ScoreConstraintPartition(
            targetOnlyDirect = targetOnlyDirect,
            voiceDependentDirect = voiceDependentDirect,
            dependent = dependent,
        )
    }

    private class ScoreConstraintPartition(
        val targetOnlyDirect: List<Constraint>,
        val voiceDependentDirect: List<Constraint>,
        val dependent: List<Constraint>,
    )
    private val demonstratedViolationRuleIds = program.constraints
        .filter { it.modality == ConstraintModality.Require }
        .flatMap { it.expr.atomicPredicates().toList() }
        .filterIsInstance<ConstraintPredicate.RuleFound>()
        .filter { it.kind == RuleFindingKind.VIOLATION }
        .map { it.ruleId }
        .toSet()
    // 只取决于程序本身，逐帧重算会在每条转移上重复扫描全部约束。
    private val demonstrations: List<Pair<Constraint, ConstraintPredicate.RuleFound>> =
        program.constraints.mapNotNull { constraint ->
            if (constraint.modality != ConstraintModality.Require) return@mapNotNull null
            val atom = constraint.expr as? ConstraintExpr.Atom ?: return@mapNotNull null
            val predicate = (atom.predicate as? ConstraintPredicate.RuleFound)
                ?.takeIf { it.kind == RuleFindingKind.VIOLATION }
                ?: return@mapNotNull null
            constraint to predicate
        }

    fun adjustDemonstratedViolations(
        findings: List<RuleFinding<EventId>>,
        frames: List<FixedVoiceWritingFrame<ChordTarget>>,
    ): List<RuleFinding<EventId>> {
        if (demonstrations.isEmpty()) return findings
        return findings.map { finding ->
            val demonstrated = finding.kind == RuleFindingKind.VIOLATION && demonstrations.any { (constraint, predicate) ->
                predicate.ruleId == finding.ruleId &&
                    frames.any { frame ->
                        constraint.scope.matches(frame.slotIndex, frame.target) &&
                            (predicate.window == null || predicate.window.contains(frame.slotIndex))
                    }
            }
            if (demonstrated) finding.copy(severity = RuleSeverity.HINT, message = "演示目标：${finding.message}")
            else finding
        }
    }

    fun checkVertical(context: FixedVoiceVerticalRuleContext<ChordTarget>): List<RuleFinding<EventId>> =
        atomConstraints.mapNotNull { constraint ->
            if (!constraint.scope.matches(context.frame.slotIndex, context.frame.target)) return@mapNotNull null
            constraint.emit(constraint.expr.evaluate { it.evaluateVertical(context) })
        }

    fun checkTransition(context: FixedVoiceTransitionRuleContext<ChordTarget>): List<RuleFinding<EventId>> =
        atomConstraints.mapNotNull { constraint ->
            if (!constraint.scope.matches(context.currentFrame.slotIndex, context.currentFrame.target)) return@mapNotNull null
            constraint.emit(constraint.expr.evaluate { it.evaluateTransition(context) })
        }

    fun checkScore(
        context: FixedVoiceScoreRuleContext<ChordTarget>,
        observed: List<RuleFinding<EventId>>,
    ): List<RuleFinding<EventId>> {
        val complete = context.state.frames.size >= program.length
        val partition = if (complete) completeScorePartition else incompleteScorePartition
        // 目标序列本身就是缓存身份，不必先拼成字符串。
        val targetPrefix = context.state.frames.map { it.target }
        val targetOnlyFindings = partition.targetOnlyDirect.mapNotNull { constraint ->
            val key = TargetOnlyEvaluationCacheKey(constraint, targetPrefix)
            val evaluation = targetOnlyEvaluationCache.getOrPut(key) {
                constraint.expr.evaluate { it.evaluateGlobal(context, observed, constraint.scope) }
            }
            constraint.emit(evaluation)
        }
        val voiceDependentFindings = partition.voiceDependentDirect.mapNotNull { constraint ->
            constraint.emit(constraint.expr.evaluate { it.evaluateGlobal(context, observed, constraint.scope) })
        }
        val directFindings = targetOnlyFindings + voiceDependentFindings
        val visibleFindings = observed + directFindings
        val dependentFindings = partition.dependent.mapNotNull { constraint ->
            constraint.emit(constraint.expr.evaluate { it.evaluateGlobal(context, visibleFindings, constraint.scope) })
        }
        return directFindings + dependentFindings
    }

    private data class TargetOnlyEvaluationCacheKey(
        val constraint: Constraint,
        val targetPrefix: List<ChordTarget>,
    )

    private fun ConstraintPredicate.evaluateGlobal(
        context: FixedVoiceScoreRuleContext<ChordTarget>,
        observed: List<RuleFinding<EventId>>,
        scope: ConstraintScope,
    ): ConstraintEvaluation {
        val frames = context.state.frames
        val isComplete = frames.size >= program.length
        fun inScope(frame: FixedVoiceWritingFrame<ChordTarget>) = scope.matches(frame.slotIndex, frame.target)
        fun anchors(frame: FixedVoiceWritingFrame<ChordTarget>) =
            voices.map { solverVoiceEventId(frame.slotIndex, it.id) }
        val result = when (this) {
            is ConstraintPredicate.ToneCompleteness -> {
                val applicable = frames.filter {
                    inScope(it) && requirement.window.contains(it.slotIndex) && requirement.selector.matches(it.target)
                }
                if (applicable.isEmpty()) if (isComplete) inactive() else ConstraintEvaluation(ConstraintTruth.UNDETERMINED) else {
                    val failed = applicable.firstOrNull { frame ->
                        !requirement.isSatisfiedBy(frame.target, voices.map { frame.pitchFor(it).pitchClass })
                    }
                    verdict(failed == null, anchors(failed ?: applicable.first()))
                }
            }
            is ConstraintPredicate.ToneDoubled -> {
                val frame = frames.getOrNull(requirement.slot) ?: return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                if (!inScope(frame) || !requirement.selector.matches(frame.target)) inactive() else {
                    val tone = frame.target.pitchClassFor(requirement.tone) ?: return satisfied(anchors(frame))
                    verdict(voices.count { frame.pitchFor(it).pitchClass == tone } >= 2, anchors(frame))
                }
            }
            is ConstraintPredicate.ToneNotDoubled -> {
                val frame = frames.getOrNull(requirement.slot) ?: return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                if (!inScope(frame) || !requirement.selector.matches(frame.target)) inactive() else {
                    val tone = frame.target.pitchClassFor(requirement.tone) ?: return satisfied(anchors(frame))
                    val doubled = voices.filter { frame.pitchFor(it).pitchClass == tone }
                    verdict(doubled.size <= 1, doubled.map { solverVoiceEventId(frame.slotIndex, it.id) })
                }
            }
            is ConstraintPredicate.ScaleDegreeNotDoubled -> {
                val frame = frames.getOrNull(requirement.slot) ?: return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                if (!inScope(frame) || !requirement.selector.matches(frame.target)) inactive() else {
                    val doubled = requirement.pitchClasses(program.key)
                    val matching = voices.filter { frame.pitchFor(it).pitchClass in doubled }
                    verdict(matching.size <= 1, matching.map { solverVoiceEventId(frame.slotIndex, it.id) })
                }
            }
            is ConstraintPredicate.Spacing -> {
                val applicable = frames.filter { inScope(it) && requirement.window.contains(it.slotIndex) }
                if (requirement.preference == SpacingPreference.ANY) inactive()
                else if (applicable.isEmpty()) if (isComplete) inactive() else ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                else {
                    val failed = applicable.firstOrNull { frame ->
                        val isOpen = abs(frame.pitchForRole(FixedVoiceRole.SOPRANO).midiNumber -
                            frame.pitchForRole(FixedVoiceRole.TENOR).midiNumber) > OCTAVE_SEMITONES
                        when (requirement.preference) {
                            SpacingPreference.OPEN -> !isOpen
                            SpacingPreference.CLOSE -> isOpen
                            SpacingPreference.ANY -> false
                        }
                    }
                    verdict(failed == null, anchors(failed ?: applicable.first()))
                }
            }
            is ConstraintPredicate.DistinctIdentities -> {
                val applicable = frames.filter { inScope(it) && requirement.window.contains(it.slotIndex) }
                if (applicable.isEmpty()) if (isComplete) inactive() else ConstraintEvaluation(ConstraintTruth.UNDETERMINED) else {
                    val duplicate = applicable
                        .groupBy { it.target.identityKey(requirement.identityMode) }
                        .values
                        .firstOrNull { it.size > 1 }
                    verdict(duplicate == null, duplicate.orEmpty().flatMap(::anchors))
                }
            }
            is ConstraintPredicate.CommonToneWithPrevious -> {
                val pairs = frames.zipWithNext().filter {
                    inScope(it.second) && requirement.window.contains(it.first.slotIndex) &&
                        requirement.window.contains(it.second.slotIndex) &&
                        it.first.target.identityKey() !in requirement.exemptIdentityKeys &&
                        it.second.target.identityKey() !in requirement.exemptIdentityKeys
                }
                if (pairs.isEmpty()) if (isComplete) inactive() else ConstraintEvaluation(ConstraintTruth.UNDETERMINED) else {
                    val failed = pairs.firstOrNull { (before, current) ->
                        val common = before.target.sonority.pitchClasses.toSet() intersect current.target.sonority.pitchClasses.toSet()
                        val held = voices.any { voice ->
                            before.pitchFor(voice).pitchClass in common &&
                                before.pitchFor(voice).midiNumber == current.pitchFor(voice).midiNumber
                        }
                        common.isEmpty() || (requirement.holdInSameVoice && !held)
                    }
                    verdict(failed == null, failed?.let { anchors(it.second) } ?: anchors(pairs.first().second))
                }
            }
            is ConstraintPredicate.NeighborTone -> {
                val sources = frames.filter { frame ->
                    requirement.window.contains(frame.slotIndex) &&
                        inScope(frame) &&
                        (requirement.sourceSlot == null || frame.slotIndex == requirement.sourceSlot) &&
                        requirement.sourceSelector.matches(frame.target)
                }
                if (sources.isEmpty()) if (isComplete) inactive() else ConstraintEvaluation(ConstraintTruth.UNDETERMINED) else {
                    val waitsForFutureNeighbor = !isComplete && sources.any { source ->
                        val neighborIndex = source.slotIndex +
                            if (requirement.direction == ChordToneNeighborDirection.NEXT) 1 else -1
                        neighborIndex >= frames.size && neighborIndex < program.length
                    }
                    if (waitsForFutureNeighbor) return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                    val results = sources.map { source ->
                        val neighborIndex = source.slotIndex + if (requirement.direction == ChordToneNeighborDirection.NEXT) 1 else -1
                        val neighbor = frames.getOrNull(neighborIndex)
                        if (neighbor == null || !requirement.window.contains(neighborIndex)) {
                            source to NeighborResult(false, emptyList())
                        } else {
                            source to requirement.checkRelation(
                                requirement.tonalKey ?: program.key,
                                source,
                                neighbor,
                            )
                        }
                    }
                    val failed = results.firstOrNull { !it.second.ok }
                    val chosen = failed ?: results.first()
                    val source = chosen.first
                    val relationAnchors = chosen.second.sourceVoices.map { solverVoiceEventId(source.slotIndex, it.id) }
                    verdict(failed == null, relationAnchors.ifEmpty { anchors(source) })
                }
            }
            is ConstraintPredicate.TargetMatches -> {
                val applicable = frames.filter { inScope(it) && requirement.window.contains(it.slotIndex) }
                if (applicable.isEmpty()) if (isComplete) inactive() else ConstraintEvaluation(ConstraintTruth.UNDETERMINED) else {
                    val matched = applicable.firstOrNull { requirement.selector.matches(it.target) }
                    val futureCanMatch = !isComplete &&
                        (frames.size until program.length).any { slot ->
                            requirement.window.contains(slot) &&
                                program.slotDomains[slot].targets.any { target ->
                                    scope.matches(slot, target) && requirement.selector.matches(target)
                                }
                        }
                    if (matched == null && futureCanMatch) {
                        ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                    } else {
                        verdict(matched != null, anchors(matched ?: applicable.first()))
                    }
                }
            }
            is ConstraintPredicate.SameSonority -> {
                val selected = slots.map { frames.getOrNull(it) }
                if (selected.any { it == null }) return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                val concrete = selected.filterNotNull()
                if (concrete.none(::inScope)) inactive() else {
                    val identities = concrete.map { it.target.sonority.pitchClasses.toSet() }.toSet()
                    verdict(identities.size == 1, concrete.flatMap(::anchors))
                }
            }
            is ConstraintPredicate.VoiceDiatonicSteps -> {
                val selected = slots.map { frames.getOrNull(it) }
                if (selected.any { it == null }) return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                val concrete = selected.filterNotNull()
                if (concrete.none(::inScope)) inactive() else {
                    val selectedVoices = voices.filter { voiceFilter.allows(it) }
                    val ok = selectedVoices.isNotEmpty() && selectedVoices.all { voice ->
                        concrete.zipWithNext().map { (before, after) ->
                            after.pitchFor(voice).diatonicSteps - before.pitchFor(voice).diatonicSteps
                        }.zip(allowedDeltas).all { (delta, allowed) -> delta in allowed }
                    }
                    verdict(
                        ok,
                        concrete.flatMap { frame -> selectedVoices.map { solverVoiceEventId(frame.slotIndex, it.id) } },
                    )
                }
            }
            is ConstraintPredicate.VoicePitchClassCardinality -> {
                val selected = slots.map { frames.getOrNull(it) }
                if (selected.any { it == null }) return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                val concrete = selected.filterNotNull()
                if (concrete.none(::inScope)) inactive() else {
                    val selectedVoices = voices.filter { voiceFilter.allows(it) }
                    val ok = selectedVoices.isNotEmpty() && selectedVoices.all { voice ->
                        concrete.map { it.pitchFor(voice).pitchClass }.toSet().size in allowedCounts
                    }
                    verdict(
                        ok,
                        concrete.flatMap { frame -> selectedVoices.map { solverVoiceEventId(frame.slotIndex, it.id) } },
                    )
                }
            }
            is ConstraintPredicate.ToneMultiplicity -> {
                val frame = frames.getOrNull(slot) ?: return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                if (!inScope(frame)) inactive() else {
                    val pitchClass = frame.target.pitchClassFor(tone) ?: return inactive()
                    val matching = voices.filter { frame.pitchFor(it).pitchClass == pitchClass }
                    verdict(
                        matching.size in allowedCounts,
                        matching.map { solverVoiceEventId(frame.slotIndex, it.id) },
                    )
                }
            }
            is ConstraintPredicate.ToneInVoiceFilter -> {
                val frame = frames.getOrNull(slot) ?: return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                if (!inScope(frame)) inactive() else {
                    val pitchClass = frame.target.pitchClassFor(tone) ?: return inactive()
                    val matching = voices.filter { voiceFilter.allows(it) && frame.pitchFor(it).pitchClass == pitchClass }
                    verdict(
                        matching.isNotEmpty(),
                        matching.map { solverVoiceEventId(frame.slotIndex, it.id) },
                    )
                }
            }
            is ConstraintPredicate.RootDiatonicMotion -> {
                val from = frames.getOrNull(fromSlot) ?: return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                val to = frames.getOrNull(toSlot) ?: return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                if (!inScope(from) && !inScope(to)) inactive() else {
                    // 直接使用符号和弦的 1-based 音级。小调 vii° 等和弦可使用升高导音作为实际根音，
                    // 该 PitchClass 不在自然小调 scale.pitchClasses 中，不能靠 indexOf(root) 反推音级。
                    verdict(
                        (to.target.degree - from.target.degree).mod(program.key.scale.pitchClasses.size) in allowedDeltas,
                        anchors(from) + anchors(to),
                    )
                }
            }
            is ConstraintPredicate.UniqueVoiceExtreme -> {
                val selectedVoices = voices.filter { voiceFilter.allows(it) }
                if (selectedVoices.isEmpty()) inactive() else {
                    val violations = selectedVoices.mapNotNull { voice ->
                        val pitches = frames.map { frame -> frame to frame.pitchFor(voice) }
                        val extremeMidi = when (extreme) {
                            VoiceExtreme.HIGHEST -> pitches.maxOfOrNull { it.second.midiNumber }
                            VoiceExtreme.LOWEST -> pitches.minOfOrNull { it.second.midiNumber }
                        } ?: return@mapNotNull null
                        val occurrences = pitches.filter { it.second.midiNumber == extremeMidi }
                        if (occurrences.size <= maxOccurrences) return@mapNotNull null
                        val recoverable = !isComplete && futureCanSupersedeExtreme(
                            frameCount = frames.size,
                            voice = voice,
                            currentExtremeMidi = extremeMidi,
                            extreme = extreme,
                        )
                        occurrences.takeIf { !recoverable }?.let { voice to it }
                    }
                    val violationAnchors = violations.flatMap { (voice, occurrences) ->
                        occurrences.map { (frame) -> solverVoiceEventId(frame.slotIndex, voice.id) }
                    }
                    when {
                        violations.isNotEmpty() -> verdict(false, violationAnchors)
                        isComplete -> satisfied(emptyList())
                        else -> ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                    }
                }
            }
            is ConstraintPredicate.NoRepeatedVoicePattern -> {
                if (!isComplete) return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                val strongest = voices.filter { voiceFilter.allows(it) }
                    .mapNotNull { voice ->
                        strongestRepeatedPattern(
                            frames = frames,
                            voice = voice,
                            minPatternNotes = minPatternNotes,
                            maxPatternNotes = maxPatternNotes,
                        )
                    }
                    .maxByOrNull { it.strength }
                if (strongest == null) {
                    satisfied(emptyList())
                } else {
                    ConstraintEvaluation(
                        truth = ConstraintTruth.VIOLATED,
                        anchors = (
                            (strongest.firstStart until strongest.firstStart + strongest.noteCount) +
                                (strongest.secondStart until strongest.secondStart + strongest.noteCount)
                            )
                            .distinct()
                            .map { slot -> solverVoiceEventId(slot, strongest.voice.id) },
                        branchScoreDelta = strongest.strength * penaltyScale,
                    )
                }
            }
            is ConstraintPredicate.MinimumSimilarChordDistance -> {
                val applicable = frames.filter(::inScope)
                val recurrence = applicable.indices.firstNotNullOfOrNull { earlierIndex ->
                    val earlier = applicable[earlierIndex]
                    applicable.drop(earlierIndex + 1).firstOrNull { later ->
                        later.slotIndex - earlier.slotIndex < minimumSlotDistance &&
                            later.target.degree == earlier.target.degree
                    }?.let { later -> earlier to later }
                }
                verdict(
                    recurrence == null,
                    recurrence?.let { (earlier, later) -> anchors(earlier) + anchors(later) }.orEmpty(),
                )
            }
            ConstraintPredicate.DistinctSimilarChordProgressions -> {
                val transitions = frames.zipWithNext().filter { (before, after) ->
                    inScope(before) && inScope(after)
                }
                val duplicate = transitions
                    .groupBy { (before, after) ->
                        before.target.degree to after.target.degree
                    }
                    .values
                    .firstOrNull { it.size > 1 }
                verdict(
                    duplicate == null,
                    duplicate.orEmpty().flatMap { (before, after) -> anchors(before) + anchors(after) }.distinct(),
                )
            }
            is ConstraintPredicate.RootProgressionPreference -> {
                if (!isComplete) return ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                val applicable = frames.filter(::inScope)
                val rootDegrees = applicable.map { frame -> frame.target.degree }
                val score = scoringPolicy.score(rootDegrees)
                if (score.total <= 0.0) {
                    satisfied(applicable.flatMap(::anchors))
                } else {
                    ConstraintEvaluation(
                        truth = ConstraintTruth.VIOLATED,
                        anchors = applicable.flatMap(::anchors),
                        branchExplanation = ConstraintExplanation(
                            satisfied = "根音进行没有产生谨慎使用成本。",
                            violated = "根音进行偏好成本 ${score.total}：" +
                                "超越进行 ${score.superstrongMotionCount} 次，" +
                                "连续超越 ${score.consecutiveSuperstrongCount} 次，" +
                                "类似和弦距离成本 ${score.similarChordProximityPenalty}。",
                        ),
                        branchScoreDelta = score.total,
                    )
                }
            }
            is ConstraintPredicate.RuleFound -> {
                if (frames.none(::inScope)) {
                    if (isComplete) inactive() else ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
                } else {
                    val found = evaluateRuleFound(context, observed, scope)
                    if (!isComplete && found.truth == ConstraintTruth.VIOLATED) {
                        found.copy(truth = ConstraintTruth.UNDETERMINED)
                    } else {
                        found
                    }
                }
            }
        }
        return if (!isComplete && result.active && result.truth == ConstraintTruth.SATISFIED) {
            result.copy(truth = ConstraintTruth.UNDETERMINED)
        } else {
            result
        }
    }

    /**
     * 唯一极值已经重复时，只有未来槽仍可能写出更外侧音高才有机会恢复合法；否则可在完整候选前硬剪枝。
     * 这里只用目标音集与声部音域计算乐观上界，不施加排列/进行规则，因此不会误删仍可能恢复的分支。
     */
    private fun futureCanSupersedeExtreme(
        frameCount: Int,
        voice: FixedVoice,
        currentExtremeMidi: Int,
        extreme: VoiceExtreme,
    ): Boolean {
        val range = program.rangeFor(voice) ?: return true
        return program.slotDomains.drop(frameCount).any { domain ->
            domain.targets.any { target ->
                val pitchClasses = if (voice.role == FixedVoiceRole.BASS) {
                    setOf(target.bassPitchClass)
                } else {
                    target.sonority.pitchClasses.toSet()
                }
                val pitchClassValues = pitchClasses.mapTo(HashSet()) { it.value }
                when (extreme) {
                    VoiceExtreme.HIGHEST ->
                        (currentExtremeMidi + 1..range.highest.midiNumber)
                            .any { midi -> midi.mod(OCTAVE_SEMITONES) in pitchClassValues }
                    VoiceExtreme.LOWEST ->
                        (range.lowest.midiNumber until currentExtremeMidi)
                            .any { midi -> midi.mod(OCTAVE_SEMITONES) in pitchClassValues }
                }
            }
        }
    }

    private fun ConstraintPredicate.evaluateVertical(
        context: FixedVoiceVerticalRuleContext<ChordTarget>,
    ): ConstraintEvaluation {
        val slot = context.frame.slotIndex
        val target = context.frame.target
        val allAnchors = voices.map { solverVoiceEventId(slot, it.id) }
        val pitchClasses = voices.map { context.frame.pitchFor(it).pitchClass }
        return when (this) {
            is ConstraintPredicate.ToneCompleteness -> {
                if (!requirement.window.contains(slot) || !requirement.selector.matches(target)) inactive()
                else verdict(requirement.isSatisfiedBy(target, pitchClasses), allAnchors)
            }
            is ConstraintPredicate.ToneDoubled -> {
                if (requirement.slot != slot || !requirement.selector.matches(target)) inactive()
                else {
                    val tone = target.pitchClassFor(requirement.tone) ?: return satisfied(allAnchors)
                    verdict(pitchClasses.count { it == tone } >= 2, allAnchors)
                }
            }
            is ConstraintPredicate.ToneNotDoubled -> {
                if (requirement.slot != slot || !requirement.selector.matches(target)) inactive()
                else {
                    val tone = target.pitchClassFor(requirement.tone) ?: return satisfied(allAnchors)
                    val anchors = voices.filter { context.frame.pitchFor(it).pitchClass == tone }
                        .map { solverVoiceEventId(slot, it.id) }
                    verdict(pitchClasses.count { it == tone } <= 1, anchors)
                }
            }
            is ConstraintPredicate.ScaleDegreeNotDoubled -> {
                if (requirement.slot != slot || !requirement.selector.matches(target)) inactive()
                else {
                    val doubled = requirement.pitchClasses(program.key)
                    val anchors = voices.filter { context.frame.pitchFor(it).pitchClass in doubled }
                        .map { solverVoiceEventId(slot, it.id) }
                    verdict(requirement.isSatisfiedBy(program.key, pitchClasses), anchors)
                }
            }
            is ConstraintPredicate.Spacing -> {
                if (!requirement.window.contains(slot) || requirement.preference == SpacingPreference.ANY) inactive()
                else {
                    val soprano = context.frame.pitchForRole(FixedVoiceRole.SOPRANO).midiNumber
                    val tenor = context.frame.pitchForRole(FixedVoiceRole.TENOR).midiNumber
                    val isOpen = abs(soprano - tenor) > OCTAVE_SEMITONES
                    verdict(
                        when (requirement.preference) {
                            SpacingPreference.OPEN -> isOpen
                            SpacingPreference.CLOSE -> !isOpen
                            SpacingPreference.ANY -> true
                        },
                        allAnchors,
                    )
                }
            }
            is ConstraintPredicate.DistinctIdentities -> {
                if (!requirement.window.contains(slot)) inactive()
                else {
                    val duplicate = context.state.frames.firstOrNull {
                        it.slotIndex != slot && requirement.window.contains(it.slotIndex) &&
                            it.target.identityKey(requirement.identityMode) ==
                                target.identityKey(requirement.identityMode)
                    }
                    verdict(duplicate == null, allAnchors)
                }
            }
            is ConstraintPredicate.TargetMatches -> {
                // TargetMatches means “at least one target in the window matches”. A single
                // vertical frame cannot disprove that existential predicate; the prefix/score
                // evaluator below decides it once a match appears or no future slot can match.
                inactive()
            }
            is ConstraintPredicate.ToneMultiplicity -> {
                if (this.slot != slot) inactive() else {
                    val tonePitchClass = target.pitchClassFor(tone) ?: return inactive()
                    val matching = voices.filter { context.frame.pitchFor(it).pitchClass == tonePitchClass }
                    verdict(matching.size in allowedCounts, matching.map { solverVoiceEventId(slot, it.id) })
                }
            }
            is ConstraintPredicate.ToneInVoiceFilter -> {
                if (this.slot != slot) inactive() else {
                    val tonePitchClass = target.pitchClassFor(tone) ?: return inactive()
                    val matching = voices.filter { voiceFilter.allows(it) && context.frame.pitchFor(it).pitchClass == tonePitchClass }
                    verdict(matching.isNotEmpty(), matching.map { solverVoiceEventId(slot, it.id) })
                }
            }
            is ConstraintPredicate.CommonToneWithPrevious,
            is ConstraintPredicate.NeighborTone,
            is ConstraintPredicate.SameSonority,
            is ConstraintPredicate.VoiceDiatonicSteps,
            is ConstraintPredicate.VoicePitchClassCardinality,
            is ConstraintPredicate.RootDiatonicMotion,
            is ConstraintPredicate.UniqueVoiceExtreme,
            is ConstraintPredicate.NoRepeatedVoicePattern,
            is ConstraintPredicate.MinimumSimilarChordDistance,
            ConstraintPredicate.DistinctSimilarChordProgressions,
            is ConstraintPredicate.RootProgressionPreference,
            is ConstraintPredicate.RuleFound -> inactive()
        }
    }

    private fun ConstraintPredicate.evaluateTransition(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): ConstraintEvaluation {
        val before = context.previousFrame
        val current = context.currentFrame
        val allAnchors = voices.map { solverVoiceEventId(current.slotIndex, it.id) }
        return when (this) {
            is ConstraintPredicate.CommonToneWithPrevious -> {
                if (!requirement.window.contains(before.slotIndex) || !requirement.window.contains(current.slotIndex)) {
                    inactive()
                } else if (
                    before.target.identityKey() in requirement.exemptIdentityKeys ||
                    current.target.identityKey() in requirement.exemptIdentityKeys
                ) {
                    inactive()
                } else {
                    val common = before.target.sonority.pitchClasses.toSet() intersect current.target.sonority.pitchClasses.toSet()
                    val held = voices.any { voice ->
                        before.pitchFor(voice).pitchClass in common &&
                            before.pitchFor(voice).midiNumber == current.pitchFor(voice).midiNumber
                    }
                    verdict(common.isNotEmpty() && (!requirement.holdInSameVoice || held), allAnchors)
                }
            }
            is ConstraintPredicate.NeighborTone -> {
                if (!requirement.window.contains(before.slotIndex) || !requirement.window.contains(current.slotIndex)) {
                    inactive()
                } else {
                    val (source, neighbor) = when (requirement.direction) {
                        ChordToneNeighborDirection.PREVIOUS -> current to before
                        ChordToneNeighborDirection.NEXT -> before to current
                    }
                    if (requirement.sourceSlot != null && source.slotIndex != requirement.sourceSlot) return inactive()
                    if (!requirement.sourceSelector.matches(source.target)) return inactive()
                    val result = requirement.checkRelation(
                        requirement.tonalKey ?: program.key,
                        source,
                        neighbor,
                    )
                    val anchors = result.sourceVoices.flatMap { voice ->
                        listOf(solverVoiceEventId(source.slotIndex, voice.id), solverVoiceEventId(neighbor.slotIndex, voice.id))
                    }.ifEmpty { allAnchors }
                    verdict(result.ok, anchors)
                }
            }
            is ConstraintPredicate.VoiceDiatonicSteps -> {
                val transitionIndex = slots.zipWithNext().indexOfFirst { (from, to) ->
                    from == before.slotIndex && to == current.slotIndex
                }
                if (transitionIndex < 0) inactive() else {
                    val selectedVoices = voices.filter { voiceFilter.allows(it) }
                    val allowed = allowedDeltas[transitionIndex]
                    val ok = selectedVoices.isNotEmpty() && selectedVoices.all { voice ->
                        current.pitchFor(voice).diatonicSteps - before.pitchFor(voice).diatonicSteps in allowed
                    }
                    verdict(ok, selectedVoices.flatMap { voice -> listOf(
                        solverVoiceEventId(before.slotIndex, voice.id), solverVoiceEventId(current.slotIndex, voice.id)
                    ) })
                }
            }
            is ConstraintPredicate.ToneCompleteness,
            is ConstraintPredicate.ToneDoubled,
            is ConstraintPredicate.ToneNotDoubled,
            is ConstraintPredicate.ScaleDegreeNotDoubled,
            is ConstraintPredicate.Spacing,
            is ConstraintPredicate.DistinctIdentities,
            is ConstraintPredicate.TargetMatches,
            is ConstraintPredicate.SameSonority,
            is ConstraintPredicate.VoicePitchClassCardinality,
            is ConstraintPredicate.ToneMultiplicity,
            is ConstraintPredicate.ToneInVoiceFilter,
            is ConstraintPredicate.RootDiatonicMotion,
            is ConstraintPredicate.UniqueVoiceExtreme,
            is ConstraintPredicate.NoRepeatedVoicePattern,
            is ConstraintPredicate.MinimumSimilarChordDistance,
            ConstraintPredicate.DistinctSimilarChordProgressions,
            is ConstraintPredicate.RootProgressionPreference,
            is ConstraintPredicate.RuleFound -> inactive()
        }
    }

    private fun ConstraintPredicate.evaluateRuleFound(
        context: FixedVoiceScoreRuleContext<ChordTarget>,
        observed: List<RuleFinding<EventId>>,
        scope: ConstraintScope,
    ): ConstraintEvaluation = when (this) {
        is ConstraintPredicate.RuleFound -> {
            val effectiveStart = maxOf(window?.start ?: 0, scope.window?.start ?: 0)
            if ((window != null || scope.window != null) && context.state.frames.size <= effectiveStart) {
                ConstraintEvaluation(ConstraintTruth.UNDETERMINED)
            } else {
                val anchorFrames = buildMap {
                    context.state.frames.forEach { frame ->
                        voices.forEach { voice ->
                            put(solverVoiceEventId(frame.slotIndex, voice.id), frame)
                            put(solverPitchEventId(frame.slotIndex, voice.id), frame)
                        }
                    }
                }
                fun RuleFinding<EventId>.inWindow(): Boolean {
                    val frames = anchors.mapNotNull { anchorFrames[it] }
                    if (frames.isEmpty()) return true
                    return frames.any { frame ->
                        (window == null || window.contains(frame.slotIndex)) &&
                            scope.matches(frame.slotIndex, frame.target)
                    }
                }
                val match = observed.firstOrNull { finding ->
                    finding.ruleId == ruleId && (kind == null || finding.kind == kind) && finding.inWindow()
                }
                verdict(match != null, match?.anchors.orEmpty())
            }
        }
        else -> inactive()
    }

    private fun Constraint.emit(result: ConstraintEvaluation): RuleFinding<EventId>? {
        if (!result.active || result.truth == ConstraintTruth.UNDETERMINED) return null
        val shouldEmit = when (modality) {
            ConstraintModality.Require, is ConstraintModality.Prefer -> result.truth == ConstraintTruth.VIOLATED
            is ConstraintModality.Reward, ConstraintModality.Annotate -> result.truth == ConstraintTruth.SATISFIED
        }
        if (!shouldEmit) return null
        val effectiveRuleId = result.branchRuleId ?: ruleId ?: defaultConstraintRuleId(expr)
        val effectiveExplanation = result.branchExplanation ?: explanation ?: defaultExplanation(expr)
        val success = result.truth == ConstraintTruth.SATISFIED
        return RuleFinding(
            ruleId = effectiveRuleId,
            kind = if (success) RuleFindingKind.INDICATION else RuleFindingKind.VIOLATION,
            severity = when (modality) {
                ConstraintModality.Require -> if (effectiveRuleId in demonstratedViolationRuleIds) {
                    RuleSeverity.HINT
                } else {
                    RuleSeverity.HARD
                }
                is ConstraintModality.Prefer -> RuleSeverity.SOFT
                is ConstraintModality.Reward, ConstraintModality.Annotate -> RuleSeverity.HINT
            },
            message = if (success) effectiveExplanation.satisfied else effectiveExplanation.violated,
            anchors = result.anchors,
            scoreDelta = result.branchScoreDelta ?: when (val mode = modality) {
                is ConstraintModality.Prefer -> mode.weight ?: 0.0
                is ConstraintModality.Reward -> -mode.bonus
                else -> 0.0
            },
            scoreIntent = if (success) RuleScoreIntent.EXPLANATORY else RuleScoreIntent.DEFAULT,
        )
    }

    private fun ConstraintExpr.evaluate(atom: (ConstraintPredicate) -> ConstraintEvaluation): ConstraintEvaluation =
        when (this) {
            is ConstraintExpr.Atom -> atom(predicate)
            is ConstraintExpr.And -> combineAnd(terms.map { it.evaluate(atom) })
            is ConstraintExpr.Or -> combineOr(branches.map { branch -> branch to branch.expr.evaluate(atom) })
            is ConstraintExpr.Not -> term.evaluate(atom).let { result ->
                result.copy(
                    truth = when (result.truth) {
                        ConstraintTruth.SATISFIED -> ConstraintTruth.VIOLATED
                        ConstraintTruth.VIOLATED -> ConstraintTruth.SATISFIED
                        ConstraintTruth.UNDETERMINED -> ConstraintTruth.UNDETERMINED
                    }
                )
            }
        }

    private fun combineAnd(results: List<ConstraintEvaluation>): ConstraintEvaluation {
        if (results.any { !it.active }) return inactive()
        val active = results
        if (active.isEmpty()) return inactive()
        val decisive = active.firstOrNull { it.truth == ConstraintTruth.VIOLATED }
            ?: active.firstOrNull { it.truth == ConstraintTruth.UNDETERMINED }
            ?: active.first()
        val truth = when {
            active.any { it.truth == ConstraintTruth.VIOLATED } -> ConstraintTruth.VIOLATED
            active.any { it.truth == ConstraintTruth.UNDETERMINED } -> ConstraintTruth.UNDETERMINED
            else -> ConstraintTruth.SATISFIED
        }
        return decisive.copy(truth = truth, anchors = active.flatMap { it.anchors }.distinct())
    }

    private fun combineOr(results: List<Pair<ConstraintBranch, ConstraintEvaluation>>): ConstraintEvaluation {
        val active = results.filter { it.second.active }
        if (active.isEmpty()) return inactive()
        val chosen = active.firstOrNull { it.second.truth == ConstraintTruth.SATISFIED }
            ?: active.firstOrNull { it.second.truth == ConstraintTruth.UNDETERMINED }
            ?: active.first()
        val truth = when {
            active.any { it.second.truth == ConstraintTruth.SATISFIED } -> ConstraintTruth.SATISFIED
            active.any { it.second.truth == ConstraintTruth.UNDETERMINED } -> ConstraintTruth.UNDETERMINED
            else -> ConstraintTruth.VIOLATED
        }
        return chosen.second.copy(
            truth = truth,
            branchRuleId = chosen.first.ruleId,
            branchExplanation = chosen.first.explanation,
            branchScoreDelta = chosen.first.scoreDelta,
        )
    }

    private fun defaultExplanation(expr: ConstraintExpr): ConstraintExplanation =
        when (val predicate = expr.atomicPredicates().first()) {
            is ConstraintPredicate.ToneCompleteness -> ConstraintExplanation("和弦音完整性满足。", "未满足和弦音完整性约束。")
            is ConstraintPredicate.ToneDoubled -> ConstraintExplanation("重复音要求满足。", "未按要求重复${predicate.requirement.tone.label()}。")
            is ConstraintPredicate.ToneNotDoubled -> ConstraintExplanation("避免重复音要求满足。", "不应重复${predicate.requirement.tone.label()}。")
            is ConstraintPredicate.ScaleDegreeNotDoubled -> ConstraintExplanation("避免音级重复要求满足。", "不应重复 ${predicate.requirement.degree} 级音。")
            is ConstraintPredicate.Spacing -> ConstraintExplanation("排列偏好满足。", "未满足排列偏好。")
            is ConstraintPredicate.DistinctIdentities -> ConstraintExplanation("和弦身份互不重复。", "窗口内重复了同一和弦。")
            is ConstraintPredicate.CommonToneWithPrevious -> ConstraintExplanation("相邻和弦共同音要求满足。", "相邻和弦未满足共同音要求。")
            is ConstraintPredicate.NeighborTone -> ConstraintExplanation("和弦音预备/解决要求满足。", "和弦音未按要求预备或解决。")
            is ConstraintPredicate.TargetMatches -> ConstraintExplanation(predicate.requirement.message)
            is ConstraintPredicate.SameSonority -> ConstraintExplanation("指定槽位使用同一和弦音集合。", "指定槽位的和弦音集合不相同。")
            is ConstraintPredicate.VoiceDiatonicSteps -> ConstraintExplanation("声部级数运动满足模式。", "声部级数运动不满足模式。")
            is ConstraintPredicate.VoicePitchClassCardinality -> ConstraintExplanation("声部音级基数满足要求。", "声部音级基数不满足要求。")
            is ConstraintPredicate.ToneMultiplicity -> ConstraintExplanation("和弦音数量满足要求。", "和弦音数量不满足要求。")
            is ConstraintPredicate.ToneInVoiceFilter -> ConstraintExplanation("和弦音位于指定声部。", "和弦音不在指定声部。")
            is ConstraintPredicate.RootDiatonicMotion -> ConstraintExplanation("根音进行方向满足要求。", "根音进行方向不满足要求。")
            is ConstraintPredicate.UniqueVoiceExtreme -> ConstraintExplanation("声部极值保持唯一。", "声部极值出现了多次。")
            is ConstraintPredicate.NoRepeatedVoicePattern -> ConstraintExplanation("旋律线没有近距离反复。", "旋律线出现了近距离反复。")
            is ConstraintPredicate.MinimumSimilarChordDistance ->
                ConstraintExplanation("同根音的类似和弦保持了足够间隔。", "同根音的类似和弦出现得过近。")
            ConstraintPredicate.DistinctSimilarChordProgressions ->
                ConstraintExplanation("没有反复类似的和弦进行。", "出现了先前已有的类似和弦进行。")
            is ConstraintPredicate.RootProgressionPreference ->
                ConstraintExplanation("根音进行偏好成本为零。", "根音进行包含应谨慎使用的走向或较近回返。")
            is ConstraintPredicate.RuleFound -> ConstraintExplanation("候选满足指定的规则要求。", "候选没有满足指定的规则要求。")
        }

    private data class RepeatedPatternMatch(
        val voice: FixedVoice,
        val firstStart: Int,
        val secondStart: Int,
        val noteCount: Int,
        val strength: Double,
    )

    /**
     * Finds the strongest non-overlapping repeated interval pattern in one voice.
     * Longer patterns score more strongly, while a smaller gap raises the score.
     */
    private fun strongestRepeatedPattern(
        frames: List<FixedVoiceWritingFrame<ChordTarget>>,
        voice: FixedVoice,
        minPatternNotes: Int,
        maxPatternNotes: Int,
    ): RepeatedPatternMatch? {
        val steps = frames.map { it.pitchFor(voice).diatonicSteps }
        val upperLength = minOf(maxPatternNotes, steps.size / 2)
        if (upperLength < minPatternNotes) return null
        var strongest: RepeatedPatternMatch? = null
        for (noteCount in minPatternNotes..upperLength) {
            for (firstStart in 0..steps.size - noteCount * 2) {
                val firstPattern = steps
                    .subList(firstStart, firstStart + noteCount)
                    .zipWithNext { before, after -> after - before }
                for (secondStart in firstStart + noteCount..steps.size - noteCount) {
                    val secondPattern = steps
                        .subList(secondStart, secondStart + noteCount)
                        .zipWithNext { before, after -> after - before }
                    if (firstPattern != secondPattern) continue
                    val interveningNotes = secondStart - (firstStart + noteCount)
                    val proximity = 1.0 + 1.0 / (interveningNotes + 1.0)
                    val match = RepeatedPatternMatch(
                        voice = voice,
                        firstStart = firstStart,
                        secondStart = secondStart,
                        noteCount = noteCount,
                        strength = noteCount * noteCount * proximity,
                    )
                    if (strongest == null || match.strength > strongest.strength) strongest = match
                }
            }
        }
        return strongest
    }

    private fun FixedVoiceWritingFrame<ChordTarget>.pitchForRole(role: FixedVoiceRole) =
        pitchFor(voices.first { it.role == role })

    private fun ChordToneNeighborRequirement.checkRelation(
        key: Key,
        source: FixedVoiceWritingFrame<ChordTarget>,
        neighbor: FixedVoiceWritingFrame<ChordTarget>,
    ): NeighborResult {
        if (!neighborSelector.matches(neighbor.target)) return NeighborResult(false, emptyList())
        val sourcePitchClass = source.target.pitchClassFor(sourceTone) ?: return NeighborResult(true, emptyList())
        if (sourcePitchClasses.isNotEmpty() && sourcePitchClass !in sourcePitchClasses) {
            return NeighborResult(true, emptyList())
        }
        val candidates = candidatePitchClasses(key)
        val sourceVoices = voices.filter { voiceFilter.allows(it) }
            .filter { source.pitchFor(it).pitchClass == sourcePitchClass }
        if (sourceVoices.isEmpty()) return NeighborResult(voiceFilter != ChordToneVoiceFilter.ANY, emptyList())
        val ok = sourceVoices.all { voice ->
            val from = source.pitchFor(voice)
            val to = neighbor.pitchFor(voice)
            to.pitchClass in candidates &&
                (allowedDiatonicStepDeltas.isEmpty() || to.diatonicSteps - from.diatonicSteps in allowedDiatonicStepDeltas)
        }
        return NeighborResult(ok, sourceVoices)
    }

    private data class NeighborResult(val ok: Boolean, val sourceVoices: List<FixedVoice>)
}

/** Stable fallback rule id shared by finding emission and DP state-plan compilation. */
internal fun defaultConstraintRuleId(expr: ConstraintExpr): RuleId =
    when (val predicate = expr.atomicPredicates().first()) {
        is ConstraintPredicate.ToneCompleteness -> RuleId("solver.constraint.tone-completeness")
        is ConstraintPredicate.ToneDoubled -> RuleId("solver.constraint.doubling")
        is ConstraintPredicate.ToneNotDoubled -> RuleId("solver.constraint.avoid-doubling")
        is ConstraintPredicate.ScaleDegreeNotDoubled -> RuleId("solver.constraint.avoid-scale-degree-doubling")
        is ConstraintPredicate.Spacing -> RuleId("solver.constraint.spacing")
        is ConstraintPredicate.DistinctIdentities -> RuleId("solver.constraint.all-different")
        is ConstraintPredicate.CommonToneWithPrevious -> RuleId("solver.constraint.adjacent-common-tone")
        is ConstraintPredicate.NeighborTone -> when (predicate.requirement.direction) {
            ChordToneNeighborDirection.PREVIOUS -> RuleId("solver.constraint.chord-tone-neighbor.previous")
            ChordToneNeighborDirection.NEXT -> RuleId("solver.constraint.chord-tone-neighbor.next")
        }
        is ConstraintPredicate.TargetMatches -> predicate.requirement.ruleId
        is ConstraintPredicate.SameSonority -> RuleId("solver.constraint.same-sonority")
        is ConstraintPredicate.VoiceDiatonicSteps -> RuleId("solver.constraint.voice-diatonic-steps")
        is ConstraintPredicate.VoicePitchClassCardinality -> RuleId("solver.constraint.voice-pitch-class-cardinality")
        is ConstraintPredicate.ToneMultiplicity -> RuleId("solver.constraint.tone-multiplicity")
        is ConstraintPredicate.ToneInVoiceFilter -> RuleId("solver.constraint.tone-in-voice-filter")
        is ConstraintPredicate.RootDiatonicMotion -> RuleId("solver.constraint.root-diatonic-motion")
        is ConstraintPredicate.UniqueVoiceExtreme -> RuleId("solver.constraint.unique-voice-extreme")
        is ConstraintPredicate.NoRepeatedVoicePattern -> RuleId("solver.constraint.no-repeated-voice-pattern")
        is ConstraintPredicate.MinimumSimilarChordDistance -> RuleId("solver.constraint.minimum-similar-chord-distance")
        ConstraintPredicate.DistinctSimilarChordProgressions ->
            RuleId("solver.constraint.distinct-similar-chord-progressions")
        is ConstraintPredicate.RootProgressionPreference ->
            RuleId("solver.constraint.root-progression-preference")
        is ConstraintPredicate.RuleFound -> predicate.ruleId
    }

private fun inactive() = ConstraintEvaluation(ConstraintTruth.UNDETERMINED, active = false)
private fun satisfied(anchors: List<EventId>) = ConstraintEvaluation(ConstraintTruth.SATISFIED, anchors = anchors)
private fun verdict(ok: Boolean, anchors: List<EventId>) =
    ConstraintEvaluation(if (ok) ConstraintTruth.SATISFIED else ConstraintTruth.VIOLATED, anchors = anchors)

private fun ConstraintPredicate.isTargetOnly(): Boolean =
    when (this) {
        is ConstraintPredicate.RootDiatonicMotion,
        is ConstraintPredicate.MinimumSimilarChordDistance,
        ConstraintPredicate.DistinctSimilarChordProgressions,
        is ConstraintPredicate.RootProgressionPreference,
        -> true
        else -> false
    }

/**
 * 终层全局规则一次求值可能贡献的最小分数（可采纳下界）。DP 终层用它做 branch-and-bound：
 * 只要「基础分 + 下界」已经劣于当前 top-k 的第 k 名，后续路径就不必再展开全局规则。
 *
 * 判据只看约束本身，不看具体路径；未发射时贡献 0，因此每条约束的下界是 `min(0, 发射分数下界)`。
 */
internal fun terminalGlobalScoreLowerBound(
    program: ConstraintProgram,
    policy: FixedVoiceWritingScorePolicy,
): Double =
    program.constraints
        .filter { constraint ->
            constraint.expr.atomicPredicates().any { it.requiresGlobalEvaluation() }
        }
        .sumOf { constraint ->
            val branchLowerBound = constraint.expr.atomicPredicates()
                .map { it.branchScoreDeltaLowerBound() }
                .minOrNull() ?: 0.0
            val emittedLowerBound = when (val modality = constraint.modality) {
                // Require / Prefer 只在 VIOLATED 时发射：scoreDelta 为 0 时退回按 kind/severity 计分。
                ConstraintModality.Require ->
                    minOf(branchLowerBound, policy.hardCost, policy.hintCost)
                is ConstraintModality.Prefer ->
                    minOf(branchLowerBound, modality.weight ?: policy.softCost)
                // Reward / Annotate 在 SATISFIED 时发射，Reward 记为负分奖励。
                is ConstraintModality.Reward -> minOf(branchLowerBound, -modality.bonus)
                // Annotate 发射的 finding 是 EXPLANATORY，scoreDelta 为 0 时不计分。
                ConstraintModality.Annotate -> minOf(branchLowerBound, 0.0)
            }
            minOf(0.0, emittedLowerBound)
        }

/**
 * 谓词自带 `branchScoreDelta` 时的最小可能值。新增谓词必须在此显式表态：漏判会让终层
 * branch-and-bound 的下界失效，把更优解剪掉。
 */
private fun ConstraintPredicate.branchScoreDeltaLowerBound(): Double =
    when (this) {
        // 惩罚强度 = 反复强度 × penaltyScale，两者都为正。
        is ConstraintPredicate.NoRepeatedVoicePattern -> 0.0
        // 只在成本 > 0 时才发射 VIOLATED。
        is ConstraintPredicate.RootProgressionPreference -> 0.0
        is ConstraintPredicate.ToneCompleteness,
        is ConstraintPredicate.ToneDoubled,
        is ConstraintPredicate.ToneNotDoubled,
        is ConstraintPredicate.ScaleDegreeNotDoubled,
        is ConstraintPredicate.Spacing,
        is ConstraintPredicate.DistinctIdentities,
        is ConstraintPredicate.CommonToneWithPrevious,
        is ConstraintPredicate.NeighborTone,
        is ConstraintPredicate.TargetMatches,
        is ConstraintPredicate.SameSonority,
        is ConstraintPredicate.VoiceDiatonicSteps,
        is ConstraintPredicate.VoicePitchClassCardinality,
        is ConstraintPredicate.ToneMultiplicity,
        is ConstraintPredicate.ToneInVoiceFilter,
        is ConstraintPredicate.RootDiatonicMotion,
        is ConstraintPredicate.UniqueVoiceExtreme,
        is ConstraintPredicate.MinimumSimilarChordDistance,
        ConstraintPredicate.DistinctSimilarChordProgressions,
        is ConstraintPredicate.RuleFound,
        -> 0.0
    }

/** Predicates whose truth cannot be decided by one vertical frame or one adjacent transition. */
private fun ConstraintPredicate.requiresGlobalEvaluation(): Boolean =
    when (this) {
        is ConstraintPredicate.TargetMatches,
        is ConstraintPredicate.SameSonority,
        is ConstraintPredicate.VoicePitchClassCardinality,
        is ConstraintPredicate.UniqueVoiceExtreme,
        is ConstraintPredicate.NoRepeatedVoicePattern,
        is ConstraintPredicate.MinimumSimilarChordDistance,
        ConstraintPredicate.DistinctSimilarChordProgressions,
        is ConstraintPredicate.RootProgressionPreference,
        is ConstraintPredicate.RuleFound,
        -> true
        else -> false
    }

private fun ChordTone.label(): String = when (this) {
    ChordTone.ROOT -> "根音"
    ChordTone.THIRD -> "三音"
    ChordTone.FIFTH -> "五音"
    ChordTone.SEVENTH -> "七音"
    ChordTone.BASS -> "低音"
}

private const val OCTAVE_SEMITONES = 12
