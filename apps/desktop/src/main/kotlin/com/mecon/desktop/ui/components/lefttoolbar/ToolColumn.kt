package com.mecon.desktop.ui.components.lefttoolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.ui.components.EditTool
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.uikit.theme.MeconColors

/** The leftmost column: selection and insertion tools. */
@Composable
internal fun ToolColumn(
    state: NoteToolState,
    bravura: FontFamily?,
    showScoreElementTool: Boolean = true,
) {
    Column(
        modifier = Modifier
            .width(40.dp)
            .fillMaxHeight()
            .background(MeconColors.Background)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = MeconColors.Border,
                    start = Offset(size.width - strokeWidth / 2, 0f),
                    end = Offset(size.width - strokeWidth / 2, size.height),
                    strokeWidth = strokeWidth
                )
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Select tool — SMuFL has no pointer glyph, so this keeps the Material cursor icon.
        IconToolButton(
            icon = Icons.Default.NearMe,
            label = "选择",
            isSelected = state.tool == EditTool.SELECT,
            onClick = { state.selectPointerTool() }
        )
        // Marquee tool — dashed-frame icon; clicking selects like the pointer, dragging rubber-bands.
        IconToolButton(
            icon = Icons.Default.HighlightAlt,
            label = "框选",
            isSelected = state.tool == EditTool.MARQUEE,
            onClick = { state.selectMarqueeTool() }
        )
        // Note pen — rendered as a quarter note from Bravura. It is a *pure palette switch*: its
        // highlight tracks palette visibility (not the active tool) and clicking it only shows/hides
        // the palette. Note-entry mode starts by picking a value from the palette / a shortcut.
        GlyphToolButton(
            glyph = Smufl.NOTE_QUARTER,
            label = "音符",
            font = bravura,
            isSelected = state.paletteExpanded,
            onClick = { state.togglePalette() }
        )
        if (showScoreElementTool) {
            // The treble clef is ~1.76em tall — larger than a notehead — so it renders smaller than
            // the note pen to stay within the 28dp button.
            GlyphToolButton(
                glyph = Smufl.CLEF_TREBLE,
                label = "乐谱元素",
                font = bravura,
                sizeSp = 14.sp,
                isSelected = state.scoreElementPaletteExpanded,
                onClick = { state.toggleScoreElementPalette() }
            )
        }
    }
}

/** Compact horizontal form used by score editors whose controls live above the notation surface. */
@Composable
internal fun ToolButtonRow(
    state: NoteToolState,
    bravura: FontFamily?,
    showScoreElementTool: Boolean = true,
) {
    Row {
        IconToolButton(
            icon = Icons.Default.NearMe,
            label = "选择",
            isSelected = state.tool == EditTool.SELECT,
            onClick = { state.selectPointerTool() },
        )
        IconToolButton(
            icon = Icons.Default.HighlightAlt,
            label = "框选",
            isSelected = state.tool == EditTool.MARQUEE,
            onClick = { state.selectMarqueeTool() },
        )
        GlyphToolButton(
            glyph = Smufl.NOTE_QUARTER,
            label = "音符",
            font = bravura,
            isSelected = state.paletteExpanded,
            onClick = { state.togglePalette() },
        )
        if (showScoreElementTool) {
            GlyphToolButton(
                glyph = Smufl.CLEF_TREBLE,
                label = "乐谱元素",
                font = bravura,
                sizeSp = 14.sp,
                isSelected = state.scoreElementPaletteExpanded,
                onClick = { state.toggleScoreElementPalette() },
            )
        }
    }
}

/** Tool-column button backed by a Material vector icon (used for the pointer-less select tool). */
@Composable
internal fun IconToolButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    ToolbarTooltip(label) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(isSelected, isPressed, isHovered))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault
            )
        }
    }
}

/** Tool-column button rendering a Bravura glyph (used for the note pen and the clef icon). */
@Composable
internal fun GlyphToolButton(
    glyph: String,
    label: String,
    font: FontFamily?,
    isSelected: Boolean,
    sizeSp: TextUnit = 18.sp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    ToolbarTooltip(label) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(isSelected, isPressed, isHovered))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            MusicGlyph(
                glyph = glyph,
                font = font,
                sizeSp = sizeSp,
                color = if (isSelected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
