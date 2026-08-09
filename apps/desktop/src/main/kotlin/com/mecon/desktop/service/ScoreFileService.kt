package com.mecon.desktop.service

import com.mecon.core.container.MeconDocument
import com.mecon.core.musicxml.MusicXmlConverter
import com.mecon.core.serializer.ScoreSerializer
import com.mecon.api.storage.StorageScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for loading and saving score files.
 * Supports:
 * - Mecon container (.mecon) - zip of manifest + score(s) + modules + frozen geometry (see
 *   [MeconDocumentService] and `docs/data_model/mecon-container.md`)
 * - YAML single-score (.mscore.yaml, .yaml, .yml) - legacy native text format
 * - MusicXML format (.xml, .musicxml) - interchange format
 */
class ScoreFileService {

    private val containerService = MeconDocumentService()

    /** True for the new zip container format (`.mecon`); false for legacy single-score text files. */
    fun isContainerFile(file: File): Boolean = file.extension.equals(CONTAINER_EXTENSION, ignoreCase = true)

    /** Load a `.mecon` container into its full [MeconDocument] (manifest + scores + modules). */
    suspend fun loadContainer(file: File): Result<MeconDocument> =
        runCatching { containerService.load(file) }

    /** Write a [MeconDocument] to a `.mecon` container (renders + stores frozen geometry per score). */
    suspend fun saveContainer(document: MeconDocument, file: File): Result<Unit> =
        runCatching { containerService.save(document, file) }

    /**
     * Load a score from a file using native YAML format.
     */
    suspend fun loadScore(file: File): Result<StorageScore> = withContext(Dispatchers.IO) {
        runCatching { ScoreSerializer.fromYaml(file.readText()) }
    }

    /**
     * Save a score to a file using native YAML format.
     */
    suspend fun saveScore(score: StorageScore, file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { file.writeText(ScoreSerializer.toYaml(score)) }
    }

    /**
     * Load a score from a MusicXML file.
     */
    suspend fun loadMusicXml(file: File): Result<StorageScore> = withContext(Dispatchers.IO) {
        runCatching {
            val content = file.readText()
            MusicXmlConverter.import(content).getOrThrow()
        }
    }

    /**
     * Save a score to a MusicXML file.
     */
    suspend fun saveMusicXml(score: StorageScore, file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val content = MusicXmlConverter.export(score).getOrThrow()
            file.writeText(content)
        }
    }

    /**
     * Load a score from a file, automatically detecting the format.
     *
     * Detects format by:
     * 1. File extension
     * 2. Content inspection (for ambiguous cases)
     */
    suspend fun loadAuto(file: File): Result<StorageScore> = withContext(Dispatchers.IO) {
        runCatching {
            val extension = file.extension.lowercase()

            when {
                // MusicXML by extension
                extension in MUSICXML_EXTENSIONS -> {
                    MusicXmlConverter.import(file.readText()).getOrThrow()
                }
                // Native format by extension
                extension in YAML_EXTENSIONS -> {
                    ScoreSerializer.fromYaml(file.readText())
                }
                // Unknown extension - try to detect by content
                else -> {
                    val content = file.readText()
                    if (MusicXmlConverter.isMusicXml(content)) {
                        MusicXmlConverter.import(content).getOrThrow()
                    } else {
                        ScoreSerializer.fromYaml(content)
                    }
                }
            }
        }
    }

    /**
     * Save a score to a file, determining format by extension.
     */
    suspend fun saveAuto(score: StorageScore, file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val extension = file.extension.lowercase()

            when {
                extension in MUSICXML_EXTENSIONS -> {
                    val content = MusicXmlConverter.export(score).getOrThrow()
                    file.writeText(content)
                }
                else -> {
                    file.writeText(ScoreSerializer.toYaml(score))
                }
            }
        }
    }

    /**
     * Check if a file is a valid score file by extension.
     */
    fun isValidScoreFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in ALL_EXTENSIONS
    }

    /**
     * Check if a file is a MusicXML file.
     */
    fun isMusicXmlFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in MUSICXML_EXTENSIONS
    }

    /**
     * Check if a file is a native YAML format file.
     */
    fun isNativeFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in YAML_EXTENSIONS
    }

    /**
     * Get the file extensions for native YAML format.
     */
    fun getYamlExtensions(): List<String> = YAML_EXTENSIONS

    /**
     * Get the file extensions for MusicXML format.
     */
    fun getMusicXmlExtensions(): List<String> = MUSICXML_EXTENSIONS

    /**
     * Get all supported file extensions.
     */
    fun getFileExtensions(): List<String> = ALL_EXTENSIONS

    companion object {
        val instance: ScoreFileService by lazy { ScoreFileService() }

        /** New zip container format. */
        const val CONTAINER_EXTENSION = "mecon"

        /** Legacy single-score YAML text extensions (the old `.mecon` fixtures are now `.mscore.yaml`). */
        val YAML_EXTENSIONS = listOf("yaml", "yml")

        /** MusicXML format extensions */
        val MUSICXML_EXTENSIONS = listOf("xml", "musicxml")

        /** All supported extensions */
        val ALL_EXTENSIONS = listOf(CONTAINER_EXTENSION) + YAML_EXTENSIONS + MUSICXML_EXTENSIONS
    }
}
