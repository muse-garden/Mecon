package com.mecon.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.File
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage

/** Native FluidSynth output. SF3 remains compressed and presets are prepared on demand. */
internal class FluidSynthOutput private constructor(
    private val api: FluidSynthLibrary,
    private val settings: Pointer,
    private val synth: Pointer,
    private val driver: Pointer
) : Receiver {
    private val soundFonts = mutableMapOf<String, Int>()
    private val pinnedPresets = mutableSetOf<Triple<Int, Int, Int>>()

    fun loadSoundFont(file: File): Int {
        val existing = soundFonts[file.absolutePath]
        if (existing != null) return existing
        val id = api.fluid_synth_sfload(synth, file.absolutePath, 1)
        check(id >= 0) { "FluidSynth could not load ${file.name}" }
        soundFonts[file.absolutePath] = id
        return id
    }

    fun unloadSoundFont(file: File) {
        val id = soundFonts.remove(file.absolutePath) ?: return
        api.fluid_synth_sfunload(synth, id, 1)
        pinnedPresets.removeAll { (soundFontId, _, _) -> soundFontId == id }
    }

    /**
     * Load and pin the preset samples before selecting the channel program. Dynamic SF3 sample
     * allocation is not realtime-safe; pinning also prevents a later channel switch from unloading
     * the preset, so the first audition after returning to an instrument has the correct timbre.
     */
    fun prepareProgram(channel: Int, bank: Int = 0, program: Int) {
        for (soundFontId in soundFonts.values) {
            val key = Triple(soundFontId, bank, program)
            if (key !in pinnedPresets && api.fluid_synth_pin_preset(synth, soundFontId, bank, program) == 0) {
                pinnedPresets += key
            }
        }
        check(api.fluid_synth_bank_select(synth, channel, bank) == 0) {
            "FluidSynth could not select bank $bank on channel $channel"
        }
        check(api.fluid_synth_program_change(synth, channel, program) == 0) {
            "FluidSynth could not prepare bank $bank program $program on channel $channel"
        }
        val selectedSoundFont = IntByReference()
        val selectedBank = IntByReference()
        val selectedProgram = IntByReference()
        check(api.fluid_synth_get_program(synth, channel, selectedSoundFont, selectedBank, selectedProgram) == 0) {
            "FluidSynth could not read selected program on channel $channel"
        }
        check(selectedBank.value == bank && selectedProgram.value == program) {
            "FluidSynth selected bank ${selectedBank.value} program ${selectedProgram.value}, " +
                "expected bank $bank program $program on channel $channel"
        }
    }

    fun setGain(gain: Float) {
        api.fluid_synth_set_gain(synth, gain.coerceIn(0f, 1f))
    }

    override fun send(message: MidiMessage, timeStamp: Long) {
        val short = message as? ShortMessage ?: return
        when (short.command) {
            ShortMessage.NOTE_ON -> if (short.data2 == 0) {
                api.fluid_synth_noteoff(synth, short.channel, short.data1)
            } else {
                api.fluid_synth_noteon(synth, short.channel, short.data1, short.data2)
            }
            ShortMessage.NOTE_OFF -> api.fluid_synth_noteoff(synth, short.channel, short.data1)
            ShortMessage.PROGRAM_CHANGE -> api.fluid_synth_program_change(synth, short.channel, short.data1)
            ShortMessage.CONTROL_CHANGE -> api.fluid_synth_cc(synth, short.channel, short.data1, short.data2)
        }
    }

    fun allSoundsOff() {
        repeat(16) { api.fluid_synth_all_sounds_off(synth, it) }
    }

    override fun close() {
        api.delete_fluid_audio_driver(driver)
        api.delete_fluid_synth(synth)
        api.delete_fluid_settings(settings)
    }

    companion object {
        fun createOrNull(): FluidSynthOutput? = runCatching {
            val library = FluidSynthNativeLoader.load()
            val settings = library.new_fluid_settings() ?: error("Cannot create FluidSynth settings")
            library.fluid_settings_setint(settings, "synth.dynamic-sample-loading", 1)
            library.fluid_settings_setnum(settings, "synth.gain", 0.8)
            val synth = library.new_fluid_synth(settings) ?: error("Cannot create FluidSynth synthesizer")
            val driver = library.new_fluid_audio_driver(settings, synth)
                ?: error("Cannot open FluidSynth audio driver")
            FluidSynthOutput(library, settings, synth, driver)
        }.getOrNull()
    }
}

private interface FluidSynthLibrary : Library {
    fun new_fluid_settings(): Pointer?
    fun delete_fluid_settings(settings: Pointer)
    fun fluid_settings_setint(settings: Pointer, name: String, value: Int): Int
    fun fluid_settings_setnum(settings: Pointer, name: String, value: Double): Int
    fun new_fluid_synth(settings: Pointer): Pointer?
    fun delete_fluid_synth(synth: Pointer)
    fun new_fluid_audio_driver(settings: Pointer, synth: Pointer): Pointer?
    fun delete_fluid_audio_driver(driver: Pointer)
    fun fluid_synth_sfload(synth: Pointer, filename: String, resetPresets: Int): Int
    fun fluid_synth_sfunload(synth: Pointer, id: Int, resetPresets: Int): Int
    fun fluid_synth_noteon(synth: Pointer, channel: Int, key: Int, velocity: Int): Int
    fun fluid_synth_noteoff(synth: Pointer, channel: Int, key: Int): Int
    fun fluid_synth_bank_select(synth: Pointer, channel: Int, bank: Int): Int
    fun fluid_synth_program_change(synth: Pointer, channel: Int, program: Int): Int
    fun fluid_synth_get_program(
        synth: Pointer,
        channel: Int,
        soundFontId: IntByReference,
        bank: IntByReference,
        program: IntByReference,
    ): Int
    fun fluid_synth_pin_preset(synth: Pointer, soundFontId: Int, bank: Int, program: Int): Int
    fun fluid_synth_cc(synth: Pointer, channel: Int, control: Int, value: Int): Int
    fun fluid_synth_all_sounds_off(synth: Pointer, channel: Int): Int
    fun fluid_synth_set_gain(synth: Pointer, gain: Float): Int
}

private object FluidSynthNativeLoader {
    private const val VERSION = "2.5.6"

    fun load(): FluidSynthLibrary {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        require(os.contains("windows") && (arch.contains("amd64") || arch.contains("x86_64"))) {
            "Bundled FluidSynth currently supports Windows x64"
        }
        val directory = File(System.getProperty("user.home"), ".mecon/native/fluidsynth-$VERSION/windows-x86_64")
        directory.mkdirs()
        val files = listOf("sndfile.dll", "SDL3.dll", "libfluidsynth-3.dll")
        for (name in files) {
            val target = File(directory, name)
            if (!target.isFile || target.length() == 0L) {
                FluidSynthNativeLoader::class.java.getResourceAsStream("/native/windows-x86_64/$name")
                    ?.use { input -> target.outputStream().use(input::copyTo) }
                    ?: error("Missing bundled FluidSynth library: $name")
            }
        }
        System.load(File(directory, "sndfile.dll").absolutePath)
        System.load(File(directory, "SDL3.dll").absolutePath)
        System.load(File(directory, "libfluidsynth-3.dll").absolutePath)
        return Native.load(File(directory, "libfluidsynth-3.dll").absolutePath, FluidSynthLibrary::class.java)
    }
}
