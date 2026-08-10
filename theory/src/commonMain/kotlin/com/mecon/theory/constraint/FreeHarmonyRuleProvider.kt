package com.mecon.theory.constraint

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.PitchClass
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.FixedVoice
import com.mecon.theory.AdjacentVoiceUnisonRule
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceTransitionRuleContext
import com.mecon.theory.FixedVoiceVerticalRuleContext
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingRuleProvider
import com.mecon.theory.ChordMemberRole
import com.mecon.theory.DefinedSonority
import com.mecon.theory.Mode
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleId
import com.mecon.theory.RuleSeverity
import com.mecon.theory.TonalContext
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.harmony.FunctionalToneRole
import com.mecon.theory.solverVoiceEventId
import kotlin.math.abs

/**
 * Small, style-neutral voice-leading core for the free solver. Every emitted finding is soft;
 * callers may disable or reweight each stable rule id through RuleProfile.
 */
internal class FreeHarmonyRuleProvider(
    private val program: ConstraintProgram,
    private val voices: List<FixedVoice>,
    private val classicalCounterpointPreferences: Boolean,
    private val voiceLeadingRelaxation: VoiceLeadingRelaxationPlan = VoiceLeadingRelaxationPlan(),
) : FixedVoiceWritingRuleProvider<ChordTarget> {
    private val boundaries = program.resolvedVoicePlan.voices.associate { it.id to it.boundary }
    private val tendencyCache =
        mutableMapOf<ChordTarget, List<Pair<SpelledPitchClass, SpelledPitchClass>>>()
    private val directionMultiplierCache = mutableMapOf<Int, Double>()
    private val dissonantPitchClassCache = mutableMapOf<ChordTarget, Set<PitchClass>>()

    override fun checkVertical(
        context: FixedVoiceVerticalRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> = buildList {
        addAll(AdjacentVoiceUnisonRule.check(context.verticality))
        if (context.frame.slotIndex < program.length - 1) {
            addAll(continuationReserveFindings(context))
        }
        addAll(voices.zipWithNext().mapNotNull { (upper, lower) ->
            val upperPitch = context.frame.pitchFor(upper)
            val lowerPitch = context.frame.pitchFor(lower)
            if (upperPitch.midiNumber >= lowerPitch.midiNumber) return@mapNotNull null
            val outer = boundaries[upper.id] != VoiceBoundary.INNER ||
                boundaries[lower.id] != VoiceBoundary.INNER
            finding(
                id = if (outer) OUTER_CROSSING else INNER_CROSSING,
                message = if (outer) "外声部发生交错。" else "内声部发生交错，需确认音色与织体是否清楚。",
                anchors = listOf(
                    solverVoiceEventId(context.frame.slotIndex, upper.id),
                    solverVoiceEventId(context.frame.slotIndex, lower.id),
                ),
                cost = if (outer) OUTER_CROSSING_COST else INNER_CROSSING_COST,
            )
        })
    }

    private fun continuationReserveFindings(
        context: FixedVoiceVerticalRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> = voices.mapNotNull { voice ->
        val range = program.rangeFor(voice) ?: return@mapNotNull null
        val pitch = context.frame.pitchFor(voice)
        val edgeDistance = minOf(
            pitch.midiNumber - range.lowest.midiNumber,
            range.highest.midiNumber - pitch.midiNumber,
        )
        val baseCost = when (edgeDistance) {
            0 -> 3.0
            1 -> 1.5
            2 -> 0.5
            else -> return@mapNotNull null
        }
        val outer = boundaries[voice.id] != VoiceBoundary.INNER
        finding(
            id = CONTINUATION_RESERVE,
            message = "声部贴近音域边缘，后续展开空间较小。",
            anchors = listOf(solverVoiceEventId(context.frame.slotIndex, voice.id)),
            cost = if (outer) baseCost else baseCost * 0.5,
        )
    }

    override fun checkTransition(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> = buildList {
        addAll(awkwardLeaps(context))
        addAll(consecutiveLeapShape(context))
        addAll(directionCrowding(context))
        if (classicalCounterpointPreferences) {
            addAll(parallelAndHiddenPerfects(context))
            addAll(tendencyToneFindings(context))
            addAll(rootlessDiminishedFindings(context))
            addAll(dissonanceReleaseFindings(context))
        }
    }

    private fun awkwardLeaps(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> =
        voices.mapNotNull { voice ->
            val distance = abs(
                context.currentFrame.pitchFor(voice).midiNumber -
                    context.previousFrame.pitchFor(voice).midiNumber
            )
            if (distance !in AWKWARD_MELODIC_INTERVALS) return@mapNotNull null
            val outer = boundaries[voice.id] != VoiceBoundary.INNER
            val relaxed = voiceLeadingRelaxation.relaxes(context.currentFrame.slotIndex, voice.id)
            finding(
                id = AWKWARD_LEAP,
                message = "声部出现不易歌唱的变化音程或七度跳进。",
                anchors = context.transitionAnchors(voice),
                cost = (if (outer) 38.0 else 20.0) * if (relaxed) 0.15 else 1.0,
            )
        }

    private fun consecutiveLeapShape(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> {
        val frames = context.state.frames
        if (frames.size < 3) return emptyList()
        val first = frames[frames.lastIndex - 2]
        val middle = frames[frames.lastIndex - 1]
        val last = frames.last()
        return voices.mapNotNull { voice ->
            val firstMotion = middle.pitchFor(voice).midiNumber - first.pitchFor(voice).midiNumber
            val secondMotion = last.pitchFor(voice).midiNumber - middle.pitchFor(voice).midiNumber
            if (
                abs(firstMotion) <= 2 || abs(secondMotion) <= 2 ||
                firstMotion.sign != secondMotion.sign
            ) return@mapNotNull null
            val pitchClassMask = (1 shl first.pitchFor(voice).pitchClass.value) or
                (1 shl middle.pitchFor(voice).pitchClass.value) or
                (1 shl last.pitchFor(voice).pitchClass.value)
            if (TRIADIC_PITCH_CLASS_MASKS[pitchClassMask]) return@mapNotNull null
            finding(
                CONSECUTIVE_LEAPS,
                "两次同向跳进没有勾勒出大/小三和弦，旋律轮廓较难把握。",
                listOf(first, middle, last).map { solverVoiceEventId(it.slotIndex, voice.id) },
                (if (boundaries[voice.id] == VoiceBoundary.INNER) 16.0 else 30.0) *
                    if (voiceLeadingRelaxation.relaxes(last.slotIndex, voice.id) ||
                        voiceLeadingRelaxation.relaxes(middle.slotIndex, voice.id)
                    ) 0.2 else 1.0,
            )
        }
    }

    private fun directionCrowding(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> {
        // 逐条转移都会走到这里：先用定长数组算出运动量，只有真正拥挤时才构造集合与 finding。
        val motions = IntArray(voices.size) { index ->
            context.currentFrame.pitchFor(voices[index]).midiNumber -
                context.previousFrame.pitchFor(voices[index]).midiNumber
        }
        var movingCount = 0
        motions.forEach { if (it != 0) movingCount++ }
        if (movingCount < 3) return emptyList()
        val crowdedIndices = motions.indices.filter { index ->
            val center = motions[index]
            center != 0 && motions.count { other -> other != 0 && abs(other - center) <= 1 } >= 3
        }
        if (crowdedIndices.size < 3) return emptyList()
        val exactGroups = crowdedIndices
            .groupingBy { motions[it] }
            .eachCount()
            .values
            .maxOrNull() ?: 0
        return listOf(
            finding(
                id = DIRECTION_CROWDING,
                message = "三个以上声部以相同或近似跨度同向运动，进行方向较单一。",
                anchors = crowdedIndices
                    .flatMap { index -> context.transitionAnchors(voices[index]) }
                    .distinct(),
                cost = 18.0 + crowdedIndices.size * 5.0 + exactGroups.coerceAtLeast(3) * 4.0,
            )
        )
    }

    private fun parallelAndHiddenPerfects(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> = buildList {
        detectParallelPerfectMotions(
            previousPitches = voices.map(context.previousFrame::pitchFor),
            currentPitches = voices.map(context.currentFrame::pitchFor),
        ).forEach { motion ->
            val upper = voices[motion.upperVoiceIndex]
            val lower = voices[motion.lowerVoiceIndex]
            add(
                finding(
                    PARALLEL_PERFECT,
                    "出现平行纯五度或纯八度；自由写作中保留为可调软偏好。",
                    context.pairAnchors(upper, lower),
                    PARALLEL_PERFECT_COST,
                )
            )
        }
        val upper = voices.firstOrNull() ?: return@buildList
        val lower = voices.lastOrNull() ?: return@buildList
        val upperMotion = context.motionOf(upper)
        val lowerMotion = context.motionOf(lower)
        if (
            upperMotion != 0 && lowerMotion != 0 && upperMotion.sign == lowerMotion.sign &&
            context.intervalClass(context.currentFrame, upper, lower) in PERFECT_INTERVALS &&
            abs(upperMotion) > 2
        ) {
            add(
                finding(
                    HIDDEN_PERFECT,
                    "外声部同向进入纯五度或纯八度，且高声部为跳进。",
                    context.pairAnchors(upper, lower),
                    HIDDEN_PERFECT_COST,
                )
            )
        }
    }

    /**
     * 倾向音表只取决于前一帧的目标（不取决于声部排列），方向权重只取决于槽位；两者都在层内对
     * 所有入边重复出现，故按目标 / 槽位缓存，不在每条转移上重建。
     */
    private fun tendenciesFor(target: ChordTarget): List<Pair<SpelledPitchClass, SpelledPitchClass>> =
        tendencyCache.getOrPut(target) {
            val interpreted = target as? InterpretedChordTarget
            val tonalContext = interpreted?.interpretation?.lens?.context
                ?: TonalContext.fromKey(target.key)
            val family = SecondaryHarmonyMetadata.familyOf(target)
            val tonicizedDegree = SecondaryHarmonyMetadata.tonicizedDegreeOf(target)
            val appliedTendency = if (family != null && tonicizedDegree != null) {
                interpreted
                    ?.spellingFor(FunctionalToneRole.LOCAL_LEADING_TONE)
                    ?.let { it to tonalContext.spellDegree(tonicizedDegree) }
            } else {
                null
            }
            if (appliedTendency != null) {
                listOf(appliedTendency)
            } else {
                when (target.key.mode) {
                    Mode.AEOLIAN, Mode.HARMONIC_MINOR, Mode.MELODIC_MINOR -> listOf(
                        tonalContext.spellDegree(4, 1) to tonalContext.spellDegree(5, 1),
                        tonalContext.spellDegree(5, 1) to tonalContext.spellDegree(6),
                    )
                    else -> listOf(
                        tonalContext.spellDegree(4) to tonalContext.spellDegree(3),
                        tonalContext.spellDegree(7) to tonalContext.spellDegree(1),
                    )
                }
            }
        }

    private fun directionMultiplierAt(slotIndex: Int): Double =
        directionMultiplierCache.getOrPut(slotIndex) {
            program.directionalWindows
                .filter { it.window.contains(slotIndex) }
                .maxOfOrNull { it.strength }
                ?: 1.0
        }

    private fun tendencyToneFindings(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> {
        val target = context.previousFrame.target
        val tendencies = tendenciesFor(target)
        val directionMultiplier = directionMultiplierAt(context.currentFrame.slotIndex)
        return voices.mapNotNull { voice ->
            val from = context.previousFrame.pitchFor(voice)
            val to = context.currentFrame.pitchFor(voice)
            val expected = if (tendencies.isNotEmpty()) {
                tendencies.firstOrNull { (source) ->
                    from.noteName == source.noteName && from.chromaticOffset == source.chromaticOffset
                }?.second ?: return@mapNotNull null
            } else {
                val scale = target.key.scale.pitchClasses
                when (from.pitchClass) {
                    scale[3] -> return@mapNotNull if (to.pitchClass == scale[2]) null else finding(
                        TENDENCY_TONE,
                        "调式倾向音没有按常见的半音/级进方向进行。",
                        context.transitionAnchors(voice),
                        16.0 * directionMultiplier,
                    )
                    scale[6] -> return@mapNotNull if (to.pitchClass == scale[0]) null else finding(
                        TENDENCY_TONE,
                        "调式倾向音没有按常见的半音/级进方向进行。",
                        context.transitionAnchors(voice),
                        16.0 * directionMultiplier,
                    )
                    else -> return@mapNotNull null
                }
            }
            if (to.noteName == expected.noteName && to.chromaticOffset == expected.chromaticOffset) {
                return@mapNotNull null
            }
            finding(
                TENDENCY_TONE,
                "调式倾向音没有按常见的半音/级进方向进行。",
                context.transitionAnchors(voice),
                16.0 * directionMultiplier,
            )
        }
    }

    private fun rootlessDiminishedFindings(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> {
        val target = context.previousFrame.target
        if (!RootlessDominantNinthMetadata.isRootlessDominantNinth(target)) return emptyList()
        val interpreted = target as? InterpretedChordTarget ?: return emptyList()
        val tonalContext = interpreted.interpretation.lens.context
            ?: TonalContext.fromKey(interpreted.key)
        val omittedRootDegree = interpreted
            .interpretation
            .attributes[RootlessDominantNinthMetadata.OMITTED_ROOT_DEGREE_NAME]
            ?.toIntOrNull()
            ?: return emptyList()
        val omittedRootAlteration = interpreted
            .interpretation
            .attributes[RootlessDominantNinthMetadata.OMITTED_ROOT_ALTERATION_NAME]
            ?.toIntOrNull()
            ?: 0
        val omittedRoot = tonalContext.spellDegree(
            omittedRootDegree,
            omittedRootAlteration,
        )
        val loweredPitchClass = interpreted
            .pitchClassFor(FunctionalToneRole.OMITTED_DOMINANT_ROOT_NEIGHBOR)
            ?: return emptyList()
        val naturalSpellings = (1..7).map(tonalContext::spellDegree)
        val alteredPitchClasses = listOf(
            ChordTone.ROOT,
            ChordTone.THIRD,
            ChordTone.FIFTH,
            ChordTone.SEVENTH,
        ).mapNotNull { tone ->
            val pitchClass = target.pitchClassFor(tone) ?: return@mapNotNull null
            val spelling = target.spellingFor(pitchClass) ?: return@mapNotNull null
            val natural = naturalSpellings.firstOrNull { it.noteName == spelling.noteName }
                ?: return@mapNotNull null
            pitchClass.takeIf { spelling.chromaticOffset != natural.chromaticOffset }
        }.toSet()
        return buildList {
            voices.forEach { voice ->
                val from = context.previousFrame.pitchFor(voice)
                val to = context.currentFrame.pitchFor(voice)
                if (from.pitchClass == loweredPitchClass &&
                    !(to.noteName == omittedRoot.noteName &&
                        to.chromaticOffset == omittedRoot.chromaticOffset &&
                        to.midiNumber == from.midiNumber - 1)
                ) {
                    add(
                        finding(
                            ROOTLESS_DIMINISHED_ROOT,
                            "减七和弦所选音没有下降半音到省略的属根音。",
                            context.transitionAnchors(voice),
                            28.0,
                        )
                    )
                }
                if (from.pitchClass in alteredPitchClasses) {
                    val naturalTarget = naturalSpellings.any {
                        to.noteName == it.noteName && to.chromaticOffset == it.chromaticOffset
                    }
                    if (!naturalTarget || abs(to.diatonicSteps - from.diatonicSteps) > 1) {
                        add(
                            finding(
                                ROOTLESS_DIMINISHED_ALTERED_STEP,
                                "非转调用法中的减七变化音没有级进到非变化音。",
                                context.transitionAnchors(voice),
                                22.0,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Compact species-counterpoint-inspired treatment: structural chord tones need no action;
     * suspensions, avoid tones and available tensions prefer to hold into the next sonority or
     * release by step. Jazz mode omits this preference entirely.
     */
    private fun dissonanceReleaseFindings(
        context: FixedVoiceTransitionRuleContext<ChordTarget>,
    ): List<RuleFinding<EventId>> {
        // 张力音集合只取决于目标和弦，按目标缓存后每条转移只剩查表。
        val dissonantPitchClasses = dissonantPitchClassCache.getOrPut(context.previousFrame.target) {
            val sonority = context.previousFrame.target.sonority as? DefinedSonority
                ?: return@getOrPut emptySet()
            sonority.definition.members
                .filter { it.role in DISSONANT_MEMBER_ROLES }
                .mapTo(hashSetOf()) { sonority.memberPitchClass(it.id) }
        }
        if (dissonantPitchClasses.isEmpty()) return emptyList()
        return voices.mapNotNull { voice ->
            val from = context.previousFrame.pitchFor(voice)
            if (from.pitchClass !in dissonantPitchClasses) return@mapNotNull null
            val to = context.currentFrame.pitchFor(voice)
            val distance = abs(to.midiNumber - from.midiNumber)
            if (distance <= 2) return@mapNotNull null
            finding(
                DISSONANCE_RELEASE,
                "张力音没有保持或级进释放；若它是刻意的色彩，可关闭或降低此偏好。",
                context.transitionAnchors(voice),
                20.0,
            )
        }
    }

    private fun FixedVoiceTransitionRuleContext<ChordTarget>.motionOf(voice: FixedVoice): Int =
        currentFrame.pitchFor(voice).midiNumber - previousFrame.pitchFor(voice).midiNumber

    private fun FixedVoiceTransitionRuleContext<ChordTarget>.transitionAnchors(
        voice: FixedVoice,
    ): List<EventId> =
        listOf(
            solverVoiceEventId(previousFrame.slotIndex, voice.id),
            solverVoiceEventId(currentFrame.slotIndex, voice.id),
        )

    private fun FixedVoiceTransitionRuleContext<ChordTarget>.pairAnchors(
        upper: FixedVoice,
        lower: FixedVoice,
    ): List<EventId> =
        transitionAnchors(upper) + transitionAnchors(lower)

    private fun FixedVoiceTransitionRuleContext<ChordTarget>.intervalClass(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        upper: FixedVoice,
        lower: FixedVoice,
    ): Int =
        abs(frame.pitchFor(upper).midiNumber - frame.pitchFor(lower).midiNumber).mod(12)

    private fun finding(
        id: RuleId,
        message: String,
        anchors: List<EventId>,
        cost: Double,
    ): RuleFinding<EventId> =
        RuleFinding(
            ruleId = id,
            kind = RuleFindingKind.VIOLATION,
            severity = RuleSeverity.SOFT,
            message = message,
            anchors = anchors,
            scoreDelta = cost,
        )

    private val Int.sign: Int get() = compareTo(0)

    companion object {
        val OUTER_CROSSING = RuleId("free.voice-crossing.outer")
        val INNER_CROSSING = RuleId("free.voice-crossing.inner")
        val AWKWARD_LEAP = RuleId("free.melody.awkward-leap")
        val CONSECUTIVE_LEAPS = RuleId("free.melody.consecutive-leaps")
        val DIRECTION_CROWDING = RuleId("free.motion.direction-crowding")
        val PARALLEL_PERFECT = RuleId("free.counterpoint.parallel-perfect")
        val HIDDEN_PERFECT = RuleId("free.counterpoint.hidden-perfect")
        val TENDENCY_TONE = RuleId("free.tonality.tendency-tone")
        val ROOTLESS_DIMINISHED_ROOT = RuleId("free.tonality.rootless-diminished-root")
        val ROOTLESS_DIMINISHED_ALTERED_STEP = RuleId("free.tonality.rootless-diminished-altered-step")
        val DISSONANCE_RELEASE = RuleId("free.counterpoint.dissonance-release")
        val CONTINUATION_RESERVE = RuleId("free.range.continuation-reserve")

        /** Every rule this provider can emit; the DP declaration completeness guard reads it. */
        val ALL_RULE_IDS: Set<RuleId> = setOf(
            OUTER_CROSSING,
            INNER_CROSSING,
            AWKWARD_LEAP,
            CONSECUTIVE_LEAPS,
            DIRECTION_CROWDING,
            PARALLEL_PERFECT,
            HIDDEN_PERFECT,
            TENDENCY_TONE,
            ROOTLESS_DIMINISHED_ROOT,
            ROOTLESS_DIMINISHED_ALTERED_STEP,
            DISSONANCE_RELEASE,
            CONTINUATION_RESERVE,
        )

        /**
         * 只在 FREE_* 的自然三和弦子集内成立的 DP 状态声明。
         * [ROOTLESS_DIMINISHED_ROOT] / [ROOTLESS_DIMINISHED_ALTERED_STEP] / [DISSONANCE_RELEASE]
         * 在该子集内不可能发射（无减七、无张力音角色），故不在此冒充覆盖——
         * 这正是 planner 对 FREE_* 保留自然三和弦审计的原因。
         */
        internal fun naturalTriadDpStateDeclarations(
            classical: Boolean,
        ): List<LayeredDpRuleStateDeclaration> = buildList {
            add(LayeredDpRuleStateDeclaration(OUTER_CROSSING))
            add(LayeredDpRuleStateDeclaration(INNER_CROSSING))
            add(LayeredDpRuleStateDeclaration(CONTINUATION_RESERVE))
            add(LayeredDpRuleStateDeclaration(AWKWARD_LEAP, recentFrames = 1))
            add(LayeredDpRuleStateDeclaration(CONSECUTIVE_LEAPS, recentFrames = 2))
            add(LayeredDpRuleStateDeclaration(DIRECTION_CROWDING, recentFrames = 1))
            if (classical) {
                add(LayeredDpRuleStateDeclaration(PARALLEL_PERFECT, recentFrames = 1))
                add(LayeredDpRuleStateDeclaration(HIDDEN_PERFECT, recentFrames = 1))
                add(LayeredDpRuleStateDeclaration(TENDENCY_TONE, recentFrames = 1))
            }
            // ROOTLESS_DIMINISHED_* and DISSONANCE_RELEASE cannot apply in this subset.
        }

        private val DISSONANT_MEMBER_ROLES = setOf(
            ChordMemberRole.AVAILABLE_TENSION,
            ChordMemberRole.SUSPENSION,
            ChordMemberRole.AVOID_TONE,
        )

        private val AWKWARD_MELODIC_INTERVALS = setOf(6, 8, 10, 11)
        private val PERFECT_INTERVALS = setOf(0, 7)

        /**
         * 三音音级集合是否勾勒出大/小三和弦。用 12 位掩码查表，避免在每条转移上为每个声部
         * 反复构造 24 个候选集合。
         */
        private val TRIADIC_PITCH_CLASS_MASKS = BooleanArray(1 shl 12).also { table ->
            (0 until 12).forEach { root ->
                listOf(3, 4).forEach { third ->
                    table[
                        (1 shl root) or (1 shl (root + third).mod(12)) or (1 shl (root + 7).mod(12))
                    ] = true
                }
            }
        }

        // General voice-leading hygiene outranks chapter/textbook preferences. These remain soft
        // so pinned user material and freer textures can still be realized and explained.
        private const val OUTER_CROSSING_COST = 160.0
        private const val INNER_CROSSING_COST = 96.0
        private const val PARALLEL_PERFECT_COST = 84.0
        private const val HIDDEN_PERFECT_COST = 36.0
    }
}
