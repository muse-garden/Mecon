package com.mecon.desktop.ui.components.lefttoolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecon.desktop.uikit.theme.MeconColors

/** A beam icon button that shows the SMuFL [glyph] (used for the isolated eighth note). */
@Composable
internal fun BeamGlyphButton(
    glyph: String,
    font: FontFamily?,
    selected: Boolean,
    tooltip: String,
    onClick: () -> Unit,
) {
    WithTooltip(tooltip) {
        GlyphToggleButton(glyph, font, selected, onClick = onClick)
    }
}

internal enum class GraceModeIcon {
    APPOGGIATURA,
    ACCIACCATURA,
    SMALL_NOTE,
}

/** Symbol-only grace/small-note mode button with a visible hover tooltip. */
@Composable
internal fun GraceModeButton(
    icon: GraceModeIcon,
    font: FontFamily?,
    selected: Boolean,
    tooltip: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val color = if (selected) MeconColors.SelectedIconOnSurface else MeconColors.IconDefault
    WithTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(CELL)
                .clip(RoundedCornerShape(4.dp))
                .background(cellBackground(selected, isPressed, isHovered))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .semantics { contentDescription = tooltip },
            contentAlignment = Alignment.Center,
        ) {
            when (icon) {
                GraceModeIcon.APPOGGIATURA,
                GraceModeIcon.ACCIACCATURA -> {
                    MusicGlyph(
                        glyph = Smufl.NOTE_8TH,
                        font = font,
                        sizeSp = 18.sp,
                        color = color,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (icon == GraceModeIcon.ACCIACCATURA) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawLine(
                                color = color,
                                start = Offset(size.width * 0.30f, size.height * 0.67f),
                                end = Offset(size.width * 0.68f, size.height * 0.31f),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }

                GraceModeIcon.SMALL_NOTE -> {
                    MusicGlyph(
                        glyph = Smufl.NOTE_8TH,
                        font = font,
                        sizeSp = 21.sp,
                        color = color.copy(alpha = 0.28f),
                        modifier = Modifier.fillMaxSize().offset(x = (-5).dp, y = (-1).dp),
                    )
                    MusicGlyph(
                        glyph = Smufl.NOTE_8TH,
                        font = font,
                        sizeSp = 14.sp,
                        color = color,
                        modifier = Modifier.fillMaxSize().offset(x = 6.dp, y = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * A beam icon button drawn in Canvas.
 *
 * [beamLeft]/[beamRight] control beam stubs on the single-note icons.
 * [isGroup] draws the two-note beam-group icon with brackets instead.
 */
@Composable
internal fun BeamPatternButton(
    beamLeft: Boolean,
    beamRight: Boolean,
    isGroup: Boolean,
    font: FontFamily?,
    selected: Boolean,
    enabled: Boolean = true,
    tooltip: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val color = when {
        !enabled -> MeconColors.TextMuted.copy(alpha = 0.35f)
        selected -> MeconColors.SelectedIconOnSurface
        else -> MeconColors.IconDefault
    }
    WithTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(CELL)
                .clip(RoundedCornerShape(4.dp))
                .background(if (enabled) cellBackground(selected, isPressed, isHovered) else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isGroup) {
                BeamGroupGlyphIcon(font, color)
            } else {
                BeamNoteGlyphIcon(font, beamLeft, beamRight, color)
            }
        }
    }
}

@Composable
private fun BeamNoteGlyphIcon(
    font: FontFamily?,
    beamLeft: Boolean,
    beamRight: Boolean,
    color: Color,
) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val glyph = drawBeamQuarterGlyph(measurer, font, GLYPH_SIZE, color)
        val stemX = glyph.xAtStaffSpaces(NOTE_QUARTER_STEM_X)
        val stemTop = glyph.yAtStaffSpaces(NOTE_QUARTER_TOP_Y)
        val stemWidth = glyph.staffSpacesToPx(STEM_THICKNESS)
        val beamThickness = glyph.staffSpacesToPx(BEAM_THICKNESS)
        val stubLength = glyph.staffSpacesToPx(1.35f)
        if (beamLeft) {
            drawBeamBar(
                color,
                Offset(stemX - stubLength, stemTop),
                Offset(stemX + stemWidth / 2f, stemTop),
                beamThickness,
            )
        }
        if (beamRight) {
            drawBeamBar(
                color,
                Offset(stemX - stemWidth / 2f, stemTop),
                Offset(stemX + stubLength, stemTop),
                beamThickness,
            )
        }
    }
}

@Composable
private fun BeamGroupGlyphIcon(font: FontFamily?, color: Color) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val left = drawBeamQuarterGlyph(measurer, font, 16.sp, color, Offset((-6).dp.toPx(), 1.dp.toPx()))
        val right = drawBeamQuarterGlyph(measurer, font, 16.sp, color, Offset(7.dp.toPx(), (-1).dp.toPx()))
        val beamThickness = left.staffSpacesToPx(BEAM_THICKNESS)
        val stemWidth = left.staffSpacesToPx(STEM_THICKNESS)
        drawBeamBar(
            color,
            Offset(left.xAtStaffSpaces(NOTE_QUARTER_STEM_X) - stemWidth / 2f, left.yAtStaffSpaces(NOTE_QUARTER_TOP_Y)),
            Offset(right.xAtStaffSpaces(NOTE_QUARTER_STEM_X) + stemWidth / 2f, right.yAtStaffSpaces(NOTE_QUARTER_TOP_Y)),
            beamThickness,
        )

        val bracketStroke = size.width * 0.04f
        val bracketTop = size.height * 0.14f
        val bracketBottom = size.height * 0.86f
        val armLength = size.width * 0.08f
        drawBeamGroupBracket(color, size.width * 0.07f, bracketTop, bracketBottom, armLength, bracketStroke, true)
        drawBeamGroupBracket(color, size.width * 0.93f, bracketTop, bracketBottom, armLength, bracketStroke, false)
    }
}

private data class BeamGlyphPlacement(
    val originX: Float,
    val baselineY: Float,
    val emPx: Float,
) {
    fun staffSpacesToPx(value: Float): Float = value / 4f * emPx
    fun xAtStaffSpaces(value: Float): Float = originX + staffSpacesToPx(value)
    fun yAtStaffSpaces(value: Float): Float = baselineY - staffSpacesToPx(value)
}

private fun DrawScope.drawBeamQuarterGlyph(
    measurer: TextMeasurer,
    font: FontFamily?,
    sizeSp: TextUnit,
    color: Color,
    offset: Offset = Offset.Zero,
): BeamGlyphPlacement {
    val style = TextStyle(fontFamily = font, fontSize = sizeSp, color = color)
    val layout = measurer.measure(AnnotatedString(Smufl.NOTE_QUARTER), style)
    val emPx = sizeSp.toPx()
    val originX = (size.width - layout.size.width) / 2f + offset.x
    val baselineY = size.height / 2f + emPx * glyphBias(Smufl.NOTE_QUARTER) + offset.y
    drawText(layout, topLeft = Offset(originX, baselineY - layout.firstBaseline))
    return BeamGlyphPlacement(originX, baselineY, emPx)
}

// Bravura metadata, in staff spaces:
// noteQuarterUp bbox NE = [1.328, 3.5], noteheadBlack stemUpSE = [1.18, 0.168].
private const val NOTE_QUARTER_TOP_Y = 3.5f
private const val NOTE_QUARTER_STEM_X = 1.24f
private const val STEM_THICKNESS = 0.12f
private const val BEAM_THICKNESS = 0.5f

private fun DrawScope.drawBeamBar(color: Color, start: Offset, end: Offset, thickness: Float) {
    val path = Path().apply {
        moveTo(start.x, start.y - 1f)
        lineTo(end.x, end.y - 1f)
        lineTo(end.x, end.y + thickness - 1f)
        lineTo(start.x, start.y + thickness - 1f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawBeamGroupBracket(
    color: Color,
    x: Float,
    top: Float,
    bottom: Float,
    armLength: Float,
    stroke: Float,
    opensRight: Boolean,
) {
    val armEndX = if (opensRight) x + armLength else x - armLength
    drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = stroke, cap = StrokeCap.Square)
    drawLine(color, Offset(x, top), Offset(armEndX, top), strokeWidth = stroke, cap = StrokeCap.Square)
    drawLine(color, Offset(x, bottom), Offset(armEndX, bottom), strokeWidth = stroke, cap = StrokeCap.Square)
}

/** Wraps [content] in a plain tooltip that appears on hover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text, fontSize = 11.sp) } },
        state = rememberTooltipState(),
        content = content,
    )
}
