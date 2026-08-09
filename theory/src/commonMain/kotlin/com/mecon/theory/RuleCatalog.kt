package com.mecon.theory

import kotlin.jvm.JvmInline

import com.mecon.theory.textbook.RootPositionTriadRuleCatalog
import com.mecon.theory.textbook.FirstInversionTriadRuleCatalog
import com.mecon.theory.textbook.SecondInversionTriadRuleCatalog
import com.mecon.theory.textbook.DominantSeventhRuleCatalog
import com.mecon.theory.textbook.NonChordToneRuleCatalog

@JvmInline
value class ChapterId(val value: String) {
    override fun toString(): String = value
}

enum class RuleKind {
    GROUP,
    PATTERN,
    CONSTRAINT,
    TENDENCY,
}

enum class RelationKind {
    EXCLUSIVE_GROUP,
    REQUIRES,
    SUPPRESSES,
}

enum class RuleKeyModeConstraint {
    MAJOR,
    MINOR,
}

data class RuleDescriptor(
    val id: RuleId,
    val titleKey: String,
    val descriptionKey: String,
    val kind: RuleKind,
    val parent: RuleId? = null,
    val chapter: ChapterId,
    val selectable: Boolean,
    val demonstrableAsViolation: Boolean = false,
)

data class RuleRelation(
    val kind: RelationKind,
    val ruleIds: List<RuleId>,
)

data class RuleDegreePair(
    val fromDegree: Int,
    val toDegree: Int,
) {
    init {
        require(fromDegree in 1..7) { "fromDegree must be in 1..7" }
        require(toDegree in 1..7) { "toDegree must be in 1..7" }
    }
}

data class RuleExampleInputOverride(
    val degreePairs: List<RuleDegreePair>? = null,
    val defaultPair: RuleDegreePair? = null,
    val keyMode: RuleKeyModeConstraint? = null,
    val degreeQualities: Map<Int, ChordQuality> = emptyMap(),
    val defaultDemonstration: Boolean = false,
    /** false 表示谱例不消费起止和弦音级，编辑器不应显示和弦选择。 */
    val usesDegreeContext: Boolean = true,
) {
    init {
        require(degreeQualities.keys.all { it in 1..7 }) { "degreeQualities keys must be in 1..7" }
    }
}

data class RuleExampleInputSpec(
    val ruleId: RuleId,
    val degreePairs: List<RuleDegreePair>,
    val defaultPair: RuleDegreePair,
    val keyMode: RuleKeyModeConstraint? = null,
    val degreeQualities: Map<Int, ChordQuality> = emptyMap(),
    val companionRuleOptions: List<RuleId> = emptyList(),
    val defaultCompanionRuleId: RuleId? = companionRuleOptions.firstOrNull(),
    val defaultDemonstrationRuleId: RuleId? = null,
    val usesDegreeContext: Boolean = true,
) {
    init {
        require(degreeQualities.keys.all { it in 1..7 }) { "degreeQualities keys must be in 1..7" }
    }
}

data class SelectionContext(
    val fromDegree: Int,
    val toDegree: Int,
) {
    init {
        require(fromDegree in 1..7) { "fromDegree must be in 1..7" }
        require(toDegree in 1..7) { "toDegree must be in 1..7" }
    }

    val degreeDistance: Int
        get() {
            val up = (toDegree - fromDegree).mod(DIATONIC_STEPS)
            return minOf(up, DIATONIC_STEPS - up)
        }
}

data class SelectionDiagnostic(
    val ruleId: RuleId,
    val message: String,
)

data class SelectionValidation(
    val errors: List<SelectionDiagnostic> = emptyList(),
    val unavailable: List<SelectionDiagnostic> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

interface RuleCatalogProvider {
    val chapterId: ChapterId
    val descriptors: List<RuleDescriptor>
    val relations: List<RuleRelation>

    /** 规则声明的适用场景（rule-scenes.md）。applicability 与 enumerate 均由此驱动。 */
    fun scenes(ruleId: RuleId): List<RuleScene> = emptyList()
    fun inputOverride(ruleId: RuleId): RuleExampleInputOverride = RuleExampleInputOverride()
}

object RuleCatalog {
    private val providers: List<RuleCatalogProvider> = listOf(
        RootPositionTriadRuleCatalog,
        FirstInversionTriadRuleCatalog,
        SecondInversionTriadRuleCatalog,
        DominantSeventhRuleCatalog,
        NonChordToneRuleCatalog,
    )
    private val descriptorsById: Map<RuleId, RuleDescriptor> =
        providers.flatMap { it.descriptors }.associateBy { it.id }
    private val providerByChapter: Map<ChapterId, RuleCatalogProvider> =
        providers.associateBy { it.chapterId }

    fun descriptor(id: RuleId): RuleDescriptor? = descriptorsById[id]

    fun children(id: RuleId): List<RuleDescriptor> =
        descriptorsById.values.filter { it.parent == id }

    fun chapter(id: ChapterId): List<RuleDescriptor> =
        providerByChapter[id]?.descriptors.orEmpty().filter { it.parent == null }

    fun allDescriptors(): List<RuleDescriptor> = descriptorsById.values.toList()

    fun scenes(ruleId: RuleId): List<RuleScene> {
        val descriptor = descriptor(ruleId) ?: return emptyList()
        return providerByChapter[descriptor.chapter]?.scenes(ruleId).orEmpty()
    }

    fun applicability(ruleId: RuleId, context: SelectionContext): RuleApplicability {
        descriptor(ruleId) ?: return RuleApplicability.notApplicable("未知规则。")
        val ruleScenes = scenes(ruleId)
        if (ruleScenes.isEmpty()) return RuleApplicability.APPLIES
        return if (SceneMatcher.appliesInContext(ruleScenes, context.fromDegree, context.toDegree)) {
            RuleApplicability.APPLIES
        } else {
            RuleApplicability.notApplicable(
                ruleScenes.firstNotNullOfOrNull { it.unavailableReason }
                    ?: "该规则不适用于当前和弦关系。",
            )
        }
    }

    fun exampleInputSpec(ruleId: RuleId): RuleExampleInputSpec {
        val descriptor = descriptor(ruleId)
        val provider = descriptor?.let { providerByChapter[it.chapter] }
        val override = provider?.inputOverride(ruleId) ?: RuleExampleInputOverride()
        val inferredPairs = override.degreePairs ?: inferDegreePairs(ruleId)
        val degreePairs = inferredPairs.ifEmpty { representativeDegreePairs }
        val defaultPair = override.defaultPair
            ?.takeIf { it in degreePairs }
            ?: degreePairs.first()
        val companionOptions = companionRuleOptions(ruleId)
        return RuleExampleInputSpec(
            ruleId = ruleId,
            degreePairs = degreePairs,
            defaultPair = defaultPair,
            keyMode = override.keyMode,
            degreeQualities = override.degreeQualities,
            companionRuleOptions = companionOptions,
            defaultCompanionRuleId = companionOptions.firstOrNull(),
            defaultDemonstrationRuleId = ruleId.takeIf {
                override.defaultDemonstration && descriptor?.demonstrableAsViolation == true
            },
            usesDegreeContext = override.usesDegreeContext,
        )
    }

    /**
     * 上下文无关的规则集校验：存在性 / [RuleDescriptor.selectable] / 关系约束
     * （EXCLUSIVE_GROUP / REQUIRES），**不查 applicability**。
     *
     * 供无 (from,to) 二元上下文的入口（如 solver-api enumerate）复用；
     * [validateSelection] 在此基础上叠加 applicability 的 unavailable。
     */
    fun validateRuleSet(selected: List<RuleId>): SelectionValidation {
        val selectedSet = selected.toSet()
        val errors = mutableListOf<SelectionDiagnostic>()

        selected.forEach { ruleId ->
            val descriptor = descriptor(ruleId)
            when {
                descriptor == null -> errors += SelectionDiagnostic(ruleId, "未知规则。")
                descriptor.selectable.not() -> errors += SelectionDiagnostic(ruleId, "该规则不能直接作为探索目标。")
            }
        }

        providers.flatMap { it.relations }.forEach { relation ->
            val chosen = relation.ruleIds.filter { it in selectedSet }
            when (relation.kind) {
                RelationKind.EXCLUSIVE_GROUP -> {
                    if (chosen.size > 1) {
                        chosen.forEach { ruleId ->
                            errors += SelectionDiagnostic(ruleId, "互斥连接模式只能选择一种。")
                        }
                    }
                }
                RelationKind.REQUIRES -> {
                    val subject = relation.ruleIds.firstOrNull()
                    val required = relation.ruleIds.drop(1)
                    if (subject != null && subject in selectedSet && required.none { it in selectedSet }) {
                        errors += SelectionDiagnostic(subject, "该附属规则需要同时选择所属连接模式。")
                    }
                }
                RelationKind.SUPPRESSES -> Unit
            }
        }

        return SelectionValidation(errors = errors)
    }

    fun validateSelection(
        selected: List<RuleId>,
        context: SelectionContext,
    ): SelectionValidation {
        val base = validateRuleSet(selected)
        val unavailable = mutableListOf<SelectionDiagnostic>()

        selected.forEach { ruleId ->
            val descriptor = descriptor(ruleId) ?: return@forEach
            if (descriptor.selectable.not()) return@forEach
            val applicability = applicability(ruleId, context)
            if (!applicability.applies) {
                unavailable += SelectionDiagnostic(ruleId, applicability.reason ?: "该规则不适用于当前和弦关系。")
            }
        }

        return base.copy(unavailable = unavailable)
    }

    private fun inferDegreePairs(ruleId: RuleId): List<RuleDegreePair> =
        representativeDegreePairs.filter { pair ->
            applicability(ruleId, SelectionContext(pair.fromDegree, pair.toDegree)).applies
        }

    private fun companionRuleOptions(ruleId: RuleId): List<RuleId> =
        providers.flatMap { it.relations }
            .firstOrNull { it.kind == RelationKind.REQUIRES && it.ruleIds.firstOrNull() == ruleId }
            ?.ruleIds
            ?.drop(1)
            .orEmpty()
}

private const val DIATONIC_STEPS = 7

private val representativeDegreePairs = listOf(
    RuleDegreePair(1, 1),
    RuleDegreePair(5, 5),
    RuleDegreePair(4, 4),
    RuleDegreePair(5, 6),
    RuleDegreePair(4, 5),
    RuleDegreePair(2, 1),
    RuleDegreePair(7, 1),
    RuleDegreePair(1, 2),
    RuleDegreePair(1, 6),
    RuleDegreePair(6, 4),
    RuleDegreePair(3, 1),
    RuleDegreePair(4, 2),
    RuleDegreePair(5, 1),
    RuleDegreePair(1, 5),
    RuleDegreePair(2, 5),
    RuleDegreePair(4, 1),
    RuleDegreePair(6, 2),
    RuleDegreePair(1, 4),
)
