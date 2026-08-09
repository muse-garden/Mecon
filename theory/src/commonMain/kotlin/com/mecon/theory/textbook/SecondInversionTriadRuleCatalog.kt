package com.mecon.theory.textbook

import com.mecon.theory.BassMotion
import com.mecon.theory.BassMotionKind
import com.mecon.theory.ChapterId
import com.mecon.theory.ChordPattern
import com.mecon.theory.RelationKind
import com.mecon.theory.RootMotion
import com.mecon.theory.RuleCatalogProvider
import com.mecon.theory.RuleDegreePair
import com.mecon.theory.RuleDescriptor
import com.mecon.theory.RuleExampleInputOverride
import com.mecon.theory.RuleId
import com.mecon.theory.RuleKind
import com.mecon.theory.RuleRelation
import com.mecon.theory.RuleScene
import com.mecon.theory.SlotChordSpec

object SecondInversionTriadRuleCatalog : RuleCatalogProvider {
    override val chapterId: ChapterId = SECOND_INVERSION_TRIAD_CHAPTER

    val CHAPTER = RuleId("textbook.second-inversion-triad")
    val STANDARD_USES = RuleId("textbook.second-inversion-triad.standard-uses")
    val VERTICALITY = RuleId("textbook.second-inversion-triad.verticality")

    override val descriptors: List<RuleDescriptor> = listOf(
        descriptor(CHAPTER, "rule.secondInversionTriad", RuleKind.GROUP, parent = null, selectable = false),
        descriptor(STANDARD_USES, "rule.secondInversionTriad.standardUses", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(
            SecondInversionTriadRules.CADENTIAL_SIX_FOUR,
            "rule.secondInversionTriad.cadentialSixFour",
            RuleKind.PATTERN,
            STANDARD_USES,
        ),
        descriptor(
            SecondInversionTriadRules.PASSING_SIX_FOUR,
            "rule.secondInversionTriad.passingSixFour",
            RuleKind.PATTERN,
            STANDARD_USES,
        ),
        descriptor(
            SecondInversionTriadRules.PEDAL_SIX_FOUR,
            "rule.secondInversionTriad.pedalSixFour",
            RuleKind.PATTERN,
            STANDARD_USES,
        ),
        descriptor(
            SecondInversionTriadRules.SAME_CHORD_INVERSION_INSERTION,
            "rule.secondInversionTriad.sameChordInversionInsertion",
            RuleKind.PATTERN,
            STANDARD_USES,
        ),
        descriptor(VERTICALITY, "rule.secondInversionTriad.verticality", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(
            SecondInversionTriadRules.SECOND_INVERSION_DECORATION,
            "rule.secondInversionTriad.decorativeUse",
            RuleKind.PATTERN,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            SecondInversionTriadRules.UNSUPPORTED_SECOND_INVERSION,
            "rule.secondInversionTriad.unsupportedSecondInversion",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            SecondInversionTriadRules.UPPER_VOICE_LEAP,
            "rule.secondInversionTriad.upperVoiceLeap",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            SecondInversionTriadRules.EXPECT_BASS_DOUBLING,
            "rule.secondInversionTriad.expectBassDoubling",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            SecondInversionTriadRules.LEADING_TONE_DOUBLED,
            "rule.secondInversionTriad.leadingToneDoubled",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            SecondInversionTriadRules.NON_CHORD_TONE,
            "rule.secondInversionTriad.nonChordTone",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            SecondInversionTriadRules.MISSING_CHORD_TONE,
            "rule.secondInversionTriad.missingChordTone",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
    )

    override val relations: List<RuleRelation> = listOf(
        RuleRelation(
            RelationKind.EXCLUSIVE_GROUP,
            listOf(
                SecondInversionTriadRules.CADENTIAL_SIX_FOUR,
                SecondInversionTriadRules.PASSING_SIX_FOUR,
                SecondInversionTriadRules.PEDAL_SIX_FOUR,
                SecondInversionTriadRules.SAME_CHORD_INVERSION_INSERTION,
            ),
        )
    )

    override fun scenes(ruleId: RuleId): List<RuleScene> =
        when (ruleId) {
            SecondInversionTriadRules.CADENTIAL_SIX_FOUR ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 3..3,
                        facets = listOf(
                            ChordPattern(
                                listOf(
                                    SlotChordSpec(degrees = setOf(1), positions = setOf(TextbookTriadPosition.SECOND_INVERSION)),
                                    SlotChordSpec(degrees = setOf(5), positions = setOf(TextbookTriadPosition.ROOT_POSITION)),
                                    SlotChordSpec(degrees = setOf(1), positions = setOf(TextbookTriadPosition.ROOT_POSITION)),
                                )
                            ),
                        ),
                        unavailableReason = "终止四六示例使用 I(46)→V，并在后续解决到 I。",
                    )
                )
            SecondInversionTriadRules.PASSING_SIX_FOUR ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 3..3,
                        facets = listOf(
                            ChordPattern(
                                listOf(
                                    SlotChordSpec(positions = rootOrFirst),
                                    SlotChordSpec(positions = setOf(TextbookTriadPosition.SECOND_INVERSION)),
                                    SlotChordSpec(positions = rootOrFirst, sameChordAsSlot = 0),
                                )
                            ),
                            RootMotion(setOf(1, 2, 3)),
                            BassMotion(BassMotionKind.PASSING_STEP),
                        ),
                        unavailableReason = "经过四六需要三和弦低音级进，至少使用三个和弦。",
                    )
                )
            SecondInversionTriadRules.PEDAL_SIX_FOUR ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 3..3,
                        facets = listOf(
                            ChordPattern(
                                listOf(
                                    SlotChordSpec(positions = setOf(TextbookTriadPosition.ROOT_POSITION)),
                                    SlotChordSpec(positions = setOf(TextbookTriadPosition.SECOND_INVERSION)),
                                    SlotChordSpec(positions = setOf(TextbookTriadPosition.ROOT_POSITION), sameChordAsSlot = 0),
                                )
                            ),
                            RootMotion(setOf(1, 2, 3)),
                            BassMotion(BassMotionKind.PEDAL_HELD),
                        ),
                        unavailableReason = "持续音四六需要在相同低音上装饰一个原位三和弦。",
                    )
                )
            SecondInversionTriadRules.SAME_CHORD_INVERSION_INSERTION ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 3..3,
                        facets = listOf(
                            ChordPattern(
                                listOf(
                                    SlotChordSpec(positions = setOf(TextbookTriadPosition.ROOT_POSITION)),
                                    SlotChordSpec(positions = setOf(TextbookTriadPosition.SECOND_INVERSION), sameChordAsSlot = 0),
                                    SlotChordSpec(positions = setOf(TextbookTriadPosition.FIRST_INVERSION), sameChordAsSlot = 0),
                                )
                            ),
                            RootMotion(setOf(0)),
                        ),
                        unavailableReason = "同和弦转位插入只适用于同一和弦的不同转位之间。",
                    )
                )
            else -> emptyList()
        }

    override fun inputOverride(ruleId: RuleId): RuleExampleInputOverride =
        when (ruleId) {
            SecondInversionTriadRules.CADENTIAL_SIX_FOUR ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(1, 5)),
                )
            SecondInversionTriadRules.PASSING_SIX_FOUR ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(1, 5), RuleDegreePair(1, 2), RuleDegreePair(3, 2)),
                )
            SecondInversionTriadRules.PEDAL_SIX_FOUR ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(1, 4), RuleDegreePair(5, 1)),
                )
            SecondInversionTriadRules.SAME_CHORD_INVERSION_INSERTION ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(1, 1), RuleDegreePair(5, 5)),
                )
            else -> RuleExampleInputOverride()
        }

    private val rootOrFirst = setOf(
        TextbookTriadPosition.ROOT_POSITION,
        TextbookTriadPosition.FIRST_INVERSION,
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

val SECOND_INVERSION_TRIAD_CHAPTER = ChapterId("textbook.second-inversion-triad")
