package com.mecon.core.engine.edit

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.events.GraceNoteInfo
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.StaffTrackOps.resolveInsertionVoice
import com.mecon.core.engine.edit.VoiceSpanEditing.createVoiceEvent

/** Editing primitives for zero-meter-time grace-note groups. */
internal object GraceNoteEditing {
    fun insert(runtime: RuntimeScore, insertion: NoteEditEngine.Insertion): NoteEditEngine.Result? {
        val grace = insertion.grace ?: return null
        if (insertion.isRest || insertion.pitch == null) return null
        val resolved = resolveInsertionVoice(runtime, insertion) ?: return null
        val voice = resolved.voice
        val score = resolved.score
        val beat = insertion.start.beat ?: Fraction.ZERO
        val anchor = TimeCode.of(insertion.start.measure, beat)
        val events = voice.events.toList()

        // Clicking an existing grace slot adds a chord tone, exactly like ordinary note entry.
        if (insertion.start.grace != null) {
            val existing = events.firstOrNull { it.onset == insertion.start && it.isGrace && !it.isRest }
                ?: return null
            if (existing.pitches.any { it == insertion.pitch }) return null
            val ordered = (existing.pitches + insertion.pitch).distinct().sortedBy { it.midiNumber }
            val insertedIndex = ordered.indexOf(insertion.pitch)
            val oldIndex = existing.pitches.withIndex().associate { it.value to it.index }
            val ties = existing.ties.mapNotNull { tie ->
                val pitch = existing.pitches.getOrNull(tie.pitchIndex) ?: return@mapNotNull null
                RuntimeTieInfo(ordered.indexOf(pitch), tie.isLetRing)
            } + if (insertion.trailingTie) listOf(RuntimeTieInfo(insertedIndex, false)) else emptyList()
            val changed = existing.copy(
                pitchEvent = existing.pitchEvent.copy(pitches = ordered),
                ties = ties.distinctBy { it.pitchIndex }.sortedBy { it.pitchIndex },
            )
            val updated = events.map { if (it.id == existing.id) changed else it }
            return NoteEditEngine.Result(
                replaceVoice(score, voice, updated),
                measureInterval(anchor.measure),
                changed.id,
            )
        }

        val group = events.filter { it.isGrace && principalAnchor(it.onset) == anchor }.sortedBy { it.onset }
        val rendering = (insertion.beaming?.let { RenderingProps.DEFAULT.copy(beaming = it) }
            ?: RenderingProps.DEFAULT).copy(graceNoteType = grace.noteType)
        val newEvent = createVoiceEvent(
            onset = TimeCode.of(anchor.measure, beat, Fraction(-1, 1)),
            duration = insertion.duration,
            pitches = listOf(insertion.pitch),
            articulations = insertion.articulations,
            isRest = false,
            rendering = rendering,
            ties = if (insertion.trailingTie) listOf(RuntimeTieInfo(0, false)) else emptyList(),
        )
        val info = group.firstOrNull()?.graceInfo
            ?: GraceNoteInfo(grace.totalDuration, grace.stealFrom)
        val reindexed = reindex(group + newEvent, info)
        val groupIds = group.map(RuntimeVoiceEvent::id).toSet()
        val updated = events.filter { it.id !in groupIds } + reindexed
        return NoteEditEngine.Result(
            replaceVoice(score, voice, updated),
            measureInterval(anchor.measure),
            newEvent.id,
        )
    }

    fun editGroups(
        runtime: RuntimeScore,
        edits: List<NoteEditEngine.GraceGroupEdit>,
    ): NoteEditEngine.EditOutcome {
        if (edits.isEmpty()) return NoteEditEngine.EditOutcome.NoOp
        var current = runtime
        val intervals = mutableListOf<TimeRange>()
        val ids = mutableListOf<EventId>()
        var changed = false
        for ((voiceId, voiceEdits) in edits.groupBy { it.voiceTrackId }) {
            val voice = current.getVoiceTrack(voiceId) ?: continue
            val requests = voiceEdits.associateBy { it.eventId }
            val all = voice.events.toList()
            val groupEdits = mutableMapOf<TimeCode, NoteEditEngine.GraceGroupEdit>()
            for (event in all) {
                val request = requests[event.id] ?: continue
                if (!event.isGrace) continue
                groupEdits[principalAnchor(event.onset)] = request
            }
            if (groupEdits.isEmpty()) continue
            val updated = all.map { event ->
                val request = groupEdits[principalAnchor(event.onset)] ?: return@map event
                if (!event.isGrace || event.graceInfo == null) return@map event
                val info = GraceNoteInfo(request.totalDuration, request.stealFrom)
                if (event.graceInfo == info) event else {
                    changed = true
                    ids += event.id
                    intervals += measureInterval(event.onset.measure)
                    event.copy(graceInfo = info)
                }
            }
            if (updated != all) current = replaceVoice(current, voice, updated)
        }
        return if (changed) {
            NoteEditEngine.EditOutcome.Changed(current, intervals.distinct(), ids)
        } else {
            NoteEditEngine.EditOutcome.NoOp
        }
    }

    fun delete(runtime: RuntimeScore, voiceId: com.mecon.api.primitive.TrackId, eventId: EventId): NoteEditEngine.Result? {
        val voice = runtime.getVoiceTrack(voiceId) ?: return null
        val target = voice.events.toList().firstOrNull { it.id == eventId && it.isGrace } ?: return null
        val anchor = principalAnchor(target.onset)
        val group = voice.events.toList()
            .filter { it.isGrace && principalAnchor(it.onset) == anchor }
            .sortedBy { it.onset }
        val remaining = group.filter { it.id != target.id }
        val info = group.firstNotNullOfOrNull { it.graceInfo }
        val reindexed = if (info != null) reindex(remaining, info) else remaining
        val groupIds = group.map(RuntimeVoiceEvent::id).toSet()
        val updated = voice.events.toList().filter { it.id !in groupIds } + reindexed
        return NoteEditEngine.Result(
            replaceVoice(runtime, voice, updated),
            measureInterval(anchor.measure),
            reindexed.firstOrNull()?.id,
        )
    }

    private fun reindex(events: List<RuntimeVoiceEvent>, info: GraceNoteInfo): List<RuntimeVoiceEvent> {
        // Callers supply the musical order. Insertion deliberately appends the new event after the
        // onset-sorted existing group; sorting again here would move its temporary -1 onset back
        // beside the first member instead of keeping it at the group tail.
        val count = events.size
        return events.mapIndexed { index, event ->
            val grace = Fraction(-(count - index), count).simplified()
            val onset = TimeCode.of(event.onset.measure, event.onset.beat ?: Fraction.ZERO, grace)
            event.copy(
                onset = onset,
                pitchEvent = event.pitchEvent.copy(onset = onset),
                graceInfo = if (index == 0) info else null,
            )
        }
    }

    private fun principalAnchor(onset: TimeCode): TimeCode =
        TimeCode.of(onset.measure, onset.beat ?: Fraction.ZERO)

    private fun measureInterval(measure: Int): TimeRange =
        TimeRange(TimeCode.of(measure, Fraction.ZERO), TimeCode.of(measure + 1, Fraction.ZERO))
}
