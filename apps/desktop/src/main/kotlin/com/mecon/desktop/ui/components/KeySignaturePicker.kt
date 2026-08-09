package com.mecon.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.api.primitive.KeySignature
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.desktop.ui.views.SimpleScoreView
import com.mecon.renderer.render.RenderElementType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeySignaturePicker(
    selected: KeySignature,
    clef: Clef,
    onSelect: (KeySignature) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: KeySignature? = selected,
) {
    FlowRow(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KEY_SIGNATURE_OPTIONS.forEach { key ->
            KeySignatureCard(
                keySignature = key,
                clef = clef,
                selected = key == highlighted,
            ) { onSelect(key) }
        }
    }
}

/** Major keys: C, sharps from few to many, then flats from few to many. */
val KEY_SIGNATURE_OPTIONS: List<KeySignature> =
    (listOf(0) + (1..7) + (-1 downTo -7)).map(KeySignature::majorByFifths)

@Composable
private fun KeySignatureCard(
    keySignature: KeySignature,
    clef: Clef,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) CARD_PAPER_SELECTED else CARD_PAPER)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) CARD_BORDER_SELECTED else CARD_BORDER,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KeySignaturePreview(
            clef = clef,
            keySignature = keySignature,
            modifier = Modifier.size(width = 64.dp, height = 40.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = keySignature.displayName,
            color = if (selected) CARD_LABEL_SELECTED else CARD_LABEL,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun KeySignaturePreview(
    clef: Clef,
    keySignature: KeySignature,
    modifier: Modifier = Modifier,
) {
    val score = remember(clef, keySignature) {
        RuntimeScore.fromStorage(
            StorageScore.create(StorageScore.CreationOptions(
                keySignature = keySignature,
                layout = if (clef == Clef.BASS) StaffLayoutPreset.BASS else StaffLayoutPreset.TREBLE,
                measureCount = 1,
            ))
        )
    }
    SimpleScoreView(
        score = score,
        modifier = modifier,
        alignment = Alignment.CenterStart,
        visibleTypes = KEY_PREVIEW_VISIBLE_TYPES,
        cropTypes = KEY_PREVIEW_CROP_TYPES,
    )
}

private val KEY_PREVIEW_VISIBLE_TYPES = setOf(
    RenderElementType.STAFF,
    RenderElementType.CLEF,
    RenderElementType.KEY_SIGNATURE,
)
private val KEY_PREVIEW_CROP_TYPES = setOf(RenderElementType.CLEF, RenderElementType.KEY_SIGNATURE)

private val CARD_PAPER = Color(0xFFF8FAFC)
private val CARD_PAPER_SELECTED = Color(0xFFE0E7FF)
private val CARD_BORDER = Color(0xFFCBD5E1)
private val CARD_BORDER_SELECTED = Color(0xFF6366F1)
private val CARD_LABEL = Color(0xFF475569)
private val CARD_LABEL_SELECTED = Color(0xFF4338CA)
