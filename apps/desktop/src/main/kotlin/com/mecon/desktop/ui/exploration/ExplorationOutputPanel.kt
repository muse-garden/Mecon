@file:OptIn(ExperimentalLayoutApi::class)

package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.engine.PlaybackState
import com.mecon.desktop.i18n.explorationText
import com.mecon.desktop.i18n.ruleLabel
import com.mecon.desktop.service.PlaybackController
import com.mecon.desktop.ui.views.RenderedScoreView
import com.mecon.desktop.ui.views.RenderedScoreViewConfig
import com.mecon.desktop.ui.views.RenderedScoreDisplayConfig
import com.mecon.desktop.ui.views.RenderedScoreSelectionConfig
import com.mecon.desktop.ui.views.RenderedScoreSource
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.exploration.CandidateComparison
import com.mecon.exploration.CellOutput
import com.mecon.exploration.OutputCandidate
import com.mecon.exploration.StoredFinding
import com.mecon.plugins.chord.ChordSymbolDisplaySettings
import com.mecon.theory.ChordSymbolDisplayStyle
import com.mecon.theory.RuleId

@Composable
internal fun OutputPanel(
    output: CellOutput?,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    running: Boolean,
    playback: PlaybackController,
) {
    Surface(color = MeconColors.Surface, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(explorationText("output.title"), color = MeconColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            when {
                running -> Text(explorationText("output.searching"), color = MeconColors.TextSecondary, fontSize = 12.sp)
                output == null -> Text(explorationText("output.empty"), color = MeconColors.TextMuted, fontSize = 12.sp)
                output.diagnostics.isNotEmpty() -> output.diagnostics.forEach {
                    Text(it, color = MeconColors.Red, fontSize = 12.sp)
                }
                output.candidates.isEmpty() -> Text(explorationText("output.noCandidates"), color = MeconColors.TextMuted, fontSize = 12.sp)
                else -> CandidateView(
                    candidates = output.candidates,
                    comparisonGroups = output.comparisonGroups,
                    selectedIndex = selectedIndex.coerceIn(output.candidates.indices),
                    onSelectIndex = onSelectIndex,
                    playback = playback,
                )
            }
        }
    }
}

@Composable
private fun CandidateView(
    candidates: List<OutputCandidate>,
    comparisonGroups: List<CandidateComparison>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    playback: PlaybackController,
) {
    val candidate = candidates[selectedIndex]
    var chordSymbolStyle by remember { mutableStateOf(ChordSymbolDisplaySettings.style) }
    val displayScore = remember(candidate.score) { candidate.score.withRecognizedChordTrack() }
    val runtimeScore = remember(displayScore) { RuntimeScore.fromStorage(displayScore) }
    val playbackState by playback.playbackState.collectAsState()
    val currentPositionTicks by playback.currentPositionTicks.collectAsState()
    var selection by remember(candidate) { mutableStateOf<Set<EventSection>>(emptySet()) }
    var hoveredFindingId by remember(candidate) { mutableStateOf<String?>(null) }
    val selectedEventId = selection.lastOrNull()?.eventId()
    val visibleFindings = remember(candidate, selectedEventId) {
        selectedEventId?.let { eventId ->
            candidate.findings.filter { it.contains(eventId) }
        } ?: candidate.findings
    }
    val activeFinding = hoveredFindingId?.let { id -> candidate.findings.firstOrNull { it.localId == id } }
    val localStyles = remember(candidate, activeFinding, selectedEventId) {
        buildFindingStyles(candidate.findings, activeFinding, selectedEventId)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEachIndexed { index, item ->
                ModeChip(explorationText("output.candidate", index + 1, item.totalScore.formatScore()), selected = index == selectedIndex) {
                    onSelectIndex(index)
                }
            }
        }

        ComparisonHint(comparisonGroups = comparisonGroups, selectedIndex = selectedIndex)

        ChordSymbolModeToggle(
            style = chordSymbolStyle,
            onStyleChange = { style ->
                chordSymbolStyle = style
                ChordSymbolDisplaySettings.style = style
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Icons.Default.SkipPrevious, explorationText("output.playFromStart")) {
                playback.playFromStart(runtimeScore)
            }
            TransportButton(
                icon = if (playbackState == PlaybackState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                label = if (playbackState == PlaybackState.PLAYING) {
                    explorationText("output.pause")
                } else {
                    explorationText("output.play")
                },
            ) {
                if (playbackState == PlaybackState.PLAYING) playback.pause() else playback.playFromCurrent(runtimeScore)
            }
            TransportButton(Icons.Default.PlaylistPlay, explorationText("output.playFromSelection"), enabled = selectedEventId != null) {
                playback.playFromSelection(runtimeScore, selection.lastOrNull()?.timeCode())
            }
            selectedEventId?.let {
                Text(explorationText("output.selectedEvent", it.value), color = MeconColors.TextMuted, fontSize = 11.sp)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MeconColors.ScoreBackground),
        ) {
            RenderedScoreView(
                config = RenderedScoreViewConfig(
                    source = RenderedScoreSource(runtimeScore),
                    selectionConfig = RenderedScoreSelectionConfig(
                        selection = selection,
                        onSelectionChange = { selection = it },
                        localEventStyles = localStyles,
                    ),
                    display = RenderedScoreDisplayConfig(
                        readOnly = true,
                        panEnabled = true,
                        zoomEnabled = true,
                        currentPositionTicks = currentPositionTicks,
                        playbackState = playbackState,
                        renderRefreshKey = chordSymbolStyle.ordinal,
                    ),
                ),
                modifier = Modifier.fillMaxWidth().height(320.dp),
            )
        }

        SummaryRow(candidate)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(explorationText("output.findings"), color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (selectedEventId != null) {
                Text(explorationText("output.findings.filtered"), color = MeconColors.TextMuted, fontSize = 10.sp)
            }
            visibleFindings.take(12).forEach { finding ->
                FindingRow(
                    finding = finding,
                    hovered = hoveredFindingId == finding.localId,
                    onHover = { hovered -> hoveredFindingId = if (hovered) finding.localId else null },
                )
            }
            if (visibleFindings.isEmpty()) {
                Text(explorationText("output.findings.empty"), color = MeconColors.TextMuted, fontSize = 11.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(explorationText("output.scoreBreakdown"), color = MeconColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            candidate.breakdownEntries.take(8).forEach {
                Text("${it.amount.formatScore()} · ${ruleLabel(RuleId(it.ruleId))}", color = MeconColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SummaryRow(candidate: OutputCandidate) {
    val hard = candidate.findings.count { it.severity == "HARD" }
    val soft = candidate.findings.count { it.severity == "SOFT" }
    val hints = candidate.findings.count { it.severity == "HINT" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill("HARD $hard")
        StatusPill("SOFT $soft")
        StatusPill("HINT $hints")
    }
}

@Composable
private fun ComparisonHint(comparisonGroups: List<CandidateComparison>, selectedIndex: Int) {
    val group = comparisonGroups.firstOrNull { comparison ->
        selectedIndex == comparison.correctCandidateIndex || selectedIndex == comparison.incorrectCandidateIndex
    } ?: return
    val isCorrect = selectedIndex == group.correctCandidateIndex
    val label = if (isCorrect) explorationText("output.correctExample") else explorationText("output.incorrectExample")
    val color = if (isCorrect) MeconColors.EmeraldLight else MeconColors.OrangeLight
    Text(
        "$label · ${group.title}",
        color = color,
        fontSize = 11.sp,
    )
}

@Composable
private fun ChordSymbolModeToggle(
    style: ChordSymbolDisplayStyle,
    onStyleChange: (ChordSymbolDisplayStyle) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(explorationText("output.chordSymbols"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        ModeChip(explorationText("output.chordSymbols.letter"), selected = style == ChordSymbolDisplayStyle.LETTER) {
            onStyleChange(ChordSymbolDisplayStyle.LETTER)
        }
        ModeChip(explorationText("output.chordSymbols.degree"), selected = style == ChordSymbolDisplayStyle.SCALE_DEGREE) {
            onStyleChange(ChordSymbolDisplayStyle.SCALE_DEGREE)
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MeconColors.SurfaceLight.copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = MeconColors.IconDefault)
        Text(label, color = MeconColors.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun FindingRow(
    finding: StoredFinding,
    hovered: Boolean,
    onHover: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        onHover(isHovered)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) MeconColors.HoverBackground else MeconColors.SurfaceLight)
            .hoverable(interactionSource)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${finding.severity} · ${ruleLabel(RuleId(finding.ruleId))}",
                color = finding.textColor(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            if (finding.isDemonstrationTarget) {
                Text(explorationText("output.demonstrationTarget"), color = MeconColors.PrimaryLight, fontSize = 10.sp)
            }
        }
        Text(finding.messageKey, color = MeconColors.TextSecondary, fontSize = 11.sp)
        if (finding.anchors.isNotEmpty()) {
            Text(
                explorationText("output.noteAnchors", finding.anchors.joinToString(" ") { it.value.substringAfterLast('-') }),
                color = MeconColors.TextMuted,
                fontSize = 10.sp,
            )
        }
    }
}
