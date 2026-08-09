package com.mecon.desktop.ui.components.lefttoolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/**
 * Shared building blocks for the [com.mecon.desktop.ui.components.LeftToolbar] palettes: the
 * common cell size, glyph-centring helper, and small toggle/label composables reused by the tool
 * column, note palette, score-element palette, and beam icons.
 */

internal val CELL = 30.dp
internal val GLYPH_SIZE = 20.sp

/** Shared plain tooltip used by every compact score-toolbar control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarTooltip(text: String, content: @Composable () -> Unit) {
    if (text.isBlank()) {
        content()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(text, fontSize = 11.sp) } },
            state = rememberTooltipState(),
            content = content,
        )
    }
}

/**
 * How far a glyph's baseline should sit below the cell centre, as a fraction of the em.
 * SMuFL note glyphs draw their ink mostly above the baseline (notehead on the baseline, stem
 * rising), so the baseline must drop below centre for the ink to look centred; symmetric glyphs
 * (sharp/natural) need no shift. Mirrors the firstBaseline-relative trick used by the Tonnetz canvas.
 */
internal fun glyphBias(glyph: String): Float = when (glyph) {
    Smufl.NOTE_HALF, Smufl.NOTE_QUARTER, Smufl.NOTE_8TH, Smufl.NOTE_16TH,
    Smufl.NOTE_32ND, Smufl.NOTE_64TH, Smufl.NOTE_128TH, Smufl.NOTE_256TH -> 0.45f
    Smufl.NOTE_DOUBLE_WHOLE, Smufl.NOTE_WHOLE, Smufl.NOTE_LONGA, Smufl.NOTE_MAXIMA -> 0.12f
    // Bravura's quarter rest has a low ink box; keep it at the same visual centre as the Web
    // palette instead of applying the note glyph's downward stem bias.
    Smufl.REST_QUARTER -> 0.12f
    Smufl.ACC_FLAT, Smufl.ACC_DOUBLE_FLAT -> 0.14f
    // gClef ink spans +4.392..-2.632 staff spaces; its centre sits 0.22em above the baseline.
    Smufl.CLEF_TREBLE -> 0.22f
    else -> 0.0f // sharp / natural / double-sharp / augmentation dot — roughly baseline-centred
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text = text, fontSize = 10.sp, color = MeconColors.TextMuted)
}

@Composable
internal fun CollapsibleSectionLabel(text: String, expanded: Boolean, onClick: () -> Unit) {
    ToolbarTooltip(text) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = text, fontSize = 10.sp, color = MeconColors.IconDefault)
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MeconColors.IconDefault,
            )
        }
    }
}

@Composable
internal fun PaletteRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
internal fun TextToggleButton(
    text: String,
    selected: Boolean,
    tooltip: String = "",
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    ToolbarTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(CELL)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(selected, isPressed, isHovered))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault,
            )
        }
    }
}

/** Voice selector with a small fixed-color identity bar below its slightly raised number. */
@Composable
internal fun VoiceToggleButton(
    number: Int,
    selected: Boolean,
    tooltip: String = "",
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val voiceColor = MeconColors.voiceToolbarColor(number)
    ToolbarTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(CELL)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(selected, isPressed, isHovered))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineWidth = 12.dp.toPx()
                val y = size.height - 4.dp.toPx()
                drawLine(
                    color = voiceColor,
                    start = Offset((size.width - lineWidth) / 2f, y),
                    end = Offset((size.width + lineWidth) / 2f, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            Text(
                text = number.toString(),
                fontSize = 13.sp,
                color = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault,
                modifier = Modifier.offset(y = (-2).dp),
            )
        }
    }
}

/** A 36dp cell rendering a Bravura glyph, used as a toggle. */
@Composable
internal fun GlyphToggleButton(
    glyph: String,
    font: FontFamily?,
    selected: Boolean,
    sizeSp: TextUnit = GLYPH_SIZE,
    letterSpacing: TextUnit = 0.sp,
    tooltip: String = "",
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    ToolbarTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(CELL)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(selected, isPressed, isHovered))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            MusicGlyph(
                glyph = glyph,
                font = font,
                sizeSp = sizeSp,
                letterSpacing = letterSpacing,
                color = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Draws a single SMuFL glyph centred in its box. Unlike [Text], which centres the font's whole
 * line box (leaving music glyphs sitting low), this measures the glyph and positions it by its
 * baseline plus a per-glyph [glyphBias], so the ink is visually centred.
 */
@Composable
internal fun MusicGlyph(
    glyph: String,
    font: FontFamily?,
    sizeSp: TextUnit,
    letterSpacing: TextUnit = 0.sp,
    color: Color,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val bias = glyphBias(glyph)
    Canvas(modifier) {
        val style = TextStyle(
            fontFamily = font,
            fontSize = sizeSp,
            letterSpacing = letterSpacing,
            color = color,
        )
        val layout = measurer.measure(AnnotatedString(glyph), style)
        val emPx = sizeSp.toPx()
        val x = (size.width - layout.size.width) / 2f
        val y = size.height / 2f - layout.firstBaseline + emPx * bias
        drawText(layout, topLeft = Offset(x, y))
    }
}

/** Expand/collapse chevron for the uncommon-durations row. */
@Composable
internal fun ChevronButton(
    expanded: Boolean,
    horizontal: Boolean = false,
    tooltip: String = "",
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    ToolbarTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(CELL)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(false, isPressed, isHovered))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val icon = if (horizontal) {
                if (expanded) Icons.AutoMirrored.Filled.KeyboardArrowLeft
                else Icons.AutoMirrored.Filled.KeyboardArrowRight
            } else {
                if (expanded) Icons.Default.KeyboardArrowUp
                else Icons.Default.KeyboardArrowDown
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MeconColors.IconDefault,
            )
        }
    }
}

/** Tie/slur button: Bravura quarter-note glyphs plus a hand-drawn curve (SMuFL has no curve glyph). */
@Composable
internal fun CurveNotePairButton(
    samePitch: Boolean,
    font: FontFamily?,
    selected: Boolean,
    enabled: Boolean,
    tooltip: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val curveColor = when {
        !enabled -> MeconColors.TextMuted.copy(alpha = 0.4f)
        selected -> MeconColors.SelectedIconOnSurface
        else -> MeconColors.IconDefault
    }
    ToolbarTooltip(tooltip) {
        Box(
            modifier = Modifier
                .width(CELL * 1.5f + 2.dp)
                .height(CELL)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(selected, isPressed, isHovered))
                .semantics { contentDescription = tooltip }
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center
        ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 3.dp)) {
            val startX = 5.dp.toPx()
            val endX = size.width - 5.dp.toPx()
            val startY = 12.dp.toPx()
            val endY = startY + if (samePitch) 0f else -4.dp.toPx()
            val depth = 5.dp.toPx()
            val thickness = 1.3.dp.toPx()
            val cx = (startX + endX) / 2f
            val controlY = minOf(startY, endY) - depth
            val path = Path().apply {
                moveTo(startX, startY)
                quadraticTo(cx, controlY, endX, endY)
                quadraticTo(cx, controlY + thickness, startX, startY)
                close()
            }
            drawPath(path = path, color = curveColor)
        }
        MusicGlyph(
            glyph = Smufl.NOTE_QUARTER,
            font = font,
            sizeSp = 22.sp,
            color = curveColor,
            modifier = Modifier
                .size(22.dp, CELL)
                .align(Alignment.CenterStart)
                .offset(x = 1.dp, y = (-1).dp),
        )
        MusicGlyph(
            glyph = Smufl.NOTE_QUARTER,
            font = font,
            sizeSp = 22.sp,
            color = curveColor,
            modifier = Modifier
                .size(22.dp, CELL)
                .align(Alignment.CenterEnd)
                .offset(x = (-1).dp, y = if (samePitch) (-1).dp else (-6).dp),
        )
        }
    }
}

@Composable
internal fun DisabledGlyph(glyph: String?, font: FontFamily?) {
    Box(
        modifier = Modifier
            .size(CELL)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (glyph != null) {
            MusicGlyph(
                glyph = glyph,
                font = font,
                sizeSp = GLYPH_SIZE,
                color = MeconColors.TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = "▾", fontSize = 12.sp, color = MeconColors.TextMuted.copy(alpha = 0.4f))
        }
    }
}

internal fun cellBackground(selected: Boolean, pressed: Boolean, hovered: Boolean): Color = when {
    selected -> MeconColors.SelectedSurface
    pressed -> MeconColors.HoverBackgroundLight
    hovered -> MeconColors.HoverBackground
    else -> Color.Transparent
}
