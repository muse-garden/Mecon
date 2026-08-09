package com.mecon.desktop.uikit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** SMuFL codepoints in Bravura for accidentals. */
object SmuflGlyph {
    const val FLAT  = "\uE260"  // accidentalFlat
    const val SHARP = "\uE262"  // accidentalSharp
    // Standard accidentals for chord symbols — consistent width, designed for inline text
    const val CHORD_FLAT  = "\uED60"  // accidentalFlatSmall
    const val CHORD_SHARP = "\uED62"  // accidentalSharpSmall
}

/**
 * Loads Bravura.otf from the host application's classpath resources.
 * Returns null and logs a warning if the font is not found.
 */
@Composable
fun rememberBravuraFont(): FontFamily? = remember {
    try {
        FontFamily(Font(resource = "fonts/Bravura.otf", weight = FontWeight.Normal))
    } catch (e: Exception) {
        println("Warning: could not load Bravura font: ${e.message}")
        null
    }
}
