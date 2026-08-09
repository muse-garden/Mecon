package com.mecon.core.engine.edit

import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.TimeIndexedList
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeSlur
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.core.engine.SlurResolver
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice

/** Pure edits for first-class phrasing slurs. Legacy count markers are promoted on first edit. */
internal object SlurEditEngine {

    fun add(runtime: RuntimeScore, targets: List<NoteEditEngine.SlurTarget>): NoteEditEngine.SlurEditResult? {
        if (targets.isEmpty()) return null
        var score = runtime
        val addedIds = LinkedHashSet<EventId>()
        val measures = ArrayList<Int>()

        for ((voiceTrackId, requested) in targets.groupBy { it.voiceTrackId }) {
            val voice = score.getVoiceTrack(voiceTrackId) ?: continue
            val events = voice.events.toList()
            val byId = events.associateBy { it.id }
            val explicit = promotedSlurs(voice)
            val additions = requested.mapNotNull { target ->
                val start = byId[target.startEventId] ?: return@mapNotNull null
                val end = byId[target.endEventId] ?: return@mapNotNull null
                if (start.isRest || end.isRest || start.onset >= end.onset) return@mapNotNull null
                if (explicit.any { it.startEventId == start.id && it.endEventId == end.id }) return@mapNotNull null
                RuntimeSlur(EventId.generate(), start.id, end.id).also {
                    addedIds += it.id
                    measures += start.onset.measure
                    measures += end.onset.measure
                }
            }
            if (additions.isEmpty()) continue
            val promoted = voice.promote(explicit + additions)
            score = replaceVoice(score, promoted, promoted.events.toList())
        }

        return result(score, runtime, measures, addedIds)
    }

    fun delete(runtime: RuntimeScore, slurIds: Set<EventId>): NoteEditEngine.SlurEditResult? {
        if (slurIds.isEmpty()) return null
        var score = runtime
        val deletedIds = LinkedHashSet<EventId>()
        val measures = ArrayList<Int>()

        for (voice in runtime.voiceTracks.values) {
            val explicit = promotedSlurs(voice)
            val removed = explicit.filter { it.id in slurIds }
            if (removed.isEmpty()) continue
            val byId = voice.events.toList().associateBy { it.id }
            removed.forEach { slur ->
                deletedIds += slur.id
                byId[slur.startEventId]?.onset?.measure?.let(measures::add)
                byId[slur.endEventId]?.onset?.measure?.let(measures::add)
            }
            val promoted = voice.promote(explicit.filterNot { it.id in slurIds })
            score = replaceVoice(score, promoted, promoted.events.toList())
        }

        if (deletedIds.isEmpty()) return null
        val geometry = score.geometry?.copy(
            slurs = score.geometry?.slurs.orEmpty().filterKeys { it !in deletedIds },
        )
        return result(score.copy(geometry = geometry), runtime, measures, deletedIds)
    }

    private fun promotedSlurs(voice: RuntimeVoiceTrack): List<RuntimeSlur> =
        if (voice.slurs.isNotEmpty()) voice.slurs else SlurResolver.computeForVoiceTrack(voice).map {
            RuntimeSlur(it.slurId, it.startEventId, it.endEventId)
        }

    private fun RuntimeVoiceTrack.promote(slurs: List<RuntimeSlur>): RuntimeVoiceTrack = copy(
        events = TimeIndexedList.of(events.toList().map { it.withoutLegacySlurs() }),
        slurs = slurs,
    )

    private fun RuntimeVoiceEvent.withoutLegacySlurs(): RuntimeVoiceEvent =
        if (slurStarts == 0 && slurEnds == 0) this else copy(slurStarts = 0, slurEnds = 0)

    private fun result(
        score: RuntimeScore,
        original: RuntimeScore,
        measures: List<Int>,
        slurIds: Set<EventId>,
    ): NoteEditEngine.SlurEditResult? {
        if (score === original || measures.isEmpty()) return null
        return NoteEditEngine.SlurEditResult(score, measures.min()..measures.max(), slurIds)
    }
}
