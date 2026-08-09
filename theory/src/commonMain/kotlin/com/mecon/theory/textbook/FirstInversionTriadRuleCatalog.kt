package com.mecon.theory.textbook

import com.mecon.theory.ChapterId
import com.mecon.theory.ChordPattern
import com.mecon.theory.ChordQuality
import com.mecon.theory.KeyContext
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

object FirstInversionTriadRuleCatalog : RuleCatalogProvider {
    override val chapterId: ChapterId = FIRST_INVERSION_TRIAD_CHAPTER

    val CHAPTER = RuleId("textbook.first-inversion-triad")
    val VERTICALITY = RuleId("textbook.first-inversion-triad.verticality")
    val MAJOR_DOMINANT = RuleId("textbook.first-inversion-triad.major-dominant")

    override val descriptors: List<RuleDescriptor> = listOf(
        descriptor(CHAPTER, "rule.firstInversionTriad", RuleKind.GROUP, parent = null, selectable = false),
        descriptor(
            FirstInversionTriadRules.FIRST_INVERSION_BASS_LINE,
            "rule.firstInversionTriad.bassLineEnrichment",
            RuleKind.PATTERN,
            CHAPTER,
        ),
        descriptor(VERTICALITY, "rule.firstInversionTriad.verticality", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(
            FirstInversionTriadRules.DIMINISHED_TRIAD_FIRST_INVERSION,
            "rule.firstInversionTriad.diminishedFirstInversion",
            RuleKind.CONSTRAINT,
            VERTICALITY,
        ),
        descriptor(
            FirstInversionTriadRules.LEADING_TONE_DOUBLED,
            "rule.firstInversionTriad.leadingToneDoubled",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            FirstInversionTriadRules.NON_CHORD_TONE,
            "rule.firstInversionTriad.nonChordTone",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(
            FirstInversionTriadRules.MISSING_CHORD_TONE,
            "rule.firstInversionTriad.missingChordTone",
            RuleKind.CONSTRAINT,
            VERTICALITY,
            selectable = false,
        ),
        descriptor(MAJOR_DOMINANT, "rule.firstInversionTriad.majorDominant", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(
            FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH,
            "rule.firstInversionTriad.majorRootDominantToMinorSixth",
            RuleKind.CONSTRAINT,
            MAJOR_DOMINANT,
            demonstrableAsViolation = true,
        ),
    )

    override val relations: List<RuleRelation> = emptyList()

    override fun scenes(ruleId: RuleId): List<RuleScene> =
        when (ruleId) {
            FirstInversionTriadRules.FIRST_INVERSION_BASS_LINE ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 2..2,
                        facets = listOf(
                            ChordPattern(listOf(SlotChordSpec(positions = setOf(TextbookTriadPosition.FIRST_INVERSION)))),
                        ),
                    )
                )
            FirstInversionTriadRules.DIMINISHED_TRIAD_FIRST_INVERSION ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 1..2,
                        facets = listOf(
                            ChordPattern(
                                listOf(
                                    SlotChordSpec(
                                        qualities = setOf(ChordQuality.DIMINISHED),
                                        positions = setOf(TextbookTriadPosition.FIRST_INVERSION),
                                    )
                                )
                            ),
                        ),
                    )
                )
            FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH ->
                listOf(
                    RuleScene(
                        ruleId = ruleId,
                        window = 2..2,
                        facets = listOf(
                            ChordPattern(
                                listOf(
                                    SlotChordSpec(
                                        degrees = setOf(5),
                                        qualities = setOf(ChordQuality.MAJOR),
                                        positions = setOf(TextbookTriadPosition.ROOT_POSITION),
                                    ),
                                    SlotChordSpec(
                                        degrees = setOf(6),
                                        qualities = setOf(ChordQuality.MINOR),
                                        positions = setOf(TextbookTriadPosition.FIRST_INVERSION),
                                    ),
                                )
                            ),
                            KeyContext(RuleKeyModeConstraint.MAJOR),
                        ),
                        role = SceneRole.DEMONSTRATION,
                        unavailableReason = "大调原位属和弦后接六级小和弦禁则只适用于 V→vi。",
                    )
                )
            else -> emptyList()
        }

    override fun inputOverride(ruleId: RuleId): RuleExampleInputOverride =
        when (ruleId) {
            FirstInversionTriadRules.DIMINISHED_TRIAD_FIRST_INVERSION ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(7, 7)),
                )
            FirstInversionTriadRules.MAJOR_ROOT_DOMINANT_TO_MINOR_SIXTH ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(5, 6)),
                    keyMode = RuleKeyModeConstraint.MAJOR,
                    degreeQualities = mapOf(
                        5 to ChordQuality.MAJOR,
                        6 to ChordQuality.MINOR,
                    ),
                    defaultDemonstration = true,
                )
            else -> RuleExampleInputOverride()
        }

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

val FIRST_INVERSION_TRIAD_CHAPTER = ChapterId("textbook.first-inversion-triad")
