@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mecon.desktop.ui.exploration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.i18n.explorationText
import com.mecon.desktop.uikit.components.CircleOfFifthsPicker
import com.mecon.desktop.uikit.components.FifthsKey
import com.mecon.desktop.uikit.components.FifthsKeyMode
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.exploration.KeyModeSpec
import com.mecon.exploration.ModulationSolverSpec
import com.mecon.theory.ChordSymbolFormatter
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationChordCandidate
import com.mecon.theory.ModulationChordId
import com.mecon.theory.ModulationCommonChordCatalog
import com.mecon.theory.ModulationKey
import com.mecon.theory.ModulationPitchDisplayMode
import com.mecon.theory.ModulationPitchLabels

internal data class ModulationEditorState(
    val sourceKey: ModulationKey,
    val selectedTargetKeys: Set<ModulationKey>,
    val selectedChordIds: Set<ModulationChordId>,
    val pitchDisplayMode: ModulationPitchDisplayMode,
    val solverPreset: ModulationSolverSpec,
    val sourceChordCount: Int,
    val targetChordCount: Int,
)

internal data class ModulationEditorActions(
    val changeSourceFifths: (Int) -> Unit,
    val changeSourceMode: (KeyModeSpec) -> Unit,
    val toggleTargetKey: (ModulationKey) -> Unit,
    val toggleChord: (ModulationChordId) -> Unit,
    val changePitchDisplayMode: (ModulationPitchDisplayMode) -> Unit,
    val changeSolverPreset: (ModulationSolverSpec) -> Unit,
    val changeSourceChordCount: (Int) -> Unit,
    val changeTargetChordCount: (Int) -> Unit,
)

@Composable
internal fun ModulationEditor(
    state: ModulationEditorState,
    actions: ModulationEditorActions,
) {
    val queryKeys = remember(state.sourceKey, state.selectedTargetKeys) {
        listOf(state.sourceKey) + state.selectedTargetKeys
            .filterNot { it == state.sourceKey }
            .sortedWith(compareBy<ModulationKey> { it.fifths }.thenBy { it.mode.ordinal })
    }
    val commonChords = remember(queryKeys) {
        ModulationCommonChordCatalog.commonChords(queryKeys)
    }
    val matchingKeys = remember(state.selectedChordIds) {
        if (state.selectedChordIds.isEmpty()) emptySet()
        else ModulationCommonChordCatalog.keysContaining(state.selectedChordIds).toSet()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModulationSettings(state, actions)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.width(600.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    explorationText("modulation.circle.title"),
                    color = MeconColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Text(
                    explorationText("modulation.circle.hint"),
                    color = MeconColors.TextMuted,
                    fontSize = 11.sp,
                )
                CircleOfFifthsPicker(
                    currentKey = state.sourceKey.toFifthsKey(),
                    selectedKeys = state.selectedTargetKeys.mapTo(linkedSetOf()) { it.toFifthsKey() },
                    matchedKeys = matchingKeys.mapTo(linkedSetOf()) { it.toFifthsKey() },
                    size = 400.dp,
                    centerLabel = state.sourceKey.displayName +
                        if (state.sourceKey.mode == KeySignatureMode.MINOR) "m" else "",
                    centerCaption = explorationText("modulation.currentKey"),
                    label = { key ->
                        modulationCircleLabel(
                            key.toModulationKey(),
                            state.sourceKey,
                            state.pitchDisplayMode,
                        )
                    },
                    onKeyClick = { key ->
                        key.toModulationKey().takeIf { it != state.sourceKey }?.let(actions.toggleTargetKey)
                    },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    explorationText("modulation.chords.title", commonChords.size),
                    color = MeconColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                if (commonChords.isEmpty()) {
                    Text(
                        explorationText("modulation.chords.empty"),
                        color = MeconColors.TextMuted,
                        fontSize = 12.sp,
                    )
                } else {
                    commonChords.forEach { candidate ->
                        CommonChordCard(
                            candidate = candidate,
                            selected = candidate.id in state.selectedChordIds,
                            displayMode = state.pitchDisplayMode,
                            onClick = { actions.toggleChord(candidate.id) },
                        )
                    }
                }
                Text(
                    explorationText("modulation.chords.inverseHint"),
                    color = MeconColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ModulationSettings(
    state: ModulationEditorState,
    actions: ModulationEditorActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(explorationText("modulation.sourceKey"), color = MeconColors.TextSecondary, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val sourceMode = when (state.sourceKey.mode) {
                KeySignatureMode.MAJOR -> KeyModeSpec.MAJOR
                KeySignatureMode.MINOR -> KeyModeSpec.MINOR
            }
            listOf(KeyModeSpec.MAJOR, KeyModeSpec.MINOR).forEach { mode ->
                ModeChip(
                    explorationText(
                        if (mode == KeyModeSpec.MAJOR) "editor.key.major" else "editor.key.minor"
                    ),
                    selected = sourceMode == mode,
                ) { actions.changeSourceMode(mode) }
            }
            (-7..7).forEach { fifths ->
                val label = ModulationKey(fifths, state.sourceKey.mode).displayName
                ModeChip(label, selected = state.sourceKey.fifths == fifths) {
                    actions.changeSourceFifths(fifths)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledChips(explorationText("modulation.pitchMode")) {
                ModeChip(
                    explorationText("modulation.pitch.absolute"),
                    state.pitchDisplayMode == ModulationPitchDisplayMode.ABSOLUTE,
                ) { actions.changePitchDisplayMode(ModulationPitchDisplayMode.ABSOLUTE) }
                ModeChip(
                    explorationText("modulation.pitch.relative"),
                    state.pitchDisplayMode == ModulationPitchDisplayMode.RELATIVE,
                ) { actions.changePitchDisplayMode(ModulationPitchDisplayMode.RELATIVE) }
            }
            LabeledChips(explorationText("modulation.solver")) {
                ModulationSolverSpec.entries.forEach { preset ->
                    ModeChip(
                        explorationText(
                            if (preset == ModulationSolverSpec.FREE) {
                                "modulation.solver.free"
                            } else {
                                "modulation.solver.schoenberg"
                            }
                        ),
                        state.solverPreset == preset,
                    ) { actions.changeSolverPreset(preset) }
                }
            }
            LabeledChips(explorationText("modulation.beforeCount")) {
                (1..6).forEach { count ->
                    ModeChip(count.toString(), state.sourceChordCount == count) {
                        actions.changeSourceChordCount(count)
                    }
                }
            }
            LabeledChips(explorationText("modulation.afterCount")) {
                (2..8).forEach { count ->
                    ModeChip(count.toString(), state.targetChordCount == count) {
                        actions.changeTargetChordCount(count)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledChips(
    label: String,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MeconColors.TextMuted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}

@Composable
private fun CommonChordCard(
    candidate: ModulationChordCandidate,
    selected: Boolean,
    displayMode: ModulationPitchDisplayMode,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MeconColors.Primary.copy(alpha = 0.2f) else MeconColors.SurfaceDark,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MeconColors.Primary else MeconColors.Border,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val first = candidate.interpretations.first()
            val symbol = first.absoluteTones.first().toString() +
                ChordSymbolFormatter.qualitySuffix(candidate.id.quality)
            Text(symbol, color = MeconColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            candidate.interpretations.forEach { interpretation ->
                val keyMode = if (interpretation.key.mode == KeySignatureMode.MAJOR) {
                    KeyModeSpec.MAJOR
                } else {
                    KeyModeSpec.MINOR
                }
                val function = romanDegree(interpretation.degree, keyMode, candidate.id.quality)
                val tones = when (displayMode) {
                    ModulationPitchDisplayMode.ABSOLUTE ->
                        interpretation.absoluteTones.joinToString("–")
                    ModulationPitchDisplayMode.RELATIVE ->
                        interpretation.relativeTones.joinToString("–")
                }
                Text(
                    "${interpretation.key.displayName}${if (interpretation.key.mode == KeySignatureMode.MINOR) "m" else ""} · $function · $tones",
                    color = MeconColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

internal fun modulationCircleLabel(
    key: ModulationKey,
    sourceKey: ModulationKey,
    displayMode: ModulationPitchDisplayMode,
): String =
    when (displayMode) {
        ModulationPitchDisplayMode.ABSOLUTE ->
            if (key.mode == KeySignatureMode.MINOR) key.displayName.lowercase() else key.displayName
        ModulationPitchDisplayMode.RELATIVE ->
            ModulationPitchLabels.relativeTonicLabel(sourceKey, key)
    }

internal fun ModulationKey.toFifthsKey(): FifthsKey = FifthsKey(
    fifths = fifths,
    mode = if (mode == KeySignatureMode.MAJOR) FifthsKeyMode.MAJOR else FifthsKeyMode.MINOR,
)

internal fun FifthsKey.toModulationKey(): ModulationKey = ModulationKey(
    fifths = fifths,
    mode = if (mode == FifthsKeyMode.MAJOR) KeySignatureMode.MAJOR else KeySignatureMode.MINOR,
)
