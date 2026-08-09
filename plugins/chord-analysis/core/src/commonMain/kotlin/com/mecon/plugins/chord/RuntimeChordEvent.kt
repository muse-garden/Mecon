package com.mecon.plugins.chord

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.PitchClass
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.theory.Chord

/**
 * Runtime view of a [StorageChordEvent] with the storage event preserved for
 * round-trip back to disk. Lazily exposes a resolved [Chord] from `:theory`.
 */
data class RuntimeChordEvent(
    override val id: EventId,
    override val onset: TimeCode,
    override val storageEvent: StorageChordEvent
) : RuntimePluginEvent<StorageChordEvent> {

    val chord: Chord by lazy {
        Chord(
            root = PitchClass(storageEvent.root),
            quality = storageEvent.quality,
            bass = storageEvent.bass?.let { PitchClass(it) }
        )
    }

    companion object {
        fun fromStorage(event: StorageChordEvent): RuntimeChordEvent =
            RuntimeChordEvent(
                id = event.id,
                onset = event.onset,
                storageEvent = event
            )
    }
}
