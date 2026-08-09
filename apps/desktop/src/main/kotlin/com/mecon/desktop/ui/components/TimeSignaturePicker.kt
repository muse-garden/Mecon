package com.mecon.desktop.ui.components

import com.mecon.desktop.uikit.components.meconTextInputFocus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.mecon.desktop.uikit.components.MeconDropdownItem
import com.mecon.desktop.uikit.components.MeconDropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import com.mecon.desktop.ui.views.SimpleScoreView
import com.mecon.desktop.uikit.theme.MeconColors
import com.mecon.renderer.render.RenderElementType

/**
 * Shared time-signature picker used by both the new-score dialog and the left score-element
 * palette. Offers a row of common meters (each engraved through [SimpleScoreView] so the glyph
 * matches the editor exactly), a custom `N / D` entry (N free, D a power-of-two dropdown), and —
 * for compound / irregular meters — a beam-grouping selector (e.g. 7/8 as 2+2+3 vs 3+2+2).
 *
 * Fully driven by [selected]; every change (chip, custom N/D, grouping) is emitted via [onSelect].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeSignaturePicker(
    selected: TimeSignature,
    onSelect: (TimeSignature) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: TimeSignature? = selected,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Common meters ─────────────────────────────────────────────────
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            COMMON_TIME_SIGNATURES.forEach { ts ->
                TimeSignatureChip(ts = ts, selected = highlighted?.let(ts::sameMeter) == true) { onSelect(ts) }
            }
        }

        // ── Custom N / D ──────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自定义", color = MeconColors.TextSecondary, fontSize = 12.sp)
            NumeratorField(numerator = selected.numerator) { n ->
                onSelect(TimeSignature(n, selected.denominator))
            }
            Text("/", color = Color.White, fontSize = 16.sp)
            DenominatorDropdown(denominator = selected.denominator) { d ->
                onSelect(TimeSignature(selected.numerator, d))
            }
        }

        // ── Beam grouping (compound / irregular only) ─────────────────────
        val candidates = selected.beatGroupCandidates()
        if (candidates.size > 1) {
            val current = selected.beatGrouping()
            Text("默认分组 (beam)", color = MeconColors.TextSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { groups ->
                    GroupingChip(label = groups.joinToString("+"), selected = highlighted != null && groups == current) {
                        onSelect(selected.copy(beatGroups = groups))
                    }
                }
            }
            GroupingField(current = current, numerator = selected.numerator) { parsed ->
                onSelect(selected.copy(beatGroups = parsed))
            }
        }
    }
}

/** Whether two meters describe the same numerator/denominator/symbol (ignoring beam grouping). */
private fun TimeSignature.sameMeter(other: TimeSignature): Boolean =
    numerator == other.numerator && denominator == other.denominator && symbol == other.symbol

/** Common meters offered as one-tap chips (shared with the new-score dialog). */
val COMMON_TIME_SIGNATURES: List<TimeSignature> = listOf(
    TimeSignature.COMMON,
    TimeSignature.THREE_FOUR,
    TimeSignature.TWO_FOUR,
    TimeSignature.CUT,
    TimeSignature.SIX_EIGHT,
    TimeSignature.NINE_EIGHT,
    TimeSignature.TWELVE_EIGHT,
)

private val DENOMINATOR_OPTIONS = listOf(1, 2, 4, 8, 16, 32)

// ── Pieces ────────────────────────────────────────────────────────────────

@Composable
private fun TimeSignatureChip(ts: TimeSignature, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MeconColors.SelectedSurface else MeconColors.SurfaceLight)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MeconColors.SelectedBorder else MeconColors.Border,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        TimeSignatureGlyph(
            ts = ts,
            ink = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault,
            modifier = Modifier.size(width = 34.dp, height = 40.dp),
        )
    }
}

/**
 * The engraved numerator/denominator glyph, drawn through the shared [SimpleScoreView] from a real
 * one-measure score so it matches exactly how the editor renders the meter — not plain text.
 */
@Composable
private fun TimeSignatureGlyph(ts: TimeSignature, ink: Color, modifier: Modifier) {
    val score = remember(ts.numerator, ts.denominator, ts.symbol) {
        RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(timeSignature = ts, measureCount = 1)))
    }
    SimpleScoreView(
        score = score,
        modifier = modifier,
        alignment = Alignment.Center,
        foreground = ink,
        fitScale = 0.9f,
        visibleTypes = GLYPH_TYPES,
        cropTypes = GLYPH_TYPES,
        verticalFitTypes = GLYPH_TYPES,
    )
}

private val GLYPH_TYPES = setOf(RenderElementType.TIME_SIGNATURE)

@Composable
private fun NumeratorField(numerator: Int, onNumerator: (Int) -> Unit) {
    var text by remember { mutableStateOf(numerator.toString()) }
    // Re-sync when the meter changes from outside (chip / dropdown / external selection).
    LaunchedEffect(numerator) { if (text.toIntOrNull() != numerator) text = numerator.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }
            text.toIntOrNull()?.takeIf { it > 0 }?.let(onNumerator)
        },
        label = { Text("N", fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(64.dp).meconTextInputFocus(),
        colors = pickerFieldColors(),
    )
}

@Composable
private fun DenominatorDropdown(denominator: Int, onDenominator: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = MeconColors.SurfaceLight,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.clickable { expanded = true },
        ) {
            Text(
                text = "$denominator  ▾",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        MeconDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DENOMINATOR_OPTIONS.forEach { d ->
                MeconDropdownItem(
                    label = "$d",
                    onClick = { expanded = false; onDenominator(d) },
                )
            }
        }
    }
}

@Composable
private fun GroupingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = if (selected) MeconColors.SelectedSurface else MeconColors.SurfaceLight,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault,
            fontSize = 13.sp,
            modifier = Modifier
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun GroupingField(current: List<Int>, numerator: Int, onGrouping: (List<Int>) -> Unit) {
    var text by remember { mutableStateOf(current.joinToString("+")) }
    LaunchedEffect(current) {
        val parsed = parseGrouping(text, numerator)
        if (parsed != current) text = current.joinToString("+")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("自定义分组", color = MeconColors.TextSecondary, fontSize = 12.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new
                parseGrouping(new, numerator)?.let(onGrouping)
            },
            label = { Text("如 2+2+3", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.width(140.dp).meconTextInputFocus(),
            colors = pickerFieldColors(),
        )
    }
}

/** Parse "2+2+3" into a valid grouping (all parts > 0 and summing to [numerator]), else null. */
private fun parseGrouping(text: String, numerator: Int): List<Int>? {
    val parts = text.split("+").map { it.trim() }
    if (parts.any { it.isEmpty() }) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    if (nums.any { it <= 0 } || nums.sum() != numerator) return null
    return nums
}

@Composable
private fun pickerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MeconColors.Primary,
    unfocusedBorderColor = MeconColors.BorderLight,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = MeconColors.TextSecondary,
    unfocusedLabelColor = MeconColors.TextMuted,
    cursorColor = MeconColors.Primary,
)
