package com.mecon.desktop.service

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimePluginEvent
import com.mecon.api.runtime.tracks.RuntimePluginTrack
import com.mecon.api.state.RenderHint
import com.mecon.api.storage.events.StoragePluginEvent
import com.mecon.api.storage.events.StoragePluginForwardAffectingEvent
import com.mecon.api.storage.events.StoragePluginIntervalEvent
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.computeScoreIncremental
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun ScoreSession.addPluginEvent(trackType: String, event: StoragePluginEvent) {
    val current = manager?.currentState?.runtimeScore ?: return
    @Suppress("UNCHECKED_CAST")
    val existing = current.pluginTracks.values.firstOrNull { it.type == trackType }
        as? RuntimePluginTrack<StoragePluginEvent>
    val runtimeEvent = runtimePluginEvent(event)
    val updatedTrack: RuntimePluginTrack<StoragePluginEvent> = if (existing != null) {
        // Plugin tracks may contain several independent annotations at the same
        // TimeCode (for example two notehead-specific non-chord-tone slices).
        // Point-event uniqueness, when desired, is handled by the contributing
        // plugin through updatePluginEvent.
        RuntimePluginTrack(
            id = existing.id,
            name = existing.name,
            type = existing.type,
            events = existing.events.insert(runtimeEvent),
        )
    } else {
        RuntimePluginTrack(
            id = com.mecon.api.primitive.TrackId.generate(),
            name = trackType,
            type = trackType,
            events = TimeIndexedList.of(listOf(runtimeEvent)),
        )
    }
    applyPluginEdit(
        current.updatePluginTrack(updatedTrack.id, updatedTrack),
        current.renderImpact(event),
    )
}

fun ScoreSession.updatePluginEvent(trackType: String, oldId: EventId, newEvent: StoragePluginEvent) {
    val current = manager?.currentState?.runtimeScore ?: return
    @Suppress("UNCHECKED_CAST")
    val existing = current.pluginTracks.values.firstOrNull { it.type == trackType }
        as? RuntimePluginTrack<StoragePluginEvent> ?: return
    // The old symbol's measure may differ from the new one (unlikely for chords, which keep their
    // onset, but a generic plugin edit could move it); re-render both so either annotation is refreshed.
    val oldEvent = existing.events.find { it.id == oldId }?.storageEvent
    val updated: RuntimePluginTrack<StoragePluginEvent> = RuntimePluginTrack(
        id = existing.id,
        name = existing.name,
        type = existing.type,
        events = TimeIndexedList.of(existing.events.map { if (it.id == oldId) runtimePluginEvent(newEvent) else it }),
    )
    applyPluginEdit(
        current.updatePluginTrack(existing.id, updated),
        *listOfNotNull(oldEvent?.let(current::renderImpact), current.renderImpact(newEvent))
            .toTypedArray(),
    )
}

fun ScoreSession.deletePluginEvent(trackType: String, eventId: EventId) {
    val current = manager?.currentState?.runtimeScore ?: return
    @Suppress("UNCHECKED_CAST")
    val existing = current.pluginTracks.values.firstOrNull { it.type == trackType }
        as? RuntimePluginTrack<StoragePluginEvent> ?: return
        // Capture the removed event's measure before it is gone — that measure's annotation vanishes and
    // its slot re-solves without the symbol's width constraint.
    val removedEvent = existing.events.find { it.id == eventId }?.storageEvent ?: return
    val updated: RuntimePluginTrack<StoragePluginEvent> = RuntimePluginTrack(
        id = existing.id,
        name = existing.name,
        type = existing.type,
        events = existing.events.filter { it.id != eventId },
    )
    applyPluginEdit(
        current.updatePluginTrack(existing.id, updated),
        current.renderImpact(removedEvent),
    )
}

/**
 * Apply a plugin-track (e.g. chord) edit and drive a **bounded** re-render of the measures whose
 * annotation geometry changed, instead of a full re-render.
 *
 * The recompute is a full [computeScore]: plugin tracks are not on the incremental voice/pitch path
 * ([computeScoreIncremental] would leave `pluginTracks` stale), and note geometry is unchanged anyway.
 * What the renderer must redo is bounded: the changed chord symbol lives in a single measure's
 * annotation slot, so a [ComputeChangeSet.forRange] over [measures] lets the splicer re-solve only that
 * slot (regenerating annotations wholesale) and translate the rest. Interval events contribute their
 * full affected range; forward-affecting events extend to the score end. Note **re-coloring** rides
 * the note-style overlay (`applyNoteStyleProviders`, re-applied globally after every render) and is not
 * bounded by [ranges], so notes governed by the chord across the whole span still re-tint.
 *
 * Falls back to a full render (null hint) when there is no displayed frame to splice against.
 * See `docs/data_model/incremental-update.md` 搂3 and `docs/plugin/chord-analysis-implementation.md` 搂10.
 */
private fun ScoreSession.applyPluginEdit(newRuntime: RuntimeScore, vararg ranges: IntRange) {
    val mgr = manager ?: return
    val previousComputed = state?.computedScore
    val range = ranges.minOfOrNull(IntRange::first)?.let { lo ->
        lo..ranges.maxOf(IntRange::last)
    }
    scope.launch {
        val computed = withContext(Dispatchers.Default) { computeScore(newRuntime) }
        val hint = if (previousComputed != null && range != null)
            RenderHint(previousComputed, ComputeChangeSet.forRange(range)) else null
        mgr.commitNewState(newRuntime, computed, hint)
    }
}

private fun RuntimeScore.renderImpact(event: StoragePluginEvent): IntRange {
    val lastMeasure = measures.lastOrNull()?.key ?: event.onset.measure
    val endMeasure = when (event) {
        is StoragePluginForwardAffectingEvent -> lastMeasure
        is StoragePluginIntervalEvent -> event.endOnset.measure
        else -> event.onset.measure
    }
    return event.onset.measure..maxOf(event.onset.measure, endMeasure)
}

private fun runtimePluginEvent(event: StoragePluginEvent): RuntimePluginEvent<StoragePluginEvent> =
    object : RuntimePluginEvent<StoragePluginEvent> {
        override val id = event.id
        override val onset = event.onset
        override val storageEvent = event
    }
