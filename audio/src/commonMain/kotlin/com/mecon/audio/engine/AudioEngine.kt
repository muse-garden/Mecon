package com.mecon.audio.engine

import com.mecon.audio.model.MidiScore
import com.mecon.audio.soundfont.SoundFontManager
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio playback engine interface.
 *
 * Provides MIDI-based audio playback with SoundFont support and tempo control.
 * Platform implementations (JVM, Android, iOS) provide the actual audio output.
 */
interface AudioEngine {

    // ========== State ==========

    /**
     * Current playback state.
     */
    val playbackState: StateFlow<PlaybackState>

    /**
     * Current playback position in ticks.
     */
    val currentPositionTicks: StateFlow<Long>

    /**
     * Whether the engine is ready for playback.
     */
    val isReady: StateFlow<Boolean>

    /**
     * Current tempo multiplier (0.25 to 4.0, where 1.0 = normal speed).
     */
    val tempoMultiplier: StateFlow<Float>

    /**
     * Master volume (0.0 to 1.0).
     */
    val masterVolume: StateFlow<Float>

    // ========== SoundFont ==========

    /**
     * The SoundFont manager for this engine.
     */
    val soundFontManager: SoundFontManager

    // ========== Lifecycle ==========

    /**
     * Initialize the audio engine.
     * Must be called before any other operations.
     */
    suspend fun initialize(): AudioResult<Unit>

    /**
     * Shutdown the audio engine and release resources.
     */
    suspend fun shutdown()

    // ========== Score Loading ==========

    /**
     * Load a RuntimeScore for playback.
     * Converts to MIDI internally.
     *
     * @param score The score to load
     * @return Success if loaded, or error
     */
    suspend fun loadScore(score: RuntimeScore): AudioResult<Unit>

    /**
     * Load a pre-converted MidiScore for playback.
     *
     * @param midiScore The MIDI score to load
     * @return Success if loaded, or error
     */
    suspend fun loadMidiScore(midiScore: MidiScore): AudioResult<Unit>

    /**
     * Unload the current score.
     */
    suspend fun unloadScore()

    // ========== Playback Control ==========

    /**
     * Start or resume playback.
     */
    fun play()

    /**
     * Pause playback.
     */
    fun pause()

    /**
     * Stop playback and reset position to beginning.
     */
    fun stop()

    /**
     * Play a short note or chord without loading a score or changing the transport position.
     * Implementations replace any earlier audition so live pitch dragging stays responsive.
     */
    fun audition(request: NoteAudition)

    /**
     * Seek to a specific position.
     *
     * @param positionTicks Position in ticks
     */
    fun seekTo(positionTicks: Long)

    // ========== Settings ==========

    /**
     * Set the tempo multiplier.
     *
     * @param multiplier Tempo factor (0.25 to 4.0, where 1.0 = normal)
     */
    fun setTempoMultiplier(multiplier: Float)

    /**
     * Set the master volume.
     *
     * @param volume Volume level (0.0 to 1.0)
     */
    fun setMasterVolume(volume: Float)

    // ========== Track Settings ==========

    /**
     * Mute or unmute a track.
     *
     * @param trackId Track to mute/unmute
     * @param muted Whether to mute
     */
    fun setTrackMuted(trackId: TrackId, muted: Boolean)

    /**
     * Solo a track (mute all others).
     *
     * @param trackId Track to solo, or null to disable solo
     */
    fun setTrackSolo(trackId: TrackId?)

    companion object {
        const val MIN_TEMPO_MULTIPLIER = 0.25f
        const val MAX_TEMPO_MULTIPLIER = 4.0f
        const val DEFAULT_TEMPO_MULTIPLIER = 1.0f

        const val MIN_VOLUME = 0.0f
        const val MAX_VOLUME = 1.0f
        const val DEFAULT_VOLUME = 0.8f
    }
}
