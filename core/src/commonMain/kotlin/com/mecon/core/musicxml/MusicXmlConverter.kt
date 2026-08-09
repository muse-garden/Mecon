package com.mecon.core.musicxml

import com.mecon.core.musicxml.export.MusicXmlExporter
import com.mecon.core.musicxml.import.MusicXmlImporter
import com.mecon.api.storage.StorageScore

/**
 * Main facade for MusicXML conversion operations.
 *
 * Provides simple import/export API for converting between
 * MusicXML format and StorageScore.
 *
 * Usage:
 * ```kotlin
 * // Import
 * val score = MusicXmlConverter.import(xmlString).getOrThrow()
 *
 * // Export
 * val xml = MusicXmlConverter.export(score).getOrThrow()
 * ```
 */
object MusicXmlConverter {

    /**
     * Import a MusicXML string to StorageScore.
     *
     * @param xml The MusicXML content as a string
     * @return Result containing the StorageScore or an error
     */
    fun import(xml: String): Result<StorageScore> = runCatching {
        val parser = MusicXmlParser()
        val xmlScore = parser.parse(xml).getOrThrow()

        val importer = MusicXmlImporter()
        importer.import(xmlScore)
    }

    /**
     * Export a StorageScore to MusicXML string.
     *
     * @param score The StorageScore to export
     * @return Result containing the MusicXML string or an error
     */
    fun export(score: StorageScore): Result<String> = runCatching {
        val exporter = MusicXmlExporter()
        val xmlScore = exporter.export(score)

        val writer = MusicXmlWriter()
        writer.write(xmlScore)
    }

    /**
     * Validate a MusicXML string without fully importing it.
     *
     * @param xml The MusicXML content to validate
     * @return Result indicating success or containing a parse error
     */
    fun validate(xml: String): Result<Unit> = runCatching {
        val parser = MusicXmlParser()
        parser.parse(xml).getOrThrow()
        Unit
    }

    /**
     * Get information about a MusicXML file without fully importing it.
     *
     * @param xml The MusicXML content
     * @return Result containing basic score information
     */
    fun getInfo(xml: String): Result<MusicXmlInfo> = runCatching {
        val parser = MusicXmlParser()
        val xmlScore = parser.parse(xml).getOrThrow()

        MusicXmlInfo(
            title = xmlScore.getTitle(),
            subtitle = xmlScore.getSubtitle(),
            composer = xmlScore.getCreator("composer"),
            arranger = xmlScore.getCreator("arranger"),
            lyricist = xmlScore.getCreator("lyricist"),
            partCount = xmlScore.parts.size,
            measureCount = xmlScore.parts.firstOrNull()?.measures?.size ?: 0,
            hasKeySignature = xmlScore.parts.any { part ->
                part.measures.any { it.attributes?.keys?.isNotEmpty() == true }
            },
            hasTimeSignature = xmlScore.parts.any { part ->
                part.measures.any { it.attributes?.times?.isNotEmpty() == true }
            }
        )
    }

    /**
     * Check if a string appears to be MusicXML content.
     *
     * Performs a quick check without full parsing.
     *
     * @param content The content to check
     * @return true if the content appears to be MusicXML
     */
    fun isMusicXml(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("<?xml") &&
                (trimmed.contains("<score-partwise") || trimmed.contains("<score-timewise"))
    }

    /**
     * Get supported file extensions for MusicXML files.
     */
    fun getSupportedExtensions(): List<String> = listOf("xml", "musicxml", "mxl")

    /**
     * Check if a file extension is a supported MusicXML extension.
     */
    fun isSupportedExtension(extension: String): Boolean =
        extension.lowercase() in getSupportedExtensions()
}

/**
 * Basic information about a MusicXML file.
 */
data class MusicXmlInfo(
    val title: String,
    val subtitle: String? = null,
    val composer: String?,
    val arranger: String? = null,
    val lyricist: String? = null,
    val partCount: Int,
    val measureCount: Int,
    val hasKeySignature: Boolean,
    val hasTimeSignature: Boolean
)
