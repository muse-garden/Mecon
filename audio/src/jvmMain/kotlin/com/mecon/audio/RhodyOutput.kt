package com.mecon.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

internal data class RhodyPatch(val family: Int, val preset: Int)

/** Stable General MIDI identities currently implemented by rhody-bridge. */
internal object RhodyProgramCatalog {
    fun resolve(channel: Int, bank: Int, program: Int): RhodyPatch? {
        if (channel == PERCUSSION_CHANNEL || bank != 0) return null
        return when (program) {
            0 -> RhodyPatch(FAMILY_PIANO, 0) // Acoustic Grand Piano
            1 -> RhodyPatch(FAMILY_PIANO, 1) // Bright/Upright Piano
            4, 5 -> RhodyPatch(FAMILY_MALLETS, 0) // Electric Piano
            8 -> RhodyPatch(FAMILY_MALLETS, 1) // Celesta
            9 -> RhodyPatch(FAMILY_MALLETS, 2) // Glockenspiel
            11 -> RhodyPatch(FAMILY_MALLETS, 5) // Vibraphone
            12 -> RhodyPatch(FAMILY_MALLETS, 4) // Marimba
            13 -> RhodyPatch(FAMILY_MALLETS, 3) // Xylophone
            14 -> RhodyPatch(FAMILY_MALLETS, 6) // Tubular Bells
            16 -> RhodyPatch(FAMILY_ORGAN, 1) // Drawbar Organ / foundations
            17 -> RhodyPatch(FAMILY_ORGAN, 5) // Percussive Organ / cornet
            18 -> RhodyPatch(FAMILY_ORGAN, 7) // Rock Organ / full organ
            19 -> RhodyPatch(FAMILY_ORGAN, 0) // Church Organ / principal chorus
            20 -> RhodyPatch(FAMILY_ORGAN, 3) // Reed Organ / solo trumpet
            72 -> RhodyPatch(FAMILY_FLUTE, 1) // Piccolo
            73 -> RhodyPatch(FAMILY_FLUTE, 0) // Flute
            else -> null
        }
    }

    private const val PERCUSSION_CHANNEL = 9
    private const val FAMILY_FLUTE = 0
    private const val FAMILY_MALLETS = 1
    private const val FAMILY_PIANO = 2
    private const val FAMILY_ORGAN = 3
}

internal interface RhodyNoteOutput {
    fun selectPatch(channel: Int, patch: RhodyPatch)
    fun noteOn(channel: Int, midi: Int, velocity: Int)
    fun noteOff(channel: Int, midi: Int)
    fun allNotesOff(channel: Int)
    fun setChannelVolume(channel: Int, value: Int)
}

/** MIDI receiver that routes only implemented programs to Rhody and everything else to MS Basic. */
internal class RhodyMidiReceiver(
    private val rhody: RhodyNoteOutput,
    private val fallback: Receiver,
) : Receiver {
    private val banks = IntArray(MIDI_CHANNEL_COUNT)
    private val programs = IntArray(MIDI_CHANNEL_COUNT)
    private val patches = arrayOfNulls<RhodyPatch>(MIDI_CHANNEL_COUNT)

    fun selectProgram(channel: Int, bank: Int, program: Int) {
        if (channel !in 0 until MIDI_CHANNEL_COUNT) return
        banks[channel] = bank.coerceAtLeast(0)
        programs[channel] = program.coerceIn(0, 127)
        val previous = patches[channel]
        val next = RhodyProgramCatalog.resolve(channel, banks[channel], programs[channel])
        if (previous != null && previous != next) rhody.allNotesOff(channel)
        patches[channel] = next
        if (next != null) rhody.selectPatch(channel, next)
    }

    override fun send(message: MidiMessage, timeStamp: Long) {
        val short = message as? ShortMessage ?: run {
            fallback.send(message, timeStamp)
            return
        }
        val channel = short.channel
        when (short.command) {
            ShortMessage.PROGRAM_CHANGE -> {
                selectProgram(channel, banks[channel], short.data1)
                // Keep the fallback channel ready in case a later program is unsupported.
                fallback.send(message, timeStamp)
            }
            ShortMessage.CONTROL_CHANGE -> when (short.data1) {
                BANK_SELECT_MSB -> {
                    banks[channel] = (short.data2 shl 7) or (banks[channel] and 0x7f)
                    selectProgram(channel, banks[channel], programs[channel])
                    fallback.send(message, timeStamp)
                }
                BANK_SELECT_LSB -> {
                    banks[channel] = (banks[channel] and 0x3f80) or short.data2
                    selectProgram(channel, banks[channel], programs[channel])
                    fallback.send(message, timeStamp)
                }
                ALL_SOUND_OFF, ALL_NOTES_OFF -> {
                    if (patches[channel] != null) rhody.allNotesOff(channel)
                    fallback.send(message, timeStamp)
                }
                CHANNEL_VOLUME -> {
                    if (patches[channel] == null) fallback.send(message, timeStamp)
                    else rhody.setChannelVolume(channel, short.data2)
                }
                else -> if (patches[channel] == null) fallback.send(message, timeStamp)
            }
            ShortMessage.NOTE_ON -> {
                val patch = patches[channel]
                    ?: RhodyProgramCatalog.resolve(channel, banks[channel], programs[channel])
                        ?.also {
                            patches[channel] = it
                            rhody.selectPatch(channel, it)
                        }
                if (patch == null) {
                    fallback.send(message, timeStamp)
                } else if (short.data2 == 0) {
                    rhody.noteOff(channel, short.data1)
                } else {
                    rhody.noteOn(channel, short.data1, short.data2)
                }
            }
            ShortMessage.NOTE_OFF -> {
                if (patches[channel] == null) fallback.send(message, timeStamp)
                else rhody.noteOff(channel, short.data1)
            }
            else -> if (patches[channel] == null) fallback.send(message, timeStamp)
        }
    }

    fun allNotesOff() {
        patches.indices.forEach { channel ->
            if (patches[channel] != null) rhody.allNotesOff(channel)
        }
    }

    override fun close() = Unit

    private companion object {
        const val MIDI_CHANNEL_COUNT = 16
        const val BANK_SELECT_MSB = 0
        const val BANK_SELECT_LSB = 32
        const val ALL_SOUND_OFF = 120
        const val ALL_NOTES_OFF = 123
        const val CHANNEL_VOLUME = 7
    }
}

/** Optional JNA host plus a small JavaSound mixer. No Rhody classes or artifacts are linked. */
internal class RhodyOutput private constructor(
    private val api: RhodyLibrary,
    private val line: SourceDataLine,
    private val sampleRate: Double,
) : RhodyNoteOutput, AutoCloseable {
    private data class ChannelState(
        val handle: Pointer,
        var patch: RhodyPatch? = null,
        var orchestralGainDb: Double = 0.0,
        var userGainDb: Double = 0.0,
    )

    private val lock = Any()
    private val channels = HashMap<Int, ChannelState>()
    private val running = AtomicBoolean(true)
    @Volatile private var gain = 0.8f
    private val renderThread = thread(name = "rhody-audio", isDaemon = true, start = true) { renderLoop() }

    override fun selectPatch(channel: Int, patch: RhodyPatch) {
        val state = stateFor(channel)
        if (state.patch == patch) return
        api.rhody_all_notes_off(state.handle)
        api.rhody_set_family(state.handle, patch.family)
        api.rhody_set_preset(state.handle, patch.preset)
        state.orchestralGainDb = api.rhody_orchestral_gain_db(patch.family, patch.preset)
        api.rhody_set_mix_gain_db(state.handle, state.userGainDb)
        state.patch = patch
    }

    override fun noteOn(channel: Int, midi: Int, velocity: Int) {
        api.rhody_note_on_velocity(
            stateFor(channel).handle,
            midiToHz(midi),
            velocity.coerceIn(0, 127) / 127.0,
        )
    }

    override fun noteOff(channel: Int, midi: Int) {
        synchronized(lock) { channels[channel] }?.let { state ->
            api.rhody_note_off_pitch(state.handle, midiToHz(midi))
        }
    }

    override fun allNotesOff(channel: Int) {
        synchronized(lock) { channels[channel] }?.let { api.rhody_all_notes_off(it.handle) }
    }

    override fun setChannelVolume(channel: Int, value: Int) {
        val normalized = value.coerceIn(0, 127) / 127.0
        // MIDI volume uses a squared amplitude taper; 127 remains unity while
        // zero is represented by a practical digital-silence floor.
        val gainDb = if (normalized <= 0.0) -96.0 else 40.0 * log10(normalized)
        setChannelMixGainDb(channel, gainDb)
    }

    /** Future mixer/UI entry point; calibration and orchestral defaults remain separate. */
    fun setChannelMixGainDb(channel: Int, gainDb: Double) {
        val state = stateFor(channel)
        state.userGainDb = gainDb.coerceIn(-96.0, 12.0)
        api.rhody_set_mix_gain_db(state.handle, state.userGainDb)
    }

    fun allNotesOff() {
        synchronized(lock) { channels.values.toList() }
            .forEach { api.rhody_all_notes_off(it.handle) }
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 1f)
    }

    private fun stateFor(channel: Int): ChannelState = synchronized(lock) {
        channels.getOrPut(channel) {
            val handle = api.rhody_create(sampleRate)
                ?: error("rhody_create returned null for MIDI channel $channel")
            ChannelState(handle)
        }
    }

    private fun renderLoop() {
        val mix = FloatArray(BUFFER_FRAMES)
        val stem = FloatArray(BUFFER_FRAMES)
        val mastered = FloatArray(BUFFER_FRAMES)
        val pcm = ByteArray(BUFFER_FRAMES * CHANNELS * BYTES_PER_SAMPLE)
        val limiter = LookaheadLimiter(sampleRate.toFloat())
        var ensembleGain = 1f
        val ensembleSmooth = 1f - exp(-1f / (0.10f * sampleRate.toFloat()))
        while (running.get()) {
            mix.fill(0f)
            val snapshot = synchronized(lock) { channels.values.toList() }
            for (state in snapshot) {
                stem.fill(0f)
                if (api.rhody_render(state.handle, stem, BUFFER_FRAMES) <= 0) continue
                for (index in mix.indices) mix[index] += stem[index]
            }
            val power = snapshot
                .filter { it.patch != null }
                .sumOf { 10.0.pow((it.orchestralGainDb + it.userGainDb) / 10.0) }
            val targetHeadroom = (1.0 / kotlin.math.sqrt(power.coerceAtLeast(1.0))).toFloat()
            val master = gain
            for (index in mix.indices) {
                ensembleGain += ensembleSmooth * (targetHeadroom - ensembleGain)
                mastered[index] = mix[index] * ensembleGain * master
            }
            limiter.process(mastered)
            encodeStereoPcm16(mastered, pcm, 1f)
            line.write(pcm, 0, pcm.size)
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        allNotesOff()
        line.stop()
        line.flush()
        line.close()
        renderThread.join(1_000L)
        if (renderThread.isAlive) return
        synchronized(lock) {
            channels.values.forEach { api.rhody_destroy(it.handle) }
            channels.clear()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100f
        private const val BUFFER_FRAMES = 512
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2

        fun createOrNull(): RhodyOutput? = runCatching {
            val api = RhodyNativeLoader.load()
            val format = AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false)
            val line = AudioSystem.getSourceDataLine(format).apply {
                open(format, BUFFER_FRAMES * CHANNELS * BYTES_PER_SAMPLE * 4)
                start()
            }
            RhodyOutput(api, line, SAMPLE_RATE.toDouble())
        }.getOrNull()

        internal fun midiToHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

        internal fun encodeStereoPcm16(input: FloatArray, output: ByteArray, gain: Float) {
            require(output.size >= input.size * CHANNELS * BYTES_PER_SAMPLE)
            var cursor = 0
            input.forEach { sample ->
                val value = (sample * gain).coerceIn(-1f, 1f)
                val pcm = (value * 32767f).toInt().toShort().toInt()
                repeat(CHANNELS) {
                    output[cursor++] = (pcm and 0xff).toByte()
                    output[cursor++] = ((pcm ushr 8) and 0xff).toByte()
                }
            }
        }
    }
}

internal interface RhodyLibrary : Library {
    fun rhody_create(sampleRate: Double): Pointer?
    fun rhody_destroy(handle: Pointer)
    fun rhody_set_family(handle: Pointer, family: Int)
    fun rhody_set_preset(handle: Pointer, preset: Int)
    fun rhody_set_mix_gain_db(handle: Pointer, gainDb: Double)
    fun rhody_calibration_gain_db(family: Int, preset: Int): Double
    fun rhody_orchestral_gain_db(family: Int, preset: Int): Double
    fun rhody_note_on_velocity(handle: Pointer, pitchHz: Double, velocity: Double)
    fun rhody_note_off_pitch(handle: Pointer, pitchHz: Double)
    fun rhody_all_notes_off(handle: Pointer)
    fun rhody_render(handle: Pointer, monoOut: FloatArray, frames: Int): Int
}

/** Allocation-free final bus limiter with 5 ms lookahead and a -1 dBFS ceiling. */
internal class LookaheadLimiter(
    sampleRate: Float,
    lookaheadMs: Float = 5f,
    releaseMs: Float = 150f,
    ceilingDb: Float = -1f,
) {
    private val lookahead = (sampleRate * lookaheadMs / 1000f).toInt().coerceAtLeast(1)
    private val delay = FloatArray(lookahead + 1)
    private val queueValues = FloatArray(lookahead + 2)
    private val queueIndices = LongArray(lookahead + 2)
    private val ceiling = 10f.pow(ceilingDb / 20f)
    private val release = (1.0 - exp(-1.0 / (releaseMs * 0.001 * sampleRate))).toFloat()
    private var write = 0
    private var head = 0
    private var tail = 0
    private var index = 0L
    private var gain = 1f

    fun process(block: FloatArray) {
        for (i in block.indices) {
            val input = if (block[i].isFinite()) block[i] else 0f
            val peak = abs(input)
            val oldest = index - lookahead
            while (head != tail && queueIndices[head] < oldest) {
                head = (head + 1) % queueValues.size
            }
            while (head != tail) {
                val back = (tail - 1 + queueValues.size) % queueValues.size
                if (queueValues[back] > peak) break
                tail = back
            }
            queueValues[tail] = peak
            queueIndices[tail] = index
            tail = (tail + 1) % queueValues.size

            delay[write] = input
            val read = (write + 1) % delay.size
            val delayed = delay[read]
            write = read

            val windowPeak = if (head == tail) 0f else queueValues[head]
            val desired = if (windowPeak > ceiling) ceiling / windowPeak else 1f
            gain = if (desired < gain) desired else gain + release * (1f - gain)
            block[i] = (delayed * gain).coerceIn(-ceiling, ceiling)
            index++
        }
    }
}

internal object RhodyNativeLoader {
    private const val PATH_PROPERTY = "mecon.rhody.library"
    private const val PATH_ENVIRONMENT = "MECON_RHODY_LIBRARY"

    fun load(): RhodyLibrary {
        val explicit = System.getProperty(PATH_PROPERTY)?.takeIf(String::isNotBlank)
            ?: System.getenv(PATH_ENVIRONMENT)?.takeIf(String::isNotBlank)
        val found = candidates(explicit).firstOrNull(Files::isRegularFile)
        return if (found != null) {
            Native.load(found.toAbsolutePath().normalize().toString(), RhodyLibrary::class.java)
        } else {
            // Also support an installer or developer-provided java.library.path entry.
            Native.load("rhody_bridge", RhodyLibrary::class.java)
        }
    }

    internal fun candidates(explicit: String?, workingDirectory: Path = Paths.get("")): List<Path> {
        val libraryName = System.mapLibraryName("rhody_bridge")
        val roots = linkedSetOf<Path>()
        explicit?.let { value ->
            val path = Paths.get(value).toAbsolutePath().normalize()
            if (Files.isDirectory(path)) roots.add(path)
            else return listOf(path) + candidates(null, workingDirectory)
        }
        var cursor: Path? = workingDirectory.toAbsolutePath().normalize()
        repeat(4) {
            cursor?.let(roots::add)
            cursor = cursor?.parent
        }
        runCatching {
            Paths.get(RhodyNativeLoader::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()?.let { location ->
            roots.add(if (Files.isDirectory(location)) location else location.parent)
        }
        System.getProperty("java.library.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .mapTo(roots) { Paths.get(it).toAbsolutePath().normalize() }

        return roots.flatMap { root ->
            listOf(
                root.resolve(libraryName),
                root.resolve("native").resolve(libraryName),
                root.resolve("lib").resolve(libraryName),
                root.resolve("target/release").resolve(libraryName),
                root.resolve("target/debug").resolve(libraryName),
                root.resolve("rhody/target/release").resolve(libraryName),
                root.resolve("rhody/target/debug").resolve(libraryName),
                root.resolve("vst-experiment/rhody/target/release").resolve(libraryName),
                root.resolve("vst-experiment/rhody/target/debug").resolve(libraryName),
            )
        }.distinct()
    }
}
