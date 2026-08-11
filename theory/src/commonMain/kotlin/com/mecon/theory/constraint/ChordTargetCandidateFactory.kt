package com.mecon.theory.constraint

import com.mecon.theory.FixedVoice
import com.mecon.theory.FixedVoiceCandidateFactory
import com.mecon.theory.FixedVoiceCandidatePriority
import com.mecon.theory.FixedVoicePitchClassChoice
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceVoicingEnumerator
import com.mecon.theory.FixedVoiceWritingFrame
import com.mecon.theory.FixedVoiceWritingState
import com.mecon.theory.ChordArity
import com.mecon.theory.SpelledPitchClass
import com.mecon.theory.WritingTask
import com.mecon.theory.SearchCancellation
import com.mecon.theory.SearchPriority
import kotlin.math.abs

class ChordTargetCandidateFactory(
    private val program: ConstraintProgram,
    private val voices: List<FixedVoice>,
    private val initialBoundary: FixedVoiceWritingFrame<ChordTarget>? = null,
    private val cancellation: SearchCancellation = SearchCancellation.NONE,
) : FixedVoiceCandidateFactory<ChordTarget>, FixedVoiceCandidatePriority<ChordTarget> {
    private val interpretedRealizationCache =
        mutableMapOf<String, List<FixedVoiceWritingFrame<ChordTarget>>>()

    override fun candidates(
        state: FixedVoiceWritingState<ChordTarget>,
        slotIndex: Int,
        target: ChordTarget,
        task: WritingTask,
    ): List<FixedVoiceWritingFrame<ChordTarget>> {
        val requestedLimit = if (task.searchConfig.prefixDiversity.enabled) {
            maxOf(
                task.searchConfig.candidateLimit,
                task.searchConfig.prefixDiversity.frontierWidth * PREFIX_CANDIDATE_MULTIPLIER,
            )
        } else {
            task.searchConfig.candidateLimit
        }
        val limit = requestedLimit.coerceIn(MIN_FRAMES_PER_TARGET, MAX_FRAMES_PER_TARGET)
        val previous = state.frames.lastOrNull() ?: initialBoundary
        val allowed = verticalCandidates(
            state = state,
            slotIndex = slotIndex,
            target = target,
            previous = previous,
            poolLimit = limit * CANDIDATE_POOL_MULTIPLIER,
        )
        val diversifyLocally = task.searchConfig.diversity.enabled ||
            task.searchConfig.prefixDiversity.enabled
        if (previous == null && !diversifyLocally) return allowed.take(limit)

        // exploit 池：局部声部移动最小的候选，保证首解质量。
        if (!diversifyLocally) return topBySearchPriority(allowed, requireNotNull(previous), limit)

        // explore 池（diverse-search.md §6）：按帧结构 key 分层抽样，暴露更远但合法的排列，
        // 供多样化重启阶段变异。求解器仍按完整前缀评分排序，探索候选不会挤掉首解。
        val exploreQuota = limit - (limit * EXPLOIT_NUMERATOR) / EXPLOIT_DENOMINATOR
        val exploitQuota = limit - exploreQuota
        val exploit = if (previous == null) {
            allowed.take(exploitQuota)
        } else {
            topBySearchPriority(allowed, previous, exploitQuota)
        }
        if (exploreQuota <= 0) return exploit
        val exploitKeys = exploit.mapTo(HashSet()) { frameExplorationKey(it) }
        val explore = stratifiedSample(allowed, previous, exploitKeys, exploreQuota)
        return (exploit + explore).distinct().take(limit)
    }

    /**
     * Enumerates a slot/target layer without consulting a predecessor state. Layered search calls
     * this once, then applies relation constraints while connecting predecessor edges.
     */
    internal fun layerCandidates(
        slotIndex: Int,
        target: ChordTarget,
        maxCandidates: Int?,
        diversifyLocally: Boolean = false,
    ): LayerCandidateBatch {
        require(maxCandidates == null || maxCandidates > 0) { "maxCandidates must be positive" }
        // 排序 key 每帧只算一次：比较器里重算省略音集合与结构字符串会主导整层构建成本。
        val candidates = verticalCandidateSequence(
            slotIndex = slotIndex,
            target = target,
        ).map { frame -> RankedFrame(frame, verticalPriority(frame), verticalSpan(frame), frameStructuralKey(frame)) }
        if (maxCandidates == null) {
            return LayerCandidateBatch(
                candidates.sortedWith(RANKED_FRAME_ORDER).map { it.frame }.toList(),
                truncated = false,
            )
        }
        if (diversifyLocally) {
            val allowed = candidates.map { it.frame }.toList()
            val exploreQuota = maxCandidates - (maxCandidates * EXPLOIT_NUMERATOR) / EXPLOIT_DENOMINATOR
            val exploitQuota = maxCandidates - exploreQuota
            val ranked = allowed.map { frame ->
                RankedFrame(frame, verticalPriority(frame), verticalSpan(frame), frameStructuralKey(frame))
            }
            val exploit = ranked.asSequence().best(exploitQuota, RANKED_FRAME_ORDER).map { it.frame }
            val exploitKeys = exploit.mapTo(HashSet()) { frameExplorationKey(it) }
            val explore = stratifiedSample(
                allowed = allowed,
                previous = null,
                excludeKeys = exploitKeys,
                quota = exploreQuota,
            )
            val frames = (exploit + explore).distinct().take(maxCandidates)
            return LayerCandidateBatch(frames = frames, truncated = allowed.size > frames.size)
        }
        val sampled = candidates.best(maxCandidates + 1, RANKED_FRAME_ORDER)
        return LayerCandidateBatch(
            frames = sampled.take(maxCandidates).map { it.frame },
            truncated = sampled.size > maxCandidates,
        )
    }

    /** 先按完整性/平顺进行放宽层，再按局部移动细排前 [limit] 个帧。 */
    private fun topBySearchPriority(
        allowed: List<FixedVoiceWritingFrame<ChordTarget>>,
        previous: FixedVoiceWritingFrame<ChordTarget>,
        limit: Int,
    ): List<FixedVoiceWritingFrame<ChordTarget>> {
        val best = mutableListOf<Pair<SearchPriority, FixedVoiceWritingFrame<ChordTarget>>>()
        allowed.forEach { frame ->
            val priority = localPriority(previous, frame)
            val insertion = best.indexOfFirst { (existingPriority) -> existingPriority > priority }
                .let { if (it < 0) best.size else it }
            if (insertion < limit) {
                best.add(insertion, priority to frame)
                if (best.size > limit) best.removeAt(best.lastIndex)
            }
        }
        return best.map { it.second }
    }

    /**
     * 按帧结构 key 分层，每层取移动最小代表，轮转填满 [quota]。已在 exploit 池覆盖的结构跳过，
     * 确保探索候选带来 exploit 前缀之外的结构。顺序确定，可复现性只由搜索 seed 决定。
     */
    private fun stratifiedSample(
        allowed: List<FixedVoiceWritingFrame<ChordTarget>>,
        previous: FixedVoiceWritingFrame<ChordTarget>?,
        excludeKeys: Set<String>,
        quota: Int,
    ): List<FixedVoiceWritingFrame<ChordTarget>> {
        val strata = LinkedHashMap<String, MutableList<FixedVoiceWritingFrame<ChordTarget>>>()
        allowed.forEach { frame ->
            val key = frameExplorationKey(frame)
            if (key in excludeKeys) return@forEach
            strata.getOrPut(key) { mutableListOf() }.add(frame)
        }
        strata.values.forEach { group -> group.sortBy { localCost(it, previous) } }
        val orderedStrata = strata.values
            .map { group -> group }
            .sortedBy { group -> localCost(group.first(), previous) }
        val picked = mutableListOf<FixedVoiceWritingFrame<ChordTarget>>()
        var depth = 0
        while (picked.size < quota) {
            var advanced = false
            for (group in orderedStrata) {
                if (depth < group.size) {
                    picked.add(group[depth])
                    advanced = true
                    if (picked.size >= quota) break
                }
            }
            if (!advanced) break
            depth++
        }
        return picked
    }

    private fun motionCost(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        previous: FixedVoiceWritingFrame<ChordTarget>,
    ): Int =
        voices.sumOf { voice ->
            abs(frame.pitchFor(voice).midiNumber - previous.pitchFor(voice).midiNumber)
        }

    /**
     * 把一帧里只依赖该帧的量（各声部 MIDI、省略音数、纵向跨度）算一次并复用。分层搜索的一层里，
     * 同一帧要对所有前驱标签重复比较，逐次重算省略音集合和声部查表会主导每条转移的成本。
     */
    internal fun candidateProfile(
        frame: FixedVoiceWritingFrame<ChordTarget>,
    ): FrameCandidateProfile {
        val midi = IntArray(voices.size) { index -> frame.pitchFor(voices[index]).midiNumber }
        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        midi.forEach { pitch ->
            if (pitch < lowest) lowest = pitch
            if (pitch > highest) highest = pitch
        }
        return FrameCandidateProfile(
            midi = midi,
            omittedTones = omittedConventionalTones(frame),
            verticalSpan = if (midi.isEmpty()) 0 else highest - lowest,
        )
    }

    override fun localPriority(
        previous: FixedVoiceWritingFrame<ChordTarget>?,
        frame: FixedVoiceWritingFrame<ChordTarget>,
    ): SearchPriority = localPriority(previous?.let(::candidateProfile), candidateProfile(frame))

    internal fun localPriority(
        previous: FrameCandidateProfile?,
        frame: FrameCandidateProfile,
    ): SearchPriority {
        val profile = transitionProfile(previous, frame)
        return SearchPriority(
            listOf(
                profile.tier.ordinal,
                profile.nonStepInnerVoices,
                profile.innerMotion,
                profile.sopranoMotion,
                profile.totalMotion,
                frame.verticalSpan,
            )
        )
    }

    override fun pathPriority(
        initialBoundary: FixedVoiceWritingFrame<ChordTarget>?,
        frames: List<FixedVoiceWritingFrame<ChordTarget>>,
    ): SearchPriority {
        if (frames.isEmpty()) return SearchPriority.NEUTRAL
        var priority = SearchPriority.NEUTRAL
        var previous = initialBoundary?.let(::candidateProfile)
        frames.forEach { frame ->
            val profile = candidateProfile(frame)
            priority = extendPathPriority(priority, previous, profile)
            previous = profile
        }
        return priority
    }

    /**
     * [pathPriority] 的增量形式：路径优先级只是各条转移放宽层的最大值与计数，因此逐帧扩展与整段
     * 重算等价。分层搜索每层为每个标签保存结果，不再在比较器里反复扫描整条路径。
     */
    internal fun extendPathPriority(
        previous: SearchPriority,
        previousFrame: FrameCandidateProfile?,
        frame: FrameCandidateProfile,
    ): SearchPriority {
        val components = IntArray(PATH_PRIORITY_COMPONENTS) { index ->
            previous.components.getOrElse(index) { 0 }
        }
        val tier = transitionProfile(previousFrame, frame).tier
        if (tier.ordinal > components[0]) components[0] = tier.ordinal
        when (tier) {
            TransitionRelaxationTier.WIDER_LEAPS -> components[1]++
            TransitionRelaxationTier.SOPRANO_SIXTH -> components[2]++
            TransitionRelaxationTier.INNER_FIFTH -> components[3]++
            TransitionRelaxationTier.OMITTED_TONE -> components[4]++
            TransitionRelaxationTier.STRICT -> Unit
        }
        return SearchPriority(components.toList())
    }

    internal fun transitionTier(
        previous: FixedVoiceWritingFrame<ChordTarget>?,
        frame: FixedVoiceWritingFrame<ChordTarget>,
    ): TransitionRelaxationTier = transitionTier(previous?.let(::candidateProfile), candidateProfile(frame))

    internal fun transitionTier(
        previous: FrameCandidateProfile?,
        frame: FrameCandidateProfile,
    ): TransitionRelaxationTier = transitionProfile(previous, frame).tier

    private fun transitionProfile(
        previous: FrameCandidateProfile?,
        frame: FrameCandidateProfile,
    ): TransitionPreferenceProfile {
        val omitted = frame.omittedTones
        if (previous == null) {
            return TransitionPreferenceProfile(
                tier = if (omitted == 0) TransitionRelaxationTier.STRICT else
                    TransitionRelaxationTier.OMITTED_TONE,
            )
        }
        var innerMotion = 0
        var nonStepInnerVoices = 0
        var widestInner = 0
        var totalMotion = 0
        for (index in frame.midi.indices) {
            val distance = abs(frame.midi[index] - previous.midi[index])
            totalMotion += distance
            val inner = index != 0 && index != frame.midi.lastIndex
            if (inner) {
                innerMotion += distance
                if (distance > STEP_MAX_SEMITONES) nonStepInnerVoices++
                if (distance > widestInner) widestInner = distance
            }
        }
        val sopranoDistance = abs(frame.midi[0] - previous.midi[0])
        val tier = when {
            widestInner > FIFTH_SEMITONES || sopranoDistance > SIXTH_MAX_SEMITONES ->
                TransitionRelaxationTier.WIDER_LEAPS
            sopranoDistance > FIFTH_SEMITONES -> TransitionRelaxationTier.SOPRANO_SIXTH
            widestInner >= FIFTH_SEMITONES -> TransitionRelaxationTier.INNER_FIFTH
            omitted > 0 -> TransitionRelaxationTier.OMITTED_TONE
            else -> TransitionRelaxationTier.STRICT
        }
        return TransitionPreferenceProfile(
            tier = tier,
            nonStepInnerVoices = nonStepInnerVoices,
            innerMotion = innerMotion,
            sopranoMotion = sopranoDistance,
            totalMotion = totalMotion,
        )
    }

    private fun verticalPriority(frame: FixedVoiceWritingFrame<ChordTarget>): SearchPriority {
        val missing = missingConventionalTones(frame)
        val omittedTonePreference = when {
            missing.isEmpty() -> 0
            missing == setOf(ChordTone.FIFTH) -> 1
            missing == setOf(ChordTone.THIRD) ->
                if (program.omissionPolicy.preference == ChordOmissionPreference.OMIT_FIFTH_FIRST) 2 else 1
            else -> 3
        }
        return SearchPriority(listOf(missing.size, omittedTonePreference))
    }

    private fun omittedConventionalTones(frame: FixedVoiceWritingFrame<ChordTarget>): Int =
        missingConventionalTones(frame).size

    private fun missingConventionalTones(
        frame: FixedVoiceWritingFrame<ChordTarget>,
    ): Set<ChordTone> {
        val notes = chordMemberNotes(frame, frame.target)
        return conventionalTones(frame.target).filterTo(linkedSetOf()) { tone ->
            frame.target.pitchClassFor(tone)?.let { it !in notes } == true
        }
    }

    private fun conventionalTones(target: ChordTarget): Set<ChordTone> =
        when (target.arity) {
            ChordArity.TRIAD -> setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH)
            ChordArity.SEVENTH -> setOf(
                ChordTone.ROOT,
                ChordTone.THIRD,
                ChordTone.FIFTH,
                ChordTone.SEVENTH,
            )
        }.filterTo(linkedSetOf()) { target.pitchClassFor(it) != null }

    private fun localCost(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        previous: FixedVoiceWritingFrame<ChordTarget>?,
    ): Int = previous?.let { motionCost(frame, it) } ?: verticalSpan(frame)

    /** 帧结构 key = 各声部 pitch class 元组，与 FixedVoiceWritingCandidateSpace.slotDiversityKeys 同源。 */
    private fun frameStructuralKey(frame: FixedVoiceWritingFrame<ChordTarget>): String =
        voices.joinToString(",") { voice -> frame.pitchFor(voice).pitchClass.value.toString() }

    private fun frameExplorationKey(frame: FixedVoiceWritingFrame<ChordTarget>): String {
        val outerPitches = voices
            .filter { voice -> voice.role == FixedVoiceRole.SOPRANO || voice.role == FixedVoiceRole.BASS }
            .joinToString(",") { voice -> frame.pitchFor(voice).midiNumber.toString() }
        return "${frameStructuralKey(frame)}@$outerPitches"
    }

    private fun verticalCandidates(
        state: FixedVoiceWritingState<ChordTarget>,
        slotIndex: Int,
        target: ChordTarget,
        previous: FixedVoiceWritingFrame<ChordTarget>?,
        poolLimit: Int,
    ): List<FixedVoiceWritingFrame<ChordTarget>> {
        val previousProfile = previous?.let(::candidateProfile)
        val candidates = verticalCandidateSequence(slotIndex, target)
            .filter { frame -> relationConstraintsAllowFrame(program, voices, state, frame) }
            .map { frame ->
                RankedFrame(
                    frame = frame,
                    priority = if (previousProfile == null) {
                        verticalPriority(frame)
                    } else {
                        localPriority(previousProfile, candidateProfile(frame))
                    },
                    verticalSpan = verticalSpan(frame),
                    structuralKey = frameStructuralKey(frame),
                )
            }
        return candidates.best(poolLimit, RANKED_FRAME_ORDER).map { it.frame }
    }

    private fun verticalCandidateSequence(
        slotIndex: Int,
        target: ChordTarget,
    ): Sequence<FixedVoiceWritingFrame<ChordTarget>> {
        val choices = voices.map { voice ->
            val participation = program.texturePlan.participationAt(slotIndex, voice.id)
            val pitchClasses = when (participation) {
                is HarmonicVoiceParticipation.Sustained -> setOf(participation.pitch.pitchClass)
                HarmonicVoiceParticipation.ChordMember ->
                    if (voice.role == FixedVoiceRole.BASS) {
                        setOf(target.bassPitchClass)
                    } else {
                        target.sonority.pitchClasses.toSet()
                    }
            }
            FixedVoicePitchClassChoice(
                voice = voice,
                pitchClasses = pitchClasses,
                range = program.rangeFor(voice)
                    ?: error("Missing writing range for role ${voice.role}"),
                spellings = when (participation) {
                    is HarmonicVoiceParticipation.Sustained ->
                        mapOf(
                            participation.pitch.pitchClass to SpelledPitchClass(
                                participation.pitch.noteName,
                                participation.pitch.chromaticOffset,
                            )
                        )
                    HarmonicVoiceParticipation.ChordMember ->
                        pitchClasses.mapNotNull { pitchClass ->
                            target.spellingFor(pitchClass)?.let { pitchClass to it }
                        }.toMap()
                },
            )
        }
        val baseCandidates = if (target is InterpretedChordTarget) {
            val cacheKey = "$slotIndex/${target.realizationIdentityKey()}"
            interpretedRealizationCache.getOrPut(cacheKey) {
                FixedVoiceVoicingEnumerator.sequence(
                    slotIndex = slotIndex,
                    target = target,
                    choices = choices,
                    duration = program.durationAt(slotIndex),
                    requireNoCrossing = program.enforceNoCrossingDuringEnumeration,
                    allowFrame = ::satisfiesFixedVoiceMaterial,
                    shouldContinue = { !cancellation.isCancelled() },
                ).toList()
            }.asSequence().map { cached -> cached.copy(target = target) }
        } else {
            FixedVoiceVoicingEnumerator.sequence(
                slotIndex = slotIndex,
                target = target,
                choices = choices,
                duration = program.durationAt(slotIndex),
                requireNoCrossing = program.enforceNoCrossingDuringEnumeration,
                allowFrame = ::satisfiesFixedVoiceMaterial,
                shouldContinue = { !cancellation.isCancelled() },
            )
        }
        return baseCandidates
            .filter { frame ->
                satisfiesToneCompleteness(frame, target) &&
                    satisfiesOmissionPolicy(frame, target) &&
                    satisfiesRequiredDoublings(frame, target) &&
                    satisfiesRequiredAvoidDoublings(frame, target) &&
                    satisfiesRequiredAvoidScaleDegreeDoublings(frame, target)
            }
    }

    private fun satisfiesFixedVoiceMaterial(frame: FixedVoiceWritingFrame<ChordTarget>): Boolean =
        program.pitchPins
            .asSequence()
            .filter { it.slot == frame.slotIndex }
            .all { pin -> frame.pitchesByVoiceId[pin.voiceId] == pin.pitch } &&
            voices.all { voice ->
                when (val participation = program.texturePlan.participationAt(frame.slotIndex, voice.id)) {
                    HarmonicVoiceParticipation.ChordMember -> true
                    is HarmonicVoiceParticipation.Sustained ->
                        frame.pitchesByVoiceId[voice.id] == participation.pitch
                }
            } &&
            structuralBassIsPresent(frame)

    private fun structuralBassIsPresent(frame: FixedVoiceWritingFrame<ChordTarget>): Boolean {
        val chordPitches = voices.mapNotNull { voice ->
            val pitch = frame.pitchesByVoiceId[voice.id] ?: return@mapNotNull null
            when (program.texturePlan.participationAt(frame.slotIndex, voice.id)) {
                HarmonicVoiceParticipation.ChordMember -> pitch
                is HarmonicVoiceParticipation.Sustained ->
                    pitch.takeIf { it.pitchClass in frame.target.sonority.pitchClasses }
            }
        }
        return chordPitches.minByOrNull { it.midiNumber }?.pitchClass == frame.target.bassPitchClass
    }

    private fun verticalSpan(frame: FixedVoiceWritingFrame<ChordTarget>): Int {
        val midi = voices.map { frame.pitchFor(it).midiNumber }
        return (midi.maxOrNull() ?: 0) - (midi.minOrNull() ?: 0)
    }

    private fun satisfiesToneCompleteness(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        target: ChordTarget,
    ): Boolean {
        val notes = chordMemberNotes(frame, target)
        return program.toneCompleteness
            .filter { it.required && it.ruleId !in program.demonstratedViolationRuleIds && it.window.contains(frame.slotIndex) && it.selector.matches(target) }
            .all { it.isSatisfiedBy(target, notes) }
    }

    private fun satisfiesRequiredDoublings(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        target: ChordTarget,
    ): Boolean {
        val slotRequirements = program.doublings.filter {
            it.required && it.ruleId !in program.demonstratedViolationRuleIds && it.slot == frame.slotIndex && it.selector.matches(target)
        }
        if (slotRequirements.isEmpty()) return true
        val notes = chordMemberNotes(frame, target)
        return slotRequirements.all { requirement ->
            val tonePc = target.pitchClassFor(requirement.tone) ?: return@all true
            notes.count { it == tonePc } >= 2
        }
    }

    private fun satisfiesRequiredAvoidDoublings(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        target: ChordTarget,
    ): Boolean {
        val slotRequirements = program.avoidDoublings
            .filter { it.required && it.ruleId !in program.demonstratedViolationRuleIds && it.slot == frame.slotIndex && it.selector.matches(target) }
        if (slotRequirements.isEmpty()) return true
        val notes = chordMemberNotes(frame, target)
        return slotRequirements.all { requirement ->
            val tonePc = target.pitchClassFor(requirement.tone) ?: return@all true
            notes.count { it == tonePc } <= 1
        }
    }

    private fun satisfiesRequiredAvoidScaleDegreeDoublings(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        target: ChordTarget,
    ): Boolean {
        val slotRequirements = program.avoidScaleDegreeDoublings
            .filter { it.required && it.ruleId !in program.demonstratedViolationRuleIds && it.slot == frame.slotIndex && it.selector.matches(target) }
        if (slotRequirements.isEmpty()) return true
        val notes = chordMemberNotes(frame, target)
        return slotRequirements.all { it.isSatisfiedBy(program.key, notes) }
    }

    private fun chordMemberNotes(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        target: ChordTarget,
    ) = voices
        .filter {
            when (program.texturePlan.participationAt(
                frame.slotIndex,
                it.id,
            )) {
                HarmonicVoiceParticipation.ChordMember -> true
                is HarmonicVoiceParticipation.Sustained ->
                    frame.pitchFor(it).pitchClass in target.sonority.pitchClasses
            }
        }
        .map { frame.pitchFor(it).pitchClass }

    /** Hard completeness boundary shared by ordinary SATB and reduced chord-member textures. */
    private fun satisfiesOmissionPolicy(
        frame: FixedVoiceWritingFrame<ChordTarget>,
        target: ChordTarget,
    ): Boolean {
        val notes = chordMemberNotes(frame, target)
        val definedTones = conventionalTones(target)
        val missing = definedTones.filterTo(linkedSetOf()) { tone ->
            target.pitchClassFor(tone)?.let { it !in notes } == true
        }
        val (required, omittable) = when (target.arity) {
            ChordArity.TRIAD -> program.omissionPolicy.triadRequiredTones to
                program.omissionPolicy.triadOmittableTones
            ChordArity.SEVENTH -> program.omissionPolicy.seventhRequiredTones to
                program.omissionPolicy.seventhOmittableTones
        }
        return missing.none { it in required } &&
            missing.all { it in omittable } &&
            missing.size <= program.omissionPolicy.maximumOmittedTones
    }

    private companion object {
        const val MIN_FRAMES_PER_TARGET = 8
        const val MAX_FRAMES_PER_TARGET = 128
        const val CANDIDATE_POOL_MULTIPLIER = 8
        const val PREFIX_CANDIDATE_MULTIPLIER = 3
        const val STEP_MAX_SEMITONES = 2
        const val FIFTH_SEMITONES = 7
        const val SIXTH_MAX_SEMITONES = 9
        const val PATH_PRIORITY_COMPONENTS = 5

        // exploit:explore ≈ 3:1（diverse-search.md §6）。
        const val EXPLOIT_NUMERATOR = 3
        const val EXPLOIT_DENOMINATOR = 4
    }
}

/**
 * 一帧的转移无关常量。同一层的候选帧要对每个前驱标签重复参与排序与放宽层判定，这些量只算一次。
 */
internal class FrameCandidateProfile(
    /** 按 voices 顺序排列的 MIDI 号。 */
    val midi: IntArray,
    val omittedTones: Int,
    val verticalSpan: Int,
)

internal enum class TransitionRelaxationTier {
    STRICT,
    OMITTED_TONE,
    INNER_FIFTH,
    SOPRANO_SIXTH,
    WIDER_LEAPS,
}

private data class TransitionPreferenceProfile(
    val tier: TransitionRelaxationTier,
    val nonStepInnerVoices: Int = 0,
    val innerMotion: Int = 0,
    val sopranoMotion: Int = 0,
    val totalMotion: Int = 0,
)

internal data class LayerCandidateBatch(
    val frames: List<FixedVoiceWritingFrame<ChordTarget>>,
    val truncated: Boolean,
)

/** 候选帧 + 预先算好的排序 key，供 [best] 的 O(log n) 比较复用。 */
private class RankedFrame(
    val frame: FixedVoiceWritingFrame<ChordTarget>,
    val priority: SearchPriority,
    val verticalSpan: Int,
    val structuralKey: String,
)

private val RANKED_FRAME_ORDER: Comparator<RankedFrame> = compareBy(
    { it.priority },
    { it.verticalSpan },
    { it.structuralKey },
)

private fun <T> Sequence<T>.best(limit: Int, comparator: Comparator<T>): List<T> {
    require(limit > 0) { "Best-candidate limit must be positive" }
    val best = mutableListOf<T>()
    forEach { candidate ->
        val insertion = best.binarySearch(candidate, comparator)
            .let { index -> if (index >= 0) index else -index - 1 }
        if (insertion < limit) {
            best.add(insertion, candidate)
            if (best.size > limit) best.removeAt(best.lastIndex)
        }
    }
    return best
}

internal fun ToneCompletenessRequirement.isSatisfiedBy(
    target: ChordTarget,
    notes: Collection<com.mecon.api.primitive.PitchClass>,
): Boolean {
    val requiredOk = requiredTones.all { tone -> target.pitchClassFor(tone)?.let { it in notes } ?: true }
    val omittedOk = omittedTones.all { tone -> target.pitchClassFor(tone)?.let { it !in notes } ?: true }
    return requiredOk && omittedOk
}

internal fun AvoidScaleDegreeDoublingRequirement.isSatisfiedBy(
    key: com.mecon.theory.Key,
    notes: Collection<com.mecon.api.primitive.PitchClass>,
): Boolean =
    pitchClasses(key).all { pitchClass -> notes.count { it == pitchClass } <= 1 }
