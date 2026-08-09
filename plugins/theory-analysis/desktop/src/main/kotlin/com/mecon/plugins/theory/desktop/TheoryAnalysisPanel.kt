package com.mecon.plugins.theory.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.desktop.uikit.i18n.i18n
import com.mecon.desktop.uikit.plugin.PluginPanel
import com.mecon.desktop.uikit.plugin.PluginPanelContext
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.theory.FixedVoiceDiagnosticCode
import com.mecon.theory.FixedVoiceLayout
import com.mecon.theory.FixedVoiceLoadDiagnostic
import com.mecon.theory.FixedVoiceRole
import com.mecon.theory.FixedVoiceScore
import com.mecon.theory.FixedVoiceScoreEvent
import com.mecon.theory.FourPartKeyboardDistribution
import com.mecon.theory.RuleAnchorRole
import com.mecon.theory.RuleFindingKind
import com.mecon.theory.RuleSeverity

internal object TheoryAnalysisPanel : PluginPanel {
    override val id: String = "mecon.theory_analysis"
    override val titleKey: String = "plugin.theory.panel.title"
    override val icon: ImageVector? = Icons.AutoMirrored.Filled.List
    override val initialHeightDp: Int = 260

    @Composable
    override fun Content(ctx: PluginPanelContext) {
        FixedVoiceAnalysisPanel(
            ctx = ctx,
            runtimeScore = ctx.runtimeScore,
            eventSelection = ctx.eventSelection,
        )
    }
}

@Composable
private fun FixedVoiceAnalysisPanel(
    ctx: PluginPanelContext,
    runtimeScore: RuntimeScore?,
    eventSelection: Set<EventSection>,
) {
    var distribution by remember { mutableStateOf(FourPartKeyboardDistribution.TREBLE_2_BASS_2) }
    val fixedState = remember(runtimeScore, distribution) {
        runtimeScore?.let { score ->
            val layout = runCatching { FixedVoiceLayout.fourPartKeyboard(score, distribution) }.getOrNull()
            when {
                layout == null -> FixedVoicePanelState.Invalid(
                    listOf(
                        FixedVoiceLoadDiagnostic(
                            code = FixedVoiceDiagnosticCode.STAFF_NOT_FOUND,
                            message = i18n("plugin.theory.panel.fixedVoice.error.needGrandStaff"),
                        )
                    )
                )
                else -> {
                    val diagnostics = FixedVoiceScore.validate(score, layout)
                    if (diagnostics.isEmpty()) {
                        FixedVoicePanelState.Ready(FixedVoiceScore.load(score, layout))
                    } else {
                        FixedVoicePanelState.Invalid(diagnostics)
                    }
                }
            }
        }
    }
    val selectedEventId = remember(eventSelection) { selectedVoiceEventId(eventSelection) }
    val selectedAnnotationEventId = ctx.selectedAnnotationEventId
    val analysis = remember(runtimeScore, distribution) {
        runtimeScore?.let { TheoryAnalysisComputer.analyze(it, distribution) }
    }

    LaunchedEffect(selectedEventId, selectedAnnotationEventId) {
        TheoryAnalysisInteraction.selectedEventId = selectedEventId
        TheoryAnalysisInteraction.selectedAnnotationEventId = selectedAnnotationEventId
        ctx.onRequestNoteStyleRecompute?.invoke()
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DistributionSelector(
            distribution = distribution,
            onChange = {
                distribution = it
                TheoryAnalysisInteraction.distribution = it
                ctx.onRequestRender?.invoke()
                ctx.onRequestNoteStyleRecompute?.invoke()
            }
        )

        when (val state = fixedState) {
            null -> StatusBox(
                text = i18n("plugin.theory.panel.fixedVoice.noScore"),
                tone = StatusTone.Muted,
            )
            is FixedVoicePanelState.Invalid -> InvalidState(diagnostics = state.diagnostics)
            is FixedVoicePanelState.Ready -> ReadyState(
                fixedScore = state.score,
                analysis = analysis,
                selectedEventId = selectedEventId,
                selectedAnnotationEventId = selectedAnnotationEventId,
                onRequestNoteStyleRecompute = ctx.onRequestNoteStyleRecompute,
            )
        }
    }
}

private sealed interface FixedVoicePanelState {
    data class Ready(val score: FixedVoiceScore) : FixedVoicePanelState
    data class Invalid(val diagnostics: List<FixedVoiceLoadDiagnostic>) : FixedVoicePanelState
}

@Composable
private fun DistributionSelector(
    distribution: FourPartKeyboardDistribution,
    onChange: (FourPartKeyboardDistribution) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = i18n("plugin.theory.panel.fixedVoice.distribution"),
            fontSize = 10.sp,
            color = MeconColors.TextMuted,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DistributionButton(
                label = i18n("plugin.theory.panel.fixedVoice.distribution.22"),
                selected = distribution == FourPartKeyboardDistribution.TREBLE_2_BASS_2,
                modifier = Modifier.weight(1f),
                onClick = { onChange(FourPartKeyboardDistribution.TREBLE_2_BASS_2) },
            )
            DistributionButton(
                label = i18n("plugin.theory.panel.fixedVoice.distribution.31"),
                selected = distribution == FourPartKeyboardDistribution.TREBLE_3_BASS_1,
                modifier = Modifier.weight(1f),
                onClick = { onChange(FourPartKeyboardDistribution.TREBLE_3_BASS_1) },
            )
        }
    }
}

@Composable
private fun DistributionButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    selected -> MeconColors.SelectedSurface
                    isHovered -> MeconColors.HoverBackground
                    else -> MeconColors.InputBackground
                }
            )
            .border(
                width = 1.dp,
                color = if (selected) MeconColors.SelectedBorder.copy(alpha = 0.85f) else MeconColors.Border,
                shape = RoundedCornerShape(4.dp),
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun InvalidState(diagnostics: List<FixedVoiceLoadDiagnostic>) {
    StatusBox(
        text = i18n("plugin.theory.panel.fixedVoice.invalid"),
        tone = StatusTone.Warning,
    )
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        diagnostics.take(4).forEach { diagnostic ->
            DiagnosticRow(diagnostic)
        }
        if (diagnostics.size > 4) {
            Text(
                text = i18n("plugin.theory.panel.fixedVoice.moreDiagnostics").format(diagnostics.size - 4),
                fontSize = 10.sp,
                color = MeconColors.TextDark,
            )
        }
    }
}

@Composable
private fun ReadyState(
    fixedScore: FixedVoiceScore,
    analysis: TheoryAnalysisResult?,
    selectedEventId: EventId?,
    selectedAnnotationEventId: EventId?,
    onRequestNoteStyleRecompute: (() -> Unit)?,
) {
    StatusBox(
        text = i18n("plugin.theory.panel.fixedVoice.ready").format(fixedScore.voices.size),
        tone = StatusTone.Ready,
    )
    VoiceMap(fixedScore)
    val selected = selectedEventId?.let { fixedScore.event(it) }
    SelectedVoiceContext(fixedScore = fixedScore, selected = selected)
    RuleFindingList(
        fixedScore = fixedScore,
        analysis = analysis,
        selectedEventId = selectedEventId,
        selectedAnnotationEventId = selectedAnnotationEventId,
        onRequestNoteStyleRecompute = onRequestNoteStyleRecompute,
    )
}

@Composable
private fun VoiceMap(fixedScore: FixedVoiceScore) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.InputBackground)
            .border(1.dp, MeconColors.Border)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = i18n("plugin.theory.panel.fixedVoice.voiceMap"),
            fontSize = 10.sp,
            color = MeconColors.TextMuted,
            fontWeight = FontWeight.Medium,
        )
        fixedScore.voices.forEach { voice ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = roleLabel(voice.role),
                    fontSize = 11.sp,
                    color = MeconColors.TextSecondary,
                )
                Text(
                    text = i18n("plugin.theory.panel.fixedVoice.staffVoice").format(
                        voice.staffIndex + 1,
                        voice.voiceIndexOnStaff + 1,
                    ),
                    fontSize = 11.sp,
                    color = MeconColors.TextDark,
                )
            }
        }
    }
}

@Composable
private fun RuleFindingList(
    fixedScore: FixedVoiceScore,
    analysis: TheoryAnalysisResult?,
    selectedEventId: EventId?,
    selectedAnnotationEventId: EventId?,
    onRequestNoteStyleRecompute: (() -> Unit)?,
) {
    val selectedTime = selectedAnnotationEventId?.let { annotationId ->
        analysis?.findingsByTime?.keys?.firstOrNull {
            TheoryAnalysisComputer.annotationEventId(it) == annotationId
        }
    }
    val findings = when {
        analysis == null -> emptyList()
        selectedTime != null -> analysis.findingsByTime[selectedTime].orEmpty()
        selectedEventId != null -> analysis.findings.filter { it.contains(selectedEventId) }
        else -> analysis.findings.take(8)
    }
    val title = when {
        selectedTime != null -> i18n("plugin.theory.panel.rules.time").format(selectedTime.format())
        selectedEventId != null -> i18n("plugin.theory.panel.rules.selection")
        else -> i18n("plugin.theory.panel.rules.summary")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.InputBackground)
            .border(1.dp, MeconColors.Border)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = title,
                fontSize = 10.sp,
                color = MeconColors.TextMuted,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = (analysis?.findings?.size ?: 0).toString(),
                fontSize = 10.sp,
                color = MeconColors.TextDark,
            )
        }
        if (analysis == null) {
            Text(
                text = i18n("plugin.theory.panel.rules.unavailable"),
                fontSize = 10.sp,
                color = MeconColors.TextDark,
            )
        } else if (findings.isEmpty()) {
            Text(
                text = i18n("plugin.theory.panel.rules.empty"),
                fontSize = 10.sp,
                color = MeconColors.TextDark,
            )
        } else {
            findings.forEach { finding ->
                RuleFindingRow(
                    fixedScore = fixedScore,
                    finding = finding,
                    onHoverChange = { hovered ->
                        TheoryAnalysisInteraction.hoveredFindingId = if (hovered) finding.id else null
                        onRequestNoteStyleRecompute?.invoke()
                    }
                )
            }
        }
    }
}

@Composable
private fun RuleFindingRow(
    fixedScore: FixedVoiceScore,
    finding: TheoryAnalysisFinding,
    onHoverChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        onHoverChange(isHovered)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) MeconColors.HoverBackground else MeconColors.InputBackground)
            .hoverable(interactionSource)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = ruleKindLabel(finding.finding.kind, finding.finding.severity),
                fontSize = 10.sp,
                color = when {
                    finding.finding.kind == RuleFindingKind.INDICATION -> MeconColors.EmeraldLight
                    finding.finding.severity == RuleSeverity.HARD -> MeconColors.Red
                    else -> MeconColors.OrangeLight
                },
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = finding.time.format(),
                fontSize = 10.sp,
                color = MeconColors.TextMuted,
            )
        }
        Text(
            text = finding.finding.message,
            fontSize = 11.sp,
            color = MeconColors.TextSecondary,
        )
        Text(
            text = i18n("plugin.theory.panel.rules.anchors").format(anchorLabels(fixedScore, finding.anchors)),
            fontSize = 10.sp,
            color = MeconColors.TextDark,
        )
        if (finding.relatedAnchors.isNotEmpty()) {
            Text(
                text = finding.relatedAnchors.joinToString("  ") { group ->
                    "${anchorRoleLabel(group.role)} ${anchorLabels(fixedScore, group.anchors)}"
                },
                fontSize = 10.sp,
                color = MeconColors.TextDark,
            )
        }
    }
}

@Composable
private fun SelectedVoiceContext(
    fixedScore: FixedVoiceScore,
    selected: FixedVoiceScoreEvent?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.InputBackground)
            .border(1.dp, MeconColors.Border)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = i18n("plugin.theory.panel.fixedVoice.selectionContext"),
            fontSize = 10.sp,
            color = MeconColors.TextMuted,
            fontWeight = FontWeight.Medium,
        )
        if (selected == null) {
            Text(
                text = i18n("plugin.theory.panel.fixedVoice.noFixedSelection"),
                fontSize = 10.sp,
                color = MeconColors.TextDark,
            )
            return
        }
        ContextRow(i18n("plugin.theory.panel.fixedVoice.selected"), eventLabel(selected))
        ContextRow(i18n("plugin.theory.panel.fixedVoice.previous"), eventLabel(fixedScore.previousInVoice(selected)))
        ContextRow(i18n("plugin.theory.panel.fixedVoice.next"), eventLabel(fixedScore.nextInVoice(selected)))
        val simultaneous = fixedScore.simultaneousNotes(selected)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = i18n("plugin.theory.panel.fixedVoice.vertical"),
                fontSize = 11.sp,
                color = MeconColors.TextMuted,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = simultaneous.joinToString(" ") { eventLabel(it) }.ifBlank { "-" },
                fontSize = 11.sp,
                color = MeconColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ContextRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = MeconColors.TextMuted)
        Text(value, fontSize = 11.sp, color = MeconColors.TextSecondary)
    }
}

@Composable
private fun DiagnosticRow(diagnostic: FixedVoiceLoadDiagnostic) {
    val text = when (diagnostic.code) {
        FixedVoiceDiagnosticCode.STAFF_NOT_FOUND -> diagnostic.message.ifBlank {
            i18n("plugin.theory.panel.fixedVoice.error.staffMissing")
        }
        FixedVoiceDiagnosticCode.STAFF_VOICE_COUNT_MISMATCH ->
            i18n("plugin.theory.panel.fixedVoice.error.voiceCount")
        FixedVoiceDiagnosticCode.CHORD_IN_MONOPHONIC_VOICE ->
            i18n("plugin.theory.panel.fixedVoice.error.chord").format(diagnostic.eventId?.value ?: "-")
    }
    Text(
        text = text,
        fontSize = 10.sp,
        color = MeconColors.OrangeLight,
    )
}

private fun ruleKindLabel(kind: RuleFindingKind, severity: RuleSeverity): String =
    when (kind) {
        RuleFindingKind.INDICATION -> i18n("plugin.theory.panel.rules.kind.indication")
        RuleFindingKind.HINT -> i18n("plugin.theory.panel.rules.kind.hint")
        RuleFindingKind.WARNING -> i18n("plugin.theory.panel.rules.kind.warning")
        RuleFindingKind.VIOLATION -> when (severity) {
            RuleSeverity.HARD -> i18n("plugin.theory.panel.rules.kind.hard")
            RuleSeverity.SOFT -> i18n("plugin.theory.panel.rules.kind.soft")
            RuleSeverity.HINT -> i18n("plugin.theory.panel.rules.kind.hint")
        }
    }

private fun anchorRoleLabel(role: RuleAnchorRole): String =
    when (role) {
        RuleAnchorRole.PRIMARY -> i18n("plugin.theory.panel.rules.role.primary")
        RuleAnchorRole.RELATED -> i18n("plugin.theory.panel.rules.role.related")
        RuleAnchorRole.SOURCE -> i18n("plugin.theory.panel.rules.role.source")
        RuleAnchorRole.TARGET -> i18n("plugin.theory.panel.rules.role.target")
        RuleAnchorRole.CONTEXT -> i18n("plugin.theory.panel.rules.role.context")
    }

private fun anchorLabels(fixedScore: FixedVoiceScore, anchors: List<EventId>): String =
    anchors.joinToString(" ") { id ->
        fixedScore.event(id)?.let { event ->
            "${roleLabel(event.voice.role)}:${eventLabel(event)}"
        } ?: id.value
    }.ifBlank { "-" }

private enum class StatusTone {
    Ready,
    Warning,
    Muted,
}

@Composable
private fun StatusBox(text: String, tone: StatusTone) {
    val color = when (tone) {
        StatusTone.Ready -> MeconColors.EmeraldLight
        StatusTone.Warning -> MeconColors.OrangeLight
        StatusTone.Muted -> MeconColors.TextMuted
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MeconColors.InputBackground)
            .border(1.dp, color.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun selectedVoiceEventId(selection: Set<EventSection>): EventId? =
    selection.lastOrNull()?.let { section ->
        when (section) {
            is VoiceNoteSection -> section.event.id
            is VoiceEventSection -> section.event.id
            else -> null
        }
    }

private fun roleLabel(role: FixedVoiceRole?): String =
    when (role) {
        FixedVoiceRole.SOPRANO -> i18n("plugin.theory.panel.fixedVoice.role.soprano")
        FixedVoiceRole.ALTO -> i18n("plugin.theory.panel.fixedVoice.role.alto")
        FixedVoiceRole.TENOR -> i18n("plugin.theory.panel.fixedVoice.role.tenor")
        FixedVoiceRole.BASS -> i18n("plugin.theory.panel.fixedVoice.role.bass")
        FixedVoiceRole.BARITONE -> i18n("plugin.theory.panel.fixedVoice.role.baritone")
        FixedVoiceRole.INNER -> i18n("plugin.theory.panel.fixedVoice.role.inner")
        FixedVoiceRole.OUTER -> i18n("plugin.theory.panel.fixedVoice.role.outer")
        null -> i18n("plugin.theory.panel.fixedVoice.role.unspecified")
    }

private fun eventLabel(event: FixedVoiceScoreEvent?): String =
    event?.pitch?.format() ?: "-"
