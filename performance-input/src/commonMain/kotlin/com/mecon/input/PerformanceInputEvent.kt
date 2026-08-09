package com.mecon.input

/**
 * Platform-neutral performance input. Desktop MIDI and Compose keyboard adapters normalize their
 * native events to this clocked stream before chord collection or real-time capture.
 */
sealed interface PerformanceInputEvent {
    val sourceId: String
    val atNanos: Long

    data class NoteOn(
        override val sourceId: String,
        override val atNanos: Long,
        val key: Int,
        val velocity: Int = 100,
        val spellingHint: SpellingHint? = null,
    ) : PerformanceInputEvent

    data class NoteOff(
        override val sourceId: String,
        override val atNanos: Long,
        val key: Int,
    ) : PerformanceInputEvent

    data class ControlChange(
        override val sourceId: String,
        override val atNanos: Long,
        val controller: Int,
        val value: Int,
    ) : PerformanceInputEvent

    /** The source vanished or was explicitly switched; consumers must close every held note. */
    data class SourceDisconnected(
        override val sourceId: String,
        override val atNanos: Long,
    ) : PerformanceInputEvent
}

enum class SpellingHint {
    NATURAL,
    RAISE,
    LOWER,
}

/** Identity of one held key; MIDI devices with the same note number remain independent sources. */
data class InputKeyId(val sourceId: String, val key: Int)
