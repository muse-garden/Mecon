package com.mecon.plugins.chord.desktop

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.mecon.api.primitive.KeySignature
import com.mecon.desktop.uikit.PitchMode
import com.mecon.desktop.uikit.SmuflGlyph

private val WHITE_KEY_PCS     = listOf(0, 2, 4, 5, 7, 9, 11)
private val WHITE_KEY_LETTERS = listOf("C", "D", "E", "F", "G", "A", "B")
private val PC_TO_LETTER: Map<Int, String> = WHITE_KEY_PCS.zip(WHITE_KEY_LETTERS).toMap()

private val ACCIDENTAL_SIZE = TextUnit(1.0f, TextUnitType.Em)

private fun accidentalStyle(fontFamily: FontFamily?) =
    SpanStyle(fontSize = ACCIDENTAL_SIZE, fontFamily = fontFamily, baselineShift = BaselineShift(0.1f))

private fun sharpGlyph(fontFamily: FontFamily?) =
    if (fontFamily != null) SmuflGlyph.CHORD_SHARP else "♯"

private fun flatGlyph(fontFamily: FontFamily?) =
    if (fontFamily != null) SmuflGlyph.CHORD_FLAT else "♭"

/**
 * Label parts for one pitch class:
 * - Diatonic note  → 1-element list  ("C" or "3")
 * - Chromatic note → 2-element list  (["♯C", "♭D"] or ["♯2", "♭3"])
 *
 * When [bravuraFont] is provided the accidental characters use SMuFL glyphs
 * (U+E262 / U+E260) rendered in Bravura; otherwise Unicode ♯/♭ are used.
 * Accidentals use an 85 % em-relative span so they scale with any base size.
 */
internal fun pitchClassLabelParts(
    pc: Int,
    pitchMode: PitchMode,
    keySignature: KeySignature,
    bravuraFont: FontFamily? = null
): List<AnnotatedString> {
    val accStyle = accidentalStyle(bravuraFont)
    val sharp    = sharpGlyph(bravuraFont)
    val flat     = flatGlyph(bravuraFont)

    return if (pitchMode == PitchMode.ABSOLUTE) {
        val letter = PC_TO_LETTER[pc]
        if (letter != null) {
            listOf(AnnotatedString(letter))
        } else {
            val lowerIdx = WHITE_KEY_PCS.indexOfLast { it < pc }
            val upperIdx = WHITE_KEY_PCS.indexOfFirst { it > pc }
            listOf(
                buildAnnotatedString { withStyle(accStyle) { append(sharp) }; append(WHITE_KEY_LETTERS[lowerIdx]) },
                buildAnnotatedString { withStyle(accStyle) { append(flat)  }; append(WHITE_KEY_LETTERS[upperIdx]) }
            )
        }
    } else {
        val rootPc    = keySignature.root.value
        val semitones = keySignature.mode.semitones()
        val offset    = (pc - rootPc + 12) % 12
        val degreeIdx = semitones.indexOf(offset)
        if (degreeIdx >= 0) {
            listOf(AnnotatedString((degreeIdx + 1).toString()))
        } else {
            val lowerIdx = semitones.indexOfLast  { it < offset }
            val upperIdx = semitones.indexOfFirst { it > offset }
            val sharpDeg = if (lowerIdx >= 0) (lowerIdx + 1).toString() else "7"
            val flatDeg  = if (upperIdx >= 0) (upperIdx + 1).toString() else "1"
            listOf(
                buildAnnotatedString { withStyle(accStyle) { append(sharp) }; append(sharpDeg) },
                buildAnnotatedString { withStyle(accStyle) { append(flat)  }; append(flatDeg)  }
            )
        }
    }
}

/**
 * Single [AnnotatedString] joining chromatic parts with "/" for compact inline use.
 * For two-line display in canvas, use [pitchClassLabelParts] with separate drawText calls.
 */
internal fun pitchClassAnnotatedLabel(
    pc: Int,
    pitchMode: PitchMode,
    keySignature: KeySignature,
    bravuraFont: FontFamily? = null
): AnnotatedString {
    val parts = pitchClassLabelParts(pc, pitchMode, keySignature, bravuraFont)
    return if (parts.size == 1) parts[0]
    else buildAnnotatedString {
        append(parts[0])
        append("/")
        append(parts[1])
    }
}
