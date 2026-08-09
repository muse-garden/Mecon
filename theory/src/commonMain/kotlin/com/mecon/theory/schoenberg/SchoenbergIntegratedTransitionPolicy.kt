package com.mecon.theory.schoenberg

import com.mecon.theory.Key
import com.mecon.theory.Mode
import com.mecon.theory.NaturalTriad
import com.mecon.theory.RuleId
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.SearchConfig
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AdjacentCommonToneRequirement
import com.mecon.theory.constraint.AllDifferentRequirement
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetFeatureBonusRequirement
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.RootlessDominantNinthMetadata
import com.mecon.theory.constraint.SecondaryHarmonyMetadata
import com.mecon.theory.constraint.InterpretedChordTarget
import com.mecon.theory.textbook.DominantSeventhRules
import com.mecon.theory.textbook.TextbookSeventhPosition
import com.mecon.theory.textbook.TextbookTriadPosition

internal object SchoenbergIntegratedTransitionPolicy {
    private const val PERFECT_FOURTH_UP_DEGREES = 3
    private const val DOMINANT_DEGREE = 5
    private const val SUBDOMINANT_DEGREE = 4
    fun allows(
        prefix: List<SchoenbergSymbolicChord>,
        after: SchoenbergSymbolicChord,
        triads: List<NaturalTriad>,
        key: Key,
        includeLeadingTriad: Boolean,
        minor: Boolean,
        requireAdjacentCommonTone: Boolean = true,
        applyRootMotionDirection: Boolean = false,
        applyHarmonicRepetitionPolicy: Boolean = false,
        dissonanceTreatment: SchoenbergDissonanceTreatment = SchoenbergDissonanceTreatment.STRICT,
        sequencePolicy: SchoenbergSymbolicSequencePolicy? = null,
        totalLength: Int,
    ): Boolean {
        val before = prefix.last()
        val beforeTarget = before.toTarget(triads)
        val afterTarget = after.toTarget(triads)
        if (sequencePolicy?.allowsPrefix(prefix + after, totalLength) == false) return false
        if (
            after.secondaryFamily != null &&
            !SchoenbergSecondaryDominantChapter.allowsPreparation(before, after, key, triads)
        ) return false
        if (
            before.secondaryFamily != null &&
            after.augmentedSixthFamily != null
        ) return false
        if (
            before.secondaryFamily != null &&
            !SchoenbergSecondaryDominantChapter.allowsResolution(before, after, key, triads)
        ) return false
        if (before.augmentedSixthFamily != null && !allowsAugmentedSixthResolution(before, after)) {
            return false
        }
        // 同根音和弦（如 I 与 I6、I7）相邻只是同一和声的换位，缺乏进行意义——不把它们排在相邻槽位。
        if (before.degree == after.degree && before.rootAlteration == after.rootAlteration) return false
        // 下降进行必须只是临时方向：当第三个和弦加入时即可判断两步后的根音结果，提前剪掉仍为下降的分支。
        if (applyRootMotionDirection && prefix.size >= 2) {
            val twoStepsBefore = prefix[prefix.lastIndex - 1]
            if (
                SchoenbergRootMotionAndRepetitionChapter.classify(twoStepsBefore.degree, before.degree) ==
                SchoenbergRootMotionDirection.DESCENDING &&
                SchoenbergRootMotionAndRepetitionChapter.classify(twoStepsBefore.degree, after.degree) ==
                SchoenbergRootMotionDirection.DESCENDING
            ) return false
        }
        if (applyHarmonicRepetitionPolicy) {
            val prospectiveSlot = prefix.size
            if (prefix.withIndex().any { (slot, chord) ->
                    prospectiveSlot - slot < 3 &&
                        chord.degree == after.degree &&
                        sequencePolicy?.exemptsHarmonicRepetitionAt(slot, totalLength) != true &&
                        sequencePolicy?.exemptsHarmonicRepetitionAt(prospectiveSlot, totalLength) != true
                }
            ) return false
            val prospectiveProgression = before.degree to after.degree
            if (prefix.zipWithNext().withIndex().any { (slot, pair) ->
                    (pair.first.degree to pair.second.degree) == prospectiveProgression &&
                        sequencePolicy?.exemptsHarmonicRepetitionAt(slot, totalLength) != true &&
                        sequencePolicy?.exemptsHarmonicRepetitionAt(slot + 1, totalLength) != true &&
                        sequencePolicy?.exemptsHarmonicRepetitionAt(prospectiveSlot - 1, totalLength) != true &&
                        sequencePolicy?.exemptsHarmonicRepetitionAt(prospectiveSlot, totalLength) != true
                }
            ) return false
        }
        // 无共同音练习放开「相邻必须有共同音」；其余练习仍要求相邻至少一个共同音。
        if (requireAdjacentCommonTone &&
            before.secondaryFamily == null &&
            after.secondaryFamily == null &&
            before.augmentedSixthFamily == null &&
            after.augmentedSixthFamily == null &&
            beforeTarget.sonority.pitchClasses.none { it in afterTarget.sonority.pitchClasses }
        ) return false
        // 四部写作根本写不出来的相邻进行（如 I→ii7 必出平行五度）由求解器逐对探测、落到数据文件，
        // 这里读表规避，不把「无解」判据写死成启发式。见 [SchoenbergForbiddenTransitions] 与生成器。
        val forbiddenProfile = if (dissonanceTreatment == SchoenbergDissonanceTreatment.STRICT) {
            SchoenbergForbiddenTransitionProfile.STRICT
        } else {
            SchoenbergForbiddenTransitionProfile.FREE
        }
        if (SchoenbergForbiddenTransitions.isForbidden(before, after, minor, forbiddenProfile)) return false
        if (minor) {
            if (!allowsMinorStep(before, after, beforeTarget, afterTarget, key, dissonanceTreatment)) return false
        } else {
            if (!includeLeadingTriad && SchoenbergIntegratedVocabulary.isLeadingTriad(after, triads)) return false
            if (
                dissonanceTreatment != SchoenbergDissonanceTreatment.FREER &&
                SchoenbergIntegratedVocabulary.isLeadingTriad(after, triads)
            ) {
                val fifth = afterTarget.pitchClassFor(ChordTone.FIFTH) ?: return false
                if (fifth !in beforeTarget.sonority.pitchClasses) return false
            }
            // 导和弦解决到 III 是勋伯格该章的教学选择（不是「写不出」，故留在代码里）。至于导和弦五音的预备/解决
            // 声部可行性（如 vii°6/4 只能解决到 iii 根位、须由低音=F 的和弦预备），属四部写不出，由禁忌表数据驱动规避。
            if (
                dissonanceTreatment != SchoenbergDissonanceTreatment.FREER &&
                SchoenbergIntegratedVocabulary.isLeadingTriad(before, triads) &&
                after.degree != MEDIANT_DEGREE
            ) return false
        }
        if (
            SchoenbergIntegratedVocabulary.isSixFour(before) &&
            SchoenbergIntegratedVocabulary.isSixFour(after)
        ) return false
        if (SchoenbergIntegratedVocabulary.isSixFour(after)) {
            val root = afterTarget.pitchClassFor(ChordTone.ROOT)
            val bass = afterTarget.bassPitchClass
            if (root !in beforeTarget.sonority.pitchClasses && bass !in beforeTarget.sonority.pitchClasses) return false
        }
        if (
            SchoenbergIntegratedVocabulary.isSixFour(before) &&
            !SchoenbergIntegratedVocabulary.bassMovesByDiatonicStep(beforeTarget, afterTarget, key)
        ) return false
        if (
            after.arity == ChordArity.SEVENTH &&
            !after.isRootlessDominantNinth() &&
            after.augmentedSixthFamily == null &&
            dissonanceTreatment == SchoenbergDissonanceTreatment.STRICT &&
            SchoenbergIntegratedVocabulary.dissonantPitchClasses(afterTarget)
                .any { it !in beforeTarget.sonority.pitchClasses }
        ) return false
        if (
            after.arity == ChordArity.SEVENTH &&
            !after.isRootlessDominantNinth() &&
            after.augmentedSixthFamily == null &&
            dissonanceTreatment == SchoenbergDissonanceTreatment.CADENTIAL &&
            !(after.degree == DOMINANT_DEGREE &&
                after.seventhPosition == TextbookSeventhPosition.ROOT_POSITION) &&
            SchoenbergIntegratedVocabulary.dissonantPitchClasses(afterTarget)
                .any { it !in beforeTarget.sonority.pitchClasses }
        ) return false
        val cadentialSeventhException =
            dissonanceTreatment != SchoenbergDissonanceTreatment.STRICT &&
                before.degree == DOMINANT_DEGREE &&
                before.seventhPosition == TextbookSeventhPosition.ROOT_POSITION &&
                after.degree in setOf(SUBDOMINANT_DEGREE, SUBMEDIANT_DEGREE)
        if (
            before.arity == ChordArity.SEVENTH &&
            before.secondaryFamily == null &&
            before.augmentedSixthFamily == null &&
            dissonanceTreatment != SchoenbergDissonanceTreatment.FREER &&
            !cadentialSeventhException
        ) {
            // 七和弦按书中原则以根音上行四度（下行五度）解决，与导和弦解决到 III（VII→III 亦是根音上四度）同理。
            // 这是教学进行选择，不是四部「写不出」，故与 VII→III 一样留在枚举层，不进 forbidden-transitions 表。
            if ((after.degree - before.degree).mod(7) != PERFECT_FOURTH_UP_DEGREES) return false
            val resolutions = SchoenbergIntegratedVocabulary
                .dissonantPitchClasses(beforeTarget)
                .map { pitchClass ->
                val degree = key.scale.pitchClasses.indexOf(pitchClass)
                if (degree < 0) return false
                key.scale.pitchClasses[(degree + 6).mod(7)]
            }
            if (resolutions.any { it !in afterTarget.sonority.pitchClasses }) return false
        }
        return true
    }

    internal fun allowsAugmentedSixthResolution(
        before: SchoenbergSymbolicChord,
        after: SchoenbergSymbolicChord,
    ): Boolean {
        val targetDegree = before.appliedToDegree ?: return false
        if (before.augmentedSixthFamily == com.mecon.theory.constraint.AugmentedSixthFamily.HALF_DIMINISHED) {
            return after.secondaryFamily == null &&
                after.augmentedSixthFamily == null &&
                after.degree == targetDegree &&
                after.rootAlteration == -1 &&
                after.quality == ChordQuality.MAJOR
        }
        if (
            after.secondaryFamily != null ||
            after.augmentedSixthFamily != null ||
            after.rootAlteration != 0
        ) return false
        if (after.degree == targetDegree) return true
        if (before.augmentedSixthFamily != com.mecon.theory.constraint.AugmentedSixthFamily.GERMAN) {
            return false
        }
        return after.degree in setOf(
            (targetDegree + 4).mod(7) + 1,
            (targetDegree + 2).mod(7) + 1,
        )
    }

    fun followsHarmonicRepetitionPolicy(
        slots: List<SchoenbergSymbolicChord>,
        sequencePolicy: SchoenbergSymbolicSequencePolicy?,
    ): Boolean {
        val totalLength = slots.size
        val bodySlots = slots.indices.filterNot {
            sequencePolicy?.exemptsHarmonicRepetitionAt(it, totalLength) == true
        }
        val similarChordsAreSeparated = bodySlots.indices.all { earlierIndex ->
            (earlierIndex + 1 until bodySlots.size).none { laterIndex ->
                val earlier = bodySlots[earlierIndex]
                val later = bodySlots[laterIndex]
                later - earlier < 3 && slots[earlier].degree == slots[later].degree
            }
        }
        if (!similarChordsAreSeparated) return false
        val bodyProgressions = slots.zipWithNext().withIndex()
            .filterNot { (slot) ->
                sequencePolicy?.exemptsHarmonicRepetitionAt(slot, totalLength) == true ||
                    sequencePolicy?.exemptsHarmonicRepetitionAt(slot + 1, totalLength) == true
            }
            .map { (_, pair) -> pair.first.degree to pair.second.degree }
        return bodyProgressions.size == bodyProgressions.distinct().size
    }

    private val DIMINISHED_QUALITIES = setOf(
        ChordQuality.DIMINISHED,
        ChordQuality.DIMINISHED7,
        ChordQuality.HALF_DIMINISHED7,
    )
    private val AUGMENTED_QUALITIES = setOf(ChordQuality.AUGMENTED, ChordQuality.AUGMENTED7)

    /**
     * 小调相邻可达性（枚举提前剪枝），与 [SchoenbergMinorChapter] 的小调硬约束一一对应：把「后继必然写不出」的
     * 相邻对先排除。四部无解的相邻对仍由小调禁忌表数据驱动（[SchoenbergForbiddenTransitions.isForbidden]）。
     */
    private fun allowsMinorStep(
        before: SchoenbergSymbolicChord,
        after: SchoenbergSymbolicChord,
        beforeTarget: com.mecon.theory.constraint.ChordTarget,
        afterTarget: com.mecon.theory.constraint.ChordTarget,
        key: Key,
        dissonanceTreatment: SchoenbergDissonanceTreatment,
    ): Boolean {
        val beforePitchClasses = beforeTarget.sonority.pitchClasses
        // 减 / 增和弦的不协和五音须由前一和弦同声部预备 → before 必须已含该五音 PC。
        if (
            (afterTarget.quality in AUGMENTED_QUALITIES ||
                dissonanceTreatment != SchoenbergDissonanceTreatment.FREER &&
                afterTarget.quality in DIMINISHED_QUALITIES &&
                !after.isRootlessDominantNinth())
        ) {
            val fifth = afterTarget.pitchClassFor(ChordTone.FIFTH) ?: return false
            if (fifth !in beforePitchClasses) return false
        }
        // 增三和弦根音上行四度解决到 i 或 VI（增三和弦三个音等距，两种根音解读各解决到一个）。
        if (before.quality in AUGMENTED_QUALITIES &&
            after.degree != TONIC_DEGREE && after.degree != SUBMEDIANT_DEGREE
        ) return false
        // 旋律硬要求的相邻可达性：升六须保持或上行到导音、导音须保持或上行到主音——后继若既无解决音也无该变化音
        // 本身（无法保持），整对写不出。
        val afterPitchClasses = afterTarget.sonority.pitchClasses
        val raisedSixth = SchoenbergMinorChapter.raisedSixthPitchClass(key)
        val leadingTone = SchoenbergMinorChapter.leadingTonePitchClass(key)
        if (raisedSixth in beforePitchClasses &&
            leadingTone !in afterPitchClasses && raisedSixth !in afterPitchClasses
        ) return false
        if (leadingTone in beforePitchClasses &&
            SchoenbergMinorChapter.tonicPitchClass(key) !in afterPitchClasses && leadingTone !in afterPitchClasses
        ) return false
        return true
    }

}
