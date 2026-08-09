package com.mecon.audio

import com.mecon.audio.engine.AudioError
import com.mecon.audio.engine.AudioResult
import com.mecon.audio.model.*
import com.mecon.audio.soundfont.SoundFontManager
import com.mecon.audio.soundfont.SoundFontLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.prefs.Preferences
import javax.sound.midi.MidiSystem
import javax.sound.midi.Soundbank
import javax.sound.midi.Synthesizer

/**
 * JVM implementation of SoundFontManager using javax.sound.midi.
 *
 * The catalogue (added file paths), which fonts were loaded, and the default
 * selection persist to [Preferences], so the library survives restarts. The
 * actual soundbanks are re-loaded into the synthesizer from disk in
 * [initialize], since synthesizer state cannot itself be serialized.
 */
class JvmSoundFontManager : SoundFontManager {

    private val prefs = Preferences.userRoot().node("com/mecon/desktop/soundfonts")

    private var synthesizer: Synthesizer? = null
    private var fluidSynth: FluidSynthOutput? = null
    private val soundFontMap = mutableMapOf<SoundFontId, SoundFontInfo>()
    private val loadedSoundBanks = mutableMapOf<SoundFontId, Soundbank>()

    private val _availableSoundFonts = MutableStateFlow<List<SoundFontInfo>>(emptyList())
    override val availableSoundFonts: StateFlow<List<SoundFontInfo>> = _availableSoundFonts.asStateFlow()

    private val _loadedSoundFonts = MutableStateFlow<Set<SoundFontId>>(emptySet())
    override val loadedSoundFonts: StateFlow<Set<SoundFontId>> = _loadedSoundFonts.asStateFlow()

    private val _defaultSoundFont = MutableStateFlow<SoundFontInfo?>(null)
    override val defaultSoundFont: StateFlow<SoundFontInfo?> = _defaultSoundFont.asStateFlow()

    private val _loadState = MutableStateFlow<SoundFontLoadState>(SoundFontLoadState.Idle)
    override val loadState: StateFlow<SoundFontLoadState> = _loadState.asStateFlow()

    init {
        restoreCatalogue()
        installBundledSoundFont()
    }

    /**
     * Initialize with a synthesizer.
     * Called by JvmAudioEngine during initialization. Re-loads the soundbanks that
     * were loaded in the previous session so playback works without re-adding them.
     */
    internal fun initialize(synthesizer: Synthesizer, fluidSynth: FluidSynthOutput?) {
        this.synthesizer = synthesizer
        this.fluidSynth = fluidSynth

        val loadedPaths = (readPaths(KEY_LOADED) + bundledSoundFontPath).filterNotNull().distinct()
        for (path in loadedPaths) {
            val info = soundFontMap.values.firstOrNull { it.filePath == path } ?: continue
            try {
                _loadState.value = SoundFontLoadState.Loading(info.name, SoundFontLoadState.Stage.OPENING)
                val file = File(info.filePath)
                if (!file.exists()) continue
                if (fluidSynth != null) {
                    fluidSynth.loadSoundFont(file)
                    val soundbank = runCatching { MidiSystem.getSoundbank(file) }.getOrNull()
                    if (soundbank != null) {
                        loadedSoundBanks[info.id] = soundbank
                        soundFontMap[info.id] = info.copy(
                            presetCount = soundbank.instruments.size,
                            name = soundbank.name ?: info.name
                        )
                    }
                    _loadedSoundFonts.value = _loadedSoundFonts.value + info.id
                } else if (info.format != SoundFontFormat.SF3) {
                    val soundbank = MidiSystem.getSoundbank(file)
                    if (!synthesizer.isSoundbankSupported(soundbank)) continue
                    synthesizer.loadAllInstruments(soundbank)
                    loadedSoundBanks[info.id] = soundbank
                    soundFontMap[info.id] = info.copy(
                        presetCount = soundbank.instruments.size,
                        name = soundbank.name ?: info.name
                    )
                    _loadedSoundFonts.value = _loadedSoundFonts.value + info.id
                }
            } catch (e: Exception) {
                _loadState.value = SoundFontLoadState.Failed(info.name, e.message ?: "SoundFont load failed")
            }
        }
        if (_loadState.value is SoundFontLoadState.Loading) _loadState.value = SoundFontLoadState.Idle
        _availableSoundFonts.value = soundFontMap.values.toList()

        // Re-resolve default against any metadata refreshed during reload.
        val defaultPath = prefs.get(KEY_DEFAULT, "").ifBlank { bundledSoundFontPath.orEmpty() }
        if (defaultPath.isNotBlank()) {
            soundFontMap.values.firstOrNull { it.filePath == defaultPath }?.let {
                _defaultSoundFont.value = it
            }
        }
    }

    override suspend fun scanDirectory(
        directoryPath: String,
        recursive: Boolean
    ): AudioResult<List<SoundFontInfo>> = withContext(Dispatchers.IO) {
        try {
            val directory = File(directoryPath)
            if (!directory.exists() || !directory.isDirectory) {
                return@withContext AudioResult.failure(
                    AudioError.SoundFontNotFound(directoryPath)
                )
            }

            val foundSoundFonts = mutableListOf<SoundFontInfo>()

            val files = if (recursive) {
                directory.walkTopDown().filter { it.isFile }
            } else {
                directory.listFiles()?.asSequence()?.filter { it.isFile } ?: emptySequence()
            }

            for (file in files) {
                val extension = file.extension.lowercase()
                val format = SoundFontFormat.fromExtension(extension)

                if (format != null && format in SoundFontFormat.SUPPORTED) {
                    val info = createSoundFontInfo(file, format)
                    foundSoundFonts.add(info)
                    soundFontMap[info.id] = info
                }
            }

            // Update available list
            _availableSoundFonts.value = soundFontMap.values.toList()
            persistCatalogue()

            AudioResult.success(foundSoundFonts)
        } catch (e: Exception) {
            AudioResult.failure(AudioError.Unknown(e.message ?: "Failed to scan directory"))
        }
    }

    override suspend fun addSoundFont(filePath: String): AudioResult<SoundFontInfo> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext AudioResult.failure(
                        AudioError.SoundFontNotFound(filePath)
                    )
                }

                val extension = file.extension.lowercase()
                val format = SoundFontFormat.fromExtension(extension)

                if (format == null || format !in SoundFontFormat.SUPPORTED) {
                    return@withContext AudioResult.failure(
                        AudioError.UnsupportedSoundFontFormat(extension)
                    )
                }

                val info = createSoundFontInfo(file, format)
                soundFontMap[info.id] = info

                // Update available list
                _availableSoundFonts.value = soundFontMap.values.toList()
                persistCatalogue()

                AudioResult.success(info)
            } catch (e: Exception) {
                AudioResult.failure(
                    AudioError.SoundFontLoadFailed(filePath, e.message ?: "Unknown error")
                )
            }
        }

    override suspend fun loadSoundFont(soundFontId: SoundFontId): AudioResult<Unit> =
        withContext(Dispatchers.IO) {
            val synth = synthesizer ?: return@withContext AudioResult.failure(
                AudioError.InitializationFailed("Synthesizer not initialized")
            )

            val info = soundFontMap[soundFontId] ?: return@withContext AudioResult.failure(
                AudioError.SoundFontNotFound(soundFontId.value)
            )

            try {
                _loadState.value = SoundFontLoadState.Loading(info.name, SoundFontLoadState.Stage.OPENING)
                val file = File(info.filePath)
                if (!file.exists()) {
                    return@withContext AudioResult.failure(
                        AudioError.SoundFontNotFound(info.filePath)
                    )
                }

                if (fluidSynth != null) {
                    fluidSynth!!.loadSoundFont(file)
                    val metadata = runCatching { MidiSystem.getSoundbank(file) }.getOrNull()
                    if (metadata != null) loadedSoundBanks[soundFontId] = metadata
                    _loadedSoundFonts.value = _loadedSoundFonts.value + soundFontId
                    val updatedInfo = info.copy(
                        presetCount = metadata?.instruments?.size ?: info.presetCount,
                        name = metadata?.name ?: info.name
                    )
                    soundFontMap[soundFontId] = updatedInfo
                    _availableSoundFonts.value = soundFontMap.values.toList()
                    persistCatalogue()
                    _loadState.value = SoundFontLoadState.Idle
                    return@withContext AudioResult.success(Unit)
                }
                if (info.format == SoundFontFormat.SF3) {
                    val message = "SF3 requires the FluidSynth backend"
                    _loadState.value = SoundFontLoadState.Failed(info.name, message)
                    return@withContext AudioResult.failure(AudioError.SoundFontLoadFailed(info.filePath, message))
                }

                val soundbank = MidiSystem.getSoundbank(file)

                // Load instruments into synthesizer
                if (synth.isSoundbankSupported(soundbank)) {
                    synth.loadAllInstruments(soundbank)
                    loadedSoundBanks[soundFontId] = soundbank
                    _loadedSoundFonts.value = loadedSoundBanks.keys.toSet()

                    // Update info with metadata from loaded soundbank
                    val updatedInfo = info.copy(
                        presetCount = soundbank.instruments.size,
                        name = soundbank.name ?: info.name
                    )
                    soundFontMap[soundFontId] = updatedInfo
                    _availableSoundFonts.value = soundFontMap.values.toList()
                    persistCatalogue()
                    _loadState.value = SoundFontLoadState.Idle

                    AudioResult.success(Unit)
                } else {
                    AudioResult.failure(
                        AudioError.SoundFontLoadFailed(info.filePath, "Soundbank not supported by synthesizer")
                    )
                }
            } catch (e: Exception) {
                _loadState.value = SoundFontLoadState.Failed(info.name, e.message ?: "Unknown error")
                AudioResult.failure(
                    AudioError.SoundFontLoadFailed(info.filePath, e.message ?: "Unknown error")
                )
            }
        }

    override suspend fun unloadSoundFont(soundFontId: SoundFontId) = withContext(Dispatchers.IO) {
        val synth = synthesizer ?: return@withContext

        try {
            fluidSynth?.unloadSoundFont(File(soundFontMap[soundFontId]?.filePath ?: ""))
            loadedSoundBanks.remove(soundFontId)?.let(synth::unloadAllInstruments)
            _loadedSoundFonts.value = _loadedSoundFonts.value - soundFontId
            persistCatalogue()
        } catch (e: Exception) {
            // Ignore unload errors
        }
    }

    override fun setDefaultSoundFont(soundFontId: SoundFontId?) {
        _defaultSoundFont.value = if (soundFontId != null) {
            soundFontMap[soundFontId]
        } else {
            null
        }
        persistCatalogue()
    }

    override suspend fun getPresets(soundFontId: SoundFontId): List<SoundFontPreset> =
        withContext(Dispatchers.IO) {
            val soundbank = loadedSoundBanks[soundFontId] ?: return@withContext emptyList()

            soundbank.instruments.map { instrument ->
                val patch = instrument.patch
                SoundFontPreset(
                    name = instrument.name ?: "Unknown",
                    bank = patch.bank,
                    program = patch.program
                )
            }
        }

    override fun getSoundFontInfo(soundFontId: SoundFontId): SoundFontInfo? {
        return soundFontMap[soundFontId]
    }

    /**
     * Create SoundFontInfo from a file.
     */
    private fun createSoundFontInfo(file: File, format: SoundFontFormat): SoundFontInfo {
        return SoundFontInfo(
            id = SoundFontId.fromPath(file.absolutePath),
            name = file.nameWithoutExtension,
            filePath = file.absolutePath,
            format = format,
            fileSizeBytes = file.length()
        )
    }

    internal fun preparePrograms(programs: Set<Pair<Int, Int>>) {
        val output = fluidSynth ?: return
        val name = _defaultSoundFont.value?.name ?: "SoundFont"
        val ordered = programs.sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
        _loadState.value = SoundFontLoadState.Loading(
            name,
            SoundFontLoadState.Stage.PREPARING_SAMPLES,
            current = 0,
            total = ordered.size,
        )
        try {
            ordered.forEachIndexed { index, (channel, program) ->
                output.prepareProgram(channel, program = program)
                _loadState.value = SoundFontLoadState.Loading(
                    name,
                    SoundFontLoadState.Stage.PREPARING_SAMPLES,
                    current = index + 1,
                    total = ordered.size,
                )
            }
            _loadState.value = SoundFontLoadState.Idle
        } catch (e: Exception) {
            _loadState.value = SoundFontLoadState.Failed(name, e.message ?: "Preset preparation failed")
            throw e
        }
    }

    // ========== Persistence ==========

    /** Rebuild the in-memory catalogue from persisted paths (no synthesizer needed). */
    private fun restoreCatalogue() {
        for (path in readPaths(KEY_AVAILABLE)) {
            val file = File(path)
            if (!file.exists()) continue
            val format = SoundFontFormat.fromExtension(file.extension.lowercase()) ?: continue
            if (format !in SoundFontFormat.SUPPORTED) continue
            val info = createSoundFontInfo(file, format)
            soundFontMap[info.id] = info
        }
        _availableSoundFonts.value = soundFontMap.values.toList()

        val defaultPath = prefs.get(KEY_DEFAULT, "")
        if (defaultPath.isNotBlank()) {
            _defaultSoundFont.value = soundFontMap.values.firstOrNull { it.filePath == defaultPath }
        }
    }

    private var bundledSoundFontPath: String? = null

    /**
     * Materialise the packaged base bank as a normal file because javax.sound.midi
     * only accepts a File/URL and native distributions may keep resources in jars.
     */
    private fun installBundledSoundFont() {
        val stream = javaClass.getResourceAsStream("/soundfonts/MS Basic.sf3") ?: return
        try {
            val directory = File(System.getProperty("user.home"), ".mecon/soundfonts")
            directory.mkdirs()
            val target = File(directory, "MS Basic.sf3")
            if (!target.exists() || target.length() == 0L) {
                stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            } else {
                stream.close()
            }
            val info = createSoundFontInfo(target, SoundFontFormat.SF3)
            soundFontMap[info.id] = info
            bundledSoundFontPath = target.absolutePath
            _availableSoundFonts.value = soundFontMap.values.toList()
            if (_defaultSoundFont.value == null) _defaultSoundFont.value = info
        } catch (_: Exception) {
            stream.close()
        }
    }

    /** Snapshot the catalogue, loaded set, and default selection to preferences. */
    private fun persistCatalogue() {
        prefs.put(KEY_AVAILABLE, writePaths(soundFontMap.values.map { it.filePath }))
        prefs.put(KEY_LOADED, writePaths(_loadedSoundFonts.value.mapNotNull { soundFontMap[it]?.filePath }))
        prefs.put(KEY_DEFAULT, _defaultSoundFont.value?.filePath ?: "")
    }

    private fun readPaths(key: String): List<String> =
        prefs.get(key, "").split('\n').filter { it.isNotBlank() }

    private fun writePaths(paths: List<String>): String =
        paths.filter { it.isNotBlank() }.joinToString("\n")

    private companion object {
        const val KEY_AVAILABLE = "available"
        const val KEY_LOADED = "loaded"
        const val KEY_DEFAULT = "default"
    }
}
