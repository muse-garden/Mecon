package com.mecon.plugins.chord

import com.mecon.api.computed.tracks.ComputedPluginTrack
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.tracks.RuntimePluginTrack
import com.mecon.api.storage.tracks.StoragePluginTrack

/**
 * Full-pass compute for chord plugin tracks. v1 has no derived score-wide state,
 * so each pass is a pure 1:1 mapping over events.
 */
object ChordCompute {

    fun runtimeFromStorage(track: StoragePluginTrack): RuntimePluginTrack<StorageChordEvent> {
        require(track.type == StorageChordEvent.TRACK_TYPE) {
            "Expected plugin track of type ${StorageChordEvent.TRACK_TYPE}, got ${track.type}"
        }
        val runtimeEvents = track.events.mapNotNull { event ->
            (event as? StorageChordEvent)?.let { RuntimeChordEvent.fromStorage(it) }
        }
        return RuntimePluginTrack(
            id = track.id,
            name = track.name,
            type = track.type,
            events = TimeIndexedList.of(runtimeEvents)
        )
    }

    fun computedFromRuntime(
        track: RuntimePluginTrack<StorageChordEvent>
    ): ComputedPluginTrack<StorageChordEvent> {
        val computedEvents = track.events.toList().map { runtimeEvent ->
            val rt = runtimeEvent as RuntimeChordEvent
            ComputedChordEvent.fromRuntime(rt)
        }
        return ComputedPluginTrack(
            id = track.id,
            name = track.name,
            type = track.type,
            events = TimeIndexedList.of(computedEvents)
        )
    }

    /** Convenience: storage → computed in one call. */
    fun computedFromStorage(track: StoragePluginTrack): ComputedPluginTrack<StorageChordEvent> =
        computedFromRuntime(runtimeFromStorage(track))
}
