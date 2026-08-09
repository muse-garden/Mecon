package com.mecon.desktop.service

import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.DiatonicTranspose
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.audio.converter.ScoreToMidiConverter
import com.mecon.audio.engine.AudioEngine
import com.mecon.audio.engine.AudioResult
import com.mecon.audio.engine.NoteAudition
import com.mecon.audio.engine.PlaybackState
import com.mecon.audio.engine.LiveNoteRequest
import com.mecon.audio.engine.LiveNoteSink
import com.mecon.audio.engine.MetronomeSink
import com.mecon.audio.model.MidiNoteRange
import com.mecon.audio.model.MidiEvent
import com.mecon.audio.model.MidiNoteOnEvent
import com.mecon.audio.model.MidiNoteOffEvent
import com.mecon.audio.model.MidiTempoEvent
import com.mecon.audio.model.MidiProgramChangeEvent
import com.mecon.audio.model.MidiControlChangeEvent
import com.mecon.audio.model.MidiScore
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.tracks.InstrumentPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mecon.audio.soundfont.SoundFontLoadState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.toImmutableList

/**
 * Wraps the [AudioEngine] transport behind the toolbar's playback intents.
 *
 * Owns the "load the score if playback is idle, then play / seek" orchestration so
 * callers only express *what* they want (play from start / current / selection) and
 * never touch the engine's loaded/stopped lifecycle directly.
 */
class PlaybackController(
    private val audioEngine: AudioEngine,
    private val scope: CoroutineScope
) {
    val playbackState: StateFlow<PlaybackState> get() = audioEngine.playbackState
    val tempoMultiplier: StateFlow<Float> get() = audioEngine.tempoMultiplier
    val currentPositionTicks: StateFlow<Long> get() = audioEngine.currentPositionTicks
    private val playbackShowsCursorFlow = MutableStateFlow(true)
    /** Edit auditions use the transport engine for sound, but must not move the score playhead. */
    val playbackShowsCursor: StateFlow<Boolean> = playbackShowsCursorFlow.asStateFlow()
    val soundFontLoadState: StateFlow<SoundFontLoadState> get() = audioEngine.soundFontManager.loadState

    suspend fun initialize() { audioEngine.initialize() }
    suspend fun shutdown() { audioEngine.shutdown() }

    private var loadedScore: RuntimeScore? = null
    private var loadedTempoBpm: Int? = null

    /** True when no prepared score is retained by the controller. STOPPED may still be loaded. */
    private val needsLoad: Boolean get() = loadedScore == null || audioEngine.playbackState.value == PlaybackState.IDLE

    private suspend fun ensureScoreLoaded(score: RuntimeScore, tempoBpm: Int? = null) {
        require(tempoBpm == null || tempoBpm in 30..240)
        val state = audioEngine.playbackState.value
        val scoreChanged = loadedScore !== score || loadedTempoBpm != tempoBpm
        if (state == PlaybackState.IDLE || loadedScore == null || scoreChanged) {
            if (scoreChanged && (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED)) {
                audioEngine.stop()
            }
            val result = if (tempoBpm == null) {
                audioEngine.loadScore(score)
            } else {
                val midi = withContext(Dispatchers.Default) {
                    ScoreToMidiConverter.convert(score).withTempo(tempoBpm.toFloat())
                }
                audioEngine.loadMidiScore(midi)
            }
            if (result is AudioResult.Success) {
                loadedScore = score
                loadedTempoBpm = tempoBpm
            }
        }
    }

    /** Convert and preload every instrument preset immediately after a document is installed. */
    suspend fun preloadScore(score: RuntimeScore?) {
        score?.let { ensureScoreLoaded(it) }
    }

    /** Play from the very start, (re)loading the score if needed, else rewinding. */
    fun playFromStart(score: RuntimeScore?, tempoBpm: Int? = null) {
        val rt = score ?: return
        playbackShowsCursorFlow.value = true
        scope.launch {
            val alreadyLoaded = !needsLoad && loadedScore === rt && loadedTempoBpm == tempoBpm
            ensureScoreLoaded(rt, tempoBpm)
            if (alreadyLoaded) audioEngine.seekTo(0L)
            audioEngine.play()
        }
    }

    /** Resume / play from the current position. */
    fun playFromCurrent(score: RuntimeScore?, tempoBpm: Int? = null) {
        val rt = score ?: return
        playbackShowsCursorFlow.value = true
        scope.launch {
            ensureScoreLoaded(rt, tempoBpm)
            audioEngine.play()
        }
    }

    /** Seek to [timeCode] (if any) then play. Caller gates this on having a selection. */
    fun playFromSelection(score: RuntimeScore?, timeCode: TimeCode?, tempoBpm: Int? = null) {
        val rt = score ?: return
        playbackShowsCursorFlow.value = true
        scope.launch {
            ensureScoreLoaded(rt, tempoBpm)
            if (timeCode != null) {
                audioEngine.seekTo(ScoreToMidiConverter.timeCodeToPlaybackTicks(timeCode, rt))
            }
            audioEngine.play()
        }
    }

    fun pause() { audioEngine.pause() }
    fun setTempo(multiplier: Float) { audioEngine.setTempoMultiplier(multiplier) }

    fun liveNoteOn(score: RuntimeScore?, staffTrackId: TrackId?, midi: Int, velocity: Int) {
        if (!MidiNoteRange.contains(midi)) return
        val sink = audioEngine as? LiveNoteSink ?: return
        val playback = score?.instruments
            ?.firstOrNull { instrument -> instrument.staves.any { it.id == staffTrackId } }
            ?.playback
            ?: InstrumentPlayback.PIANO
        sink.liveNoteOn(
            LiveNoteRequest(
                midi = midi,
                velocity = velocity.coerceIn(1, 127),
                midiBank = playback.midiBank,
                midiProgram = playback.midiProgram,
            )
        )
    }

    /** Plays an isolated score excerpt at an explicit BPM without changing the global tempo control. */
    fun playExcerpt(
        score: RuntimeScore?,
        start: TimeCode,
        end: TimeCode,
        tempoBpm: Int,
    ) {
        val runtime = score ?: return
        require(tempoBpm in 30..240)
        playbackShowsCursorFlow.value = false
        scope.launch {
            val excerpt = withContext(Dispatchers.Default) {
                val midi = ScoreToMidiConverter.convert(runtime)
                val startTicks = ScoreToMidiConverter.timeCodeToPlaybackTicks(start, runtime)
                val endTicks = ScoreToMidiConverter.timeCodeToPlaybackTicks(end, runtime)
                midi.excerpt(startTicks, endTicks, tempoBpm.toFloat())
            }
            audioEngine.stop()
            audioEngine.setTempoMultiplier(1f)
            if (audioEngine.loadMidiScore(excerpt) is AudioResult.Success) {
                loadedScore = null
                loadedTempoBpm = null
                audioEngine.play()
            }
        }
    }

    fun liveNoteOff(midi: Int) {
        (audioEngine as? LiveNoteSink)?.liveNoteOff(midi)
    }

    fun liveAllNotesOff() {
        (audioEngine as? LiveNoteSink)?.liveAllNotesOff()
    }

    fun metronomeTick(accent: Boolean) {
        (audioEngine as? MetronomeSink)?.metronomeTick(accent)
    }

    /**
     * Preview one computed notehead ([pitchIndices]) or its complete owning chord (null).
     * [stepDelta] is used by drag-to-transpose and follows the same key-based spelling as the edit.
     */
    fun audition(
        score: RuntimeScore?,
        event: ComputedVoiceEvent,
        pitchIndices: Set<Int>? = null,
        transposedPitchIndices: Set<Int>? = pitchIndices,
        stepDelta: Int = 0,
    ) = auditionWithPlayback(
        score = score,
        event = event,
        pitchIndices = pitchIndices,
        transposedPitchIndices = transposedPitchIndices,
        stepDelta = stepDelta,
        playbackOverride = null,
    )

    /** Audition reduction notation with a predictable neutral timbre. */
    fun auditionPiano(
        score: RuntimeScore?,
        event: ComputedVoiceEvent,
        pitchIndices: Set<Int>? = null,
        transposedPitchIndices: Set<Int>? = pitchIndices,
        stepDelta: Int = 0,
    ) = auditionWithPlayback(
        score = score,
        event = event,
        pitchIndices = pitchIndices,
        transposedPitchIndices = transposedPitchIndices,
        stepDelta = stepDelta,
        playbackOverride = InstrumentPlayback.PIANO,
    )

    /** Replays a shared free-practice edit audition without re-deriving its selected event. */
    fun auditionMidiNumbers(midiNumbers: List<Int>) {
        audioEngine.audition(NoteAudition(midiNumbers = midiNumbers))
    }

    private fun auditionWithPlayback(
        score: RuntimeScore?,
        event: ComputedVoiceEvent,
        pitchIndices: Set<Int>?,
        transposedPitchIndices: Set<Int>?,
        stepDelta: Int,
        playbackOverride: InstrumentPlayback?,
    ) {
        val rt = score ?: return
        if (event.isRest) return
        val indices = pitchIndices?.filter { it in event.pitchData.indices }
            ?: event.pitchData.indices.toList()
        if (indices.isEmpty()) return
        val key = rt.getKeySignatureAt(event.onset.measure)
        val midiNumbers = indices.mapNotNull { index ->
            val pitchData = event.pitchData[index]
            val transposeThisPitch = stepDelta != 0 &&
                (transposedPitchIndices == null || index in transposedPitchIndices)
            val midi = if (!transposeThisPitch) {
                pitchData.midiPitch
            } else {
                val moved = DiatonicTranspose.spell(key, pitchData.pitch.diatonicSteps + stepDelta)
                pitchData.midiPitch + moved.midiNumber - pitchData.pitch.midiNumber
            }
            midi.takeIf(MidiNoteRange::contains)
        }
        if (midiNumbers.isEmpty()) return
        val playback = playbackOverride ?: playbackForEvent(rt, event) ?: InstrumentPlayback.PIANO
        audioEngine.audition(
            NoteAudition(
                midiNumbers = midiNumbers,
                midiBank = playback.midiBank,
                midiProgram = playback.midiProgram,
            )
        )
    }

    /** Stop and unload — used before swapping in a different document. */
    suspend fun stopAndUnload() {
        audioEngine.stop()
        audioEngine.unloadScore()
        loadedScore = null
    }

    private fun playbackForEvent(score: RuntimeScore, event: ComputedVoiceEvent): InstrumentPlayback? {
        // Note edits rebuild the canonical voice/staff maps, while RuntimeInstrument intentionally
        // keeps the originally resolved staff objects. Resolve the changing event through the maps,
        // then cross the stable staff id into the owning instrument. Scanning instrument event
        // snapshots would miss every newly inserted/replaced event and silently fall back to piano.
        val voiceTrackId = score.voiceTracks.entries.firstOrNull { (_, voice) ->
            voice.events.any { it.id == event.id }
        }?.key ?: event.originVoiceTrackId ?: return null
        val staffTrackId = score.staffTracks.entries.firstOrNull { (_, staff) ->
            staff.voiceTracks.any { it.id == voiceTrackId }
        }?.key ?: return null
        return score.instruments.firstOrNull { instrument ->
            instrument.staves.any { it.id == staffTrackId }
        }?.playback
    }
}

private fun MidiScore.excerpt(startTicks: Long, endTicks: Long, bpm: Float): MidiScore {
    require(endTicks > startTicks)
    val length = endTicks - startTicks
    val clippedTracks = tracks.map { track ->
        val setup = track.events
            .filter { it.absoluteTicks <= startTicks &&
                (it is MidiProgramChangeEvent || it is MidiControlChangeEvent) }
            .groupBy { it::class to when (it) {
                is MidiControlChangeEvent -> it.controller
                else -> -1
            } }
            .values
            .mapNotNull { events -> events.maxByOrNull(MidiEvent::absoluteTicks) }
            .map { it.shiftedTo(0L) }
        val inside = track.events
            .filter { event ->
                event.absoluteTicks >= startTicks &&
                    (event.absoluteTicks < endTicks || event is MidiNoteOffEvent)
            }
            .map { it.shiftedTo((it.absoluteTicks - startTicks).coerceIn(0L, length)) }
        track.copy(events = (setup + inside).sortedBy { it.absoluteTicks }.toImmutableList())
    }
    return copy(
        tracks = clippedTracks.toImmutableList(),
        tempoTrack = listOf(MidiTempoEvent(0L, bpm)).toImmutableList(),
    )
}

private fun MidiScore.withTempo(bpm: Float): MidiScore = copy(
    tempoTrack = listOf(MidiTempoEvent(0L, bpm)).toImmutableList(),
)

private fun MidiEvent.shiftedTo(ticks: Long): MidiEvent = when (this) {
    is MidiNoteOnEvent -> copy(absoluteTicks = ticks)
    is MidiNoteOffEvent -> copy(absoluteTicks = ticks)
    is MidiTempoEvent -> copy(absoluteTicks = ticks)
    is MidiProgramChangeEvent -> copy(absoluteTicks = ticks)
    is MidiControlChangeEvent -> copy(absoluteTicks = ticks)
}
