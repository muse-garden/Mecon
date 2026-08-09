package com.mecon.core.engine.edit

import com.mecon.api.computed.globalBreathComputedId
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.events.DynamicLevel
import com.mecon.api.storage.events.HairpinStyle
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.events.StaffAttachmentPlacement
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageStaffAttachment
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.events.OrnamentKind
import com.mecon.api.storage.events.OrnamentAnchor
import com.mecon.api.storage.events.TrillPlaybackMode
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.RenderingProps
import com.mecon.api.primitive.Accidental
import com.mecon.api.runtime.resolvedTempoKeyframes
import com.mecon.api.storage.AttachmentGeometry
import com.mecon.api.storage.tracks.BreathMarkScope
import com.mecon.api.storage.tracks.BreathMarkShape
import com.mecon.api.storage.tracks.FermataShape
import com.mecon.api.storage.tracks.StorageFermata
import com.mecon.api.storage.tracks.StorageGlobalBreathMark

/** Immutable editing operations for note articulations and staff-wide expression attachments. */
object ExpressionEditEngine {
    data class NoteTarget(val voiceTrackId: TrackId, val eventId: EventId)

    data class Result(
        val score: RuntimeScore,
        val affectedMeasures: IntRange,
        val selectedAttachmentIds: Set<EventId> = emptySet(),
        val selectedEventIds: Set<EventId> = emptySet(),
    )

    data class Clipboard(val items: List<ClipboardItem>) { val isEmpty get() = items.isEmpty() }
    data class ClipboardItem(
        val staffOffset: Int,
        val startOffset: com.mecon.api.primitive.Fraction,
        val endOffset: com.mecon.api.primitive.Fraction? = null,
        val dynamic: DynamicLevel? = null,
        val hairpinType: HairpinType? = null,
        val hairpinStyle: HairpinStyle? = null,
        val octaveType: OctaveShiftType? = null,
        val breathPause: com.mecon.api.primitive.Fraction? = null,
        val breathShape: BreathMarkShape? = null,
        val breathVoiceNumber: Int? = null,
    )

    /** Copy staff attachments; octave shifts must be explicitly authorised by a full note-span selection. */
    fun copyAttachments(
        runtime: RuntimeScore,
        ids: Set<EventId>,
        completeOctaveIds: Set<EventId> = emptySet(),
        clipRanges: Map<TrackId, Pair<TimeCode, TimeCode>> = emptyMap(),
    ): Clipboard? {
        val ordered = runtime.orderedStaffs()
        data class Located(
            val staffIndex: Int,
            val attachment: StorageStaffAttachment,
            val start: TimeCode,
            val end: TimeCode?,
        )
        val located = buildList {
            for ((staffIndex, staff) in ordered.withIndex()) {
                val octaveEnds = staff.attachments.filterIsInstance<StorageOctaveShiftEnd>().associateBy { it.id }
                for (attachment in staff.attachments) {
                    if (attachment.id !in ids) continue
                    when (attachment) {
                        is StorageOctaveShiftStart -> if (attachment.id in completeOctaveIds) {
                            add(Located(staffIndex, attachment, attachment.onset, octaveEnds[attachment.endEventId]?.onset))
                        }
                        is StorageOctaveShiftEnd -> {}
                        is StorageHairpin -> {
                            val clip = clipRanges[staff.id]
                            val clippedStart = clip?.first?.let { maxOf(attachment.onset, it) } ?: attachment.onset
                            val clippedEnd = clip?.second?.let { minOf(attachment.endOnset, it) } ?: attachment.endOnset
                            if (clippedStart < clippedEnd) add(Located(staffIndex, attachment, clippedStart, clippedEnd))
                        }
                        else -> add(Located(staffIndex, attachment, attachment.onset, null))
                    }
                }
            }
        }
        if (located.isEmpty()) return null
        val baseTime = located.minOf { EditGeometry.absolute(runtime, it.start) }
        val baseStaff = located.minOf { it.staffIndex }
        return Clipboard(located.mapNotNull { item ->
            val startOffset = EditGeometry.absolute(runtime, item.start) - baseTime
            val endOffset = item.end?.let { EditGeometry.absolute(runtime, it) - baseTime }
            when (val attachment = item.attachment) {
                is StorageDynamicMark -> ClipboardItem(item.staffIndex - baseStaff, startOffset, dynamic = attachment.level)
                is StorageHairpin -> ClipboardItem(
                    item.staffIndex - baseStaff, startOffset, endOffset,
                    hairpinType = attachment.direction, hairpinStyle = attachment.style,
                )
                is StorageOctaveShiftStart -> ClipboardItem(
                    item.staffIndex - baseStaff, startOffset, endOffset, octaveType = attachment.shiftType,
                )
                is StorageBreathMark -> ClipboardItem(
                    staffOffset = item.staffIndex - baseStaff,
                    startOffset = startOffset,
                    breathPause = attachment.pause,
                    breathShape = attachment.shape,
                    breathVoiceNumber = attachment.voiceNumber,
                )
                else -> null
            }
        })
    }

    fun pasteAttachments(
        runtime: RuntimeScore,
        clipboard: Clipboard,
        targetStaffId: TrackId,
        targetTime: TimeCode,
    ): Result? {
        val ordered = runtime.orderedStaffs()
        val baseStaff = ordered.indexOfFirst { it.id == targetStaffId }.takeIf { it >= 0 } ?: return null
        val baseTime = EditGeometry.absolute(runtime, targetTime)
        var score = runtime
        var combined: Result? = null
        for (item in clipboard.items) {
            val staffId = ordered.getOrNull(baseStaff + item.staffOffset)?.id ?: continue
            val start = EditGeometry.timeCodeAt(score, baseTime + item.startOffset)
            val end = item.endOffset?.let { EditGeometry.timeCodeAt(score, baseTime + it) }
            val next = when {
                item.dynamic != null -> addDynamic(score, staffId, start, item.dynamic)
                item.hairpinType != null && item.hairpinStyle != null && end != null ->
                    addHairpin(score, staffId, start, end, item.hairpinType, item.hairpinStyle)
                item.octaveType != null && end != null -> addOctaveShift(score, staffId, start, end, item.octaveType)
                item.breathPause != null && item.breathShape != null -> {
                    val targetStaff = score.staffTracks[staffId]
                    val voiceNumber = item.breathVoiceNumber?.let { copied ->
                        targetStaff?.voiceTracks?.firstOrNull { it.voiceNumber == copied }?.voiceNumber
                            ?: targetStaff?.voiceTracks?.firstOrNull()?.voiceNumber
                    }
                    addBreathMark(
                        score,
                        staffId,
                        start,
                        if (voiceNumber == null) BreathMarkScope.STAFF else BreathMarkScope.VOICE,
                        item.breathShape,
                        item.breathPause,
                        voiceNumber,
                    )
                }
                else -> null
            } ?: continue
            score = next.score
            combined = next.copy(
                affectedMeasures = combined?.let {
                    minOf(it.affectedMeasures.first, next.affectedMeasures.first)..
                        maxOf(it.affectedMeasures.last, next.affectedMeasures.last)
                } ?: next.affectedMeasures,
                selectedAttachmentIds = combined?.selectedAttachmentIds.orEmpty() + next.selectedAttachmentIds,
            )
        }
        return combined
    }

    fun toggleArticulation(runtime: RuntimeScore, targets: List<NoteTarget>, articulation: Articulation): Result? {
        if (targets.isEmpty()) return null
        val resolved = targets.mapNotNull { target ->
            val voice = runtime.getVoiceTrack(target.voiceTrackId) ?: return@mapNotNull null
            val event = voice.events.toList().firstOrNull { it.id == target.eventId } ?: return@mapNotNull null
            if (event.isRest) null else Triple(target, voice, event)
        }
        if (resolved.isEmpty()) return null
        val remove = resolved.all { articulation in it.third.pitchEvent.articulations }
        var score = runtime
        val selected = LinkedHashSet<EventId>()
        val measures = ArrayList<Int>()
        for ((voiceId, entries) in resolved.groupBy { it.first.voiceTrackId }) {
            val voice = score.getVoiceTrack(voiceId) ?: continue
            val ids = entries.mapTo(HashSet()) { it.first.eventId }
            val events = voice.events.toList().map { event ->
                if (event.id !in ids) return@map event
                val old = event.pitchEvent.articulations
                val next = if (remove) old.filter { it != articulation } else (old + articulation).distinct()
                selected += event.id
                measures += event.onset.measure
                event.copy(pitchEvent = event.pitchEvent.copy(articulations = next))
            }
            score = StaffTrackOps.replaceVoice(score, voice, events)
        }
        if (measures.isEmpty()) return null
        return Result(score, measures.min()..measures.max(), selectedEventIds = selected)
    }

    fun addDynamic(runtime: RuntimeScore, staffId: TrackId, onset: TimeCode, level: DynamicLevel): Result? {
        val staff = runtime.staffTracks[staffId] ?: return null
        val mark = StorageDynamicMark.create(onset, level, voiceNumber = null)
        val updated = staff.copy(attachments = deduplicate(staff.attachments + mark))
        return Result(StaffTrackOps.replaceStaff(runtime, updated), onset.measure..onset.measure, setOf(mark.id))
    }

    /** Choose the notated beat fraction whose sounding length is closest to about 125 ms. */
    fun defaultOrnamentElementDuration(runtime: RuntimeScore, onset: TimeCode): com.mecon.api.primitive.Fraction {
        val bpm = runtime.resolvedTempoKeyframes()
            .lastOrNull { it.source.onset <= onset }
            ?.effectiveBpm
            ?: runtime.defaultTempo
        val millisecondsPerQuarter = 60_000f / bpm
        return listOf(
            com.mecon.api.primitive.Fraction(1, 16),
            com.mecon.api.primitive.Fraction(1, 8),
            com.mecon.api.primitive.Fraction(1, 4),
            com.mecon.api.primitive.Fraction.HALF,
            com.mecon.api.primitive.Fraction.ONE,
        ).minBy { fraction ->
            kotlin.math.abs(millisecondsPerQuarter * fraction.toFloat() - 125f)
        }
    }

    fun addOrnament(
        runtime: RuntimeScore,
        staffId: TrackId,
        sourceEventId: EventId,
        kind: OrnamentKind,
        anchor: OrnamentAnchor = OrnamentAnchor.ON_NOTE,
        endOnset: TimeCode? = null,
    ): Result? {
        val staff = runtime.staffTracks[staffId] ?: return null
        val event = staff.voiceTracks.asSequence()
            .flatMap { it.events.toList().asSequence() }
            .firstOrNull { it.id == sourceEventId && !it.isRest }
            ?: return null
        val onset = if (anchor == OrnamentAnchor.BETWEEN_NOTES) event.endTime else event.onset
        val validEnd = endOnset?.takeIf { kind == OrnamentKind.TRILL && it > onset }
        val mark = StorageOrnamentMark.create(
            onset = onset,
            sourceEventId = sourceEventId,
            kind = kind,
            anchor = anchor,
            endOnset = validEnd,
            elementDuration = defaultOrnamentElementDuration(runtime, onset),
            voiceNumber = staff.voiceTracks.firstOrNull { voice ->
                voice.events.toList().any { it.id == sourceEventId }
            }?.voiceNumber,
        )
        val retained = staff.attachments.filterNot {
            it is StorageOrnamentMark && it.sourceEventId == sourceEventId && it.kind == kind
        }
        return Result(
            StaffTrackOps.replaceStaff(runtime, staff.copy(attachments = retained + mark)),
            onset.measure..(validEnd?.measure ?: onset.measure),
            selectedAttachmentIds = setOf(mark.id),
        )
    }

    fun updateOrnament(
        runtime: RuntimeScore,
        id: EventId,
        upperAccidental: Accidental? = null,
        lowerAccidental: Accidental? = null,
        elementDuration: com.mecon.api.primitive.Fraction? = null,
        oscillations: Int? = null,
        trillPlaybackMode: TrillPlaybackMode? = null,
        updateUpperAccidental: Boolean = false,
        updateLowerAccidental: Boolean = false,
    ): Result? {
        if (elementDuration != null && !elementDuration.isPositive) return null
        if (oscillations != null && oscillations !in 1..16) return null
        val staff = runtime.staffTracks.values.firstOrNull {
            it.attachments.any { attachment -> attachment is StorageOrnamentMark && attachment.id == id }
        } ?: return null
        val old = staff.attachments.filterIsInstance<StorageOrnamentMark>().first { it.id == id }
        val updatedMark = old.copy(
            upperAccidental = if (updateUpperAccidental) upperAccidental else old.upperAccidental,
            lowerAccidental = if (updateLowerAccidental) lowerAccidental else old.lowerAccidental,
            elementDuration = elementDuration?.simplified() ?: old.elementDuration,
            oscillations = oscillations ?: old.oscillations,
            trillPlaybackMode = trillPlaybackMode ?: old.trillPlaybackMode,
        )
        val updated = staff.copy(attachments = staff.attachments.map {
            if (it.id == id) updatedMark else it
        })
        return Result(
            StaffTrackOps.replaceStaff(runtime, updated),
            old.onset.measure..(old.endOnset?.measure ?: old.onset.measure),
            selectedAttachmentIds = setOf(id),
        )
    }

    fun setArpeggio(
        runtime: RuntimeScore,
        targets: List<NoteTarget>,
        type: ArpeggioType?,
    ): Result? {
        if (targets.isEmpty()) return null
        var score = runtime
        val selected = LinkedHashSet<EventId>()
        val measures = ArrayList<Int>()
        for ((voiceId, grouped) in targets.groupBy { it.voiceTrackId }) {
            val voice = score.getVoiceTrack(voiceId) ?: continue
            val ids = grouped.mapTo(HashSet()) { it.eventId }
            val events = voice.events.toList().map { event ->
                if (event.id !in ids || event.isRest) return@map event
                selected += event.id
                measures += event.onset.measure
                event.copy(rendering = (event.rendering ?: RenderingProps.DEFAULT).copy(arpeggio = type))
            }
            score = StaffTrackOps.replaceVoice(score, voice, events)
        }
        if (measures.isEmpty()) return null
        return Result(score, measures.min()..measures.max(), selectedEventIds = selected)
    }

    fun addFermata(
        runtime: RuntimeScore,
        afterTime: TimeCode,
        shape: FermataShape = FermataShape.NORMAL,
        extension: com.mecon.api.primitive.Fraction = com.mecon.api.primitive.Fraction.ONE,
    ): Result? {
        if (!extension.isPositive) return null
        val mark = StorageFermata.create(afterTime, extension, shape)
        val retained = runtime.globalTrack.events.filterNot {
            it is StorageFermata && it.onset == afterTime
        }
        return Result(
            score = runtime.copy(globalTrack = runtime.globalTrack.copy(events = retained + mark)),
            affectedMeasures = afterTime.measure..afterTime.measure,
            selectedAttachmentIds = setOf(mark.id),
        )
    }

    fun addBreathMark(
        runtime: RuntimeScore,
        staffId: TrackId,
        afterTime: TimeCode,
        scope: BreathMarkScope,
        shape: BreathMarkShape = BreathMarkShape.COMMA,
        pause: com.mecon.api.primitive.Fraction = com.mecon.api.primitive.Fraction.HALF,
        voiceNumber: Int? = null,
    ): Result? {
        if (!pause.isPositive) return null
        if (scope == BreathMarkScope.GLOBAL) {
            val mark = StorageGlobalBreathMark.create(afterTime, pause, shape)
            val retained = runtime.globalTrack.events.filterNot {
                it is StorageGlobalBreathMark && it.onset == afterTime
            }
            return Result(
                runtime.copy(globalTrack = runtime.globalTrack.copy(events = retained + mark)),
                afterTime.measure..afterTime.measure,
                selectedAttachmentIds = setOf(globalBreathComputedId(mark.id, staffId)),
            )
        }
        val staff = runtime.staffTracks[staffId] ?: return null
        val targetVoice = if (scope == BreathMarkScope.VOICE) {
            voiceNumber ?: staff.voiceTracks.firstOrNull()?.voiceNumber ?: return null
        } else null
        val mark = StorageBreathMark.create(afterTime, pause, shape, targetVoice)
        val retained = staff.attachments.filterNot {
            it is StorageBreathMark && it.onset == afterTime && it.voiceNumber == targetVoice
        }
        return Result(
            StaffTrackOps.replaceStaff(runtime, staff.copy(attachments = retained + mark)),
            afterTime.measure..afterTime.measure,
            selectedAttachmentIds = setOf(mark.id),
        )
    }

    fun updatePerformanceMark(
        runtime: RuntimeScore,
        id: EventId,
        amount: com.mecon.api.primitive.Fraction,
    ): Result? {
        if (!amount.isPositive) return null
        runtime.globalTrack.events.firstOrNull {
            (it is StorageFermata && it.id == id) || (it is StorageGlobalBreathMark && it.id == id)
        }?.let { found ->
            val events = runtime.globalTrack.events.map {
                when {
                    it is StorageFermata && it.id == id -> it.copy(extension = amount.simplified())
                    it is StorageGlobalBreathMark && it.id == id -> it.copy(pause = amount.simplified())
                    else -> it
                }
            }
            return Result(
                runtime.copy(globalTrack = runtime.globalTrack.copy(events = events)),
                found.onset.measure..found.onset.measure,
            )
        }
        for (staff in runtime.staffTracks.values) {
            val mark = staff.attachments.filterIsInstance<StorageBreathMark>().firstOrNull { it.id == id } ?: continue
            val updated = staff.copy(attachments = staff.attachments.map {
                if (it is StorageBreathMark && it.id == id) it.copy(pause = amount.simplified()) else it
            })
            return Result(
                StaffTrackOps.replaceStaff(runtime, updated),
                mark.onset.measure..mark.onset.measure,
                selectedAttachmentIds = setOf(id),
            )
        }
        return null
    }

    fun deleteGlobalPerformanceMarks(runtime: RuntimeScore, ids: Set<EventId>): Result? {
        if (ids.isEmpty()) return null
        val removed = runtime.globalTrack.events.filter {
            when (it) {
                is StorageFermata -> it.id in ids
                is StorageGlobalBreathMark -> it.id in ids
                else -> false
            }
        }
        if (removed.isEmpty()) return null
        val next = runtime.globalTrack.events.filterNot { it in removed }
        val measures = removed.map { it.onset.measure }
        return Result(
            runtime.copy(globalTrack = runtime.globalTrack.copy(events = next)),
            measures.min()..measures.max(),
        )
    }

    fun addHairpin(
        runtime: RuntimeScore,
        staffId: TrackId,
        start: TimeCode,
        end: TimeCode,
        type: HairpinType,
        style: HairpinStyle,
    ): Result? {
        if (end <= start) return null
        val staff = runtime.staffTracks[staffId] ?: return null
        val mark = StorageHairpin.create(start, end, type, style, voiceNumber = null)
        val updated = staff.copy(attachments = staff.attachments + mark)
        return Result(StaffTrackOps.replaceStaff(runtime, updated), start.measure..end.measure, setOf(mark.id))
    }

    /** Add 8va/8vb and transpose sounding pitches in the same transaction so written positions stay fixed. */
    fun addOctaveShift(
        runtime: RuntimeScore,
        staffId: TrackId,
        start: TimeCode,
        end: TimeCode,
        type: OctaveShiftType,
    ): Result? {
        if (end <= start) return null
        val initialStaff = runtime.staffTracks[staffId] ?: return null
        val placement = if (type == OctaveShiftType.OTTAVA) StaffAttachmentPlacement.ABOVE else StaffAttachmentPlacement.BELOW
        val endMark = StorageOctaveShiftEnd.create(end, placement, voiceNumber = null)
        val startMark = StorageOctaveShiftStart.create(start, type, endMark.id, placement, voiceNumber = null)
        var score = StaffTrackOps.replaceStaff(
            runtime,
            initialStaff.copy(attachments = initialStaff.attachments + startMark + endMark),
        )
        val delta = if (type == OctaveShiftType.OTTAVA) 7 else -7
        val staff = score.staffTracks.getValue(staffId)
        for (voiceRef in staff.voiceTracks) {
            val voice = score.getVoiceTrack(voiceRef.id) ?: continue
            val events = voice.events.toList().map { event ->
                if (event.onset < start || event.onset >= end || event.isRest) event
                else event.copy(pitchEvent = event.pitchEvent.copy(
                    pitches = event.pitches.map { pitch -> pitch.copy(diatonicSteps = pitch.diatonicSteps + delta) }
                ))
            }
            score = StaffTrackOps.replaceVoice(score, voice, events)
        }
        return Result(score, start.measure..end.measure, setOf(startMark.id))
    }

    fun deleteAttachments(runtime: RuntimeScore, ids: Set<EventId>): Result? {
        if (ids.isEmpty()) return null
        var score = runtime
        val touched = ArrayList<Int>()
        for (staff0 in runtime.staffTracks.values) {
            val starts = staff0.attachments.filterIsInstance<StorageOctaveShiftStart>()
                .filter { it.id in ids }
            val pairedIds = starts.mapTo(HashSet()) { it.endEventId }
            val removeIds = ids + pairedIds
            val removed = staff0.attachments.filter { it.id in removeIds }
            if (removed.isEmpty()) continue
            var staff = score.staffTracks[staff0.id] ?: staff0
            staff = staff.copy(attachments = staff.attachments.filter { it.id !in removeIds })
            score = StaffTrackOps.replaceStaff(score, staff)
            for (startMark in starts) {
                val end = staff0.attachments.filterIsInstance<StorageOctaveShiftEnd>()
                    .firstOrNull { it.id == startMark.endEventId }?.onset ?: continue
                val delta = if (startMark.shiftType == OctaveShiftType.OTTAVA) -7 else 7
                val liveStaff = score.staffTracks[staff0.id] ?: continue
                for (voiceRef in liveStaff.voiceTracks) {
                    val voice = score.getVoiceTrack(voiceRef.id) ?: continue
                    val events = voice.events.toList().map { event ->
                        if (event.onset < startMark.onset || event.onset >= end || event.isRest) event
                        else event.copy(pitchEvent = event.pitchEvent.copy(
                            pitches = event.pitches.map { p -> p.copy(diatonicSteps = p.diatonicSteps + delta) }
                        ))
                    }
                    score = StaffTrackOps.replaceVoice(score, voice, events)
                }
                touched += startMark.onset.measure
                touched += end.measure
            }
            removed.forEach { touched += it.onset.measure }
        }
        if (touched.isEmpty()) return null
        val base = score.geometry
        if (base != null) score = score.copy(geometry = base.copy(attachments = base.attachments.filterKeys { it !in ids }))
        return Result(score, touched.min()..touched.max())
    }

    /** Re-anchor an attachment after an editor drag; octave sounding pitches follow the new range. */
    fun moveAttachment(
        runtime: RuntimeScore,
        id: EventId,
        start: TimeCode,
        end: TimeCode?,
        geometry: AttachmentGeometry,
    ): Result? {
        val globalBreath = runtime.globalTrack.events
            .filterIsInstance<StorageGlobalBreathMark>()
            .firstOrNull { mark ->
                runtime.staffTracks.keys.any { staffId ->
                    globalBreathComputedId(mark.id, staffId) == id
                }
            }
        if (globalBreath != null) {
            val events = runtime.globalTrack.events.map {
                if (it is StorageGlobalBreathMark && it.id == globalBreath.id) {
                    it.copy(onset = start)
                } else it
            }
            val base = runtime.geometry ?: com.mecon.api.storage.ScoreGeometry.EMPTY
            return Result(
                score = runtime.copy(
                    globalTrack = runtime.globalTrack.copy(events = events),
                    geometry = base.copy(
                        attachments = base.attachments +
                            (id to geometry.copy(manuallyAdjustedY = true)),
                    ),
                ),
                affectedMeasures = minOf(globalBreath.onset.measure, start.measure)..
                    maxOf(globalBreath.onset.measure, start.measure),
                selectedAttachmentIds = setOf(id),
            )
        }
        val staff0 = runtime.staffTracks.values.firstOrNull { staff -> staff.attachments.any { it.id == id } } ?: return null
        val attachment = staff0.attachments.first { it.id == id }
        var score = runtime
        var staff = staff0
        val touched = mutableListOf(attachment.onset.measure, start.measure)
        val placement = if (geometry.startDy < 0f) StaffAttachmentPlacement.ABOVE else StaffAttachmentPlacement.BELOW
        when (attachment) {
            is StorageDynamicMark -> staff = staff.copy(attachments = staff.attachments.map {
                if (it.id == id) attachment.copy(onset = start, placement = placement, voiceNumber = null) else it
            })
            is StorageBreathMark -> staff = staff.copy(attachments = staff.attachments.map {
                if (it.id == id) attachment.copy(onset = start, placement = placement) else it
            })
            is StorageOrnamentMark -> staff = staff.copy(attachments = staff.attachments.map {
                if (it.id == id) attachment.copy(
                    onset = start,
                    endOnset = end?.takeIf { attachment.kind == OrnamentKind.TRILL && it > start }
                        ?: attachment.endOnset,
                    placement = placement,
                ) else it
            })
            is StorageHairpin -> {
                val newEnd = end?.takeIf { it > start } ?: return null
                touched += attachment.endOnset.measure
                touched += newEnd.measure
                staff = staff.copy(attachments = staff.attachments.map {
                    if (it.id == id) attachment.copy(onset = start, endOnset = newEnd, placement = placement, voiceNumber = null) else it
                })
            }
            is StorageOctaveShiftStart -> {
                val oldEnd = staff.attachments.filterIsInstance<StorageOctaveShiftEnd>()
                    .firstOrNull { it.id == attachment.endEventId } ?: return null
                val newEnd = end?.takeIf { it > start } ?: return null
                touched += oldEnd.onset.measure
                touched += newEnd.measure
                val forward = if (attachment.shiftType == OctaveShiftType.OTTAVA) 7 else -7
                score = transposeStaffRange(score, staff.id, attachment.onset, oldEnd.onset, -forward)
                score = transposeStaffRange(score, staff.id, start, newEnd, forward)
                staff = score.staffTracks.getValue(staff.id).copy(attachments = score.staffTracks.getValue(staff.id).attachments.map {
                    when (it.id) {
                        id -> attachment.copy(onset = start, placement = placement, voiceNumber = null)
                        attachment.endEventId -> oldEnd.copy(onset = newEnd, placement = placement, voiceNumber = null)
                        else -> it
                    }
                })
            }
            is StorageOctaveShiftEnd -> return null
        }
        score = StaffTrackOps.replaceStaff(score, staff)
        val base = score.geometry ?: com.mecon.api.storage.ScoreGeometry.EMPTY
        score = score.copy(geometry = base.copy(
            attachments = base.attachments + (id to geometry.copy(manuallyAdjustedY = true)),
        ))
        return Result(score, touched.min()..touched.max(), selectedAttachmentIds = setOf(id))
    }

    private fun transposeStaffRange(
        runtime: RuntimeScore,
        staffId: TrackId,
        start: TimeCode,
        end: TimeCode,
        diatonicDelta: Int,
    ): RuntimeScore {
        var score = runtime
        val staff = score.staffTracks[staffId] ?: return score
        for (voiceRef in staff.voiceTracks) {
            val voice = score.getVoiceTrack(voiceRef.id) ?: continue
            val events = voice.events.toList().map { event ->
                if (event.onset < start || event.onset >= end || event.isRest) event
                else event.copy(pitchEvent = event.pitchEvent.copy(
                    pitches = event.pitches.map { it.copy(diatonicSteps = it.diatonicSteps + diatonicDelta) }
                ))
            }
            score = StaffTrackOps.replaceVoice(score, voice, events)
        }
        return score
    }

    private fun deduplicate(items: List<StorageStaffAttachment>): List<StorageStaffAttachment> {
        val seen = HashSet<String>()
        return items.asReversed().filter { item ->
            val key = when (item) {
                is StorageDynamicMark -> "dynamic:${item.onset}:${item.level}"
                is StorageBreathMark -> "breath:${item.onset}:${item.voiceNumber}:${item.shape}"
                is StorageOrnamentMark -> "ornament:${item.sourceEventId}:${item.kind}"
                else -> item.id.value
            }
            seen.add(key)
        }.asReversed()
    }
}
