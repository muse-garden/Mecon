package com.mecon.core.engine.edit

import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.resolvedTempoKeyframes
import com.mecon.api.storage.events.StorageTempoEvent
import com.mecon.api.storage.events.TempoDisplayStyle
import com.mecon.api.storage.events.TempoMarkType
import com.mecon.api.storage.events.TempoTransition
import com.mecon.api.storage.events.quarterNoteFactor

/** Immutable global tempo-map edits, returned through the normal expression undo/selection pipeline. */
object TempoEditEngine {
    fun addMark(
        runtime: RuntimeScore,
        onset: TimeCode,
        type: TempoMarkType,
        bpm: Float? = null,
        beatUnit: DurationBase = DurationBase.QUARTER,
        equivalentBeatUnit: DurationBase = DurationBase.HALF,
    ): ExpressionEditEngine.Result? {
        val resolved = runtime.resolvedTempoKeyframes()
        val previous = resolved.lastOrNull { it.source.onset < onset } ?: resolved.firstOrNull()
        val previousBpm = previous?.effectiveBpm ?: runtime.defaultTempo
        val opening = resolved.firstOrNull()?.source
        val previousGradual = resolved.lastOrNull {
            it.source.onset < onset && it.source.markType in setOf(
                TempoMarkType.ACCELERANDO,
                TempoMarkType.RITARDANDO,
            )
        }
        val reference = when (type) {
            TempoMarkType.PIU_MOSSO, TempoMarkType.MENO_MOSSO -> previous?.source
            TempoMarkType.A_TEMPO -> previousGradual?.source ?: previous?.source
            TempoMarkType.TEMPO_I -> opening
            TempoMarkType.METRIC_MODULATION -> previous?.source
            else -> null
        }
        val ratio = when (type) {
            TempoMarkType.PIU_MOSSO -> 1.15f
            TempoMarkType.MENO_MOSSO -> 0.85f
            TempoMarkType.METRIC_MODULATION -> equivalentBeatUnit.quarterNoteFactor() / beatUnit.quarterNoteFactor()
            else -> 1f
        }
        val effective = (bpm ?: (reference?.let { source ->
            resolved.firstOrNull { it.source.id == source.id }?.effectiveBpm?.times(ratio)
        } ?: previousBpm)).coerceIn(10f, 600f)
        val (text, style) = when (type) {
            TempoMarkType.PIU_MOSSO -> "più mosso" to TempoDisplayStyle.TEXT
            TempoMarkType.MENO_MOSSO -> "meno mosso" to TempoDisplayStyle.TEXT
            TempoMarkType.A_TEMPO -> "a tempo" to TempoDisplayStyle.TEXT
            TempoMarkType.TEMPO_I -> "Tempo I" to TempoDisplayStyle.TEXT
            TempoMarkType.METRIC_MODULATION -> null to TempoDisplayStyle.METRIC_MODULATION
            TempoMarkType.KEYFRAME -> null to TempoDisplayStyle.HIDDEN
            else -> null to TempoDisplayStyle.METRONOME
        }
        val exact = runtime.globalTrack.tempoEvents.firstOrNull { it.onset == onset }
        val event = StorageTempoEvent(
            id = exact?.id ?: EventId.generate(),
            onset = onset,
            bpm = effective,
            beatUnit = beatUnit,
            text = text,
            markType = type,
            displayStyle = style,
            equivalentBeatUnit = equivalentBeatUnit.takeIf { type == TempoMarkType.METRIC_MODULATION },
            referenceEventId = reference?.id?.takeIf { it != exact?.id },
            referenceRatio = ratio,
        )
        return replace(runtime, event, onset.measure..onset.measure, select = event.id)
    }

    /** A gradual mark is one keyframe whose interpolation ends at the next keyframe. */
    fun addGradual(
        runtime: RuntimeScore,
        start: TimeCode,
        end: TimeCode,
        type: TempoMarkType,
    ): ExpressionEditEngine.Result? {
        if (end <= start || type !in setOf(TempoMarkType.ACCELERANDO, TempoMarkType.RITARDANDO)) return null
        val resolved = runtime.resolvedTempoKeyframes()
        val previous = resolved.lastOrNull { it.source.onset <= start } ?: resolved.firstOrNull()
        val startBpm = previous?.effectiveBpm ?: runtime.defaultTempo
        val ratio = if (type == TempoMarkType.ACCELERANDO) 1.15f else 0.85f
        val startExisting = runtime.globalTrack.tempoEvents.firstOrNull { it.onset == start }
        val startEvent = StorageTempoEvent(
            id = startExisting?.id ?: EventId.generate(),
            onset = start,
            bpm = startBpm,
            text = if (type == TempoMarkType.ACCELERANDO) "accel." else "rit.",
            markType = type,
            displayStyle = TempoDisplayStyle.GRADUAL_TEXT,
            referenceEventId = previous?.source?.id?.takeIf { it != startExisting?.id },
            transitionToNext = TempoTransition.LINEAR,
        )
        val endExisting = runtime.globalTrack.tempoEvents.firstOrNull { it.onset == end }
        val endEvent = (endExisting ?: StorageTempoEvent.create(
            onset = end,
            bpm = startBpm * ratio,
            markType = TempoMarkType.KEYFRAME,
            displayStyle = TempoDisplayStyle.HIDDEN,
        )).copy(
            bpm = startBpm * ratio,
            referenceEventId = startEvent.id,
            referenceRatio = ratio,
        )
        val events = runtime.globalTrack.tempoEvents
            .filter { it.id != startEvent.id && it.id != endEvent.id }
            .plus(startEvent)
            .plus(endEvent)
            .sortedWith(compareBy<StorageTempoEvent> { it.onset }.thenBy { it.id.value })
        return ExpressionEditEngine.Result(
            runtime.copy(globalTrack = runtime.globalTrack.copy(tempoEvents = events)),
            start.measure..end.measure,
            selectedAttachmentIds = setOf(startEvent.id),
        )
    }

    fun update(
        runtime: RuntimeScore,
        id: EventId,
        effectiveBpm: Float? = null,
        displayStyle: TempoDisplayStyle? = null,
        transition: TempoTransition? = null,
        text: String? = null,
    ): ExpressionEditEngine.Result? {
        val old = runtime.globalTrack.tempoEvents.firstOrNull { it.id == id }
            ?: runtime.resolvedTempoKeyframes().firstOrNull { it.source.id == id }?.source
            ?: return null
        val resolvedById = runtime.resolvedTempoKeyframes().associate { it.source.id to it.effectiveBpm }
        val desired = effectiveBpm?.coerceIn(10f, 600f)
        val sourceBpm = old.referenceEventId?.let(resolvedById::get)
        val updated = old.copy(
            bpm = desired ?: old.bpm,
            referenceRatio = if (desired != null && sourceBpm != null) desired / sourceBpm else old.referenceRatio,
            displayStyle = displayStyle ?: old.displayStyle,
            transitionToNext = transition ?: old.transitionToNext,
            text = text ?: old.text,
        )
        return replace(runtime, updated, old.onset.measure..old.onset.measure, select = id)
    }

    fun move(runtime: RuntimeScore, id: EventId, start: TimeCode, end: TimeCode? = null): ExpressionEditEngine.Result? {
        val ordered = runtime.globalTrack.tempoEvents.sortedBy { it.onset }
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = ordered[index]
        if (isOpening(old)) return null
        val moved = old.copy(onset = start)
        val next = ordered.getOrNull(index + 1)
        val moveEnd = old.displayStyle == TempoDisplayStyle.GRADUAL_TEXT && end != null && end > start && next != null
        val events = ordered.map { event ->
            when (event.id) {
                id -> moved
                next?.id -> if (moveEnd) event.copy(onset = end!!) else event
                else -> event
            }
        }.sortedWith(compareBy<StorageTempoEvent> { it.onset }.thenBy { it.id.value })
        val measures = listOfNotNull(old.onset.measure, start.measure, next?.onset?.measure, end?.measure)
        return ExpressionEditEngine.Result(
            runtime.copy(globalTrack = runtime.globalTrack.copy(tempoEvents = events)),
            measures.min()..measures.max(),
            selectedAttachmentIds = setOf(id),
        )
    }

    fun delete(runtime: RuntimeScore, ids: Set<EventId>): ExpressionEditEngine.Result? {
        val removable = runtime.globalTrack.tempoEvents.filter { it.id in ids && !isOpening(it) }
        if (removable.isEmpty()) return null
        val paired = removable.filter { it.displayStyle == TempoDisplayStyle.GRADUAL_TEXT }.mapNotNull { start ->
            runtime.globalTrack.tempoEvents.filter { it.onset > start.onset }.minByOrNull { it.onset }
                ?.takeIf { it.markType == TempoMarkType.KEYFRAME && it.displayStyle == TempoDisplayStyle.HIDDEN }
                ?.id
        }.toSet()
        val removeIds = removable.mapTo(HashSet()) { it.id } + paired
        val events = runtime.globalTrack.tempoEvents.filter { it.id !in removeIds }
        val measures = runtime.globalTrack.tempoEvents.filter { it.id in removeIds }.map { it.onset.measure }
        return ExpressionEditEngine.Result(
            runtime.copy(globalTrack = runtime.globalTrack.copy(tempoEvents = events)),
            measures.min()..measures.max(),
        )
    }

    private fun replace(
        runtime: RuntimeScore,
        event: StorageTempoEvent,
        affected: IntRange,
        select: EventId,
    ): ExpressionEditEngine.Result {
        val events = runtime.globalTrack.tempoEvents.filter { it.id != event.id }
            .plus(event)
            .sortedWith(compareBy<StorageTempoEvent> { it.onset }.thenBy { it.id.value })
        return ExpressionEditEngine.Result(
            runtime.copy(globalTrack = runtime.globalTrack.copy(tempoEvents = events)),
            affected,
            selectedAttachmentIds = setOf(select),
        )
    }

    private fun isOpening(event: StorageTempoEvent): Boolean =
        event.onset.measure == 1 && (event.onset.beat ?: Fraction.ZERO) == Fraction.ZERO
}
