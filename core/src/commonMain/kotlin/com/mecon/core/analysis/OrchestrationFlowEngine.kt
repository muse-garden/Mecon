package com.mecon.core.analysis

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.PlayerId
import com.mecon.api.primitive.ReductionId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.StorageNoteLink
import com.mecon.api.storage.StoragePerformance
import com.mecon.api.storage.StorageStaffAssignment
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import kotlin.math.roundToInt

/**
 * The concrete A5/O2 flow:
 *
 * reduction note -> content line -> player performance -> staff assignment -> written score note.
 *
 * Reduction links never point directly to a written staff event in this flow. They point to
 * orchestration line events; orchestration links then connect those line events to written notes.
 */
object OrchestrationFlowEngine {
    enum class BindingDirection { REDUCTION_TO_WRITTEN, WRITTEN_TO_REDUCTION, ROUTE_ONLY }

    data class PlayerRoute(
        val playerId: PlayerId,
        val staffId: TrackId,
        val voiceNumber: Int = 1,
    )

    data class BindRequest(
        val reductionId: ReductionId,
        val reductionNotes: Set<NoteRef> = emptySet(),
        val writtenNotes: Set<NoteRef> = emptySet(),
        val targetReductionStaffId: TrackId? = null,
        val targetReductionVoiceId: TrackId? = null,
        val existingLineId: TrackId? = null,
        val lineName: String = "内容线",
        val onset: TimeCode = TimeCode.ofMeasure(1),
        val routes: List<PlayerRoute>,
        val realizeNow: Boolean = true,
    )

    data class BindResult(
        val score: StorageScore,
        val lineId: TrackId,
        val direction: BindingDirection,
        val boundNotes: Int,
        val realizedNotes: Int,
        val unresolvedNotes: Int,
        val conflicts: Int,
    )

    data class RealizeResult(
        val score: StorageScore,
        val realizedNotes: Int,
        val unresolvedNotes: Int,
        val conflicts: Int,
    )

    data class TogglePlayerResult(
        val score: StorageScore,
        val lineId: TrackId,
        val playerAdded: Boolean,
        val groupDeleted: Boolean,
        val playerCount: Int,
        val realizedNotes: Int = 0,
    )

    fun bindReductionSelection(score: StorageScore, request: BindRequest): BindResult? {
        val reduction = score.getReduction(request.reductionId) ?: return null
        if (request.reductionNotes.isNotEmpty() && request.writtenNotes.isNotEmpty()) return null
        if (
            request.reductionNotes.isEmpty() &&
            request.writtenNotes.isEmpty() &&
            request.existingLineId == null
        ) return null
        if (request.routes.isEmpty()) return null
        var updated = ensureOrchestration(score)
        val line = request.existingLineId
            ?.takeIf { it in updated.orchestration!!.lines }
            ?.let(updated.voiceTracks::get)
        val lineId: TrackId
        if (line == null) {
            val pitchTrack = StoragePitchTrack.create("${request.lineName} Notes")
            val voiceTrack = StorageVoiceTrack.create(request.lineName.ifBlank { "内容线" }, 1, pitchTrack.id)
            updated = updated.addPitchTrack(pitchTrack).addVoiceTrack(voiceTrack)
            lineId = voiceTrack.id
            updated = updated.copy(
                orchestration = updated.orchestration!!.copy(
                    lines = updated.orchestration!!.lines + lineId,
                )
            )
        } else {
            lineId = line.id
        }

        if (request.writtenNotes.isNotEmpty()) {
            return bindWrittenSelection(updated, reduction, lineId, request)
        }

        var updatedReduction = reduction
        var bound = 0
        val refsByEvent = request.reductionNotes.groupBy { it.eventId }
        refsByEvent.forEach { (eventId, refs) ->
            val reductionEvent = reduction.notationScore.findVoiceEvent(eventId) ?: return@forEach
            val reductionPitchEvent = reduction.notationScore.findPitchEvent(reductionEvent.pitchEventId) ?: return@forEach
            val currentLine = updated.voiceTracks[lineId] ?: return@forEach
            val currentLineEventIds = currentLine.events.map { it.id }.toSet()
            val validRefs = refs.distinctBy { it.pitchIndex }
                .filter { it.pitchIndex in reductionPitchEvent.pitches.indices }
                .filter { reductionRef ->
                    updatedReduction.links.none {
                        it.target == reductionRef && it.source.eventId in currentLineEventIds
                    }
                }
            if (validRefs.isEmpty()) return@forEach

            val existingEvent = currentLine.events.firstOrNull {
                it.onset == reductionEvent.onset && it.duration == reductionEvent.duration
            }
            val lineEvent: StorageVoiceEvent
            val linePitchEvent: StoragePitchEvent
            val lineRefs = mutableListOf<Pair<NoteRef, NoteRef>>()
            if (existingEvent == null) {
                linePitchEvent = StoragePitchEvent.create(
                    onset = reductionEvent.onset,
                    pitches = validRefs.map { reductionPitchEvent.pitches[it.pitchIndex] },
                    articulations = reductionPitchEvent.articulations,
                )
                lineEvent = StorageVoiceEvent.create(
                    onset = reductionEvent.onset,
                    pitchEventId = linePitchEvent.id,
                    duration = reductionEvent.duration,
                    rendering = reductionEvent.rendering,
                )
                updated = updated
                    .addPitchEvent(currentLine.pitchTrackId, linePitchEvent)
                    .addVoiceEvent(lineId, lineEvent)
                validRefs.forEachIndexed { index, reductionRef ->
                    lineRefs += NoteRef(lineEvent.id, index) to reductionRef
                }
            } else {
                lineEvent = existingEvent
                val currentPitch = updated.findPitchEvent(existingEvent.pitchEventId) ?: return@forEach
                val merged = currentPitch.pitches.toMutableList()
                validRefs.forEach { reductionRef ->
                    val reductionPitch = reductionPitchEvent.pitches[reductionRef.pitchIndex]
                    val index = merged.indexOfFirst { it.midiNumber == reductionPitch.midiNumber }
                        .takeIf { it >= 0 }
                        ?: merged.size.also { merged += reductionPitch }
                    lineRefs += NoteRef(lineEvent.id, index) to reductionRef
                }
                linePitchEvent = currentPitch.copy(pitches = merged)
                if (linePitchEvent != currentPitch) {
                    updated = updated.updatePitchTrack(currentLine.pitchTrackId) { track ->
                        track.copy(events = track.events.map { event ->
                            if (event.id == linePitchEvent.id) linePitchEvent else event
                        })
                    }
                }
            }

            lineRefs.forEach { (lineRef, reductionRef) ->
                if (updatedReduction.links.none { it.source == lineRef && it.target == reductionRef }) {
                    val linePitch = linePitchEvent.pitches[lineRef.pitchIndex]
                    val reductionPitch = reductionPitchEvent.pitches[reductionRef.pitchIndex]
                    updatedReduction = updatedReduction.copy(
                        links = updatedReduction.links + StorageNoteLink(
                            source = lineRef,
                            target = reductionRef,
                            octaveShift = nearestOctaveShift(linePitch.midiNumber, reductionPitch.midiNumber),
                        )
                    )
                    bound++
                }
            }
        }
        updated = updated.copy(reductions = updated.reductions.map {
            if (it.id == updatedReduction.id) updatedReduction else it
        })
        updated = setRoutes(updated, lineId, request.onset, request.routes)
        val realization = if (request.realizeNow) realizeLine(updated, lineId) else {
            RealizeResult(updated, 0, 0, 0)
        }
        return BindResult(
            score = realization.score,
            lineId = lineId,
            direction = if (request.reductionNotes.isEmpty()) {
                BindingDirection.ROUTE_ONLY
            } else {
                BindingDirection.REDUCTION_TO_WRITTEN
            },
            boundNotes = bound,
            realizedNotes = realization.realizedNotes,
            unresolvedNotes = realization.unresolvedNotes,
            conflicts = realization.conflicts,
        )
    }

    /**
     * Toggle one player on the synchronization group touched by [BindRequest.reductionNotes].
     *
     * A content line is the persisted synchronization-group identity. If the selection is not
     * grouped yet, the normal bind flow creates it. If the player is already assigned, the route
     * is removed; removing the last player deletes the hidden content line and both link sets while
     * leaving the visible reduction/written notation intact.
     */
    fun toggleReductionSelectionPlayer(
        score: StorageScore,
        request: BindRequest,
    ): TogglePlayerResult? {
        val route = request.routes.singleOrNull() ?: return null
        val reduction = score.getReduction(request.reductionId) ?: return null
        val lineId = request.existingLineId
            ?.takeIf { it in score.orchestration?.lines.orEmpty() }
            ?: lineIdForReductionNotes(score, reduction, request.reductionNotes)
        if (lineId == null) {
            val bound = bindReductionSelection(score, request) ?: return null
            return TogglePlayerResult(
                score = bound.score,
                lineId = bound.lineId,
                playerAdded = true,
                groupDeleted = false,
                playerCount = 1,
                realizedNotes = bound.realizedNotes,
            )
        }

        val orchestration = score.orchestration ?: return null
        val playerWasAssigned = orchestration.performances.any {
            it.lineId == lineId && route.playerId in it.playerIds
        }
        if (!playerWasAssigned) {
            val lineOnset = score.voiceTracks[lineId]?.events?.minOfOrNull { it.onset } ?: request.onset
            val matching = orchestration.performances.filter { it.lineId == lineId }
            val performances = if (matching.isEmpty()) {
                orchestration.performances + StoragePerformance(
                    lineId = lineId,
                    onset = lineOnset,
                    playerIds = listOf(route.playerId),
                )
            } else {
                orchestration.performances.map { performance ->
                    if (performance.lineId == lineId) {
                        performance.copy(playerIds = (performance.playerIds + route.playerId).distinct())
                    } else performance
                }
            }
            val assignments = orchestration.staffAssignments
                .filterNot { it.lineId == lineId && it.playerId == route.playerId } +
                StorageStaffAssignment(
                    playerId = route.playerId,
                    lineId = lineId,
                    onset = lineOnset,
                    staffId = route.staffId,
                    voiceHint = route.voiceNumber.coerceAtLeast(1),
                )
            val routed = score.copy(
                orchestration = orchestration.copy(
                    performances = performances,
                    staffAssignments = assignments,
                )
            )
            val realization = if (request.realizeNow) realizeLine(routed, lineId)
            else RealizeResult(routed, 0, 0, 0)
            val playerCount = realization.score.orchestration?.performances
                .orEmpty()
                .filter { it.lineId == lineId }
                .flatMap { it.playerIds }
                .distinct()
                .size
            return TogglePlayerResult(
                score = realization.score,
                lineId = lineId,
                playerAdded = true,
                groupDeleted = false,
                playerCount = playerCount,
                realizedNotes = realization.realizedNotes,
            )
        }

        val removedAssignments = orchestration.staffAssignments.filter {
            it.lineId == lineId && it.playerId == route.playerId
        }
        val performances = orchestration.performances
            .map { performance ->
                if (performance.lineId == lineId) {
                    performance.copy(playerIds = performance.playerIds - route.playerId)
                } else performance
            }
            .filter { it.lineId != lineId || it.playerIds.isNotEmpty() }
        val assignments = orchestration.staffAssignments.filterNot {
            it.lineId == lineId && it.playerId == route.playerId
        }
        val remainingPlayers = performances
            .filter { it.lineId == lineId }
            .flatMap { it.playerIds }
            .distinct()
        if (remainingPlayers.isEmpty()) {
            return TogglePlayerResult(
                score = deleteSynchronizationGroup(score, reduction.id, lineId),
                lineId = lineId,
                playerAdded = false,
                groupDeleted = true,
                playerCount = 0,
            )
        }

        val removedStaffIds = removedAssignments.mapNotNullTo(hashSetOf()) { it.staffId }
        val retainedStaffIds = assignments
            .filter { it.lineId == lineId }
            .mapNotNullTo(hashSetOf()) { it.staffId }
        val exclusivelyRemovedStaffIds = removedStaffIds - retainedStaffIds
        val writtenVoiceIds = exclusivelyRemovedStaffIds.flatMapTo(hashSetOf()) { staffId ->
            score.staffTracks[staffId]?.voiceTrackIds.orEmpty()
        }
        val writtenEventIds = writtenVoiceIds.flatMapTo(hashSetOf()) { voiceId ->
            score.voiceTracks[voiceId]?.events.orEmpty().map { it.id }
        }
        val lineEventIds = score.voiceTracks[lineId]?.events.orEmpty().mapTo(hashSetOf()) { it.id }
        val links = orchestration.links.filterNot {
            it.source.eventId in lineEventIds && it.target.eventId in writtenEventIds
        }
        return TogglePlayerResult(
            score = score.copy(
                orchestration = orchestration.copy(
                    performances = performances,
                    staffAssignments = assignments,
                    links = links,
                )
            ),
            lineId = lineId,
            playerAdded = false,
            groupDeleted = false,
            playerCount = remainingPlayers.size,
        )
    }

    fun lineIdForReductionNotes(
        score: StorageScore,
        reduction: com.mecon.api.storage.StorageReduction,
        notes: Set<NoteRef>,
    ): TrackId? {
        val sourceEventId = reduction.links.firstOrNull { it.target in notes }?.source?.eventId
            ?: return null
        return score.voiceTracks.entries.firstOrNull { (voiceId, voice) ->
            voiceId in score.orchestration?.lines.orEmpty() &&
                voice.events.any { it.id == sourceEventId }
        }?.key
    }

    private fun deleteSynchronizationGroup(
        score: StorageScore,
        reductionId: ReductionId,
        lineId: TrackId,
    ): StorageScore {
        val line = score.voiceTracks[lineId] ?: return score
        val lineEventIds = line.events.mapTo(hashSetOf()) { it.id }
        val orchestration = score.orchestration ?: return score
        return score.copy(
            pitchTracks = score.pitchTracks - line.pitchTrackId,
            voiceTracks = score.voiceTracks - lineId,
            reductions = score.reductions.map { reduction ->
                if (reduction.id == reductionId) {
                    reduction.copy(
                        links = reduction.links.filterNot { it.source.eventId in lineEventIds },
                    )
                } else reduction
            },
            orchestration = orchestration.copy(
                lines = orchestration.lines - lineId,
                performances = orchestration.performances.filterNot { it.lineId == lineId },
                staffAssignments = orchestration.staffAssignments.filterNot { it.lineId == lineId },
                links = orchestration.links.filterNot { it.source.eventId in lineEventIds },
            ),
        )
    }

    private fun bindWrittenSelection(
        score: StorageScore,
        reduction: com.mecon.api.storage.StorageReduction,
        lineId: TrackId,
        request: BindRequest,
    ): BindResult? {
        val targetStaff = request.targetReductionStaffId
            ?.let(reduction.notationScore.staffTracks::get)
            ?: reduction.notationScore.staffTracks.values.firstOrNull()
            ?: return null
        val targetVoiceId = request.targetReductionVoiceId
            ?.takeIf { it in targetStaff.voiceTrackIds }
            ?: targetStaff.voiceTrackIds.firstOrNull()
            ?: return null
        var updated = score
        var updatedReduction = reduction
        var bound = 0

        request.writtenNotes.groupBy { it.eventId }.forEach { (eventId, refs) ->
            val writtenEvent = updated.findVoiceEvent(eventId) ?: return@forEach
            val writtenPitchEvent = updated.findPitchEvent(writtenEvent.pitchEventId) ?: return@forEach
            val validRefs = refs.distinctBy { it.pitchIndex }
                .filter { it.pitchIndex in writtenPitchEvent.pitches.indices }
            if (validRefs.isEmpty()) return@forEach

            val currentLine = updated.voiceTracks[lineId] ?: return@forEach
            val lineEvent = currentLine.events.firstOrNull {
                it.onset == writtenEvent.onset && it.duration == writtenEvent.duration
            }
            val resolvedLineEvent: StorageVoiceEvent
            val resolvedLinePitch: StoragePitchEvent
            val linePairs = mutableListOf<Pair<NoteRef, NoteRef>>()
            if (lineEvent == null) {
                resolvedLinePitch = StoragePitchEvent.create(
                    onset = writtenEvent.onset,
                    pitches = validRefs.map { writtenPitchEvent.pitches[it.pitchIndex] },
                    articulations = writtenPitchEvent.articulations,
                )
                resolvedLineEvent = StorageVoiceEvent.create(
                    onset = writtenEvent.onset,
                    pitchEventId = resolvedLinePitch.id,
                    duration = writtenEvent.duration,
                    rendering = writtenEvent.rendering,
                )
                updated = updated
                    .addPitchEvent(currentLine.pitchTrackId, resolvedLinePitch)
                    .addVoiceEvent(lineId, resolvedLineEvent)
                validRefs.forEachIndexed { index, writtenRef ->
                    linePairs += NoteRef(resolvedLineEvent.id, index) to writtenRef
                }
            } else {
                resolvedLineEvent = lineEvent
                val currentPitch = updated.findPitchEvent(lineEvent.pitchEventId) ?: return@forEach
                val merged = currentPitch.pitches.toMutableList()
                validRefs.forEach { writtenRef ->
                    val pitch = writtenPitchEvent.pitches[writtenRef.pitchIndex]
                    val index = merged.indexOfFirst { it.midiNumber == pitch.midiNumber }
                        .takeIf { it >= 0 }
                        ?: merged.size.also { merged += pitch }
                    linePairs += NoteRef(lineEvent.id, index) to writtenRef
                }
                resolvedLinePitch = currentPitch.copy(pitches = merged)
                if (resolvedLinePitch != currentPitch) {
                    updated = updated.updatePitchTrack(currentLine.pitchTrackId) { track ->
                        track.copy(events = track.events.map {
                            if (it.id == resolvedLinePitch.id) resolvedLinePitch else it
                        })
                    }
                }
            }

            val targetVoice = updatedReduction.notationScore.voiceTracks[targetVoiceId] ?: return@forEach
            val reductionEvent = targetVoice.events.firstOrNull {
                it.onset == writtenEvent.onset && it.duration == writtenEvent.duration
            }
            val resolvedReductionEvent: StorageVoiceEvent
            val resolvedReductionPitch: StoragePitchEvent
            if (reductionEvent == null) {
                resolvedReductionPitch = StoragePitchEvent.create(
                    onset = writtenEvent.onset,
                    pitches = linePairs.map { (lineRef, _) -> resolvedLinePitch.pitches[lineRef.pitchIndex] },
                    articulations = writtenPitchEvent.articulations,
                )
                resolvedReductionEvent = StorageVoiceEvent.create(
                    onset = writtenEvent.onset,
                    pitchEventId = resolvedReductionPitch.id,
                    duration = writtenEvent.duration,
                    rendering = writtenEvent.rendering,
                )
                updatedReduction = updatedReduction.updateLayerScore(
                    ReductionLayerKind.NOTATION,
                    updatedReduction.notationScore
                        .addPitchEvent(targetVoice.pitchTrackId, resolvedReductionPitch)
                        .addVoiceEvent(targetVoiceId, resolvedReductionEvent),
                )
            } else {
                resolvedReductionEvent = reductionEvent
                val currentPitch = updatedReduction.notationScore.findPitchEvent(reductionEvent.pitchEventId)
                    ?: return@forEach
                val additions = linePairs.map { (lineRef, _) -> resolvedLinePitch.pitches[lineRef.pitchIndex] }
                    .filter { value -> currentPitch.pitches.none { it.midiNumber == value.midiNumber } }
                resolvedReductionPitch = currentPitch.copy(pitches = currentPitch.pitches + additions)
                if (additions.isNotEmpty()) {
                    updatedReduction = updatedReduction.updateLayerScore(
                        ReductionLayerKind.NOTATION,
                        updatedReduction.notationScore.updatePitchTrack(targetVoice.pitchTrackId) { track ->
                            track.copy(events = track.events.map {
                                if (it.id == resolvedReductionPitch.id) resolvedReductionPitch else it
                            })
                        },
                    )
                }
            }

            var currentOrchestration = updated.orchestration ?: return@forEach
            linePairs.forEach { (lineRef, writtenRef) ->
                if (currentOrchestration.links.none { it.source == lineRef && it.target == writtenRef }) {
                    currentOrchestration = currentOrchestration.copy(
                        links = currentOrchestration.links + StorageNoteLink(lineRef, writtenRef),
                    )
                }
                val linePitch = resolvedLinePitch.pitches[lineRef.pitchIndex]
                val reductionPitchIndex = resolvedReductionPitch.pitches.indexOfFirst {
                    it.midiNumber == linePitch.midiNumber
                }
                if (reductionPitchIndex >= 0) {
                    val reductionRef = NoteRef(resolvedReductionEvent.id, reductionPitchIndex)
                    if (updatedReduction.links.none {
                            it.source == lineRef && it.target == reductionRef
                        }
                    ) {
                        updatedReduction = updatedReduction.copy(
                            links = updatedReduction.links + StorageNoteLink(
                                source = lineRef,
                                target = reductionRef,
                                octaveShift = nearestOctaveShift(
                                    linePitch.midiNumber,
                                    resolvedReductionPitch.pitches[reductionPitchIndex].midiNumber,
                                ),
                            ),
                        )
                        bound++
                    }
                }
            }
            updated = updated.copy(orchestration = currentOrchestration)
        }

        updated = updated.copy(reductions = updated.reductions.map {
            if (it.id == updatedReduction.id) updatedReduction else it
        })
        updated = setRoutes(updated, lineId, request.onset, request.routes)
        return BindResult(
            score = updated,
            lineId = lineId,
            direction = BindingDirection.WRITTEN_TO_REDUCTION,
            boundNotes = bound,
            realizedNotes = 0,
            unresolvedNotes = 0,
            conflicts = 0,
        )
    }

    fun setRoutes(
        score: StorageScore,
        lineId: TrackId,
        onset: TimeCode,
        routes: List<PlayerRoute>,
    ): StorageScore {
        val orchestration = score.orchestration ?: return score
        val playerIds = routes.map { it.playerId }.distinct()
        val performances = orchestration.performances
            .filterNot { it.lineId == lineId && it.onset == onset } +
            StoragePerformance(lineId = lineId, onset = onset, playerIds = playerIds)
        val assignments = orchestration.staffAssignments.filterNot { assignment ->
            assignment.lineId == lineId && assignment.onset == onset
        } + routes.distinctBy { it.playerId }.map { route ->
            StorageStaffAssignment(
                playerId = route.playerId,
                lineId = lineId,
                onset = onset,
                staffId = route.staffId,
                voiceHint = route.voiceNumber.coerceAtLeast(1),
            )
        }
        return score.copy(
            orchestration = orchestration.copy(
                performances = performances,
                staffAssignments = assignments,
            )
        )
    }

    fun realizeAllLines(score: StorageScore): RealizeResult {
        var updated = score
        var realized = 0
        var unresolved = 0
        var conflicts = 0
        score.orchestration?.lines.orEmpty().forEach { lineId ->
            val result = realizeLine(updated, lineId)
            updated = result.score
            realized += result.realizedNotes
            unresolved += result.unresolvedNotes
            conflicts += result.conflicts
        }
        return RealizeResult(updated, realized, unresolved, conflicts)
    }

    fun realizeLine(score: StorageScore, lineId: TrackId): RealizeResult {
        val orchestration = score.orchestration ?: return RealizeResult(score, 0, 0, 0)
        val line = score.voiceTracks[lineId] ?: return RealizeResult(score, 0, 0, 0)
        var updated = score
        var realized = 0
        var unresolved = 0
        var conflicts = 0

        line.events.sortedBy { it.onset }.forEach { lineEvent ->
            val linePitch = updated.findPitchEvent(lineEvent.pitchEventId) ?: return@forEach
            val performance = effectivePerformance(orchestration, lineId, lineEvent.onset)
            if (performance == null || performance.playerIds.isEmpty()) {
                unresolved += linePitch.pitches.size
                return@forEach
            }
            val targetGroups = performance.playerIds.mapNotNull { playerId ->
                effectiveAssignment(orchestration, playerId, lineId, lineEvent.onset)
                    ?.staffId
                    ?.let { staffId -> StaffVoiceTarget(staffId, effectiveAssignment(orchestration, playerId, lineId, lineEvent.onset)?.voiceHint ?: 1) }
            }.distinct()
            if (targetGroups.isEmpty()) {
                unresolved += linePitch.pitches.size
                return@forEach
            }

            targetGroups.forEach { target ->
                val ensured = ensureVoice(updated, target.staffId, target.voiceNumber)
                updated = ensured.first
                val voiceId = ensured.second ?: run {
                    unresolved += linePitch.pitches.size
                    return@forEach
                }
                val voice = updated.voiceTracks[voiceId] ?: return@forEach
                val existing = voice.events.firstOrNull { it.onset == lineEvent.onset }
                val writtenEvent: StorageVoiceEvent
                val writtenPitch: StoragePitchEvent
                if (existing == null) {
                    writtenPitch = StoragePitchEvent.create(
                        onset = lineEvent.onset,
                        pitches = linePitch.pitches,
                        articulations = linePitch.articulations,
                    )
                    writtenEvent = StorageVoiceEvent.create(
                        onset = lineEvent.onset,
                        pitchEventId = writtenPitch.id,
                        duration = lineEvent.duration,
                        rendering = lineEvent.rendering,
                    )
                    updated = updated
                        .addPitchEvent(voice.pitchTrackId, writtenPitch)
                        .addVoiceEvent(voiceId, writtenEvent)
                } else {
                    if (existing.duration != lineEvent.duration) {
                        conflicts += linePitch.pitches.size
                        return@forEach
                    }
                    writtenEvent = existing
                    val currentPitch = updated.findPitchEvent(existing.pitchEventId) ?: return@forEach
                    val additions = linePitch.pitches.filter { lineValue ->
                        currentPitch.pitches.none { it.midiNumber == lineValue.midiNumber }
                    }
                    writtenPitch = currentPitch.copy(pitches = currentPitch.pitches + additions)
                    if (additions.isNotEmpty()) {
                        updated = updated.updatePitchTrack(voice.pitchTrackId) { track ->
                            track.copy(events = track.events.map { event ->
                                if (event.id == writtenPitch.id) writtenPitch else event
                            })
                        }
                    }
                }

                val currentOrchestration = updated.orchestration ?: return@forEach
                val newLinks = linePitch.pitches.mapIndexedNotNull { lineIndex, pitch ->
                    val targetIndex = writtenPitch.pitches.indexOfFirst { it.midiNumber == pitch.midiNumber }
                    if (targetIndex < 0) return@mapIndexedNotNull null
                    val link = StorageNoteLink(
                        source = NoteRef(lineEvent.id, lineIndex),
                        target = NoteRef(writtenEvent.id, targetIndex),
                    )
                    link.takeIf { candidate -> currentOrchestration.links.none {
                        it.source == candidate.source && it.target == candidate.target
                    } }
                }
                if (newLinks.isNotEmpty()) {
                    updated = updated.copy(
                        orchestration = currentOrchestration.copy(links = currentOrchestration.links + newLinks)
                    )
                    realized += newLinks.size
                }
            }
        }
        return RealizeResult(updated, realized, unresolved, conflicts)
    }

    fun effectivePerformance(
        orchestration: com.mecon.api.storage.StorageOrchestration,
        lineId: TrackId,
        time: TimeCode,
    ): StoragePerformance? = orchestration.performances
        .filter { it.lineId == lineId && it.onset <= time }
        .maxByOrNull { it.onset }

    fun effectiveAssignment(
        orchestration: com.mecon.api.storage.StorageOrchestration,
        playerId: PlayerId,
        lineId: TrackId,
        time: TimeCode,
    ): StorageStaffAssignment? = orchestration.staffAssignments
        .filter {
            it.playerId == playerId && it.onset <= time && (it.lineId == null || it.lineId == lineId)
        }
        .maxWithOrNull(compareBy<StorageStaffAssignment> { it.onset }.thenBy { if (it.lineId == lineId) 1 else 0 })

    private data class StaffVoiceTarget(val staffId: TrackId, val voiceNumber: Int)

    private fun ensureOrchestration(score: StorageScore): StorageScore =
        if (score.orchestration != null) score
        else score.copy(orchestration = OrchestrationEngine.initializeFromInstruments(score))

    private fun ensureVoice(
        score: StorageScore,
        staffId: TrackId,
        voiceNumber: Int,
    ): Pair<StorageScore, TrackId?> {
        val staff = score.staffTracks[staffId] ?: return score to null
        staff.voiceTrackIds.mapNotNull(score.voiceTracks::get)
            .firstOrNull { it.voiceNumber == voiceNumber }
            ?.let { return score to it.id }
        val pitchTrack = StoragePitchTrack.create("${staff.name} Voice $voiceNumber Notes")
        val voiceTrack = StorageVoiceTrack.create("Voice $voiceNumber", voiceNumber, pitchTrack.id)
        return score
            .addPitchTrack(pitchTrack)
            .addVoiceTrack(voiceTrack)
            .updateStaffTrack(staffId) { it.copy(voiceTrackIds = it.voiceTrackIds + voiceTrack.id) } to voiceTrack.id
    }

    private fun nearestOctaveShift(sourceMidi: Int, targetMidi: Int): Int =
        ((sourceMidi - targetMidi) / 12.0).roundToInt()
}
