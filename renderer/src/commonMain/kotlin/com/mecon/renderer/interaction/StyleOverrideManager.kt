package com.mecon.renderer.interaction

import com.mecon.api.interaction.*
import com.mecon.api.primitive.TimeCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import com.mecon.renderer.render.spatial.platformSynchronized

/**
 * Snapshot of current style overrides across all tracks.
 */
data class StyleSnapshot(val overrides: Map<EventSectionId, OrderedOverride>) {
    fun getOverride(sectionIds: List<EventSectionId>): StyleOverride? {
        if (overrides.isEmpty() || sectionIds.isEmpty()) return null
        var bestOverride: StyleOverride? = null
        var bestOrder = -1
        for (sectionId in sectionIds) {
            val oe = overrides[sectionId]
            if (oe != null && oe.order > bestOrder) {
                bestOrder = oe.order
                bestOverride = oe.override
            }
        }
        return bestOverride
    }

    /**
     * Whether any override on [sectionIds] hides the element. Decoupled from [getOverride]'s
     * single-winner colour pick so a high-priority colour override (e.g. selection blue) on one
     * section can't mask a hide flag set on another section of the same element.
     */
    fun isHidden(sectionIds: List<EventSectionId>): Boolean {
        if (overrides.isEmpty() || sectionIds.isEmpty()) return false
        return sectionIds.any { overrides[it]?.override?.hidden == true }
    }

    val isEmpty: Boolean get() = overrides.isEmpty()

    companion object {
        val EMPTY = StyleSnapshot(emptyMap())
    }
}

/**
 * Represents an override along with its accumulated sort order.
 */
data class OrderedOverride(val order: Int, val override: StyleOverride)

/**
 * Core implementation for the declarative style registry.
 */
class StyleOverrideManager : StyleRegistry {
    private val tracks = mutableListOf<StyleTrackImpl>()
    
    private val _snapshotFlow = MutableStateFlow(StyleSnapshot.EMPTY)
    val snapshotFlow: StateFlow<StyleSnapshot> = _snapshotFlow.asStateFlow()

    override fun createTrack(priority: Int): StyleTrack {
        val track = StyleTrackImpl(priority, this)
        platformSynchronized(tracks) {
            tracks.add(track)
            tracks.sortBy { it.priority }
        }
        rebuildSnapshot()
        return track
    }

    override fun removeTrack(track: StyleTrack) {
        if (track !is StyleTrackImpl) return
        val removed = platformSynchronized(tracks) {
            tracks.remove(track)
        }
        if (removed) rebuildSnapshot()
    }

    internal fun rebuildSnapshot() {
        // Build new snapshot by merging all tracks in priority order
        val newOverrides = mutableMapOf<EventSectionId, OrderedOverride>()
        var orderCounter = 0
        
        platformSynchronized(tracks) {
            // Track are sorted by priority (lowest to highest) in the list
            for (track in tracks) {
                val trackOverrides = track.getOverrides()
                for ((key, override) in trackOverrides) {
                    // OrderedOverride uses the order field to pick the best style if sections overlap
                    // we can just use the orderCounter which increases monotonically since tracks are sorted
                    newOverrides[key] = OrderedOverride(orderCounter++, override)
                }
            }
        }
        
        _snapshotFlow.update { StyleSnapshot(newOverrides) }
    }

    fun snapshot(): StyleSnapshot = _snapshotFlow.value
    
    // For legacy subscribers
    fun subscribe(listener: (StyleSnapshot) -> Unit): Subscription {
        val job = kotlinx.coroutines.GlobalScope.launch {
            _snapshotFlow.collect { listener(it) }
        }
        return object : Subscription {
            override fun cancel() {
                job.cancel()
            }
        }
    }
    
    // Legacy API dummy implementation
    fun updateScore(score: com.mecon.api.runtime.RuntimeScore) {}
}

interface Subscription {
    fun cancel()
}

/**
 * Implementation of a style track. 
 */
class StyleTrackImpl(
    override val priority: Int,
    private val manager: StyleOverrideManager
) : StyleTrack {

    private val baseOverrides = mutableMapOf<EventSectionId, StyleOverride>()
    private val timecodeOverrides = mutableMapOf<Pair<EventSectionId, TimeCode>, StyleOverride>()
    
    internal fun getOverrides(): Map<EventSectionId, StyleOverride> {
        val result = mutableMapOf<EventSectionId, StyleOverride>()
        platformSynchronized(this) {
            result.putAll(baseOverrides)
            // Timecode specific overrides would ideally require a timecode to lookup
            // But for now, we just merge them by sectionId (this means we lose time granularity unless handled manually in rendering loop)
            // Note: properly handling timecode specific styles requires B+ tree or similar for time axis.
            for ((key, override) in timecodeOverrides) {
                // If a timecode override exists, it overwrites the base one for now in this simple map implementation.
                result[key.first] = override
            }
        }
        return result
    }

    /** Set a style override using a pre-computed numeric section ID. */
    fun setStyleBySectionId(sectionId: EventSectionId, override: StyleOverride) {
        platformSynchronized(this) {
            baseOverrides[sectionId] = override
        }
    }

    fun applyPatchBySectionId(
        upserts: Map<EventSectionId, StyleOverride>,
        removes: Set<EventSectionId>,
    ) {
        platformSynchronized(this) {
            removes.forEach(baseOverrides::remove)
            baseOverrides.putAll(upserts)
        }
    }

    override fun setStyle(section: EventSection, override: StyleOverride) {
        platformSynchronized(this) {
            baseOverrides[section.id] = override
        }
    }

    override fun setStyle(section: EventSection, timeCode: TimeCode, override: StyleOverride) {
        platformSynchronized(this) {
            timecodeOverrides[Pair(section.id, timeCode)] = override
        }
    }

    override fun removeStyle(section: EventSection) {
        platformSynchronized(this) {
            baseOverrides.remove(section.id)
        }
    }

    override fun removeStyle(section: EventSection, timeCode: TimeCode) {
        platformSynchronized(this) {
            timecodeOverrides.remove(Pair(section.id, timeCode))
        }
    }

    override fun clear() {
        platformSynchronized(this) {
            baseOverrides.clear()
            timecodeOverrides.clear()
        }
    }

    override fun submit() {
        manager.rebuildSnapshot()
    }
}
