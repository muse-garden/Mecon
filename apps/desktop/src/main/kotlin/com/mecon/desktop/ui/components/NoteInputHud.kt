package com.mecon.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.runtime.RuntimeScore
import com.mecon.desktop.input.NoteInputEntryMode
import com.mecon.desktop.input.NoteInputPhase
import com.mecon.desktop.input.NoteInputState
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.input.InputPitchMode

@Composable
fun NoteInputHud(
    state: NoteInputState,
    runtime: RuntimeScore?,
    midiDeviceName: String?,
    onCycleMidiDevice: () -> Unit,
    onToggleEntryMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.active || runtime == null) return
    val center = state.centerPitch(runtime)?.format() ?: "—"
    val range = state.pitchRange(runtime)
    val rangeText = range?.let { "${it.low.format()}–${it.high.format()}" } ?: "—"
    val caret = state.caret
    val phase = when (state.phase) {
        NoteInputPhase.STEP_READY -> "步进"
        NoteInputPhase.REALTIME_ARMED -> "实时·待命"
        NoteInputPhase.RECORDING -> "实时·录音"
        NoteInputPhase.INACTIVE -> ""
    }
    val pitchMode = when (state.pitchMode) {
        InputPitchMode.ABSOLUTE -> "绝对"
        InputPitchMode.RELATIVE_TO_KEY -> "相对"
    }

    Column(
        modifier = modifier
            .background(MeconColors.Surface.copy(alpha = 0.94f), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(phase, onToggleEntryMode)
            HudChip(pitchMode, state::togglePitchMode)
            HudChip("MIDI:${midiDeviceName ?: "无"}", onCycleMidiDevice)
            HudChip("${state.anchorKey.name}=$center", state::cycleAnchorKey)
            if (state.entryMode == NoteInputEntryMode.REALTIME) {
                HudChip("1/${state.quantizeDenominator} · ${state.allowedTuplets.sorted().joinToString("/")}", state::cycleQuantization)
            }
            Text(text = rangeText, color = Color.White, fontSize = 12.sp)
        }
        Text(
            text = "m${caret?.onset?.measure ?: "—"} ${caret?.onset?.beat ?: "0"} · " +
                if (state.entryMode == NoteInputEntryMode.REALTIME) {
                    "声部 ${caret?.voiceNumber ?: "—"} · Space/Esc 停止并提交"
                } else {
                    "声部 ${caret?.voiceNumber ?: "—"} · ↑↓ 八度  ←→ 回溯  Esc 退出"
                },
            color = MeconColors.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun HudChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .background(MeconColors.Primary.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
