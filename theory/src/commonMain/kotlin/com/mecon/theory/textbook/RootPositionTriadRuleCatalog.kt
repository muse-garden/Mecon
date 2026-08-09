package com.mecon.theory.textbook

import com.mecon.theory.ChapterId
import com.mecon.theory.ChordPattern
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeyContext
import com.mecon.theory.RelationKind
import com.mecon.theory.RootMotion
import com.mecon.theory.RuleCatalogProvider
import com.mecon.theory.RuleDegreePair
import com.mecon.theory.RuleDescriptor
import com.mecon.theory.RuleExampleInputOverride
import com.mecon.theory.RuleId
import com.mecon.theory.RuleKeyModeConstraint
import com.mecon.theory.RuleKind
import com.mecon.theory.RuleRelation
import com.mecon.theory.RuleScene
import com.mecon.theory.SceneRole
import com.mecon.theory.SlotChordSpec

object RootPositionTriadRuleCatalog : RuleCatalogProvider {
    override val chapterId: ChapterId = ROOT_POSITION_TRIAD_CHAPTER

    val CHAPTER = RuleId("textbook.root-position-triad")
    val FOURTH_FIFTH = RuleId("textbook.root-position-triad.fourth-fifth")
    val THIRD_SIXTH = RuleId("textbook.root-position-triad.third-sixth")
    val SECOND_SEVENTH = RuleId("textbook.root-position-triad.second-seventh")

    override val descriptors: List<RuleDescriptor> = listOf(
        descriptor(CHAPTER, "rule.rootPositionTriad", RuleKind.GROUP, parent = null, selectable = false),
        descriptor(RootPositionTriadRules.SAME_CHORD_REPETITION, "rule.rootPositionTriad.sameChord", RuleKind.PATTERN, CHAPTER),
        descriptor(FOURTH_FIFTH, "rule.rootPositionTriad.fourthFifth", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(
            RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
            "rule.rootPositionTriad.fourthFifth.commonTone",
            RuleKind.PATTERN,
            FOURTH_FIFTH,
        ),
        descriptor(
            RootPositionTriadRules.FOURTH_FIFTH_NO_COMMON_TONE,
            "rule.rootPositionTriad.fourthFifth.noCommonTone",
            RuleKind.PATTERN,
            FOURTH_FIFTH,
        ),
        descriptor(
            RootPositionTriadRules.FOURTH_FIFTH_OPEN_CLOSE_SHIFT,
            "rule.rootPositionTriad.fourthFifth.openCloseShift",
            RuleKind.PATTERN,
            FOURTH_FIFTH,
        ),
        descriptor(
            RootPositionTriadRules.INNER_LEADING_TONE_LEAP,
            "rule.rootPositionTriad.innerLeadingToneLeap",
            RuleKind.TENDENCY,
            FOURTH_FIFTH,
        ),
        descriptor(THIRD_SIXTH, "rule.rootPositionTriad.thirdSixth", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(
            RootPositionTriadRules.THIRD_SIXTH_COMMON_TONES,
            "rule.rootPositionTriad.thirdSixth.commonTones",
            RuleKind.PATTERN,
            THIRD_SIXTH,
        ),
        descriptor(SECOND_SEVENTH, "rule.rootPositionTriad.secondSeventh", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(
            RootPositionTriadRules.SECOND_SEVENTH_CONTRARY_SMOOTH,
            "rule.rootPositionTriad.secondSeventh.contrarySmooth",
            RuleKind.PATTERN,
            SECOND_SEVENTH,
        ),
        descriptor(
            RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE,
            "rule.rootPositionTriad.majorDominantSixthInnerLeadingTone",
            RuleKind.TENDENCY,
            SECOND_SEVENTH,
        ),
        descriptor(
            RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH,
            "rule.rootPositionTriad.minorRaisedFifthToFourth",
            RuleKind.CONSTRAINT,
            SECOND_SEVENTH,
            demonstrableAsViolation = true,
        ),
        descriptor(RootPositionTriadRules.NON_CHORD_TONE, "rule.rootPositionTriad.nonChordTone", RuleKind.CONSTRAINT, CHAPTER, false),
        descriptor(RootPositionTriadRules.MISSING_CHORD_TONE, "rule.rootPositionTriad.missingChordTone", RuleKind.CONSTRAINT, CHAPTER, false),
        descriptor(RootPositionTriadRules.EXPECT_ROOT_DOUBLING, "rule.rootPositionTriad.expectRootDoubling", RuleKind.CONSTRAINT, CHAPTER, false),
        descriptor(RootPositionTriadRules.LEADING_TONE_DOUBLED, "rule.rootPositionTriad.leadingToneDoubled", RuleKind.CONSTRAINT, CHAPTER, false),
        descriptor(RootPositionTriadRules.FINAL_TONIC_SPACING, "rule.rootPositionTriad.finalTonicSpacing", RuleKind.CONSTRAINT, CHAPTER, false),
    )

    override val relations: List<RuleRelation> = listOf(
        RuleRelation(
            RelationKind.EXCLUSIVE_GROUP,
            listOf(
                RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
                RootPositionTriadRules.FOURTH_FIFTH_NO_COMMON_TONE,
                RootPositionTriadRules.FOURTH_FIFTH_OPEN_CLOSE_SHIFT,
            ),
        ),
        RuleRelation(
            RelationKind.REQUIRES,
            listOf(
                RootPositionTriadRules.INNER_LEADING_TONE_LEAP,
                RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
                RootPositionTriadRules.FOURTH_FIFTH_NO_COMMON_TONE,
                RootPositionTriadRules.FOURTH_FIFTH_OPEN_CLOSE_SHIFT,
            ),
        ),
    )

    override fun scenes(ruleId: RuleId): List<RuleScene> =
        when (ruleId) {
            RootPositionTriadRules.SAME_CHORD_REPETITION ->
                listOf(rootMotionScene(ruleId, setOf(0), "同和弦反复只适用于前后同级和弦。"))
            RootPositionTriadRules.FOURTH_FIFTH_COMMON_TONE,
            RootPositionTriadRules.FOURTH_FIFTH_NO_COMMON_TONE,
            RootPositionTriadRules.FOURTH_FIFTH_OPEN_CLOSE_SHIFT,
            RootPositionTriadRules.INNER_LEADING_TONE_LEAP ->
                listOf(rootMotionScene(ruleId, setOf(3), "根音四（五）度关系规则只适用于根音相距四/五度的和弦对。"))
            RootPositionTriadRules.THIRD_SIXTH_COMMON_TONES ->
                listOf(rootMotionScene(ruleId, setOf(2), "根音三（六）度关系规则只适用于根音相距三/六度的和弦对。"))
            RootPositionTriadRules.SECOND_SEVENTH_CONTRARY_SMOOTH ->
                listOf(rootMotionScene(ruleId, setOf(1), "根音二（七）度关系规则只适用于根音相距二/七度的和弦对。"))
            RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 2..2,
                        facets = listOf(
                            ChordPattern(listOf(SlotChordSpec(degrees = setOf(5)), SlotChordSpec(degrees = setOf(6)))),
                            KeyContext(RuleKeyModeConstraint.MAJOR),
                        ),
                        unavailableReason = "大调属和弦到六级的内声部导音跳进只适用于 V→VI。",
                    )
                )
            RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 2..2,
                        facets = listOf(
                            ChordPattern(
                                listOf(
                                    SlotChordSpec(degrees = setOf(5), qualities = setOf(ChordQuality.MAJOR)),
                                    SlotChordSpec(degrees = setOf(6)),
                                )
                            ),
                            KeyContext(RuleKeyModeConstraint.MINOR),
                        ),
                        role = SceneRole.DEMONSTRATION,
                        unavailableReason = "小调升五级不可进行到四音级的禁则只适用于小调 V→VI。",
                    )
                )
            else -> emptyList()
        }

    override fun inputOverride(ruleId: RuleId): RuleExampleInputOverride =
        when (ruleId) {
            RootPositionTriadRules.MAJOR_DOMINANT_TO_SIXTH_INNER_LEADING_TONE ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(5, 6)),
                    keyMode = RuleKeyModeConstraint.MAJOR,
                )
            RootPositionTriadRules.MINOR_RAISED_FIFTH_TO_FOURTH ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(5, 6)),
                    keyMode = RuleKeyModeConstraint.MINOR,
                    degreeQualities = mapOf(5 to ChordQuality.MAJOR),
                    defaultDemonstration = true,
                )
            else -> RuleExampleInputOverride()
        }

    private fun rootMotionScene(ruleId: RuleId, steps: Set<Int>, reason: String): RuleScene =
        RuleScene(
            ruleId = ruleId,
            window = 2..2,
            facets = listOf(RootMotion(steps)),
            unavailableReason = reason,
        )

    private fun descriptor(
        id: RuleId,
        titleKey: String,
        kind: RuleKind,
        parent: RuleId?,
        selectable: Boolean = true,
        demonstrableAsViolation: Boolean = false,
    ): RuleDescriptor =
        RuleDescriptor(
            id = id,
            titleKey = titleKey,
            descriptionKey = "$titleKey.description",
            kind = kind,
            parent = parent,
            chapter = chapterId,
            selectable = selectable,
            demonstrableAsViolation = demonstrableAsViolation,
        )
}

val ROOT_POSITION_TRIAD_CHAPTER = ChapterId("textbook.root-position-triad")
