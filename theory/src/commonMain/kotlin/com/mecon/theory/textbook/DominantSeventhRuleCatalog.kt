package com.mecon.theory.textbook

import com.mecon.theory.ChapterId
import com.mecon.theory.ChordArity
import com.mecon.theory.ChordPattern
import com.mecon.theory.ChordQuality
import com.mecon.theory.DoublingExpectation
import com.mecon.theory.KeyContext
import com.mecon.theory.RelationKind
import com.mecon.theory.RuleCatalogProvider
import com.mecon.theory.RuleDegreePair
import com.mecon.theory.RuleDescriptor
import com.mecon.theory.RuleExampleInputOverride
import com.mecon.theory.RuleId
import com.mecon.theory.RuleKeyModeConstraint
import com.mecon.theory.RuleKind
import com.mecon.theory.RuleRelation
import com.mecon.theory.RuleScene
import com.mecon.theory.SceneFacet
import com.mecon.theory.SceneRole
import com.mecon.theory.SlotChordSpec

object DominantSeventhRuleCatalog : RuleCatalogProvider {
    override val chapterId: ChapterId = DOMINANT_SEVENTH_CHAPTER

    val CHAPTER = RuleId("textbook.dominant-seventh")
    val GENERAL = RuleId("textbook.dominant-seventh.general")
    val ROOT_POSITION = RuleId("textbook.dominant-seventh.root-position")
    val OTHER_RESOLUTIONS = RuleId("textbook.dominant-seventh.other-resolutions")
    val INVERSIONS = RuleId("textbook.dominant-seventh.inversions")
    val PREPARATION = RuleId("textbook.dominant-seventh.preparation")
    val OTHER_SEVENTHS = RuleId("textbook.dominant-seventh.other-sevenths")
    val CIRCLE_OF_FIFTHS = RuleId("textbook.dominant-seventh.circle-of-fifths")

    override val descriptors: List<RuleDescriptor> = listOf(
        descriptor(CHAPTER, "rule.dominantSeventh", RuleKind.GROUP, parent = null, selectable = false),
        descriptor(GENERAL, "rule.dominantSeventh.general", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(DominantSeventhRules.SEVENTH_RESOLVES_DOWN, "rule.dominantSeventh.seventhResolvesDown", RuleKind.TENDENCY, GENERAL),
        descriptor(
            DominantSeventhRules.SEVENTH_ASCENDS,
            "rule.dominantSeventh.seventhAscends",
            RuleKind.CONSTRAINT,
            GENERAL,
            demonstrableAsViolation = true,
        ),
        descriptor(DominantSeventhRules.DOMINANT_SEVENTH_QUALITY, "rule.dominantSeventh.quality", RuleKind.CONSTRAINT, GENERAL),
        descriptor(
            DominantSeventhRules.MINOR_REQUIRES_LEADING_TONE,
            "rule.dominantSeventh.minorRequiresLeadingTone",
            RuleKind.CONSTRAINT,
            GENERAL,
            demonstrableAsViolation = true,
        ),
        descriptor(
            DominantSeventhRules.OUTER_LEADING_TONE_RESOLUTION,
            "rule.dominantSeventh.outerLeadingToneResolution",
            RuleKind.CONSTRAINT,
            GENERAL,
            demonstrableAsViolation = true,
        ),
        descriptor(ROOT_POSITION, "rule.dominantSeventh.rootPosition", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH, "rule.dominantSeventh.rootV7ToIOmittedFifth", RuleKind.PATTERN, ROOT_POSITION),
        descriptor(
            DominantSeventhRules.ROOT_V7_COMPLETE_I_PARALLEL_FIFTH,
            "rule.dominantSeventh.rootV7CompleteIParallelFifth",
            RuleKind.CONSTRAINT,
            ROOT_POSITION,
            demonstrableAsViolation = true,
        ),
        descriptor(DominantSeventhRules.INCOMPLETE_V7_TO_COMPLETE_I, "rule.dominantSeventh.incompleteV7ToCompleteI", RuleKind.PATTERN, ROOT_POSITION),
        descriptor(DominantSeventhRules.INNER_LEADING_TONE_COMPLETE_I, "rule.dominantSeventh.innerLeadingToneCompleteI", RuleKind.PATTERN, ROOT_POSITION),
        descriptor(OTHER_RESOLUTIONS, "rule.dominantSeventh.otherResolutions", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(DominantSeventhRules.DECEPTIVE_RESOLUTION, "rule.dominantSeventh.deceptiveResolution", RuleKind.PATTERN, OTHER_RESOLUTIONS),
        descriptor(INVERSIONS, "rule.dominantSeventh.inversions", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(DominantSeventhRules.INVERSION_TENDENCY_TONES, "rule.dominantSeventh.inversionTendencyTones", RuleKind.TENDENCY, INVERSIONS),
        descriptor(DominantSeventhRules.SECOND_INVERSION_PASSING, "rule.dominantSeventh.secondInversionPassing", RuleKind.PATTERN, INVERSIONS),
        descriptor(DominantSeventhRules.THIRD_INVERSION_TO_I6, "rule.dominantSeventh.thirdInversionToI6", RuleKind.PATTERN, INVERSIONS),
        descriptor(PREPARATION, "rule.dominantSeventh.preparation", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(DominantSeventhRules.PREPARATION_SUSPENSION, "rule.dominantSeventh.preparationSuspension", RuleKind.PATTERN, PREPARATION),
        descriptor(DominantSeventhRules.PREPARATION_PASSING, "rule.dominantSeventh.preparationPassing", RuleKind.PATTERN, PREPARATION),
        descriptor(DominantSeventhRules.PREPARATION_NEIGHBOR, "rule.dominantSeventh.preparationNeighbor", RuleKind.PATTERN, PREPARATION),
        descriptor(DominantSeventhRules.PREPARATION_APPOGGIATURA, "rule.dominantSeventh.preparationAppoggiatura", RuleKind.PATTERN, PREPARATION),
        descriptor(
            DominantSeventhRules.PREPARATION_ABOVE_LEAP,
            "rule.dominantSeventh.preparationAboveLeap",
            RuleKind.CONSTRAINT,
            PREPARATION,
            demonstrableAsViolation = true,
        ),
        descriptor(OTHER_SEVENTHS, "rule.dominantSeventh.otherSevenths", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(DominantSeventhRules.OMIT_FIFTH_PREFERRED, "rule.dominantSeventh.omitFifthPreferred", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.OMIT_THIRD_SECONDARY, "rule.dominantSeventh.omitThirdSecondary", RuleKind.TENDENCY, OTHER_SEVENTHS),
        descriptor(
            DominantSeventhRules.ROOT_OR_SEVENTH_OMITTED,
            "rule.dominantSeventh.rootOrSeventhOmitted",
            RuleKind.CONSTRAINT,
            OTHER_SEVENTHS,
            selectable = false,
        ),
        descriptor(DominantSeventhRules.SUPERTONIC_TO_DOMINANT, "rule.dominantSeventh.supertonicToDominant", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.SUPERTONIC_TO_CADENTIAL_SIX_FOUR, "rule.dominantSeventh.supertonicToCadentialSixFour", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.SUPERTONIC_TO_LEADING, "rule.dominantSeventh.supertonicToLeading", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.MAJOR_LEADING_HALF_DIMINISHED, "rule.dominantSeventh.majorLeadingHalfDiminished", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.MINOR_LEADING_DIMINISHED, "rule.dominantSeventh.minorLeadingDiminished", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.LEADING_TO_TONIC, "rule.dominantSeventh.leadingToTonic", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.LEADING_TO_DOMINANT_SEVENTH, "rule.dominantSeventh.leadingToDominantSeventh", RuleKind.PATTERN, OTHER_SEVENTHS),
        descriptor(DominantSeventhRules.LEADING_TONIC_DOUBLES_THIRD, "rule.dominantSeventh.leadingTonicDoublesThird", RuleKind.TENDENCY, OTHER_SEVENTHS),
        descriptor(
            DominantSeventhRules.MINOR_LEADING_DIM5_TO_PERF5,
            "rule.dominantSeventh.minorLeadingDim5ToPerf5",
            RuleKind.CONSTRAINT,
            OTHER_SEVENTHS,
            demonstrableAsViolation = true,
        ),
        descriptor(CIRCLE_OF_FIFTHS, "rule.dominantSeventh.circleOfFifths", RuleKind.GROUP, CHAPTER, selectable = false),
        descriptor(DominantSeventhRules.CIRCLE_OF_FIFTHS_SEVENTHS, "rule.dominantSeventh.circleOfFifthsSevenths", RuleKind.PATTERN, CIRCLE_OF_FIFTHS),
        descriptor(DominantSeventhRules.CIRCLE_ROOT_POSITION_ALTERNATION, "rule.dominantSeventh.circleRootPositionAlternation", RuleKind.PATTERN, CIRCLE_OF_FIFTHS),
        descriptor(DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION, "rule.dominantSeventh.circleFirstThirdInversion", RuleKind.PATTERN, CIRCLE_OF_FIFTHS),
        descriptor(DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION, "rule.dominantSeventh.circleSecondRootInversion", RuleKind.PATTERN, CIRCLE_OF_FIFTHS),
    )

    override val relations: List<RuleRelation> = listOf(
        RuleRelation(
            RelationKind.EXCLUSIVE_GROUP,
            listOf(
                DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH,
                DominantSeventhRules.INCOMPLETE_V7_TO_COMPLETE_I,
                DominantSeventhRules.INNER_LEADING_TONE_COMPLETE_I,
                DominantSeventhRules.ROOT_V7_COMPLETE_I_PARALLEL_FIFTH,
            ),
        )
    )

    override fun scenes(ruleId: RuleId): List<RuleScene> =
        when (ruleId) {
            // ---- 属七原位解决（V7→I）：进行完全由场景数据指定，取代 runner 的 dominantSeventhSlots 分支 ----
            DominantSeventhRules.MINOR_REQUIRES_LEADING_TONE ->
                listOf(seventhScene(ruleId, v7ToI(), extraFacets = minorOnly()))
            DominantSeventhRules.SEVENTH_RESOLVES_DOWN,
            DominantSeventhRules.SEVENTH_ASCENDS,
            DominantSeventhRules.DOMINANT_SEVENTH_QUALITY,
            DominantSeventhRules.OUTER_LEADING_TONE_RESOLUTION,
            DominantSeventhRules.INVERSION_TENDENCY_TONES,
            DominantSeventhRules.ROOT_V7_TO_I_OMITTED_FIFTH,
            DominantSeventhRules.ROOT_V7_COMPLETE_I_PARALLEL_FIFTH,
            DominantSeventhRules.INCOMPLETE_V7_TO_COMPLETE_I,
            DominantSeventhRules.INNER_LEADING_TONE_COMPLETE_I ->
                listOf(seventhScene(ruleId, v7ToI()))
            DominantSeventhRules.DECEPTIVE_RESOLUTION ->
                listOf(seventhScene(ruleId, listOf(seventh(5), triad(6))))
            // ---- 属七转位（第三转位→I6、第二转位经过）----
            DominantSeventhRules.THIRD_INVERSION_TO_I6 ->
                listOf(seventhScene(ruleId, listOf(seventh(5, I3), triad(1, I1))))
            DominantSeventhRules.SECOND_INVERSION_PASSING ->
                listOf(seventhScene(ruleId, listOf(seventh(5, I2), triad(1))))
            // ---- 七音预备（I–V7–I 三槽语境）----
            DominantSeventhRules.PREPARATION_SUSPENSION,
            DominantSeventhRules.PREPARATION_PASSING,
            DominantSeventhRules.PREPARATION_NEIGHBOR,
            DominantSeventhRules.PREPARATION_APPOGGIATURA,
            DominantSeventhRules.PREPARATION_ABOVE_LEAP ->
                listOf(seventhScene(ruleId, listOf(triad(1), seventh(5), triad(1))))
            // ---- 其余七和弦的完整性/省略（单个七和弦即可示范）----
            DominantSeventhRules.OMIT_FIFTH_PREFERRED,
            DominantSeventhRules.OMIT_THIRD_SECONDARY,
            DominantSeventhRules.ROOT_OR_SEVENTH_OMITTED ->
                listOf(seventhScene(ruleId, listOf(seventh(2))))
            // ---- 上主七（II7）的连接 ----
            DominantSeventhRules.SUPERTONIC_TO_DOMINANT ->
                listOf(seventhScene(ruleId, listOf(seventh(2), seventh(5), triad(1))))
            DominantSeventhRules.SUPERTONIC_TO_CADENTIAL_SIX_FOUR ->
                listOf(seventhScene(ruleId, listOf(seventh(2), triad(1, I2), seventh(5), triad(1))))
            DominantSeventhRules.SUPERTONIC_TO_LEADING ->
                listOf(seventhScene(ruleId, listOf(seventh(2), seventh(7), triad(1))))
            // ---- 导七（vii°7 / viiø7）的解决 ----
            DominantSeventhRules.MAJOR_LEADING_HALF_DIMINISHED,
            DominantSeventhRules.LEADING_TO_TONIC,
            DominantSeventhRules.LEADING_TONIC_DOUBLES_THIRD ->
                listOf(seventhScene(ruleId, listOf(seventh(7), triad(1))))
            DominantSeventhRules.LEADING_TO_DOMINANT_SEVENTH ->
                listOf(seventhScene(ruleId, listOf(seventh(7), seventh(5), triad(1))))
            DominantSeventhRules.MINOR_LEADING_DIMINISHED,
            DominantSeventhRules.MINOR_LEADING_DIM5_TO_PERF5 ->
                listOf(seventhScene(ruleId, listOf(seventh(7), triad(1)), extraFacets = minorOnly()))
            // ---- 五度圈模进 4-7-3-6-2-5-1：转位/五音交替全部编码进槽位，取代 circleOfFifthsSlots ----
            DominantSeventhRules.CIRCLE_OF_FIFTHS_SEVENTHS ->
                listOf(circleScene(ruleId, positionFor = { R }, finalPosition = R))
            DominantSeventhRules.CIRCLE_ROOT_POSITION_ALTERNATION ->
                listOf(
                    circleScene(
                        ruleId,
                        positionFor = { R },
                        finalPosition = R,
                        // 完全/省五交替：偶数槽保留五音、奇数槽省略五音，与 checkScore 判定一致（本轮 bug 修复）。
                        fifthFor = { index -> if (index % 2 == 0) SeventhFifthConstraint.REQUIRE_FIFTH else SeventhFifthConstraint.OMIT_FIFTH },
                    )
                )
            DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION ->
                // V42（第三转位）低音为七音须下行级进，故一/三转位交替以 I6 收束（教材 V42-I6）。
                listOf(
                    circleScene(
                        ruleId,
                        positionFor = { index -> if (index % 2 == 0) I1 else I3 },
                        finalPosition = I1,
                        fifthFor = { SeventhFifthConstraint.REQUIRE_FIFTH },
                    )
                )
            DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION ->
                listOf(
                    circleScene(
                        ruleId,
                        positionFor = { index -> if (index % 2 == 0) I2 else R },
                        finalPosition = R,
                        fifthFor = { SeventhFifthConstraint.REQUIRE_FIFTH },
                    )
                )
            else -> emptyList()
        }

    override fun inputOverride(ruleId: RuleId): RuleExampleInputOverride =
        when (ruleId) {
            DominantSeventhRules.MINOR_REQUIRES_LEADING_TONE ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(5, 1)),
                    defaultPair = RuleDegreePair(5, 1),
                    keyMode = RuleKeyModeConstraint.MINOR,
                    degreeQualities = mapOf(5 to ChordQuality.DOMINANT7),
                    defaultDemonstration = true,
                )
            DominantSeventhRules.DECEPTIVE_RESOLUTION ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(5, 6)),
                    defaultPair = RuleDegreePair(5, 6),
                    degreeQualities = mapOf(5 to ChordQuality.DOMINANT7),
                )
            DominantSeventhRules.THIRD_INVERSION_TO_I6 ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(5, 1)),
                    defaultPair = RuleDegreePair(5, 1),
                    degreeQualities = mapOf(5 to ChordQuality.DOMINANT7),
                )
            DominantSeventhRules.SUPERTONIC_TO_DOMINANT ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(2, 5)),
                    defaultPair = RuleDegreePair(2, 5),
                )
            DominantSeventhRules.SUPERTONIC_TO_CADENTIAL_SIX_FOUR ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(2, 1)),
                    defaultPair = RuleDegreePair(2, 1),
                )
            DominantSeventhRules.SUPERTONIC_TO_LEADING ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(2, 7)),
                    defaultPair = RuleDegreePair(2, 7),
                )
            DominantSeventhRules.MAJOR_LEADING_HALF_DIMINISHED,
            DominantSeventhRules.LEADING_TO_TONIC,
            DominantSeventhRules.LEADING_TONIC_DOUBLES_THIRD ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(7, 1)),
                    defaultPair = RuleDegreePair(7, 1),
                    keyMode = RuleKeyModeConstraint.MAJOR,
                )
            DominantSeventhRules.MINOR_LEADING_DIMINISHED,
            DominantSeventhRules.MINOR_LEADING_DIM5_TO_PERF5 ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(7, 1)),
                    defaultPair = RuleDegreePair(7, 1),
                    keyMode = RuleKeyModeConstraint.MINOR,
                    defaultDemonstration = descriptorById(ruleId)?.demonstrableAsViolation == true,
                )
            DominantSeventhRules.LEADING_TO_DOMINANT_SEVENTH ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(7, 5)),
                    defaultPair = RuleDegreePair(7, 5),
                )
            DominantSeventhRules.CIRCLE_OF_FIFTHS_SEVENTHS,
            DominantSeventhRules.CIRCLE_ROOT_POSITION_ALTERNATION,
            DominantSeventhRules.CIRCLE_FIRST_THIRD_INVERSION,
            DominantSeventhRules.CIRCLE_SECOND_ROOT_INVERSION ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(4, 7)),
                    defaultPair = RuleDegreePair(4, 7),
                )
            else ->
                RuleExampleInputOverride(
                    degreePairs = listOf(RuleDegreePair(5, 1)),
                    defaultPair = RuleDegreePair(5, 1),
                    degreeQualities = mapOf(5 to ChordQuality.DOMINANT7),
                    defaultDemonstration = descriptorById(ruleId)?.demonstrableAsViolation == true,
                )
        }

    private fun descriptorById(ruleId: RuleId): RuleDescriptor? =
        descriptors.firstOrNull { it.id == ruleId }

    // ---- 七和弦场景构造辅助（rule-scenes §3 / solver-api §1）--------------------
    // 七和弦进行完全由场景槽位描述：degree + 七和弦转位 + 和弦规模（七和弦/解决用三和弦）+ 五音完整性，
    // runner 不再按规则集合硬编码槽位扩展。

    /** 五度圈模进的七和弦框架（不含终止主三和弦）：4-7-3-6-2-5。 */
    private val CIRCLE_OF_FIFTHS_DEGREES = listOf(4, 7, 3, 6, 2, 5)

    private val R = setOf(TextbookSeventhPosition.ROOT_POSITION)
    private val I1 = setOf(TextbookSeventhPosition.FIRST_INVERSION)
    private val I2 = setOf(TextbookSeventhPosition.SECOND_INVERSION)
    private val I3 = setOf(TextbookSeventhPosition.THIRD_INVERSION)

    /** 该音级上的七和弦槽。 */
    private fun seventh(degree: Int, positions: Set<TextbookSeventhPosition> = R): SlotChordSpec =
        SlotChordSpec(degrees = setOf(degree), seventhPositions = positions, arity = ChordArity.SEVENTH)

    /** 该音级上的（解决用）三和弦槽。 */
    private fun triad(degree: Int, positions: Set<TextbookSeventhPosition> = R): SlotChordSpec =
        SlotChordSpec(degrees = setOf(degree), seventhPositions = positions, arity = ChordArity.TRIAD)

    /** V7 → I（原位属七解决到主三和弦）。 */
    private fun v7ToI(): List<SlotChordSpec> = listOf(seventh(5), triad(1))

    private fun minorOnly(): List<SceneFacet> = listOf(KeyContext(RuleKeyModeConstraint.MINOR))

    private fun seventhScene(
        ruleId: RuleId,
        slots: List<SlotChordSpec>,
        extraFacets: List<SceneFacet> = emptyList(),
    ): RuleScene =
        RuleScene(
            ruleId = ruleId,
            window = slots.size..slots.size,
            facets = listOf(ChordPattern(slots)) + extraFacets,
            role = roleFor(ruleId),
            chordArity = ChordArity.SEVENTH,
        )

    private fun circleScene(
        ruleId: RuleId,
        positionFor: (Int) -> Set<TextbookSeventhPosition>,
        finalPosition: Set<TextbookSeventhPosition>,
        fifthFor: (Int) -> SeventhFifthConstraint? = { null },
    ): RuleScene {
        val slots = CIRCLE_OF_FIFTHS_DEGREES.mapIndexed { index, degree -> seventh(degree, positionFor(index)) } +
            triad(1, finalPosition)
        val doublings: List<SceneFacet> = CIRCLE_OF_FIFTHS_DEGREES.indices.mapNotNull { index ->
            fifthFor(index)?.let { DoublingExpectation(index, it) }
        }
        return RuleScene(
            ruleId = ruleId,
            window = slots.size..slots.size,
            facets = listOf(ChordPattern(slots)) + doublings,
            role = roleFor(ruleId),
            chordArity = ChordArity.SEVENTH,
        )
    }

    private fun roleFor(ruleId: RuleId): SceneRole =
        if (descriptorById(ruleId)?.demonstrableAsViolation == true) SceneRole.DEMONSTRATION else SceneRole.EXAMPLE

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

val DOMINANT_SEVENTH_CHAPTER = ChapterId("textbook.dominant-seventh")
