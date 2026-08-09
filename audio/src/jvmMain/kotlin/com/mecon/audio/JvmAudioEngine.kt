package com.mecon.audio

import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.audio.engine.*
import com.mecon.audio.model.*
import com.mecon.audio.soundfont.SoundFontManager
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.Sequence
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer
import javax.sound.midi.MidiEvent as JavaMidiEvent

/**
 * JVM implementation of AudioEngine using javax.sound.midi.
 */
class JvmAudioEngine(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    val requestedDefaultTimbreLibrary: DefaultTimbreLibrary = DefaultTimbreLibrary.fromSystem(),
) : AudioEngine, LiveNoteSink, MetronomeSink {

    private var sequencer: Sequencer? = null
    private var synthesizer: Synthesizer? = null
    private var fluidSynth: FluidSynthOutput? = null
    private var rhodyOutput: RhodyOutput? = null
    private var rhodyReceiver: RhodyMidiReceiver? = null
    private var midiOutput: Receiver? = null
    val activeDefaultTimbreLibrary: DefaultTimbreLibrary
        get() = if (rhodyOutput != null) DefaultTimbreLibrary.RHODY else DefaultTimbreLibrary.MS_BASIC
    private var currentMidiScore: MidiScore? = null
    private var positionTrackingJob: Job? = null
    private var auditionJob: Job? = null
    private val auditionLock = Any()
    private val auditionNativeLock = Any()
    private var activeAuditionNotes: List<Int> = emptyList()
    private val liveLock = Any()
    private val liveGeneration = HashMap<Int, Long>()
    private val soundingLiveNotes = HashSet<Int>()
    private var nextLiveGeneration = 1L

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentPositionTicks = MutableStateFlow(0L)
    override val currentPositionTicks: StateFlow<Long> = _currentPositionTicks.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _tempoMultiplier = MutableStateFlow(AudioEngine.DEFAULT_TEMPO_MULTIPLIER)
    override val tempoMultiplier: StateFlow<Float> = _tempoMultiplier.asStateFlow()

    private val _masterVolume = MutableStateFlow(AudioEngine.DEFAULT_VOLUME)
    override val masterVolume: StateFlow<Float> = _masterVolume.asStateFlow()

    private val _soundFontManager = JvmSoundFontManager()
    override val soundFontManager: SoundFontManager = _soundFontManager

    private val mutedTracks = mutableSetOf<TrackId>()
    private var soloTrack: TrackId? = null

    // ========== Lifecycle ==========

    override suspend fun initialize(): AudioResult<Unit> = withContext(Dispatchers.IO) {
        try {
            // Get sequencer
            sequencer = MidiSystem.getSequencer(false)?.also { seq ->
                seq.open()
            } ?: return@withContext AudioResult.failure(
                AudioError.NoAudioDevice("No MIDI sequencer available")
            )

            fluidSynth = FluidSynthOutput.createOrNull()

            // Keep the JVM synthesizer as the SF2/system fallback.
            synthesizer = MidiSystem.getSynthesizer()?.also { synth ->
                synth.open()
            } ?: return@withContext AudioResult.failure(
                AudioError.NoAudioDevice("No MIDI synthesizer available")
            )

            // Initialize SoundFont manager with synthesizer
            _soundFontManager.initialize(synthesizer!!, fluidSynth)

            val fallback = fluidSynth ?: synthesizer!!.receiver
            if (requestedDefaultTimbreLibrary == DefaultTimbreLibrary.RHODY) {
                rhodyOutput = RhodyOutput.createOrNull()
                rhodyReceiver = rhodyOutput?.let { RhodyMidiReceiver(it, fallback) }
            }
            midiOutput = rhodyReceiver ?: fallback
            sequencer?.transmitter?.receiver = midiOutput

            _isReady.value = true
            _playbackState.value = PlaybackState.IDLE

            AudioResult.success(Unit)
        } catch (e: MidiUnavailableException) {
            AudioResult.failure(AudioError.InitializationFailed(e.message ?: "MIDI unavailable"))
        } catch (e: Exception) {
            AudioResult.failure(AudioError.InitializationFailed(e.message ?: "Unknown error"))
        }
    }

    override suspend fun shutdown() = withContext(Dispatchers.IO) {
        positionTrackingJob?.cancel()
        positionTrackingJob = null
        stopAudition()
        liveAllNotesOff()

        sequencer?.apply {
            stop()
            close()
        }
        sequencer = null

        rhodyReceiver = null
        midiOutput = null
        rhodyOutput?.close()
        rhodyOutput = null

        synthesizer?.close()
        synthesizer = null

        fluidSynth?.close()
        fluidSynth = null

        _isReady.value = false
        _playbackState.value = PlaybackState.IDLE
    }

    // ========== Score Loading ==========

    override suspend fun loadScore(score: RuntimeScore): AudioResult<Unit> {
        val midiScore = ScoreToMidiConverter.convert(score)
        return loadMidiScore(midiScore)
    }

    override suspend fun loadMidiScore(midiScore: MidiScore): AudioResult<Unit> = withContext(Dispatchers.IO) {
        val seq = sequencer ?: return@withContext AudioResult.failure(
            AudioError.InvalidState(PlaybackState.IDLE, "load score - engine not initialized")
        )

        try {
            _playbackState.value = PlaybackState.LOADING

            // Create MIDI sequence
            val sequence = createMidiSequence(midiScore)

            _soundFontManager.preparePrograms(
                midiScore.tracks.flatMap { track ->
                    track.getEventsSorted().filterIsInstance<MidiProgramChangeEvent>()
                        .map { it.channel.value to it.program }
                }.toSet()
            )

            // Load into sequencer
            seq.sequence = sequence
            seq.tickPosition = 0

            // Apply tempo multiplier
            seq.tempoFactor = _tempoMultiplier.value

            currentMidiScore = midiScore
            _currentPositionTicks.value = 0
            _playbackState.value = PlaybackState.STOPPED

            AudioResult.success(Unit)
        } catch (e: Exception) {
            _playbackState.value = PlaybackState.IDLE
            AudioResult.failure(AudioError.ScoreLoadFailed(e.message ?: "Failed to load score"))
        }
    }

    override suspend fun unloadScore() = withContext(Dispatchers.IO) {
        stop()
        sequencer?.sequence = null
        currentMidiScore = null
        _playbackState.value = PlaybackState.IDLE
    }

    // ========== Playback Control ==========

    override fun play() {
        val seq = sequencer ?: return
        if (currentMidiScore == null) return

        when (_playbackState.value) {
            PlaybackState.STOPPED, PlaybackState.PAUSED -> {
                seq.start()
                _playbackState.value = PlaybackState.PLAYING
                startPositionTracking()
            }
            else -> {}
        }
    }

    override fun pause() {
        val seq = sequencer ?: return

        if (_playbackState.value == PlaybackState.PLAYING) {
            seq.stop()
            _playbackState.value = PlaybackState.PAUSED
            stopPositionTracking()
        }
    }

    override fun stop() {
        val seq = sequencer ?: return

        seq.stop()
        seq.tickPosition = 0
        _currentPositionTicks.value = 0
        _playbackState.value = PlaybackState.STOPPED
        stopPositionTracking()

        // Send all notes off to prevent stuck notes
        rhodyReceiver?.allNotesOff()
        fluidSynth?.allSoundsOff()
        synthesizer?.let { synth ->
            for (channel in synth.channels) {
                channel?.allNotesOff()
            }
        }
    }

    override fun audition(request: NoteAudition) {
        if (!_isReady.value || request.midiNumbers.isEmpty()) return
        val notes = MidiNoteRange.filter(request.midiNumbers).distinct()
        if (notes.isEmpty()) return
        lateinit var job: Job
        synchronized(auditionLock) {
            stopAuditionLocked()
            job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    val channel = AUDITION_CHANNEL
                    // Preset pinning may decompress SF3 samples. Serialize native preparation off
                    // the UI/audio threads; a superseded request is checked again before note-on.
                    synchronized(auditionNativeLock) {
                        ensureActive()
                        prepareOutputProgram(channel, request.midiBank, request.midiProgram)
                    }
                    ensureActive()
                    synchronized(auditionLock) {
                        if (auditionJob !== job) return@launch
                        notes.forEach { midi ->
                            sendMidi(ShortMessage().apply {
                                setMessage(ShortMessage.NOTE_ON, channel, midi, request.velocity)
                            })
                        }
                        activeAuditionNotes = notes
                    }
                    delay(request.durationMillis)
                } finally {
                    synchronized(auditionLock) {
                        if (auditionJob === job) {
                            auditionJob = null
                            stopActiveAuditionNotesLocked()
                        }
                    }
                }
            }
            auditionJob = job
        }
        job.start()
    }

    override fun liveNoteOn(request: LiveNoteRequest) {
        if (!_isReady.value || !MidiNoteRange.contains(request.midi)) return
        val token = synchronized(liveLock) {
            val value = nextLiveGeneration++
            liveGeneration[request.midi] = value
            value
        }
        coroutineScope.launch(Dispatchers.IO) {
            synchronized(auditionNativeLock) {
                prepareOutputProgram(LIVE_INPUT_CHANNEL, request.midiBank, request.midiProgram)
            }
            synchronized(liveLock) {
                if (liveGeneration[request.midi] != token) return@launch
                sendMidi(ShortMessage().apply {
                    setMessage(
                        ShortMessage.NOTE_ON,
                        LIVE_INPUT_CHANNEL,
                        request.midi,
                        request.velocity,
                    )
                })
                soundingLiveNotes += request.midi
            }
        }
    }

    override fun liveNoteOff(midi: Int) {
        if (!MidiNoteRange.contains(midi)) return
        synchronized(liveLock) {
            liveGeneration.remove(midi)
            if (!soundingLiveNotes.remove(midi)) return
            sendLiveNoteOff(midi)
        }
    }

    override fun liveAllNotesOff() {
        synchronized(liveLock) {
            liveGeneration.clear()
            soundingLiveNotes.forEach(::sendLiveNoteOff)
            soundingLiveNotes.clear()
        }
    }

    override fun metronomeTick(accent: Boolean) {
        if (!_isReady.value) return
        val note = if (accent) METRONOME_ACCENT_NOTE else METRONOME_TICK_NOTE
        val velocity = if (accent) 112 else 82
        sendMidi(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_ON, METRONOME_CHANNEL, note, velocity)
        })
        coroutineScope.launch {
            delay(35L)
            sendMidi(ShortMessage().apply {
                setMessage(ShortMessage.NOTE_OFF, METRONOME_CHANNEL, note, 0)
            })
        }
    }

    private fun sendLiveNoteOff(midi: Int) {
        sendMidi(ShortMessage().apply {
            setMessage(ShortMessage.NOTE_OFF, LIVE_INPUT_CHANNEL, midi, 0)
        })
    }

    override fun seekTo(positionTicks: Long) {
        val seq = sequencer ?: return
        val midiScore = currentMidiScore ?: return

        val maxTicks = midiScore.getTotalTicks()
        val clampedPosition = positionTicks.coerceIn(0, maxTicks)

        seq.tickPosition = clampedPosition
        _currentPositionTicks.value = clampedPosition
    }

    // ========== Settings ==========

    override fun setTempoMultiplier(multiplier: Float) {
        val clamped = multiplier.coerceIn(
            AudioEngine.MIN_TEMPO_MULTIPLIER,
            AudioEngine.MAX_TEMPO_MULTIPLIER
        )
        _tempoMultiplier.value = clamped
        sequencer?.tempoFactor = clamped
    }

    override fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(AudioEngine.MIN_VOLUME, AudioEngine.MAX_VOLUME)
        _masterVolume.value = clamped

        // Apply volume to all channels
        synthesizer?.let { synth ->
            val midiVolume = (clamped * 127).toInt()
            for (channel in synth.channels) {
                channel?.controlChange(MidiControlChangeEvent.VOLUME, midiVolume)
            }
        }
        fluidSynth?.setGain(clamped)
        rhodyOutput?.setGain(clamped)
    }

    override fun setTrackMuted(trackId: TrackId, muted: Boolean) {
        if (muted) {
            mutedTracks.add(trackId)
        } else {
            mutedTracks.remove(trackId)
        }
        // Track muting would require rebuilding the sequence or using channel muting
        // For now, this is a placeholder for future implementation
    }

    override fun setTrackSolo(trackId: TrackId?) {
        soloTrack = trackId
        // Solo would require rebuilding the sequence or using channel muting
        // For now, this is a placeholder for future implementation
    }

    // ========== Private Helpers ==========

    /**
     * Create a javax.sound.midi.Sequence from MidiScore.
     */
    private fun createMidiSequence(midiScore: MidiScore): Sequence {
        val sequence = Sequence(Sequence.PPQ, midiScore.ticksPerQuarter)

        // Create tempo track
        val tempoTrack = sequence.createTrack()
        for (tempoEvent in midiScore.tempoTrack) {
            val tempoMessage = MetaMessage().apply {
                // Tempo meta event (type 0x51)
                val mpq = tempoEvent.microsecondsPerQuarter
                val data = byteArrayOf(
                    ((mpq shr 16) and 0xFF).toByte(),
                    ((mpq shr 8) and 0xFF).toByte(),
                    (mpq and 0xFF).toByte()
                )
                setMessage(0x51, data, 3)
            }
            tempoTrack.add(JavaMidiEvent(tempoMessage, tempoEvent.absoluteTicks))
        }

        // Create tracks for each MIDI track
        for (midiTrack in midiScore.tracks) {
            val track = sequence.createTrack()

            for (event in midiTrack.getEventsSorted()) {
                val midiEvent = when (event) {
                    is MidiNoteOnEvent -> {
                        val message = ShortMessage().apply {
                            setMessage(
                                ShortMessage.NOTE_ON,
                                event.channel.value,
                                event.midiNumber,
                                event.velocity.value
                            )
                        }
                        JavaMidiEvent(message, event.absoluteTicks)
                    }
                    is MidiNoteOffEvent -> {
                        val message = ShortMessage().apply {
                            setMessage(
                                ShortMessage.NOTE_OFF,
                                event.channel.value,
                                event.midiNumber,
                                0
                            )
                        }
                        JavaMidiEvent(message, event.absoluteTicks)
                    }
                    is MidiProgramChangeEvent -> {
                        val message = ShortMessage().apply {
                            setMessage(
                                ShortMessage.PROGRAM_CHANGE,
                                event.channel.value,
                                event.program,
                                0
                            )
                        }
                        JavaMidiEvent(message, event.absoluteTicks)
                    }
                    is MidiControlChangeEvent -> {
                        val message = ShortMessage().apply {
                            setMessage(
                                ShortMessage.CONTROL_CHANGE,
                                event.channel.value,
                                event.controller,
                                event.value
                            )
                        }
                        JavaMidiEvent(message, event.absoluteTicks)
                    }
                    is MidiTempoEvent -> continue // Handled in tempo track
                }
                track.add(midiEvent)
            }
        }

        return sequence
    }

    /**
     * Start tracking playback position.
     */
    private fun startPositionTracking() {
        positionTrackingJob?.cancel()
        positionTrackingJob = coroutineScope.launch {
            while (isActive) {
                val seq = sequencer
                if (seq != null) {
                    if (seq.isRunning) {
                        _currentPositionTicks.value = seq.tickPosition
                    }

                    // Check if playback ended. Even if seq.isRunning is false, it might have naturally stopped.
                    val midiScore = currentMidiScore
                    if (midiScore != null && seq.tickPosition >= midiScore.getTotalTicks() && _playbackState.value == PlaybackState.PLAYING) {
                        seq.stop()
                        _playbackState.value = PlaybackState.PAUSED
                        break
                    }
                }
                // TODO: no hardcode
                delay(50) // Update every 50ms
            }
        }
    }

    /**
     * Stop tracking playback position.
     */
    private fun stopPositionTracking() {
        positionTrackingJob?.cancel()
        positionTrackingJob = null
    }

    private fun stopAudition() {
        synchronized(auditionLock) { stopAuditionLocked() }
    }

    private fun stopAuditionLocked() {
        auditionJob?.cancel()
        auditionJob = null
        stopActiveAuditionNotesLocked()
    }

    private fun stopActiveAuditionNotesLocked() {
        if (activeAuditionNotes.isNotEmpty()) {
            val channel = AUDITION_CHANNEL
            activeAuditionNotes.forEach { midi ->
                sendMidi(ShortMessage().apply {
                    setMessage(ShortMessage.NOTE_OFF, channel, midi, 0)
                })
            }
            activeAuditionNotes = emptyList()
        }
    }

    private fun prepareOutputProgram(channel: Int, bank: Int, program: Int) {
        fluidSynth?.prepareProgram(channel, bank, program)
            ?: synthesizer?.channels?.getOrNull(channel)?.programChange(bank, program)
        rhodyReceiver?.selectProgram(channel, bank, program)
    }

    private fun sendMidi(message: ShortMessage) {
        midiOutput?.send(message, -1L)
    }

    private companion object {
        /** Channel 10 is percussion; use the last melodic channel for the short-lived UI audition. */
        const val AUDITION_CHANNEL = 15
        /** Keep live input separate from transport and fixed-duration audition. */
        const val LIVE_INPUT_CHANNEL = 14
        const val METRONOME_CHANNEL = 9
        const val METRONOME_TICK_NOTE = 76
        const val METRONOME_ACCENT_NOTE = 77
    }
}
