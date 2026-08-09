package com.mecon.audio.engine

/**
 * Playback state of the audio engine.
 */
enum class PlaybackState {
    /** Engine is idle, no score loaded */
    IDLE,

    /** Score is being loaded/prepared */
    LOADING,

    /** Playback is active */
    PLAYING,

    /** Playback is paused */
    PAUSED,

    /** Playback has stopped (can resume from beginning) */
    STOPPED
}
