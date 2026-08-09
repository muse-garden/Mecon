package com.mecon.api.runtime

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.events.StorageTempoEvent
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoMarkType

/** A tempo keyframe with all reference relationships resolved to quarter-note BPM. */
data class ResolvedTempoKeyframe(
    val source: StorageTempoEvent,
    val effectiveBpm: Float,
)

/**
 * Onset-ordered effective tempo map. Old scores without a stored opening keyframe get a stable,
 * synthesized hidden keyframe backed by [RuntimeScore.defaultTempo].
 */
fun RuntimeScore.resolvedTempoKeyframes(): List<ResolvedTempoKeyframe> {
    val opening = TimeCode.of(1, Fraction.ZERO)
    val stored = globalTrack.tempoEvents
    val events = if (stored.any { it.onset == opening || it.onset == TimeCode.ofMeasure(1) }) stored else {
        listOf(StorageTempoEvent(
            id = EventId("tempo:${id.value}:opening"),
            onset = opening,
            bpm = defaultTempo,
            markType = TempoMarkType.KEYFRAME,
            displayStyle = TempoDisplayStyle.HIDDEN,
        )) + stored
    }
    val ordered = events.sortedWith(compareBy<StorageTempoEvent> { it.onset }.thenBy { it.id.value })
    val byId = events.associateBy { it.id }
    val cache = HashMap<EventId, Float>()

    fun resolve(event: StorageTempoEvent, visiting: MutableSet<EventId>): Float {
        cache[event.id]?.let { return it }
        if (!visiting.add(event.id)) return event.bpm
        val referenced = event.referenceEventId?.let(byId::get)
        val resolved = if (referenced == null) event.bpm else {
            resolve(referenced, visiting) * event.referenceRatio
        }.takeIf { it.isFinite() && it > 0f } ?: event.bpm
        visiting.remove(event.id)
        return resolved.coerceIn(MIN_TEMPO_BPM, MAX_TEMPO_BPM).also { cache[event.id] = it }
    }

    return ordered.map { ResolvedTempoKeyframe(it, resolve(it, LinkedHashSet())) }
}

const val MIN_TEMPO_BPM: Float = 10f
const val MAX_TEMPO_BPM: Float = 600f

