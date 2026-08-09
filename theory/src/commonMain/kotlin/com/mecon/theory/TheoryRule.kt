package com.mecon.theory

import kotlin.jvm.JvmInline

@JvmInline
value class RuleId(val value: String) {
    override fun toString(): String = value
}

enum class RuleSeverity {
    HARD,
    SOFT,
    HINT,
}

enum class RuleFindingKind {
    VIOLATION,
    WARNING,
    HINT,
    INDICATION,
}

enum class RuleScoreIntent {
    DEFAULT,
    EXPLANATORY,
}

enum class RequirementMode {
    REQUIRE_INDICATION,
    REQUIRE_VIOLATION,
    FORBID,
}

/**
 * 槽位窗口（constraint-program.md §2）：约束 / requirement 适用的槽位区间（含端点）。
 * [end] 为 null 表示开放端（"结尾处" / "任意位置"）。
 */
data class SlotWindow(
    val start: Int,
    val end: Int? = null,
) {
    init {
        require(start >= 0) { "SlotWindow start must be >= 0" }
        require(end == null || end >= start) { "SlotWindow end must be >= start" }
    }

    fun contains(slot: Int): Boolean = slot >= start && (end == null || slot <= end)
}

/**
 * @param window requirement 的生成期投影（rule-scenes / roadmap P2）：仅在 [window] 覆盖的槽位上裁决
 *   满足 / 缺失。null = 全局语义（现行为，全部槽位参与）。RuleAt 编译到此以避免"末端 finding 中途不可见、
 *   indicationBonus 跨槽叠加把前缀挤出 beam"。
 */
data class RuleRequirement(
    val ruleId: RuleId,
    val mode: RequirementMode,
    val window: SlotWindow? = null,
)

enum class RuleAnchorRole {
    PRIMARY,
    RELATED,
    SOURCE,
    TARGET,
    CONTEXT,
}

@JvmInline
value class RuleTag(val value: String) {
    override fun toString(): String = value
}

data class RuleAnchorGroup<A>(
    val role: RuleAnchorRole,
    val anchors: List<A>,
    val label: String? = null,
)

data class RuleDiagnostic<A>(
    val ruleId: RuleId,
    val severity: RuleSeverity,
    val message: String,
    val anchors: List<A> = emptyList(),
)

data class RuleFinding<A>(
    val ruleId: RuleId,
    val kind: RuleFindingKind,
    val severity: RuleSeverity,
    val message: String,
    val anchors: List<A> = emptyList(),
    val relatedAnchors: List<RuleAnchorGroup<A>> = emptyList(),
    val tags: Set<RuleTag> = emptySet(),
    val scoreDelta: Double = 0.0,
    val scoreIntent: RuleScoreIntent = RuleScoreIntent.DEFAULT,
) {
    fun toDiagnostic(): RuleDiagnostic<A> =
        RuleDiagnostic(
            ruleId = ruleId,
            severity = severity,
            message = message,
            anchors = anchors,
        )
}

fun <A> RuleDiagnostic<A>.toFinding(
    kind: RuleFindingKind = when (severity) {
        RuleSeverity.HARD,
        RuleSeverity.SOFT -> RuleFindingKind.VIOLATION
        RuleSeverity.HINT -> RuleFindingKind.HINT
    },
): RuleFinding<A> =
    RuleFinding(
        ruleId = ruleId,
        kind = kind,
        severity = severity,
        message = message,
        anchors = anchors,
    )

data class RuleConfig(
    val enabled: Boolean = true,
    val severityOverride: RuleSeverity? = null,
    val weightOverride: Double? = null,
)

enum class RuleAnchorOverlap {
    ANY_SHARED_ANCHOR,
    ALL_SUPPRESSED_ANCHORS,
}

data class RuleSuppression(
    val dominantRuleId: RuleId,
    val suppressedRuleId: RuleId,
    val anchorOverlap: RuleAnchorOverlap = RuleAnchorOverlap.ANY_SHARED_ANCHOR,
)

data class RuleProfile(
    val id: String,
    val overrides: Map<RuleId, RuleConfig> = emptyMap(),
    val suppressions: List<RuleSuppression> = emptyList(),
    val requirements: List<RuleRequirement> = emptyList(),
) {
    fun configFor(ruleId: RuleId): RuleConfig =
        overrides[ruleId] ?: RuleConfig()
}

fun <A> List<RuleFinding<A>>.applyProfile(profile: RuleProfile): List<RuleFinding<A>> {
    val configured = mapNotNull { finding -> finding.configuredBy(profile) }
    return configured.filterNot { candidate ->
        candidate.isSuppressedBy(configured, profile.suppressions)
    }
}

/**
 * Monotonic profile accumulator used by layered DP. [configured] deliberately retains hidden
 * findings: a suppressed dominant still participates in the batch semantics of [applyProfile].
 */
internal data class IncrementalProfiledFindings<A>(
    val configured: List<RuleFinding<A>> = emptyList(),
    val visible: List<RuleFinding<A>> = emptyList(),
) {
    fun append(
        findings: List<RuleFinding<A>>,
        profile: RuleProfile,
    ): IncrementalProfileUpdate<A> {
        if (findings.isEmpty()) return IncrementalProfileUpdate(this)
        val addedConfigured = findings.mapNotNull { finding -> finding.configuredBy(profile) }
        if (addedConfigured.isEmpty()) return IncrementalProfileUpdate(this)
        val allConfigured = configured + addedConfigured
        if (profile.suppressions.isEmpty()) {
            // 没有抑制链时新 finding 一定可见，且不会回撤既有可见项：跳过两次全量扫描。
            return IncrementalProfileUpdate(
                result = IncrementalProfiledFindings(
                    configured = allConfigured,
                    visible = visible + addedConfigured,
                ),
                addedVisible = addedConfigured,
            )
        }
        val removedVisible = visible.filter { candidate ->
            candidate.isSuppressedBy(addedConfigured, profile.suppressions)
        }
        val retainedVisible = if (removedVisible.isEmpty()) visible else visible - removedVisible.toSet()
        val addedVisible = addedConfigured.filterNot { candidate ->
            candidate.isSuppressedBy(allConfigured, profile.suppressions)
        }
        return IncrementalProfileUpdate(
            result = IncrementalProfiledFindings(
                configured = allConfigured,
                visible = retainedVisible + addedVisible,
            ),
            removedVisible = removedVisible,
            addedVisible = addedVisible,
        )
    }
}

internal data class IncrementalProfileUpdate<A>(
    val result: IncrementalProfiledFindings<A>,
    val removedVisible: List<RuleFinding<A>> = emptyList(),
    val addedVisible: List<RuleFinding<A>> = emptyList(),
)

private fun <A> RuleFinding<A>.configuredBy(profile: RuleProfile): RuleFinding<A>? {
    val config = profile.configFor(ruleId)
    if (!config.enabled) return null
    return copy(
        severity = config.severityOverride ?: severity,
        scoreDelta = config.weightOverride ?: scoreDelta,
    )
}

private fun <A> RuleFinding<A>.isSuppressedBy(
    configured: List<RuleFinding<A>>,
    suppressions: List<RuleSuppression>,
): Boolean = suppressions.any { suppression ->
    ruleId == suppression.suppressedRuleId &&
        configured.any { dominant ->
            dominant.ruleId == suppression.dominantRuleId &&
                dominant.overlaps(this, suppression.anchorOverlap)
        }
}

private fun <A> RuleFinding<A>.overlaps(
    other: RuleFinding<A>,
    anchorOverlap: RuleAnchorOverlap,
): Boolean {
    val anchors = anchors.toSet()
    val otherAnchors = other.anchors.toSet()
    if (anchors.isEmpty() || otherAnchors.isEmpty()) return false
    return when (anchorOverlap) {
        RuleAnchorOverlap.ANY_SHARED_ANCHOR -> anchors.any { it in otherAnchors }
        RuleAnchorOverlap.ALL_SUPPRESSED_ANCHORS -> otherAnchors.all { it in anchors }
    }
}

data class RuleCheckScope<A>(
    val anchors: Set<A> = emptySet(),
    val includeRelatedTransitions: Boolean = true,
) {
    fun isGlobal(): Boolean = anchors.isEmpty()

    companion object {
        fun <A> global(): RuleCheckScope<A> = RuleCheckScope()
        fun <A> anchors(vararg anchors: A): RuleCheckScope<A> =
            RuleCheckScope(anchors.toSet())
    }
}

data class RuleApplicability(
    val applies: Boolean,
    val reason: String? = null,
    val suggestedRuleSet: String? = null,
) {
    companion object {
        val APPLIES = RuleApplicability(applies = true)

        fun notApplicable(
            reason: String,
            suggestedRuleSet: String? = null,
        ): RuleApplicability =
            RuleApplicability(
                applies = false,
                reason = reason,
                suggestedRuleSet = suggestedRuleSet,
            )
    }
}

interface ContextualRule<C, A> {
    val id: RuleId
    fun applicability(context: C): RuleApplicability = RuleApplicability.APPLIES
    fun check(context: C): List<RuleFinding<A>>
}
