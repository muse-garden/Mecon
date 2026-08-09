package com.mecon.api.computed.tracks

import com.mecon.api.primitive.*
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.computed.ComputedPluginEvent

/**
 * Computed plugin track - contains all computed plugin events in time-indexed order.
 */
data class ComputedPluginTrack<T : StoragePluginEvent>(
    val id: TrackId,
    val name: String,
    val type: String,
    val events: TimeIndexedList<ComputedPluginEvent<T>>
) {
    /**
     * Get events in time range.
     */
    fun eventsInRange(start: TimeCode, end: TimeCode): List<ComputedPluginEvent<T>> =
        events.range(start, end)

    /**
     * Get events at exact time.
     */
    fun eventsAt(time: TimeCode): List<ComputedPluginEvent<T>> =
        events.at(time)
}
