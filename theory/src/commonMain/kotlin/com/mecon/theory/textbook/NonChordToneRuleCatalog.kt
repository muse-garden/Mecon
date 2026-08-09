package com.mecon.theory.textbook

import com.mecon.theory.ChapterId
import com.mecon.theory.NonChordToneType
import com.mecon.theory.RuleCatalogProvider
import com.mecon.theory.RuleDescriptor
import com.mecon.theory.RuleId
import com.mecon.theory.RuleKind
import com.mecon.theory.RuleScene
import com.mecon.theory.ChordPattern
import com.mecon.theory.SlotChordSpec

val NON_CHORD_TONE_CHAPTER = ChapterId("textbook.non-chord-tones")

object NonChordToneRules {
    val CHAPTER = RuleId("textbook.non-chord-tones")
    val PASSING = RuleId("nct.passing")
    val NEIGHBOR = RuleId("nct.neighbor")
    /** 延留音分类节点；具体音程型与延留音链是其可选子规则。 */
    val SUSPENSION = RuleId("nct.suspension")
    val SUSPENSION_4_3 = RuleId("nct.suspension.4-3")
    val SUSPENSION_7_6 = RuleId("nct.suspension.7-6")
    val SUSPENSION_9_8 = RuleId("nct.suspension.9-8")
    val RETARDATION = RuleId("nct.retardation")
    val SUSPENSION_CHAIN = RuleId("nct.suspension-chain")
    val APPOGGIATURA = RuleId("nct.appoggiatura")
    val ESCAPE = RuleId("nct.escape")
    val NEIGHBOR_GROUP = RuleId("nct.neighbor-group")
    val ANTICIPATION = RuleId("nct.anticipation")
    val PEDAL = RuleId("nct.pedal")

    val selectable = listOf(
        PASSING, NEIGHBOR, SUSPENSION_4_3, SUSPENSION_7_6, SUSPENSION_9_8,
        RETARDATION, SUSPENSION_CHAIN, APPOGGIATURA,
        ESCAPE, NEIGHBOR_GROUP, ANTICIPATION, PEDAL,
    )

    fun typeFor(ruleId: RuleId): NonChordToneType? = when (ruleId) {
        PASSING -> NonChordToneType.PASSING
        NEIGHBOR -> NonChordToneType.NEIGHBOR
        SUSPENSION_4_3, SUSPENSION_7_6, SUSPENSION_9_8, SUSPENSION_CHAIN ->
            NonChordToneType.SUSPENSION
        // 保留旧 rule id 兼容已存请求；教材语义是低音声部向下解决的 2-3 延留音。
        RETARDATION -> NonChordToneType.SUSPENSION
        APPOGGIATURA -> NonChordToneType.APPOGGIATURA
        ESCAPE -> NonChordToneType.ESCAPE
        NEIGHBOR_GROUP -> NonChordToneType.NEIGHBOR_GROUP
        ANTICIPATION -> NonChordToneType.ANTICIPATION
        PEDAL -> NonChordToneType.PEDAL
        else -> null
    }
}

object NonChordToneRuleCatalog : RuleCatalogProvider {
    override val chapterId: ChapterId = NON_CHORD_TONE_CHAPTER
    override val relations = emptyList<com.mecon.theory.RuleRelation>()
    private val suspensionChildren = setOf(
        NonChordToneRules.SUSPENSION_4_3,
        NonChordToneRules.SUSPENSION_7_6,
        NonChordToneRules.SUSPENSION_9_8,
        NonChordToneRules.RETARDATION,
        NonChordToneRules.SUSPENSION_CHAIN,
    )
    override val descriptors: List<RuleDescriptor> = listOf(
        RuleDescriptor(
            id = NonChordToneRules.CHAPTER,
            titleKey = "textbook.nonChordTones",
            descriptionKey = "textbook.nonChordTones.description",
            kind = RuleKind.GROUP,
            chapter = chapterId,
            selectable = false,
        ),
        RuleDescriptor(
            id = NonChordToneRules.SUSPENSION,
            titleKey = NonChordToneRules.SUSPENSION.value,
            descriptionKey = "${NonChordToneRules.SUSPENSION.value}.description",
            kind = RuleKind.GROUP,
            parent = NonChordToneRules.CHAPTER,
            chapter = chapterId,
            selectable = false,
        ),
    ) + NonChordToneRules.selectable.map { ruleId ->
        RuleDescriptor(
            id = ruleId,
            titleKey = ruleId.value,
            descriptionKey = "${ruleId.value}.description",
            kind = RuleKind.PATTERN,
            parent = if (ruleId in suspensionChildren) {
                NonChordToneRules.SUSPENSION
            } else {
                NonChordToneRules.CHAPTER
            },
            chapter = chapterId,
            selectable = true,
        )
    }

    /**
     * v1 的专用谱例生成器消费旋律/拍位上下文；这里声明宽松的两槽符号场景，维持 RuleCatalog
     * “每个可选规则均可查询场景”的协议。F2 落地 NonChordTone facet 后替换为精确必要条件。
     */
    override fun scenes(ruleId: RuleId): List<RuleScene> =
        if (ruleId in NonChordToneRules.selectable) {
            listOf(
                RuleScene(
                    ruleId = ruleId,
                    window = 2..2,
                    facets = listOf(ChordPattern(listOf(SlotChordSpec(), SlotChordSpec()))),
                )
            )
        } else emptyList()

    override fun inputOverride(ruleId: RuleId) =
        com.mecon.theory.RuleExampleInputOverride(
            usesDegreeContext = ruleId !in NonChordToneRules.selectable,
        )

}
