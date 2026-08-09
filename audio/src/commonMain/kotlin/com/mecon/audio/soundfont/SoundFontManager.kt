package com.mecon.audio.soundfont

import com.mecon.audio.engine.AudioResult
import com.mecon.audio.model.SoundFontId
import com.mecon.audio.model.SoundFontInfo
import com.mecon.audio.model.SoundFontPreset
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for managing SoundFont files.
 *
 * Platform implementations handle the actual loading and scanning of SoundFont files.
 */
interface SoundFontManager {
    /** Current user-visible SoundFont preparation state. */
    val loadState: StateFlow<SoundFontLoadState>

    /**
     * List of available SoundFonts discovered by scanning.
     */
    val availableSoundFonts: StateFlow<List<SoundFontInfo>>

    /**
     * Set of currently loaded SoundFont IDs.
     */
    val loadedSoundFonts: StateFlow<Set<SoundFontId>>

    /**
     * The default SoundFont to use when no specific SoundFont is assigned.
     */
    val defaultSoundFont: StateFlow<SoundFontInfo?>

    /**
     * Scan a directory for SoundFont files.
     *
     * @param directoryPath Path to scan
     * @param recursive Whether to scan subdirectories
     * @return List of discovered SoundFont files
     */
    suspend fun scanDirectory(
        directoryPath: String,
        recursive: Boolean = true
    ): AudioResult<List<SoundFontInfo>>

    /**
     * Add a SoundFont from a specific file path.
     *
     * @param filePath Path to the SoundFont file
     * @return SoundFontInfo if successful
     */
    suspend fun addSoundFont(filePath: String): AudioResult<SoundFontInfo>

    /**
     * Load a SoundFont into memory for playback.
     *
     * @param soundFontId ID of the SoundFont to load
     * @return Success if loaded
     */
    suspend fun loadSoundFont(soundFontId: SoundFontId): AudioResult<Unit>

    /**
     * Unload a SoundFont from memory.
     *
     * @param soundFontId ID of the SoundFont to unload
     */
    suspend fun unloadSoundFont(soundFontId: SoundFontId)

    /**
     * Set the default SoundFont.
     *
     * @param soundFontId ID of the SoundFont, or null for system default
     */
    fun setDefaultSoundFont(soundFontId: SoundFontId?)

    /**
     * Get the presets (instruments) available in a SoundFont.
     *
     * @param soundFontId ID of the SoundFont
     * @return List of presets, or empty if SoundFont not loaded
     */
    suspend fun getPresets(soundFontId: SoundFontId): List<SoundFontPreset>

    /**
     * Get SoundFontInfo by ID.
     */
    fun getSoundFontInfo(soundFontId: SoundFontId): SoundFontInfo?
}
