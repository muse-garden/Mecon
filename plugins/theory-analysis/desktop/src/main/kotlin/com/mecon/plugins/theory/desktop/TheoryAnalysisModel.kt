package com.mecon.plugins.theory.desktop

import com.mecon.api.computed.ComputedScore
import com.mecon.api.interaction.StyleOverride
import com.mecon.api.plugin.AnnotationAlignment
import com.mecon.api.plugin.AnnotationElement
import com.mecon.api.plugin.AnnotationLayoutContext
import com.mecon.api.plugin.AnnotationStaffProvider
import com.mecon.api.plugin.NoteStyleProvider
import com.mecon.api.plugin.PluginStaffId
import com.mecon.api.plugin.StaffAnchor
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.theory.FixedVoiceLayout
import com.mecon.theory.FixedVoiceScore
import com.mecon.theory.FourPartKeyboardDistribution
import com.mecon.theory.RuleAnchorGroup
import com.mecon.theory.RuleFinding
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleSeverity
import com.mecon.theory.textbook.FourPartTextbookRules

internal object TheoryAnalysisInteraction {
    var distribution: FourPartKeyboardDistribution = FourPartKeyboardDistribution.TREBLE_2_BASS_2
    var selectedEventId: EventId? = null
    var selectedAnnotationEventId: EventId? = null
    var hoveredFindingId: EventId? = null
}

internal data class TheoryAnalysisResult(
    val fixedScore: FixedVoiceScore,
    val findings: List<TheoryAnalysisFinding>,
) {
    val findingsByTime: Map<TimeCode, List<TheoryAnalysisFinding>> =
        findings.groupBy { it.time }.toSortedMap()
}

internal data class TheoryAnalysisFinding(
    val id: EventId,
    val time: TimeCode,
    val finding: RuleFinding<EventId>,
) {
    val anchors: List<EventId> get() = finding.anchors
    val relatedAnchors: List<RuleAnchorGroup<EventId>> get() = finding.relatedAnchors

    fun contains(eventId: EventId): Boolean =
        eventId in anchors || relatedAnchors.any { eventId in it.anchors }
}

internal object TheoryAnalysisComputer {
    fun analyze(
        score: RuntimeScore,
        distribution: FourPartKeyboardDistribution = TheoryAnalysisInteraction.distribution,
    ): TheoryAnalysisResult? {
        val layout = runCatching { FixedVoiceLayout.fourPartKeyboard(score, distribution) }.getOrNull()
            ?: return null
        val fixed = runCatching { FixedVoiceScore.load(score, layout) }.getOrNull()
            ?: return null
        val findings = FourPartTextbookRules.checkFixedVoiceScoreFindings(fixed)
            .mapNotNull { finding -> finding.toAnalysisFinding(fixed) }
        return TheoryAnalysisResult(fixed, findings)
    }

    fun annotationEventId(time: TimeCode): EventId =
        EventId("mecon.theory-analysis.annotation.${safeId(time.format())}")

    private fun RuleFinding<EventId>.toAnalysisFinding(fixedScore: FixedVoiceScore): TheoryAnalysisFinding? {
        val anchorTime = anchors
            .asSequence()
            .mapNotNull { fixedScore.event(it)?.onset }
            .minOrNull()
            ?: relatedAnchors
                .asSequence()
                .flatMap { it.anchors.asSequence() }
                .mapNotNull { fixedScore.event(it)?.onset }
                .minOrNull()
            ?: return null
        return TheoryAnalysisFinding(
            id = EventId("mecon.theory-analysis.finding.${safeId(ruleId.value)}.${safeId(anchors.joinToString("_") { it.value })}"),
            time = anchorTime,
            finding = this,
        )
    }

    private fun safeId(value: String): String =
        value.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                else -> '_'
            }
        }.joinToString("")
}

internal object TheoryAnalysisAnnotationProvider : AnnotationStaffProvider {
    override val staffId = PluginStaffId("mecon.theory-analysis.rules")
    override val anchor = StaffAnchor.BelowAllStaves
    override val pluginTrackTypes: Set<String> = emptySet()

    override fun layout(ctx: AnnotationLayoutContext): List<AnnotationElement> {
        val analysis = TheoryAnalysisComputer.analyze(ctx.computedScore.runtime) ?: return emptyList()
        return analysis.findingsByTime.map { (time, findings) ->
            AnnotationElement.Text.plain(
                time = time,
                relativeY = 0f,
                sourceEventId = TheoryAnalysisComputer.annotationEventId(time),
                trackId = TrackId("mecon.theory-analysis.rules"),
                text = findings.size.toString(),
                fontSize = 12f,
                color = findings.maxSeverityColor(),
                alignment = AnnotationAlignment.CENTER,
                interactive = true,
            )
        }
    }
}

internal object TheoryAnalysisNoteStyleProvider : NoteStyleProvider {
    override val pluginTrackTypes: Set<String> = emptySet()

    override fun computeStyles(computedScore: ComputedScore): Map<Pair<EventId, Int>, StyleOverride> {
        val analysis = TheoryAnalysisComputer.analyze(computedScore.runtime) ?: return emptyMap()
        val styles = linkedMapOf<Pair<EventId, Int>, StyleOverride>()

        for (finding in analysis.findings) {
            val color = finding.finding.ruleColor()
            for (eventId in finding.anchors) {
                styles.apply(eventId, StyleOverride(fillColor = color))
            }
        }

        activeFindings(analysis).forEachIndexed { index, finding ->
            val anchorColor = finding.finding.ruleColor()
            for (eventId in finding.anchors) {
                styles.apply(
                    eventId,
                    StyleOverride(
                        fillColor = anchorColor,
                        backgroundColor = anchorColor.withAlpha(if (isHovered(finding)) 92 else 58),
                    )
                )
            }
            val relatedColor = RELATED_COLORS[index % RELATED_COLORS.size]
            finding.relatedAnchors.flatMap { it.anchors }.forEach { eventId ->
                styles.apply(
                    eventId,
                    StyleOverride(
                        fillColor = relatedColor,
                        backgroundColor = relatedColor.withAlpha(48),
                    )
                )
            }
        }

        return styles
    }

    private fun activeFindings(analysis: TheoryAnalysisResult): List<TheoryAnalysisFinding> {
        val hovered = TheoryAnalysisInteraction.hoveredFindingId
        if (hovered != null) {
            analysis.findings.firstOrNull { it.id == hovered }?.let { return listOf(it) }
        }

        val annotation = TheoryAnalysisInteraction.selectedAnnotationEventId
        if (annotation != null) {
            analysis.findingsByTime.entries
                .firstOrNull { TheoryAnalysisComputer.annotationEventId(it.key) == annotation }
                ?.value
                ?.let { return it }
        }

        val selected = TheoryAnalysisInteraction.selectedEventId
        if (selected != null) return analysis.findings.filter { it.contains(selected) }

        return emptyList()
    }

    private fun MutableMap<Pair<EventId, Int>, StyleOverride>.apply(eventId: EventId, override: StyleOverride) {
        val key = eventId to 0
        this[key] = this[key]?.mergeOver(override) ?: override
    }

    private fun isHovered(finding: TheoryAnalysisFinding): Boolean =
        TheoryAnalysisInteraction.hoveredFindingId == finding.id

    private val RELATED_COLORS = listOf(
        RenderColor.rgb(37, 99, 235),
        RenderColor.rgb(147, 51, 234),
        RenderColor.rgb(20, 184, 166),
        RenderColor.rgb(202, 138, 4),
    )
}

internal fun List<TheoryAnalysisFinding>.maxSeverityColor(): RenderColor =
    minByOrNull { it.finding.severity.rank }?.finding?.ruleColor() ?: RenderColor.rgb(100, 116, 139)

internal fun RuleFinding<EventId>.ruleColor(): RenderColor =
    when {
        kind == RuleFindingKind.INDICATION -> RenderColor.rgb(22, 163, 74)
        kind == RuleFindingKind.HINT -> RenderColor.rgb(37, 99, 235)
        severity == RuleSeverity.HARD -> RenderColor.rgb(220, 38, 38)
        severity == RuleSeverity.SOFT -> RenderColor.rgb(234, 88, 12)
        else -> RenderColor.rgb(100, 116, 139)
    }

private val RuleSeverity.rank: Int
    get() = when (this) {
        RuleSeverity.HARD -> 0
        RuleSeverity.SOFT -> 1
        RuleSeverity.HINT -> 2
    }

private fun RenderColor.withAlpha(alpha: Int): RenderColor =
    copy(alpha = alpha)
