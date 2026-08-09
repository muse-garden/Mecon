package com.mecon.audio.soundfont

sealed interface SoundFontLoadState {
    data object Idle : SoundFontLoadState
    data class Loading(
        val soundFontName: String,
        val stage: Stage,
        /** Completed preparation units; meaningful when [total] is non-null. */
        val current: Int = 0,
        /** Total presets/channels to prepare, or null for an indeterminate file operation. */
        val total: Int? = null,
    ) : SoundFontLoadState {
        val progress: Float? get() = total?.takeIf { it > 0 }
            ?.let { current.coerceIn(0, it).toFloat() / it.toFloat() }
    }
    data class Failed(val soundFontName: String, val message: String) : SoundFontLoadState

    enum class Stage { OPENING, READING_PRESETS, PREPARING_SAMPLES }
}
