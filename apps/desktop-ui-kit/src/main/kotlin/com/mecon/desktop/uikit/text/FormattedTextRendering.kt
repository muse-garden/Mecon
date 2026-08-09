package com.mecon.desktop.uikit.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.mecon.api.render.FormattedText
import com.mecon.api.render.TextBaseline
import com.mecon.api.render.TextRunStyle

/**
 * Convert the platform-agnostic [FormattedText] model into a Compose
 * [AnnotatedString]. Per-run sizes are emitted as `em` so they scale relative to
 * whatever base font size the host `Text` / `TextStyle` uses — the same
 * [FormattedText] therefore renders identically in the right-hand panels and on
 * the score (only the base size differs). A run whose [TextRunStyle.color] is
 * null inherits the host color.
 *
 * Shared by the annotation-staff renderer and the chord-analysis panel so both
 * present the agreed styling convention consistently.
 */
fun FormattedText.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        val style = run.style
        if (style == TextRunStyle.DEFAULT) {
            append(run.text)
        } else {
            withStyle(style.toSpanStyle()) { append(run.text) }
        }
    }
}

private fun TextRunStyle.toSpanStyle(): SpanStyle = SpanStyle(
    fontSize = if (sizeScale != 1f) sizeScale.em else TextUnit.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    baselineShift = when (baseline) {
        TextBaseline.SUPERSCRIPT -> BaselineShift.Superscript
        TextBaseline.SUBSCRIPT -> BaselineShift.Subscript
        TextBaseline.NORMAL -> null
    },
    color = color?.let { Color(it.toArgb()) } ?: Color.Unspecified,
)
