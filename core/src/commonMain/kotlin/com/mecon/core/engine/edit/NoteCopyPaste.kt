package com.mecon.core.engine.edit

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeTieInfo
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.runtime.tracks.RuntimeSlur
import com.mecon.api.runtime.tracks.RuntimeVoiceTrack
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.RenderingProps
import com.mecon.api.storage.events.TupletSpan
import com.mecon.core.engine.edit.EditGeometry.absolute
import com.mecon.core.engine.edit.EditGeometry.advance
import com.mecon.core.engine.edit.EditGeometry.isMeasureLocalSpan
import com.mecon.core.engine.edit.EditGeometry.measureLength
import com.mecon.core.engine.edit.EditGeometry.timeCodeAt
import com.mecon.core.engine.edit.StaffTrackOps.ensureVoiceTrack
import com.mecon.core.engine.edit.StaffTrackOps.replaceVoice
import com.mecon.core.engine.edit.StaffTrackOps.staffForVoice
import com.mecon.core.engine.edit.TupletSupport.TupletContext
import com.mecon.core.engine.edit.TupletSupport.activeTupletContext
import com.mecon.core.engine.edit.TupletSupport.tupletRestDurations
import com.mecon.core.engine.edit.VoiceSpanEditing.clearInterval
import com.mecon.core.engine.edit.VoiceSpanEditing.createVoiceEvent
import com.mecon.core.engine.edit.VoiceSpanEditing.fillGaps
import com.mecon.core.engine.SlurResolver

/** Copy/paste of notes and chords, including the tuplet groups they belong to. */
internal object NoteCopyPaste {

    fun copyNotes(runtime: RuntimeScore, targets: List<NoteEditEngine.CopyTarget>): NoteEditEngine.NoteClipboard? {
        if (targets.isEmpty()) return null

        data class Resolved(
            val voice: RuntimeVoiceTrack,
            val event: RuntimeVoiceEvent,
            val target: NoteEditEngine.CopyTarget,
        )

        data class TupletGroupKey(
            val voiceTrackId: TrackId,
            val startAbs: Fraction,
            val endAbs: Fraction,
        )

        data class TupletMember(
            val item: Resolved,
            val context: TupletContext,
            val span: TupletSpan,
        )

        data class ClipboardMaterial(
            val voiceNumberOffset: Int,
            val offset: Fraction,
            val duration: Duration,
            val pitches: List<Pitch>,
            val articulations: List<Articulation>,
            val rendering: RenderingProps?,
            val ties: List<RuntimeTieInfo>,
            val tupletSpan: NoteEditEngine.ClipboardTupletSpan? = null,
            val sourceEventId: EventId? = null,
            val order: Int = 0,
        )

        val resolved = targets.mapNotNull { target ->
            val voice = runtime.getVoiceTrack(target.voiceTrackId) ?: return@mapNotNull null
            val event = voice.events.toList().firstOrNull { it.id == target.eventId } ?: return@mapNotNull null
            if (event.isRest || event.pitches.isEmpty()) return@mapNotNull null
            Resolved(voice, event, target)
        }.sortedWith(compareBy<Resolved> { absolute(runtime, it.event.onset) }.thenBy { it.voice.voiceNumber })

        if (resolved.isEmpty()) return null

        val tupletMembers = resolved.mapNotNull { item ->
            if (item.event.duration.tuplet == null) return@mapNotNull null
            val context = activeTupletContext(runtime, item.voice, item.event.onset) ?: return@mapNotNull null
            val startAbs = absolute(runtime, context.start.onset)
            val endAbs = absolute(runtime, context.span.endTimeCode)
            TupletGroupKey(item.voice.id, startAbs, endAbs) to TupletMember(item, context, context.span)
        }
        val tuplettedEventIds = tupletMembers.map { it.second.item.event.id }.toSet()
        val anchorTime = (resolved.map { absolute(runtime, it.event.onset) } +
            tupletMembers.map { absolute(runtime, it.second.context.start.onset) })
            .minOrNull() ?: return null
        val anchorVoiceNumber = resolved.first().voice.voiceNumber

        fun copiedMaterial(item: Resolved, tupletSpan: NoteEditEngine.ClipboardTupletSpan? = null): ClipboardMaterial? {
            val selected = (item.target.pitchIndices ?: item.event.pitches.indices.toSet())
                .filter { it in item.event.pitches.indices }
                .toSet()
            if (selected.isEmpty()) return null

            val ordered = selected.sorted()
            val oldToNew = ordered.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
            val copiedPitches = ordered.map { item.event.pitches[it] }
            val copiedTies = item.event.ties.mapNotNull { tie ->
                oldToNew[tie.pitchIndex]?.let { RuntimeTieInfo(it, tie.isLetRing) }
            }
            val rendering = item.target.beaming?.let { beaming ->
                (item.event.rendering ?: RenderingProps.DEFAULT).copy(beaming = beaming)
            } ?: item.event.rendering

            return ClipboardMaterial(
                voiceNumberOffset = item.voice.voiceNumber - anchorVoiceNumber,
                offset = absolute(runtime, item.event.onset) - anchorTime,
                duration = item.event.duration,
                pitches = copiedPitches,
                articulations = item.event.pitchEvent.articulations,
                rendering = rendering,
                ties = copiedTies,
                tupletSpan = tupletSpan,
                sourceEventId = item.event.id,
                order = 1,
            )
        }

        fun restMaterials(
            voice: RuntimeVoiceTrack,
            startAbs: Fraction,
            actualLength: Fraction,
            spec: NoteEditEngine.TupletSpec,
        ): List<ClipboardMaterial> {
            var onsetAbs = startAbs
            return tupletRestDurations(actualLength, spec).map { duration ->
                ClipboardMaterial(
                    voiceNumberOffset = voice.voiceNumber - anchorVoiceNumber,
                    offset = onsetAbs - anchorTime,
                    duration = duration,
                    pitches = emptyList(),
                    articulations = emptyList(),
                    rendering = null,
                    ties = emptyList(),
                    order = 0,
                ).also {
                    onsetAbs += duration.toFraction()
                }
            }
        }

        val tuplettedMaterials = tupletMembers
            .groupBy({ it.first }, { it.second })
            .values
            .flatMap { members ->
                val sorted = members.sortedBy { absolute(runtime, it.item.event.onset) }
                val first = sorted.first()
                val groupStartAbs = absolute(runtime, first.context.start.onset)
                val groupEndAbs = absolute(runtime, first.span.endTimeCode)
                val tuplet = first.item.event.duration.tuplet ?: return@flatMap emptyList()
                val spec = NoteEditEngine.TupletSpec(
                    count = first.span.count,
                    normal = tuplet.normal,
                    beatUnit = first.span.beatUnit,
                    displayStyle = first.span.displayStyle,
                )
                val span = NoteEditEngine.ClipboardTupletSpan(
                    endOffset = groupEndAbs - anchorTime,
                    count = first.span.count,
                    beatUnit = first.span.beatUnit,
                    displayStyle = first.span.displayStyle,
                    smallNotes = first.span.smallNotes,
                )
                val materials = ArrayList<ClipboardMaterial>()
                var cursorAbs = groupStartAbs
                for (member in sorted) {
                    val eventStartAbs = absolute(runtime, member.item.event.onset)
                    if (eventStartAbs > cursorAbs) {
                        materials += restMaterials(member.item.voice, cursorAbs, eventStartAbs - cursorAbs, spec)
                    }
                    copiedMaterial(member.item)?.let { materials += it }
                    val eventEndAbs = eventStartAbs + member.item.event.duration.toFraction()
                    if (eventEndAbs > cursorAbs) cursorAbs = eventEndAbs
                }
                if (groupEndAbs > cursorAbs) {
                    materials += restMaterials(first.item.voice, cursorAbs, groupEndAbs - cursorAbs, spec)
                }
                materials.firstOrNull()?.let { head ->
                    materials[0] = head.copy(tupletSpan = span)
                }
                materials
            }

        val materials = resolved
            .filter { it.event.id !in tuplettedEventIds }
            .mapNotNull { copiedMaterial(it) } + tuplettedMaterials
        val events = materials
            .sortedWith(compareBy<ClipboardMaterial> { it.offset }.thenBy { it.voiceNumberOffset }.thenBy { it.order })
            .map { material ->
                NoteEditEngine.ClipboardEvent(
                    voiceNumberOffset = material.voiceNumberOffset,
                    offset = material.offset,
                    duration = material.duration,
                    pitches = material.pitches,
                    articulations = material.articulations,
                    rendering = material.rendering,
                    ties = material.ties,
                    tupletSpan = material.tupletSpan,
                    sourceEventId = material.sourceEventId,
            )
        }

        val selectedIdsByVoice = resolved.groupBy { it.voice.id }
            .mapValues { (_, items) -> items.mapTo(HashSet()) { it.event.id } }
        val slurs = resolved.map { it.voice }.distinctBy { it.id }.flatMap { voice ->
            val selectedIds = selectedIdsByVoice[voice.id].orEmpty()
            SlurResolver.computeForVoiceTrack(voice).mapNotNull { slur ->
                if (slur.startEventId !in selectedIds || slur.endEventId !in selectedIds) return@mapNotNull null
                NoteEditEngine.ClipboardSlur(
                    voiceNumberOffset = voice.voiceNumber - anchorVoiceNumber,
                    startEventId = slur.startEventId,
                    endEventId = slur.endEventId,
                )
            }
        }
        return events.takeIf { it.isNotEmpty() }?.let { NoteEditEngine.NoteClipboard(it, slurs) }
    }

    fun pasteNotes(
        runtime: RuntimeScore,
        clipboard: NoteEditEngine.NoteClipboard,
        target: NoteEditEngine.PasteTarget,
    ): NoteEditEngine.PasteResult? {
        return when (val outcome = pasteNotesWithStatus(runtime, clipboard, target)) {
            is NoteEditEngine.PasteOutcome.Changed -> outcome.result
            NoteEditEngine.PasteOutcome.NoOp,
            NoteEditEngine.PasteOutcome.TupletCrossesBarline -> null
        }
    }

    fun pasteNotesWithStatus(
        runtime: RuntimeScore,
        clipboard: NoteEditEngine.NoteClipboard,
        target: NoteEditEngine.PasteTarget,
    ): NoteEditEngine.PasteOutcome {
        if (clipboard.isEmpty) return NoteEditEngine.PasteOutcome.NoOp
        val anchorVoice = runtime.getVoiceTrack(target.voiceTrackId) ?: return NoteEditEngine.PasteOutcome.NoOp
        val anchorStaff = staffForVoice(runtime, anchorVoice.id) ?: return NoteEditEngine.PasteOutcome.NoOp
        val targetAnchor = absolute(runtime, target.start)
        var current = runtime
        val intervals = ArrayList<TimeRange>()
        val pastedIds = ArrayList<EventId>()
        val pastedEndpointIds = HashMap<Pair<Int, EventId>, EventId>()

        // A measure-cell paste is replacement, not an insertion into the first copied span. Clear
        // every existing voice of the selected staff first so omitted clipboard voices do not leave
        // stale notes behind. The normal gap fill restores explicit rests across the measure.
        if (target.clearMeasure) {
            val measure = target.start.measure
            val start = TimeCode.of(measure, Fraction.ZERO)
            val end = TimeCode.of(measure + 1, Fraction.ZERO)
            for (existingVoice in anchorStaff.voiceTracks) {
                val kept = clearInterval(current, existingVoice, start, end)
                val filled = fillGaps(current, kept, measure, measure)
                current = replaceVoice(current, existingVoice, filled)
            }
            intervals += TimeRange(start, end)
        }

        for ((voiceOffset, copiedEvents) in clipboard.events.groupBy { it.voiceNumberOffset }) {
            val targetVoiceNumber = (anchorVoice.voiceNumber + voiceOffset).coerceAtLeast(1)
            val resolved = ensureVoiceTrack(current, anchorStaff.id, targetVoiceNumber) ?: continue
            current = resolved.score
            val voice = resolved.voice
            val starts = copiedEvents.map { timeCodeAt(current, targetAnchor + it.offset) }
            for ((event, start) in copiedEvents.zip(starts)) {
                if (event.duration.tuplet != null) {
                    val eventEnd = advance(current, start, event.duration.toFraction())
                    if (!isMeasureLocalSpan(start, eventEnd)) return NoteEditEngine.PasteOutcome.TupletCrossesBarline
                }
                val span = event.tupletSpan ?: continue
                val spanEnd = timeCodeAt(current, targetAnchor + span.endOffset)
                if (!isMeasureLocalSpan(start, spanEnd)) return NoteEditEngine.PasteOutcome.TupletCrossesBarline
            }
            val ends = copiedEvents.zip(starts).map { (event, start) ->
                advance(current, start, event.duration.toFraction())
            }
            val spanStart = starts.minOrNull() ?: continue
            val spanEnd = ends.maxOrNull() ?: continue
            val kept = clearInterval(current, voice, spanStart, spanEnd)
            val pasted = copiedEvents.zip(starts).flatMap { (event, start) ->
                materializePastedNote(current, start, event).also { pieces ->
                    pastedIds += pieces.map { it.id }
                    val sourceId = event.sourceEventId
                    if (sourceId != null) pieces.firstOrNull()?.let {
                        pastedEndpointIds[voiceOffset to sourceId] = it.id
                    }
                }
            }
            val endBeat = spanEnd.beat ?: Fraction.ZERO
            val toMeasure = if (endBeat.isPositive) spanEnd.measure else spanEnd.measure - 1
            val filled = fillGaps(current, kept + pasted, spanStart.measure, toMeasure)
            val validIds = filled.mapTo(HashSet()) { it.id }
            val cleanedVoice = voice.copy(slurs = voice.slurs.filter {
                it.startEventId in validIds && it.endEventId in validIds
            })
            current = replaceVoice(current, cleanedVoice, filled)
            intervals.add(TimeRange(spanStart, spanEnd))
        }

        for ((voiceOffset, copiedSlurs) in clipboard.slurs.groupBy { it.voiceNumberOffset }) {
            val targetVoiceNumber = (anchorVoice.voiceNumber + voiceOffset).coerceAtLeast(1)
            val voice = current.staffTracks[anchorStaff.id]?.voiceTracks
                ?.firstOrNull { it.voiceNumber == targetVoiceNumber } ?: continue
            val additions = copiedSlurs.mapNotNull { slur ->
                val startId = pastedEndpointIds[voiceOffset to slur.startEventId] ?: return@mapNotNull null
                val endId = pastedEndpointIds[voiceOffset to slur.endEventId] ?: return@mapNotNull null
                RuntimeSlur(EventId.generate(), startId, endId)
            }
            if (additions.isNotEmpty()) {
                val existing = if (voice.slurs.isNotEmpty()) voice.slurs else
                    SlurResolver.computeForVoiceTrack(voice).map {
                        RuntimeSlur(it.slurId, it.startEventId, it.endEventId)
                    }
                val cleanEvents = voice.events.toList().map { event ->
                    if (event.slurStarts == 0 && event.slurEnds == 0) event
                    else event.copy(slurStarts = 0, slurEnds = 0)
                }
                current = replaceVoice(
                    current,
                    voice.copy(slurs = existing + additions),
                    cleanEvents,
                )
            }
        }

        return if (pastedIds.isEmpty()) {
            NoteEditEngine.PasteOutcome.NoOp
        } else {
            NoteEditEngine.PasteOutcome.Changed(NoteEditEngine.PasteResult(current, intervals.distinct(), pastedIds))
        }
    }

    private fun materializePastedNote(
        runtime: RuntimeScore,
        start: TimeCode,
        event: NoteEditEngine.ClipboardEvent,
    ): List<RuntimeVoiceEvent> {
        if (event.duration.tuplet != null) {
            val end = advance(runtime, start, event.duration.toFraction())
            if (!isMeasureLocalSpan(start, end)) return emptyList()
            val tupletSpan = event.tupletSpan?.let {
                TupletSpan(
                    endTimeCode = timeCodeAt(runtime, absolute(runtime, start) - event.offset + it.endOffset),
                    count = it.count,
                    beatUnit = it.beatUnit,
                    displayStyle = it.displayStyle,
                    smallNotes = it.smallNotes,
                )
            }
            return listOf(createVoiceEvent(
                onset = start,
                duration = event.duration,
                pitches = event.pitches,
                articulations = event.articulations,
                isRest = event.pitches.isEmpty(),
                rendering = event.rendering,
                ties = event.ties,
                tupletSpan = tupletSpan,
            ))
        }
        val pieces = ArrayList<Pair<TimeCode, Duration>>()
        var measure = start.measure
        var beat = start.beat ?: Fraction.ZERO
        var remaining = event.duration.toFraction()
        while (remaining.isPositive) {
            val measureLen = measureLength(runtime, measure)
            val available = measureLen - beat
            val chunk = if (remaining <= available) remaining else available
            for (duration in DurationDecomposer.decompose(chunk)) {
                pieces.add(TimeCode.of(measure, beat) to duration)
                beat = beat + duration.toFraction()
            }
            remaining = remaining - chunk
            if (remaining.isPositive) {
                measure += 1
                beat = Fraction.ZERO
            }
        }

        return pieces.mapIndexed { index, (onset, duration) ->
            val isLast = index == pieces.lastIndex
            val ties = if (isLast) {
                event.ties
            } else {
                event.pitches.indices.map { RuntimeTieInfo(it, isLetRing = false) }
            }
            val pitchEvent = RuntimePitchEvent(
                id = EventId.generate(),
                onset = onset,
                pitches = event.pitches,
                articulations = event.articulations,
            )
            RuntimeVoiceEvent.create(
                onset = onset,
                pitchEvent = pitchEvent,
                duration = duration,
                rendering = event.rendering,
                ties = ties,
            )
        }
    }
}
