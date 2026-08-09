package com.mecon.audio.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Unique identifier for a SoundFont.
 */
@JvmInline
@Serializable
value class SoundFontId(val value: String) {
    companion object {
        fun fromPath(path: String): SoundFontId =
            SoundFontId(path.hashCode().toString(16))
    }
}

/**
 * Supported SoundFont formats.
 */
enum class SoundFontFormat(val extension: String, val description: String) {
    SF2("sf2", "SoundFont 2"),
    SF3("sf3", "Compressed SoundFont"),  // Future support
    SFZ("sfz", "SFZ Format");            // Future support

    companion object {
        fun fromExtension(ext: String): SoundFontFormat? =
            entries.find { it.extension.equals(ext, ignoreCase = true) }

        val SUPPORTED = listOf(SF2, SF3)
    }
}

/**
 * Information about a SoundFont file.
 */
@Serializable
data class SoundFontInfo(
    val id: SoundFontId,
    val name: String,
    val filePath: String,
    val format: SoundFontFormat,
    val fileSizeBytes: Long = 0,
    val presetCount: Int = 0,
    val instrumentCount: Int = 0,
    val sampleCount: Int = 0
) {
    /**
     * File size in human-readable format.
     */
    val fileSizeFormatted: String
        get() = when {
            fileSizeBytes < 1024 -> "$fileSizeBytes B"
            fileSizeBytes < 1024 * 1024 -> "${fileSizeBytes / 1024} KB"
            else -> "${fileSizeBytes / (1024 * 1024)} MB"
        }

    companion object {
        /**
         * Create SoundFontInfo from file path (basic info only).
         * Full metadata requires loading the file.
         */
        fun fromPath(
            filePath: String,
            name: String? = null
        ): SoundFontInfo? {
            val extension = filePath.substringAfterLast('.', "")
            val format = SoundFontFormat.fromExtension(extension) ?: return null
            val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')

            return SoundFontInfo(
                id = SoundFontId.fromPath(filePath),
                name = name ?: fileName.substringBeforeLast('.'),
                filePath = filePath,
                format = format
            )
        }
    }
}

/**
 * Preset (instrument) within a SoundFont.
 */
@Serializable
data class SoundFontPreset(
    val name: String,
    val bank: Int,
    val program: Int
) {
    /**
     * Bank and program as display string.
     */
    val displayId: String get() = "$bank:$program"
}
