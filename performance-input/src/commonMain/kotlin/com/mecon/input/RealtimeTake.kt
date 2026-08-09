package com.mecon.input

import com.mecon.api.primitive.Pitch

data class RawPlayedNote(
    val inputKey: InputKeyId,
    val pitch: Pitch,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val velocity: Int,
)

data class RawPerformanceTake(
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val notes: List<RawPlayedNote>,
)

/**
 * Captures immutable note spans without interpreting wall-clock time or notation. Pitch is resolved
 * at NoteOn, so later key-signature/settings changes cannot rewrite a held key.
 */
class RealtimeTakeRecorder {
    private data class Active(
        val pitch: Pitch,
        val startedAtNanos: Long,
        val velocity: Int,
    )

    private val active = LinkedHashMap<InputKeyId, Active>()
    private val completed = ArrayList<RawPlayedNote>()
    private var firstNanos: Long? = null

    val isEmpty: Boolean get() = firstNanos == null
    val activeCount: Int get() = active.size

    fun noteOn(key: InputKeyId, pitch: Pitch, atNanos: Long, velocity: Int): Boolean {
        if (key in active) return false
        firstNanos = firstNanos ?: atNanos
        active[key] = Active(pitch, atNanos, velocity.coerceIn(1, 127))
        return true
    }

    fun noteOff(key: InputKeyId, atNanos: Long): Boolean {
        val note = active.remove(key) ?: return false
        completed += RawPlayedNote(
            inputKey = key,
            pitch = note.pitch,
            startedAtNanos = note.startedAtNanos,
            endedAtNanos = atNanos.coerceAtLeast(note.startedAtNanos),
            velocity = note.velocity,
        )
        return true
    }

    fun releaseSource(sourceId: String, atNanos: Long) {
        active.keys.filter { it.sourceId == sourceId }.forEach { noteOff(it, atNanos) }
    }

    fun finish(atNanos: Long): RawPerformanceTake? {
        active.keys.toList().forEach { noteOff(it, atNanos) }
        val started = firstNanos ?: return null
        val ended = maxOf(atNanos, completed.maxOfOrNull { it.endedAtNanos } ?: started)
        return RawPerformanceTake(started, ended, completed.sortedBy { it.startedAtNanos })
    }

    fun reset() {
        active.clear()
        completed.clear()
        firstNanos = null
    }
}
