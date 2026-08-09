package com.mecon.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * Provides the Bravura music notation font for Compose Desktop.
 */
object BravuraFontProvider {

    /**
     * Load the Bravura font family from resources.
     */
    fun loadBravuraFont(): FontFamily? {
        return try {
            FontFamily(
                Font(
                    resource = "fonts/Bravura.otf",
                    weight = FontWeight.Normal
                )
            )
        } catch (e: Exception) {
            println("Warning: Could not load Bravura font: ${e.message}")
            null
        }
    }
}

/**
 * Composable function to remember the Bravura font family.
 */
@Composable
fun rememberBravuraFont(): FontFamily? {
    return remember { BravuraFontProvider.loadBravuraFont() }
}
