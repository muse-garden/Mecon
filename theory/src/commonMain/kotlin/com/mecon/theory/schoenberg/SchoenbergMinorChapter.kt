package com.mecon.theory.schoenberg

import com.mecon.api.primitive.PitchClass
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordQuality
import com.mecon.theory.Key
import com.mecon.theory.RuleId
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AvoidDoublingRequirement
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ChordToneNeighborDirection
import com.mecon.theory.constraint.ChordToneNeighborRequirement
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.textbook.DominantSeventhRules

/**
 * 小调综合练习的额外规则族（大调不适用，全部 gate 在 `key.mode == Mode.AEOLIAN` 的调用点）。
 *
 * 遵循 AGENTS.md「⚠️ 勋伯格和声练习」复用原则：规则一律用**不限 arity/转位**的 typed
 * [ChordToneNeighborRequirement] / [AvoidDoublingRequirement] / [ToneCompletenessRequirement] 表达，落在
 * 被禁忌表探测器投影的字段（`chordToneNeighbors` / `avoidDoublings` / `toneCompleteness`），因此无需改求解器，
 * 且新规则重刷后自动进入小调禁忌表。
 *
 * 三条硬旋律要求（书中「4#→5#、5#→6 必须；5、4 禁止进行到变化音」）与减/增三和弦的不协和音预备解决，
 * 都表达为 typed 相邻音约束。**减五度/增五度按性质选择器**（`qualities`）而非按度数，故 ii°/vii° 与其对应七和弦
 * （iiø7 / vii°7）一体适用；升六 / 导音的旋律解决按**源音级 PC**（`sourcePitchClasses`）选择，不论它在哪个
 * 和弦音角色都触发。
 */
object SchoenbergMinorChapter {
    val MELODY_RAISED_ASCENT_RULE_ID = RuleId("schoenberg.minor.melody.raised-ascent")
    val MELODY_NATURAL_NO_ALTERED_RULE_ID = RuleId("schoenberg.minor.melody.natural-no-altered")
    val DIMINISHED_PREPARATION_RULE_ID = RuleId("schoenberg.minor.diminished.preparation")
    val DIMINISHED_RESOLUTION_RULE_ID = RuleId("schoenberg.minor.diminished.resolution")
    val AUGMENTED_PREPARATION_RULE_ID = RuleId("schoenberg.minor.augmented.preparation")
    val ESSENTIAL_FIFTH_RULE_ID = RuleId("schoenberg.minor.dissonance.fifth-present")

    private val DIMINISHED_QUALITIES = setOf(
        ChordQuality.DIMINISHED,
        ChordQuality.DIMINISHED7,
        ChordQuality.HALF_DIMINISHED7,
    )
    private val AUGMENTED_QUALITIES = setOf(ChordQuality.AUGMENTED, ChordQuality.AUGMENTED7)

    /** 升六（旋律小调上行第 6 级，如 a 小调 F#）：自然小调第 6 级升高半音。 */
    internal fun raisedSixthPitchClass(key: Key): PitchClass = key.scale.pitchClasses[5].transpose(1)

    /** 导音（升七级，如 a 小调 G#）：自然小调第 7 级升高半音。 */
    internal fun leadingTonePitchClass(key: Key): PitchClass = key.scale.pitchClasses[6].transpose(1)

    /** 主音 PC。 */
    internal fun tonicPitchClass(key: Key): PitchClass = key.scale.pitchClasses[0]

    private fun naturalSixthPitchClass(key: Key): PitchClass = key.scale.pitchClasses[5]
    private fun naturalSeventhPitchClass(key: Key): PitchClass = key.scale.pitchClasses[6]

    // 源音级可能落在任一和弦音角色（导音既可作 V 的三音，也可作 vii° 的根音、III+ 的五音）；逐角色发射，
    // checkRelation 对「该角色 PC ≠ 源 PC」的 requirement 直接返回满足，故只有真正持有该 PC 的角色触发。
    private val SOURCE_ROLES = listOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH, ChordTone.SEVENTH)

    /**
     * 小调旋律进行硬约束：
     * - 升六 → **保持** 或 上行级进到导音（`必须`）。
     * - 导音 → **保持** 或 上行级进到主音（`必须`）。
     * - 自然第 6 / 第 7 级 → 只能进行到自然音级（`禁止进行到变化音`）；表达为「后继同声部须落在自然小调音级」，
     *   即候选集只含 alteration 0 的七个音级，从而排除升六 / 导音。方向不限（保持、上行、下行皆可，只是不得升高）。
     *
     * 「保持」（同声部持续到相邻和弦仍是该变化音）是允许的：倾向音的解决义务只在它**离开**时才触发，故候选集除
     * 上行解决音外还含变化音自身（delta 0）。升六用 `{degree6,7}+alt1`={F#,G#}；导音用 `degree1+alt{-1,0}`={G#,A}
     * ——后者刻意以「主音降半音」编码持续的导音，避免 `degree7+alt0` 把自然七级 G 也漏进候选而误判 G#→G。
     */
    internal fun minorMelodyNeighborRequirements(
        key: Key,
        window: SlotWindow,
        sourceIdentityKeys: Set<String> = emptySet(),
    ): List<ChordToneNeighborRequirement> = buildList {
        addAll(
            resolveOrHold(
                window = window,
                sourcePitchClass = raisedSixthPitchClass(key),
                sourceIdentityKeys = sourceIdentityKeys,
                candidateDegrees = setOf(6, 7),
                candidateAlterations = setOf(1),
                ruleId = MELODY_RAISED_ASCENT_RULE_ID,
                satisfied = "升高的第六级保持或上行级进到导音。",
                violated = "升高的第六级只能保持或上行级进到导音。",
            )
        )
        addAll(
            resolveOrHold(
                window = window,
                sourcePitchClass = leadingTonePitchClass(key),
                sourceIdentityKeys = sourceIdentityKeys,
                candidateDegrees = setOf(1),
                candidateAlterations = setOf(-1, 0),
                ruleId = MELODY_RAISED_ASCENT_RULE_ID,
                satisfied = "导音保持或上行级进到主音。",
                violated = "导音只能保持或上行级进到主音。",
            )
        )
        addAll(
            stayNatural(
                window = window,
                sourcePitchClass = naturalSixthPitchClass(key),
                sourceIdentityKeys = sourceIdentityKeys,
                ruleId = MELODY_NATURAL_NO_ALTERED_RULE_ID,
                satisfied = "自然第六级没有进行到变化音。",
                violated = "自然第六级禁止进行到变化音（升六 / 导音）。",
            )
        )
        addAll(
            stayNatural(
                window = window,
                sourcePitchClass = naturalSeventhPitchClass(key),
                sourceIdentityKeys = sourceIdentityKeys,
                ruleId = MELODY_NATURAL_NO_ALTERED_RULE_ID,
                satisfied = "自然第七级没有进行到变化音。",
                violated = "自然第七级禁止进行到变化音（升六 / 导音）。",
            )
        )
    }

    // 关键：源侧必须用 `sourceSelector`（含 [TargetSelector.requiredPitchClasses]）把规则限定在**确实含源音级 PC 的
    // 和弦**上。搜索剪枝路径（ChordTargetRelationConstraints.frameRelationAllows）以 `sourceSelector` 短路，只有当
    // 源和弦匹配时才校验「后继须含解决音」；若只靠 `sourcePitchClasses` 而 selector 放空，则规则会误挂到不含该音的
    // 和弦上、把「后继无解决音」误判为禁忌（曾令 i→III 等全部进禁忌表）。`sourcePitchClasses` 仍保留以在
    // checkRelation 内锁定具体声部。
    private fun resolveOrHold(
        window: SlotWindow,
        sourcePitchClass: PitchClass,
        sourceIdentityKeys: Set<String>,
        candidateDegrees: Set<Int>,
        candidateAlterations: Set<Int>,
        ruleId: RuleId,
        satisfied: String,
        violated: String,
    ): List<ChordToneNeighborRequirement> =
        SOURCE_ROLES.map { role ->
            ChordToneNeighborRequirement(
                window = window,
                sourceTone = role,
                direction = ChordToneNeighborDirection.NEXT,
                candidateScaleDegrees = candidateDegrees,
                candidateAlterations = candidateAlterations,
                // 0 = 保持（同声部持续该变化音），1 = 上行级进解决；其余方向由候选集本身排除。
                allowedDiatonicStepDeltas = setOf(0, 1),
                sourceSelector = TargetSelector(
                    requiredPitchClasses = setOf(sourcePitchClass),
                    identityKeys = sourceIdentityKeys,
                ),
                sourcePitchClasses = setOf(sourcePitchClass),
                ruleId = ruleId,
                explanation = ConstraintExplanation(satisfied, violated),
            )
        }

    private fun stayNatural(
        window: SlotWindow,
        sourcePitchClass: PitchClass,
        sourceIdentityKeys: Set<String>,
        ruleId: RuleId,
        satisfied: String,
        violated: String,
    ): List<ChordToneNeighborRequirement> =
        SOURCE_ROLES.map { role ->
            ChordToneNeighborRequirement(
                window = window,
                sourceTone = role,
                direction = ChordToneNeighborDirection.NEXT,
                candidateScaleDegrees = (1..7).toSet(),
                candidateAlterations = setOf(0),
                allowedDiatonicStepDeltas = emptySet(),
                sourceSelector = TargetSelector(
                    requiredPitchClasses = setOf(sourcePitchClass),
                    identityKeys = sourceIdentityKeys,
                ),
                sourcePitchClasses = setOf(sourcePitchClass),
                ruleId = ruleId,
                explanation = ConstraintExplanation(satisfied, violated),
            )
        }

    /**
     * 减三和弦（含对应减 / 半减七和弦）的减五度：同声部保持预备 + 下行级进解决。按 `qualities` 选择器不限度数/转位，
     * ii° 与 vii°、iiø7 与 vii°7 一体适用（书中三和弦规则在对应七和弦一体适用）。
     */
    internal fun minorDiminishedNeighborRequirements(
        window: SlotWindow,
        sourceIdentityKeys: Set<String> = emptySet(),
    ): List<ChordToneNeighborRequirement> = listOf(
        heldPreparation(
            window = window,
            qualities = DIMINISHED_QUALITIES,
            ruleId = DIMINISHED_PREPARATION_RULE_ID,
            satisfied = "减三和弦的减五度已由前一和弦同声部保持预备。",
            violated = "减三和弦的减五度必须由前一和弦同声部保持预备。",
            sourceIdentityKeys = sourceIdentityKeys,
        ),
        ChordToneNeighborRequirement(
            window = window,
            sourceTone = ChordTone.FIFTH,
            direction = ChordToneNeighborDirection.NEXT,
            candidateScaleDegrees = (1..7).toSet(),
            candidateAlterations = setOf(0),
            allowedDiatonicStepDeltas = setOf(-1),
            sourceSelector = if (sourceIdentityKeys.isEmpty()) {
                TargetSelector(qualities = DIMINISHED_QUALITIES)
            } else {
                TargetSelector(identityKeys = sourceIdentityKeys)
            },
            ruleId = DIMINISHED_RESOLUTION_RULE_ID,
            explanation = ConstraintExplanation(
                "减三和弦的减五度已下行级进解决。",
                "减三和弦的减五度必须下行级进解决。",
            ),
        ),
    )

    /**
     * 增三和弦（含增七和弦）的增五度：同声部保持预备。增五度即导音，其上行解决由 [minorMelodyNeighborRequirements]
     * 的「导音→主音」统一拥有，本处不再重复一份解决约束（避免同一规则两处并存）。
     */
    internal fun minorAugmentedNeighborRequirements(window: SlotWindow): List<ChordToneNeighborRequirement> = listOf(
        heldPreparation(
            window = window,
            qualities = AUGMENTED_QUALITIES,
            ruleId = AUGMENTED_PREPARATION_RULE_ID,
            satisfied = "增三和弦的增五度已由前一和弦同声部保持预备。",
            violated = "增三和弦的增五度必须由前一和弦同声部保持预备。",
        ),
    )

    private fun heldPreparation(
        window: SlotWindow,
        qualities: Set<ChordQuality>,
        ruleId: RuleId,
        satisfied: String,
        violated: String,
        sourceIdentityKeys: Set<String> = emptySet(),
    ): ChordToneNeighborRequirement =
        ChordToneNeighborRequirement(
            window = window,
            sourceTone = ChordTone.FIFTH,
            direction = ChordToneNeighborDirection.PREVIOUS,
            candidateScaleDegrees = (1..7).toSet(),
            candidateAlterations = setOf(0, 1),
            allowedDiatonicStepDeltas = setOf(0),
            sourceSelector = if (sourceIdentityKeys.isEmpty()) {
                TargetSelector(qualities = qualities)
            } else {
                TargetSelector(identityKeys = sourceIdentityKeys)
            },
            ruleId = ruleId,
            explanation = ConstraintExplanation(satisfied, violated),
        )

    /** 减 / 增和弦禁重复不协和的五音（减五度 / 增五度）。 */
    internal fun minorDissonanceAvoidDoublings(length: Int): List<AvoidDoublingRequirement> =
        (0 until length).flatMap { slot ->
            listOf(
                AvoidDoublingRequirement(
                    slot = slot,
                    tone = ChordTone.FIFTH,
                    required = true,
                    selector = TargetSelector(qualities = DIMINISHED_QUALITIES),
                ),
                AvoidDoublingRequirement(
                    slot = slot,
                    tone = ChordTone.FIFTH,
                    required = true,
                    selector = TargetSelector(qualities = AUGMENTED_QUALITIES),
                ),
            )
        }

    /** 减 / 增和弦的五音是决定性的不协和音，必须在场（不可省略）。 */
    internal fun minorEssentialFifthCompleteness(window: SlotWindow): ToneCompletenessRequirement =
        ToneCompletenessRequirement(
            window = window,
            requiredTones = setOf(ChordTone.FIFTH),
            selector = TargetSelector(qualities = DIMINISHED_QUALITIES + AUGMENTED_QUALITIES),
            required = true,
            ruleId = ESSENTIAL_FIFTH_RULE_ID,
            explanation = ConstraintExplanation(
                "减 / 增三和弦保留了决定性的不协和五音。",
                "减 / 增三和弦不可省略不协和的五音。",
            ),
        )

    /**
     * 小调七和弦词汇表，剔除**七音为 #4 / #5（升六 / 导音）** 的和弦：这类七音须下行解决，而升六 / 导音的旋律要求
     * 是上行解决，二者冲突，教材注明现阶段还不能使用。其余按度数生成（复用 [DominantSeventhRules.seventhChordInKey]）。
     */
    internal fun minorSeventhVocabulary(key: Key): List<SchoenbergSymbolicChord> {
        val excludedSevenths = setOf(raisedSixthPitchClass(key), leadingTonePitchClass(key))
        return (1..7).flatMap { degree ->
            val chord = DominantSeventhRules.seventhChordInKey(key, degree)
            val seventhPitchClass = chord.chord.pitchClasses.getOrNull(3)
            if (seventhPitchClass in excludedSevenths) return@flatMap emptyList()
            com.mecon.theory.textbook.TextbookSeventhPosition.entries.map { position ->
                SchoenbergSymbolicChord(
                    degree = degree,
                    quality = chord.quality,
                    arity = ChordArity.SEVENTH,
                    seventhPosition = position,
                )
            }
        }
    }
}
