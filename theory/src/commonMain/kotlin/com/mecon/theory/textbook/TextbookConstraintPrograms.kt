package com.mecon.theory.textbook

import com.mecon.theory.ChordArity
import com.mecon.theory.SlotWindow
import com.mecon.theory.constraint.AvoidScaleDegreeDoublingRequirement
import com.mecon.theory.constraint.ChordTone
import com.mecon.theory.constraint.ConstraintExplanation
import com.mecon.theory.constraint.ConstraintProgram
import com.mecon.theory.constraint.DoublingRequirement
import com.mecon.theory.constraint.SlotDomain
import com.mecon.theory.constraint.TargetSelector
import com.mecon.theory.constraint.ToneCompletenessRequirement
import com.mecon.theory.constraint.defaultChordRuleModules

/**
 * Textbook 三和弦章节的生成期约束包。规则 finding 由规则模块无条件计算；本对象只声明哪些建议
 * 在当前练习级别提升为候选硬约束。
 */
data class TextbookTriadConstraintPreset(
    val completeChordTonePositions: Set<TextbookTriadPosition> = emptySet(),
    val requiredDoublings: Map<TextbookTriadPosition, ChordTone> = emptyMap(),
    val avoidLeadingToneDoublingPositions: Set<TextbookTriadPosition> = TextbookTriadPosition.entries.toSet(),
) {
    companion object {
        val GENERAL = TextbookTriadConstraintPreset(
            requiredDoublings = mapOf(TextbookTriadPosition.SECOND_INVERSION to ChordTone.BASS),
        )

        val INTRODUCTORY = TextbookTriadConstraintPreset(
            completeChordTonePositions = TextbookTriadPosition.entries.toSet(),
            requiredDoublings = mapOf(
                TextbookTriadPosition.ROOT_POSITION to ChordTone.ROOT,
                TextbookTriadPosition.SECOND_INVERSION to ChordTone.BASS,
            ),
        )
    }
}

data class TextbookTriadConstraintRequirements(
    val toneCompleteness: List<ToneCompletenessRequirement>,
    val doublings: List<DoublingRequirement>,
    val avoidScaleDegreeDoublings: List<AvoidScaleDegreeDoublingRequirement>,
)

/** 将 textbook 三和弦章节配置编译成通用约束程序；不再保留章节专属候选工厂。 */
fun TextbookTriadWritingProblem.toConstraintProgram(): ConstraintProgram {
    val requirements = constraintPreset.requirementsFor(slots, finalTonicMayOmitFifth)
    return ConstraintProgram.fromRequirements(
        key = key,
        slotDomains = slots.map { slot ->
            SlotDomain(slot.allowedPositions.map { position -> TextbookTriadTarget(slot.triad, position) })
        },
        configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
            ruleProfile = ruleProfile,
            toneCompleteness = requirements.toneCompleteness,
            doublings = requirements.doublings,
            avoidScaleDegreeDoublings = requirements.avoidScaleDegreeDoublings,
            rangeProfile = rangeProfile,
            searchConfig = searchConfig,
            finalTonicMayOmitFifth = finalTonicMayOmitFifth,
            ruleModules = defaultChordRuleModules(
                key = key,
                slotCount = slots.size,
                finalTonicMayOmitFifth = finalTonicMayOmitFifth,
            ),

        ),
    )
}

/** 章节 preset 到 requirement 的唯一编译入口，供 textbook 门面与公开 spec 编译共用。 */
fun TextbookTriadConstraintPreset.requirementsFor(
    slots: List<TextbookTriadWritingSlot>,
    finalTonicMayOmitFifth: Boolean = true,
): TextbookTriadConstraintRequirements {
    val toneCompleteness = buildList {
        slots.forEachIndexed { slotIndex, slot ->
            val isFinalTonic = finalTonicMayOmitFifth &&
                slotIndex == slots.lastIndex &&
                slot.triad.degree == TONIC_DEGREE
            addCompletenessForPosition(
                slotIndex,
                slot,
                TextbookTriadPosition.ROOT_POSITION,
                TextbookTriadPosition.ROOT_POSITION in completeChordTonePositions && !isFinalTonic,
            )
            addCompletenessForPosition(
                slotIndex,
                slot,
                TextbookTriadPosition.FIRST_INVERSION,
                TextbookTriadPosition.FIRST_INVERSION in completeChordTonePositions,
            )
            addCompletenessForPosition(
                slotIndex,
                slot,
                TextbookTriadPosition.SECOND_INVERSION,
                TextbookTriadPosition.SECOND_INVERSION in completeChordTonePositions,
            )
            addSoftCompletenessForPosition(slotIndex, slot, TextbookTriadPosition.ROOT_POSITION, !isFinalTonic)
            addSoftCompletenessForPosition(slotIndex, slot, TextbookTriadPosition.FIRST_INVERSION, true)
            addSoftCompletenessForPosition(slotIndex, slot, TextbookTriadPosition.SECOND_INVERSION, true)
        }
    }
    val doublings = buildList {
        slots.forEachIndexed { slotIndex, slot ->
            val isFinalTonic = finalTonicMayOmitFifth &&
                slotIndex == slots.lastIndex &&
                slot.triad.degree == TONIC_DEGREE
            slot.allowedPositions.forEach { position ->
                val defaultTone = when (position) {
                    TextbookTriadPosition.ROOT_POSITION -> ChordTone.ROOT
                    TextbookTriadPosition.FIRST_INVERSION -> null
                    TextbookTriadPosition.SECOND_INVERSION -> ChordTone.BASS
                } ?: return@forEach
                val configuredTone = requiredDoublings[position] ?: defaultTone
                add(
                    DoublingRequirement(
                        slot = slotIndex,
                        tone = configuredTone,
                        required = requiredDoublings[position] != null &&
                            !(position == TextbookTriadPosition.ROOT_POSITION && isFinalTonic),
                        selector = positionSelector(position),
                        ruleId = when (position) {
                            TextbookTriadPosition.ROOT_POSITION -> RootPositionTriadRules.EXPECT_ROOT_DOUBLING
                            TextbookTriadPosition.FIRST_INVERSION -> null
                            TextbookTriadPosition.SECOND_INVERSION -> SecondInversionTriadRules.EXPECT_BASS_DOUBLING
                        },
                        explanation = ConstraintExplanation(
                            satisfied = "重复音要求满足。",
                            violated = when (position) {
                                TextbookTriadPosition.ROOT_POSITION -> "原位三和弦通常重复根音。"
                                TextbookTriadPosition.FIRST_INVERSION -> "第一转位重复音可自由选择。"
                                TextbookTriadPosition.SECOND_INVERSION -> "四六和弦通常重复低音。"
                            },
                        ),
                    )
                )
            }
        }
    }
    val avoidLeadingToneDoublings = buildList {
        slots.forEachIndexed { slotIndex, slot ->
            slot.allowedPositions.forEach { position ->
                if (position in avoidLeadingToneDoublingPositions) {
                    add(
                        AvoidScaleDegreeDoublingRequirement(
                            slot = slotIndex,
                            degree = LEADING_TONE_DEGREE,
                            required = true,
                            selector = positionSelector(position),
                            ruleId = when (position) {
                                TextbookTriadPosition.ROOT_POSITION -> RootPositionTriadRules.LEADING_TONE_DOUBLED
                                TextbookTriadPosition.FIRST_INVERSION -> FirstInversionTriadRules.LEADING_TONE_DOUBLED
                                TextbookTriadPosition.SECOND_INVERSION -> SecondInversionTriadRules.LEADING_TONE_DOUBLED
                            },
                            explanation = ConstraintExplanation(
                                satisfied = "导音未被重复。",
                                violated = "7级音不应重复。",
                            ),
                        )
                    )
                }
            }
        }
    }
    return TextbookTriadConstraintRequirements(
        toneCompleteness = toneCompleteness,
        doublings = doublings,
        avoidScaleDegreeDoublings = avoidLeadingToneDoublings,
    )
}

/** 将 textbook 七和弦章节配置编译成通用约束程序。 */
fun TextbookSeventhWritingProblem.toConstraintProgram(): ConstraintProgram =
    ConstraintProgram.fromRequirements(
        key = key,
        slotDomains = slots.map { slot ->
            SlotDomain(slot.allowedPositions.map { position -> TextbookSeventhTarget(slot.chord, position) })
        },
            configuration = com.mecon.theory.constraint.ConstraintRequirementConfiguration(
            ruleProfile = ruleProfile,
            toneCompleteness = slots.flatMapIndexed { slotIndex, slot ->
                buildList {
                    if (slot.chord.chord.pitchClasses.size >= SEVENTH_CHORD_SIZE) {
                        add(
                            ToneCompletenessRequirement(
                                window = SlotWindow(slotIndex, slotIndex),
                                requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
                                selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                                ruleId = DominantSeventhRules.ROOT_OR_SEVENTH_OMITTED,
                                explanation = ConstraintExplanation(
                                    satisfied = "七和弦保留根音和七音。",
                                    violated = "七和弦不应省略根音或七音。",
                                ),
                            )
                        )
                    }
                    when (slot.fifthConstraint) {
                        SeventhFifthConstraint.REQUIRE_FIFTH -> add(
                            ToneCompletenessRequirement(
                                window = SlotWindow(slotIndex, slotIndex),
                                requiredTones = setOf(
                                    ChordTone.ROOT,
                                    ChordTone.THIRD,
                                    ChordTone.FIFTH,
                                    ChordTone.SEVENTH,
                                ),
                                selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                            )
                        )
                        SeventhFifthConstraint.OMIT_FIFTH -> add(
                            ToneCompletenessRequirement(
                                window = SlotWindow(slotIndex, slotIndex),
                                requiredTones = setOf(ChordTone.ROOT, ChordTone.SEVENTH),
                                omittedTones = setOf(ChordTone.FIFTH),
                                selector = TargetSelector(arities = setOf(ChordArity.SEVENTH)),
                            )
                        )
                        null -> Unit
                    }
                }
            },
            rangeProfile = rangeProfile,
            searchConfig = searchConfig,
            ruleModules = defaultChordRuleModules(key = key, slotCount = slots.size),

        ),
    )

private fun MutableList<ToneCompletenessRequirement>.addSoftCompletenessForPosition(
    slotIndex: Int,
    slot: TextbookTriadWritingSlot,
    position: TextbookTriadPosition,
    enabled: Boolean,
) {
    if (!enabled || position !in slot.allowedPositions) return
    add(
        ToneCompletenessRequirement(
            window = SlotWindow(slotIndex, slotIndex),
            requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH),
            selector = positionSelector(position),
            required = false,
            ruleId = when (position) {
                TextbookTriadPosition.ROOT_POSITION -> RootPositionTriadRules.MISSING_CHORD_TONE
                TextbookTriadPosition.FIRST_INVERSION -> FirstInversionTriadRules.MISSING_CHORD_TONE
                TextbookTriadPosition.SECOND_INVERSION -> SecondInversionTriadRules.MISSING_CHORD_TONE
            },
            explanation = ConstraintExplanation(
                satisfied = "和弦音完整。",
                violated = "四声部三和弦通常不应省略和弦音。",
            ),
        )
    )
}

private fun MutableList<ToneCompletenessRequirement>.addCompletenessForPosition(
    slotIndex: Int,
    slot: TextbookTriadWritingSlot,
    position: TextbookTriadPosition,
    enabled: Boolean,
) {
    if (!enabled || position !in slot.allowedPositions) return
    add(
        ToneCompletenessRequirement(
            window = SlotWindow(slotIndex, slotIndex),
            requiredTones = setOf(ChordTone.ROOT, ChordTone.THIRD, ChordTone.FIFTH),
            selector = positionSelector(position),
        )
    )
}

private fun positionSelector(position: TextbookTriadPosition): TargetSelector =
    TargetSelector(
        inversions = setOf(position.ordinal),
        arities = setOf(ChordArity.TRIAD),
    )

private const val TONIC_DEGREE = 1
private const val LEADING_TONE_DEGREE = 7
private const val SEVENTH_CHORD_SIZE = 4
