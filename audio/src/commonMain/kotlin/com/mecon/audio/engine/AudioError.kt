package com.mecon.audio.engine

/**
 * Audio engine error types.
 */
sealed class AudioError(val message: String) {

    /** Failed to initialize audio system */
    class InitializationFailed(message: String) : AudioError(message)

    /** No audio device available */
    class NoAudioDevice(message: String = "No audio device available") : AudioError(message)

    /** Failed to load score */
    class ScoreLoadFailed(message: String) : AudioError(message)

    /** Failed to load SoundFont */
    class SoundFontLoadFailed(
        val filePath: String,
        message: String
    ) : AudioError("Failed to load SoundFont '$filePath': $message")

    /** SoundFont file not found */
    class SoundFontNotFound(val filePath: String) : AudioError("SoundFont not found: $filePath")

    /** Unsupported SoundFont format */
    class UnsupportedSoundFontFormat(
        val format: String
    ) : AudioError("Unsupported SoundFont format: $format")

    /** Playback error */
    class PlaybackError(message: String) : AudioError(message)

    /** Invalid operation for current state */
    class InvalidState(
        val currentState: PlaybackState,
        val operation: String
    ) : AudioError("Cannot $operation in state $currentState")

    /** Generic error */
    class Unknown(message: String) : AudioError(message)

    override fun toString(): String = "${this::class.simpleName}: $message"
}

/**
 * Result type for audio operations.
 */
sealed class AudioResult<out T> {
    data class Success<T>(val value: T) : AudioResult<T>()
    data class Failure(val error: AudioError) : AudioResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): AudioResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun <R> flatMap(transform: (T) -> AudioResult<R>): AudioResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): AudioResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (AudioError) -> Unit): AudioResult<T> {
        if (this is Failure) action(error)
        return this
    }

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw AudioException(error)
    }

    companion object {
        fun <T> success(value: T): AudioResult<T> = Success(value)
        fun failure(error: AudioError): AudioResult<Nothing> = Failure(error)
    }
}

/**
 * Exception wrapper for AudioError.
 */
class AudioException(val error: AudioError) : Exception(error.message)
