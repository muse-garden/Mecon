package com.mecon.desktop.service

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.computed.MeasurePosition
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.storage.InstrumentTemplate
import com.mecon.api.storage.StaffTemplate
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.tracks.InstrumentPlayback
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.audio.engine.AudioEngine
import com.mecon.audio.engine.AudioResult
import com.mecon.audio.engine.NoteAudition
import com.mecon.audio.engine.PlaybackState
import com.mecon.audio.model.MidiScore
import com.mecon.audio.model.SoundFontId
import com.mecon.audio.model.SoundFontInfo
import com.mecon.audio.model.SoundFontPreset
import com.mecon.audio.soundfont.SoundFontManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlaybackControllerTest {
    @Test
    fun `resuming same score does not reload`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val score = RuntimeScore.create("Playback")

        controller.playFromCurrent(score)
        drainLaunchedJobs()
        controller.pause()
        controller.playFromCurrent(score)
        drainLaunchedJobs()

        assertEquals(1, engine.loadedScores.size)
        assertSame(score, engine.loadedScore)
        assertEquals(2, engine.playCalls)
        assertEquals(0, engine.stopCalls)
    }

    @Test
    fun `new runtime score reloads while paused`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val oldScore = RuntimeScore.create("Old")
        val newScore = RuntimeScore.create("New")

        controller.playFromCurrent(oldScore)
        drainLaunchedJobs()
        controller.pause()
        controller.playFromCurrent(newScore)
        drainLaunchedJobs()

        assertEquals(listOf(oldScore, newScore), engine.loadedScores)
        assertSame(newScore, engine.loadedScore)
        assertEquals(2, engine.playCalls)
        assertEquals(1, engine.stopCalls)
    }

    @Test
    fun `play from start rewinds already loaded score`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val score = RuntimeScore.create("Playback")

        controller.playFromCurrent(score)
        drainLaunchedJobs()
        controller.pause()
        controller.playFromStart(score)
        drainLaunchedJobs()

        assertEquals(1, engine.loadedScores.size)
        assertEquals(listOf(0L), engine.seekCalls)
    }

    @Test
    fun `preloaded score plays without loading a second time`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val score = RuntimeScore.create("Preloaded")

        controller.preloadScore(score)
        controller.playFromCurrent(score)
        drainLaunchedJobs()

        assertEquals(listOf(score), engine.loadedScores)
        assertEquals(1, engine.playCalls)
    }

    @Test
    fun `excerpt uses embedded tempo and resets global multiplier`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val score = RuntimeScore.create("Excerpt")
        engine.setTempoMultiplier(1.75f)

        controller.playExcerpt(
            score,
            TimeCode.of(1, Fraction.ZERO),
            TimeCode.of(1, Fraction.QUARTER),
            tempoBpm = 88,
        )
        drainLaunchedJobs()

        assertEquals(88f, engine.loadedMidiScores.single().getInitialTempo())
        assertEquals(1f, engine.tempoMultiplier.value)
        assertEquals(1, engine.playCalls)
        assertEquals(false, controller.playbackShowsCursor.value)
    }

    @Test
    fun `practice transport embeds configured tempo without changing global multiplier`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val score = RuntimeScore.create("Practice")
        engine.setTempoMultiplier(1.25f)

        controller.playFromStart(score, tempoBpm = 84)
        drainLaunchedJobs()

        assertEquals(84f, engine.loadedMidiScores.single().getInitialTempo())
        assertEquals(1.25f, engine.tempoMultiplier.value)
        assertEquals(1, engine.playCalls)
        assertEquals(true, controller.playbackShowsCursor.value)
    }

    @Test
    fun `changing practice tempo reloads the same score`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val score = RuntimeScore.create("Practice")

        controller.playFromCurrent(score, tempoBpm = 84)
        drainLaunchedJobs()
        controller.pause()
        controller.playFromCurrent(score, tempoBpm = 96)
        drainLaunchedJobs()

        assertEquals(listOf(84f, 96f), engine.loadedMidiScores.map { it.getInitialTempo() })
        assertEquals(1, engine.stopCalls)
    }

    @Test
    fun `notehead audition plays only requested chord pitch`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)

        controller.audition(RuntimeScore.create("Audition"), chordEvent(), setOf(1))

        assertEquals(listOf(76), engine.auditions.single().midiNumbers)
    }

    @Test
    fun `chord audition keeps computed transposition while following drag spelling`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)

        controller.audition(RuntimeScore.create("Audition"), chordEvent(), stepDelta = 1)

        // Written C4/E4 become D4/F4 in C major, while preserving the computed +12 transposition.
        assertEquals(listOf(74, 77), engine.auditions.single().midiNumbers)
    }

    @Test
    fun `partial chord drag auditions full chord but moves only dragged pitch`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)

        controller.audition(
            RuntimeScore.create("Audition"),
            chordEvent(),
            pitchIndices = null,
            transposedPitchIndices = setOf(0),
            stepDelta = 1,
        )

        assertEquals(listOf(74, 76), engine.auditions.single().midiNumbers)
    }

    @Test
    fun `completed edit auditions only a single selected voice event`() = runBlocking {
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)
        val score = RuntimeScore.create("Audition")
        val first = chordEvent(EventId("first"))
        val second = chordEvent(EventId("second"))

        controller.auditionSingleEditedEvent(setOf(VoiceEventSection(first)), score)
        controller.auditionSingleEditedEvent(
            setOf(VoiceEventSection(first), VoiceEventSection(second)),
            score,
        )

        assertEquals(1, engine.auditions.size)
        assertEquals(listOf(72, 76), engine.auditions.single().midiNumbers)
    }

    @Test
    fun `inserted event audition resolves owning instrument from canonical track maps`() = runBlocking {
        val storage = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "Organ",
                    staves = listOf(StaffTemplate("Manual", Clef.TREBLE)),
                    midiProgram = 19,
                )
            )
        ))
        val original = RuntimeScore.fromStorage(storage)
        val voiceTrackId = original.voiceTracks.keys.single()
        val pitchEvent = RuntimePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val voiceEvent = RuntimeVoiceEvent.create(
            onset = pitchEvent.onset,
            pitchEvent = pitchEvent,
            duration = Duration.QUARTER,
        )
        // addVoiceEvent refreshes canonical voice/staff maps but not RuntimeInstrument's old snapshot.
        val edited = original.addVoiceEvent(voiceTrackId, voiceEvent)
        val engine = FakeAudioEngine()
        val controller = PlaybackController(engine, this)

        controller.audition(edited, chordEvent(voiceEvent.id))

        assertEquals(19, engine.auditions.single().midiProgram)
    }

    @Test
    fun `reduction audition explicitly uses default piano`() = runBlocking {
        val storage = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "Organ",
                    staves = listOf(StaffTemplate("Manual", Clef.TREBLE)),
                    midiProgram = 19,
                )
            )
        ))
        val score = RuntimeScore.fromStorage(storage)
        val engine = FakeAudioEngine()

        PlaybackController(engine, this).auditionPiano(score, chordEvent())

        assertEquals(InstrumentPlayback.PIANO.midiProgram, engine.auditions.single().midiProgram)
        assertEquals(InstrumentPlayback.PIANO.midiBank, engine.auditions.single().midiBank)
    }

    @Test
    fun `note insertion callback auditions against committed score`() = runBlocking {
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val storage = StorageScore.create(StorageScore.CreationOptions(
                instrumentTemplates = listOf(
                    InstrumentTemplate(
                        name = "Organ",
                        staves = listOf(StaffTemplate("Manual", Clef.TREBLE)),
                        midiProgram = 19,
                    )
                )
            ))
            val session = ScoreSession(sessionScope)
            session.replaceDocument(storage, file = null, fileName = "Audition.mecon")
            val voiceTrackId = session.runtimeScore!!.voiceTracks.keys.single()
            val inserted = CompletableDeferred<Pair<VoiceEventSection, RuntimeScore>>()

            session.applyNoteEdit(
                NoteEditEngine.Insertion(
                    voiceTrackId = voiceTrackId,
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration.QUARTER,
                    pitch = Pitch.C4,
                )
            ) { section, committedScore ->
                inserted.complete(section as VoiceEventSection to committedScore)
            }

            val (section, committedScore) = withTimeout(5_000) { inserted.await() }
            val engine = FakeAudioEngine()
            PlaybackController(engine, this).audition(committedScore, section.event)

            assertEquals(19, engine.auditions.single().midiProgram)
        } finally {
            sessionScope.cancel()
        }
    }

    private suspend fun drainLaunchedJobs() {
        coroutineContext[Job]?.children?.toList()?.joinAll()
    }
}

private fun chordEvent(id: EventId = EventId("audition-event")) = ComputedVoiceEvent(
    id = id,
    onset = TimeCode.of(1, Fraction.ZERO),
    duration = Duration.QUARTER,
    pitchData = listOf(
        ComputedPitchData(Pitch.C4, 72, 0, null, false),
        ComputedPitchData(Pitch.fromMidi(64), 76, 2, null, false),
    ),
    measurePosition = MeasurePosition(1, Fraction.ZERO, Fraction.ZERO),
    isRest = false,
    beamInfo = null,
)

private class FakeAudioEngine : AudioEngine {
    private val playbackStateFlow = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = playbackStateFlow.asStateFlow()

    private val currentPositionTicksFlow = MutableStateFlow(0L)
    override val currentPositionTicks: StateFlow<Long> = currentPositionTicksFlow.asStateFlow()

    private val isReadyFlow = MutableStateFlow(true)
    override val isReady: StateFlow<Boolean> = isReadyFlow.asStateFlow()

    private val tempoMultiplierFlow = MutableStateFlow(AudioEngine.DEFAULT_TEMPO_MULTIPLIER)
    override val tempoMultiplier: StateFlow<Float> = tempoMultiplierFlow.asStateFlow()

    private val masterVolumeFlow = MutableStateFlow(AudioEngine.DEFAULT_VOLUME)
    override val masterVolume: StateFlow<Float> = masterVolumeFlow.asStateFlow()

    override val soundFontManager: SoundFontManager = FakeSoundFontManager()

    val loadedScores = mutableListOf<RuntimeScore>()
    val loadedMidiScores = mutableListOf<MidiScore>()
    val seekCalls = mutableListOf<Long>()
    var loadedScore: RuntimeScore? = null
        private set
    var playCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    val auditions = mutableListOf<NoteAudition>()

    override suspend fun initialize(): AudioResult<Unit> = AudioResult.success(Unit)

    override suspend fun shutdown() {
        playbackStateFlow.value = PlaybackState.IDLE
        loadedScore = null
    }

    override suspend fun loadScore(score: RuntimeScore): AudioResult<Unit> {
        loadedScores += score
        loadedScore = score
        currentPositionTicksFlow.value = 0L
        playbackStateFlow.value = PlaybackState.STOPPED
        return AudioResult.success(Unit)
    }

    override suspend fun loadMidiScore(midiScore: MidiScore): AudioResult<Unit> {
        loadedMidiScores += midiScore
        playbackStateFlow.value = PlaybackState.STOPPED
        return AudioResult.success(Unit)
    }

    override suspend fun unloadScore() {
        loadedScore = null
        playbackStateFlow.value = PlaybackState.IDLE
    }

    override fun play() {
        if ((loadedScore != null || loadedMidiScores.isNotEmpty()) &&
            playbackStateFlow.value in listOf(PlaybackState.STOPPED, PlaybackState.PAUSED)
        ) {
            playCalls += 1
            playbackStateFlow.value = PlaybackState.PLAYING
        }
    }

    override fun pause() {
        if (playbackStateFlow.value == PlaybackState.PLAYING) {
            playbackStateFlow.value = PlaybackState.PAUSED
        }
    }

    override fun stop() {
        stopCalls += 1
        currentPositionTicksFlow.value = 0L
        playbackStateFlow.value = PlaybackState.STOPPED
    }

    override fun audition(request: NoteAudition) {
        auditions += request
    }

    override fun seekTo(positionTicks: Long) {
        seekCalls += positionTicks
        currentPositionTicksFlow.value = positionTicks
    }

    override fun setTempoMultiplier(multiplier: Float) {
        tempoMultiplierFlow.value = multiplier
    }

    override fun setMasterVolume(volume: Float) {
        masterVolumeFlow.value = volume
    }

    override fun setTrackMuted(trackId: TrackId, muted: Boolean) = Unit

    override fun setTrackSolo(trackId: TrackId?) = Unit
}

private class FakeSoundFontManager : SoundFontManager {
    override val loadState: StateFlow<com.mecon.audio.soundfont.SoundFontLoadState> =
        MutableStateFlow<com.mecon.audio.soundfont.SoundFontLoadState>(
            com.mecon.audio.soundfont.SoundFontLoadState.Idle
        ).asStateFlow()

    private val availableSoundFontsFlow = MutableStateFlow(emptyList<SoundFontInfo>())
    override val availableSoundFonts: StateFlow<List<SoundFontInfo>> = availableSoundFontsFlow.asStateFlow()

    private val loadedSoundFontsFlow = MutableStateFlow(emptySet<SoundFontId>())
    override val loadedSoundFonts: StateFlow<Set<SoundFontId>> = loadedSoundFontsFlow.asStateFlow()

    private val defaultSoundFontFlow = MutableStateFlow<SoundFontInfo?>(null)
    override val defaultSoundFont: StateFlow<SoundFontInfo?> = defaultSoundFontFlow.asStateFlow()

    override suspend fun scanDirectory(directoryPath: String, recursive: Boolean): AudioResult<List<SoundFontInfo>> =
        AudioResult.success(emptyList())

    override suspend fun addSoundFont(filePath: String): AudioResult<SoundFontInfo> =
        AudioResult.failure(com.mecon.audio.engine.AudioError.SoundFontNotFound(filePath))

    override suspend fun loadSoundFont(soundFontId: SoundFontId): AudioResult<Unit> =
        AudioResult.success(Unit)

    override suspend fun unloadSoundFont(soundFontId: SoundFontId) = Unit

    override fun setDefaultSoundFont(soundFontId: SoundFontId?) = Unit

    override suspend fun getPresets(soundFontId: SoundFontId): List<SoundFontPreset> = emptyList()

    override fun getSoundFontInfo(soundFontId: SoundFontId): SoundFontInfo? = null
}
