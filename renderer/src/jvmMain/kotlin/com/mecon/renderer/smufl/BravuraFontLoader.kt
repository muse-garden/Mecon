package com.mecon.renderer.smufl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JVM implementation of BravuraFontLoader.
 * Loads Bravura font resources from the assets directory.
 */
actual class BravuraFontLoader {

    /**
     * Load the Bravura font from resources.
     * Tries multiple locations:
     * 1. Classpath resources
     * 2. assets/bravura directory relative to working directory
     */
    actual suspend fun load(): BravuraFont? = withContext(Dispatchers.IO) {
        try {
            val metadataJson = loadResource("bravuraMetadata.json")
            val glyphNamesJson = loadResource("glyphnames.json")

            if (metadataJson != null && glyphNamesJson != null) {
                BravuraFont.fromJson(metadataJson, glyphNamesJson)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun loadResource(filename: String): String? {
        // Try classpath first
        val classPathResource = javaClass.classLoader?.getResourceAsStream("bravura/$filename")
        if (classPathResource != null) {
            return classPathResource.bufferedReader().use { it.readText() }
        }

        // Try assets directory
        val assetsFile = File("assets/bravura/$filename")
        if (assetsFile.exists()) {
            return assetsFile.readText()
        }

        // Try relative to project root
        val projectFile = File("../assets/bravura/$filename")
        if (projectFile.exists()) {
            return projectFile.readText()
        }

        // Try absolute path from common locations
        val possiblePaths = listOf(
            "assets/bravura/$filename",
            "../assets/bravura/$filename",
            "../../assets/bravura/$filename"
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                return file.readText()
            }
        }

        println("Warning: Could not find $filename in any expected location")
        return null
    }
}
