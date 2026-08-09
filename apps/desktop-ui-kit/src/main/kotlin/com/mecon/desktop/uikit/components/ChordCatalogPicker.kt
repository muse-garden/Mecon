package com.mecon.desktop.uikit.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

enum class ChordToneLabelMode {
    RELATIVE,
    ABSOLUTE,
}

data class ChordCatalogPickerChoice(
    val identity: String,
    val functionalSymbol: String,
    val absoluteTones: List<String>,
    val relativeTones: List<String>,
    /** Optional tonal-overlap color supplied by the caller. */
    val accent: Color? = null,
    /** Short alternate-reading label, for example “另 2 调”. */
    val annotation: String? = null,
)

data class ChordCatalogPickerGroup(
    val id: String,
    val title: String,
    val description: String,
    val choices: List<ChordCatalogPickerChoice>,
)

data class ChordCatalogPickerStrings(
    val chooseChord: String,
    val currentChord: (String) -> String,
    val chordTones: String,
    val relativePitch: String,
    val absolutePitch: String,
)

/**
 * Reusable chord catalog selector. Domain discovery, ordering, and localization are supplied by
 * the caller, so the component is usable by free practice, chord analysis, and plugins.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChordCatalogPicker(
    groups: List<ChordCatalogPickerGroup>,
    selectedIdentity: String?,
    strings: ChordCatalogPickerStrings,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxExpandedHeight: androidx.compose.ui.unit.Dp = 250.dp,
    toneMode: ChordToneLabelMode? = null,
    onToneModeChange: (ChordToneLabelMode) -> Unit = {},
    showToneModeControls: Boolean = true,
) {
    var internalToneMode by remember { mutableStateOf(ChordToneLabelMode.RELATIVE) }
    val effectiveToneMode = toneMode ?: internalToneMode
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (showToneModeControls) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(strings.chordTones, color = MeconColors.TextMuted, fontSize = 11.sp)
            CatalogChip(strings.relativePitch, effectiveToneMode == ChordToneLabelMode.RELATIVE) {
                internalToneMode = ChordToneLabelMode.RELATIVE
                onToneModeChange(ChordToneLabelMode.RELATIVE)
            }
            CatalogChip(strings.absolutePitch, effectiveToneMode == ChordToneLabelMode.ABSOLUTE) {
                internalToneMode = ChordToneLabelMode.ABSOLUTE
                onToneModeChange(ChordToneLabelMode.ABSOLUTE)
            }
        }
        Column(
            modifier = Modifier
                .heightIn(max = maxExpandedHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            groups.forEach { group ->
                Text(
                    "${group.title}：${group.description}",
                    color = MeconColors.TextMuted,
                    fontSize = 11.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    group.choices.forEach { chord ->
                        val tones = when (effectiveToneMode) {
                            ChordToneLabelMode.ABSOLUTE -> chord.absoluteTones
                            ChordToneLabelMode.RELATIVE -> chord.relativeTones
                        }
                        CatalogChip(
                            buildString {
                                append(chord.functionalSymbol)
                                append(" · ")
                                append(tones.joinToString("–"))
                                chord.annotation?.let {
                                    append(" · ")
                                    append(it)
                                }
                            },
                            selectedIdentity == chord.identity,
                            accent = chord.accent ?: MeconColors.Primary,
                            unselectedAccent = chord.accent,
                            onClick = { onSelect(chord.identity) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogChip(
    label: String,
    selected: Boolean,
    accent: Color = MeconColors.Primary,
    unselectedAccent: Color? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) {
            accent.copy(alpha = 0.28f)
        } else {
            unselectedAccent?.copy(alpha = 0.14f) ?: MeconColors.SurfaceDark
        },
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (selected) accent else MeconColors.Border),
    ) {
        Text(
            label,
            color = if (selected) MeconColors.TextPrimary else MeconColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
        )
    }
}
