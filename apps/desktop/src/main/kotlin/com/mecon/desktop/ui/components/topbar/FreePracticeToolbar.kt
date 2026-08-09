package com.mecon.desktop.ui.components.topbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.ui.exploration.FreePracticeToolbarController
import com.mecon.desktop.ui.exploration.FreePracticeWorkbenchLayout
import com.mecon.desktop.ui.exploration.ExplorationToolbarController
import com.mecon.desktop.uikit.components.MeconChoiceChip
import com.mecon.desktop.uikit.components.CompactNumberInput
import com.mecon.desktop.uikit.components.MeconDropdownPanel
import com.mecon.desktop.uikit.components.MeconDropdownPanelTitle
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.writing.GrandStaffVoiceLayout
import com.mecon.api.primitive.Fraction

@Composable
internal fun ExplorationModeToolbar(controller: ExplorationToolbarController) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        MeconChoiceChip("自动求解", !controller.freePracticeMode) {
            controller.changeMode(false)
        }
        MeconChoiceChip("自由练习", controller.freePracticeMode) {
            controller.changeMode(true)
        }
    }
}

@Composable
internal fun FreePracticeToolbar(controller: FreePracticeToolbarController) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        MeconChoiceChip(
            "经典布局",
            controller.workbenchLayout == FreePracticeWorkbenchLayout.CLASSIC,
        ) { controller.changeWorkbenchLayout(FreePracticeWorkbenchLayout.CLASSIC) }
        MeconChoiceChip(
            "分区布局",
            controller.workbenchLayout == FreePracticeWorkbenchLayout.WRITING_WITH_LOWER_PANELS,
        ) {
            controller.changeWorkbenchLayout(
                FreePracticeWorkbenchLayout.WRITING_WITH_LOWER_PANELS,
            )
        }
    }
    ToolbarDivider()
    ToolbarButton(
        icon = Icons.Default.Refresh,
        label = "重新写作",
        enabled = !controller.writingState.running,
        onClick = controller.rewriteSelection,
    )
    ToolbarButton(
        icon = Icons.Default.SwapHoriz,
        label = "换一个结果",
        enabled = !controller.writingState.running && controller.writingState.canAlternate,
        onClick = controller.alternate,
    )
    ToolbarDivider()
    ToolbarButton(
        icon = Icons.Default.Edit,
        label = "自动写作",
        isActive = controller.writingSettings.autoWritingEnabled,
        enabled = !controller.writingState.running,
        onClick = {
            controller.changeWritingSettings(
                controller.writingSettings.copy(
                    autoWritingEnabled = !controller.writingSettings.autoWritingEnabled,
                ),
            )
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IntegerSetting("回溯和弦", controller.writingSettings.backtrackChordCount, 0..16) {
            controller.changeWritingSettings(
                controller.writingSettings.copy(backtrackChordCount = it),
            )
        }
        IntegerSetting("回放个数", controller.writingSettings.replayChordCount, 0..16) {
            controller.changeWritingSettings(
                controller.writingSettings.copy(replayChordCount = it),
            )
        }
        IntegerSetting("BPM", controller.writingSettings.playbackTempoBpm, 30..240) {
            controller.changeWritingSettings(
                controller.writingSettings.copy(playbackTempoBpm = it),
            )
        }
        IntegerSetting("声部数", controller.voiceCount, 3..6) {
            controller.changeVoiceCount(it)
        }
        IntegerSetting(
            "上谱声部",
            controller.staffVoices.upperVoiceCount,
            1 until controller.voiceCount,
        ) { upper ->
            controller.changeStaffVoices(
                GrandStaffVoiceLayout(upper, controller.voiceCount - upper),
            )
        }
        GridUnitSetting(controller.gridUnit, controller.changeGridUnit)
        IntegerSetting("和弦默认拍数", controller.defaultChordBeats, 1..16) {
            controller.changeDefaultChordBeats(it)
        }
    }
    KeySetting(controller.initialKey, controller.changeInitialKey)
    controller.writingState.message?.let { message ->
        Text(
            text = message,
            color = MeconColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun IntegerSetting(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, color = MeconColors.TextMuted, fontSize = 9.sp)
        CompactNumberInput(
            value = value,
            range = range,
            onValueChange = onChange,
            modifier = Modifier.width(72.dp),
            dense = true,
        )
    }
}

@Composable
private fun GridUnitSetting(selected: Fraction, onChange: (Fraction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "四分音符" to Fraction.QUARTER,
        "八分音符" to Fraction.EIGHTH,
        "十六分音符" to Fraction(1, 16),
        "三十二分音符" to Fraction(1, 32),
        "六十四分音符" to Fraction(1, 64),
    )
    val label = options.firstOrNull { it.second == selected }?.first ?: "八分音符"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("自动吸附单位", color = MeconColors.TextMuted, fontSize = 9.sp)
        Box {
            MeconChoiceChip(label, selected = expanded) { expanded = true }
            MeconDropdownPanel(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(132.dp),
            ) {
                options.forEach { (optionLabel, value) ->
                    MeconChoiceChip(optionLabel, selected == value) {
                        expanded = false
                        onChange(value)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeySetting(selected: ModulationKey, onChange: (ModulationKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val fifths = listOf(0) + (1..7) + (-1 downTo -7)

    Box {
        ToolbarButton(
            icon = Icons.Default.MusicNote,
            label = selected.displayName,
            isActive = expanded,
            onClick = { expanded = true },
        )
        MeconDropdownPanel(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(250.dp),
        ) {
            MeconDropdownPanelTitle("调式")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MeconChoiceChip("大调", selected.mode == KeySignatureMode.MAJOR) {
                    expanded = false
                    onChange(ModulationKey(selected.fifths, KeySignatureMode.MAJOR))
                }
                MeconChoiceChip("小调", selected.mode == KeySignatureMode.MINOR) {
                    expanded = false
                    onChange(ModulationKey(selected.fifths, KeySignatureMode.MINOR))
                }
            }
            MeconDropdownPanelTitle("调性")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                fifths.forEach { fifth ->
                    val key = ModulationKey(fifth, selected.mode)
                    MeconChoiceChip(key.displayName, key == selected) {
                        expanded = false
                        onChange(key)
                    }
                }
            }
        }
    }
}
