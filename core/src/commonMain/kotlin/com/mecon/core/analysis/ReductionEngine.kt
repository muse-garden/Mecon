package com.mecon.core.analysis

import com.mecon.api.primitive.*
import com.mecon.api.storage.*
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.Clef

/** A0/A1 analysis operations. The engine is deliberately storage-oriented so one edit can be
 * committed atomically by the desktop session. */
object ReductionEngine {
    enum class LinkStatus { OK, PITCH_DIVERGED, TIME_DIVERGED, DANGLING }

    data class LinkFinding(val link: StorageNoteLink, val status: LinkStatus)

    data class ConsistencyReport(
        val links: List<LinkFinding>,
        val unmappedSource: List<NoteRef>,
        val unrealizedTargets: List<NoteRef>,
    )

    /** Create the first-version reduction: one fixed set of staves covering the whole source. */
    fun createFixed(
        source: StorageScore,
        title: String = "缩谱",
        clefs: List<Clef>,
    ): StorageReduction {
        require(clefs.isNotEmpty()) { "A reduction must contain at least one staff" }
        val staffTemplates = clefs.mapIndexed { index, clef ->
            StaffTemplate("${title} ${index + 1}", clef)
        }
        val measureCount = source.measures.maxOfOrNull { it.number } ?: 1
        val reductionScore = StorageScore.create(
            StorageScore.CreationOptions(
                title = title,
                timeSignature = source.defaultTimeSignature,
                keySignature = source.defaultKeySignature,
                tempo = source.defaultTempo,
                measureCount = measureCount,
                pageLayout = source.pageLayout,
                instrumentTemplates = listOf(
                    InstrumentTemplate(
                        name = title,
                        abbreviation = "缩谱",
                        staves = staffTemplates,
                        labelInHeader = true,
                    )
                ),
            )
        ).copy(
            measures = source.measures,
            globalTrack = source.globalTrack,
            defaultTimeSignature = source.defaultTimeSignature,
            defaultKeySignature = source.defaultKeySignature,
            defaultTempo = source.defaultTempo,
            viewPreferences = source.viewPreferences,
        )
        val lastMeasure = (source.measures.maxOfOrNull { it.number } ?: 1) + 1
        return StorageReduction(
            id = ReductionId.generate(),
            title = title,
            anchor = ReductionAnchor(TimeCode.ofMeasure(1), TimeCode.ofMeasure(lastMeasure)),
            template = ReductionTemplate.FREE,
            layers = ReductionLayerKind.entries.map { kind ->
                StorageReductionLayer(
                    id = ReductionLayerId("reduction-${kind.name.lowercase()}-${reductionScore.id.value}"),
                    kind = kind,
                    visible = kind != ReductionLayerKind.SKELETON,
                    score = reductionScore.takeIf { kind == ReductionLayerKind.NOTATION },
                )
            },
        ).migrated()
    }

    /** Recompute the user-visible A0 report. No result is persisted and no edit is blocked. */
    fun consistency(source: StorageScore, reduction: StorageReduction): ConsistencyReport {
        val linkedSourceEventIds = reduction.links.map { it.source.eventId }.toSet()
        val sourceVoiceIds = source.voiceTracks.values
            .filter { voice ->
                voice.id in source.orchestration?.lines.orEmpty() ||
                    voice.events.any { it.id in linkedSourceEventIds }
            }
            .map { it.id }
            .toSet()
        val sourceNotes = notes(source, sourceVoiceIds, reduction.anchor)
        val targetScore = reduction.notationScore
        val targetNotes = notes(targetScore, targetScore.voiceTracks.keys, null)
        val sourceByRef = sourceNotes.associateBy { it.ref }
        val targetByRef = targetNotes.associateBy { it.ref }
        val findings = reduction.links.map { link ->
            val sourceNote = sourceByRef[link.source]
            val targetNote = targetByRef[link.target]
            val status = when {
                sourceNote == null || targetNote == null -> LinkStatus.DANGLING
                sourceNote.event.onset != targetNote.event.onset || sourceNote.event.duration != targetNote.event.duration ->
                    LinkStatus.TIME_DIVERGED
                sourceNote.pitch.midiNumber != targetNote.pitch.midiNumber + link.octaveShift * 12 ->
                    LinkStatus.PITCH_DIVERGED
                else -> LinkStatus.OK
            }
            LinkFinding(link, status)
        }
        val linkedSources = reduction.links.map { it.source }.toSet()
        val linkedTargets = reduction.links.map { it.target }.toSet()
        return ConsistencyReport(
            links = findings,
            unmappedSource = sourceNotes.map { it.ref }.filter { it !in linkedSources },
            unrealizedTargets = targetNotes.map { it.ref }.filter { it !in linkedTargets },
        )
    }

    private data class Note(val ref: NoteRef, val event: StorageVoiceEvent, val pitch: Pitch)

    private fun notes(score: StorageScore, voiceIds: Set<TrackId>, anchor: ReductionAnchor?): List<Note> {
        return score.voiceTracks.values.filter { it.id in voiceIds }
            .flatMap { voice -> voice.events.flatMap { event ->
                if (anchor != null && (event.onset < anchor.sourceStart || event.onset >= anchor.sourceEnd)) emptyList()
                else score.findPitchEvent(event.pitchEventId)?.pitches.orEmpty().mapIndexed { index, pitch ->
                    Note(NoteRef(event.id, index), event, pitch)
                }
            } }
    }
}

object OrchestrationEngine {
    /** O0 migration: every legacy instrument becomes one SINGLE player and keeps its staff. */
    fun initializeFromInstruments(score: StorageScore): StorageOrchestration {
        val players = score.instruments.map { instrument ->
            StoragePlayer(
                id = PlayerId.generate(),
                name = instrument.name,
                abbreviation = instrument.abbreviation,
                instruments = listOf(StoragePlayerInstrument(
                    id = instrument.id,
                    name = instrument.name,
                    abbreviation = instrument.abbreviation,
                    playback = instrument.playback,
                )),
            )
        }
        val assignments = score.instruments.zip(players).flatMap { (instrument, player) ->
            instrument.staffIds.map { staffId ->
                StorageStaffAssignment(player.id, onset = TimeCode.ofMeasure(1), staffId = staffId)
            }
        }
        return StorageOrchestration(players = players, staffAssignments = assignments)
    }

    /** Replace one instrument's player model while preserving all other instruments. */
    fun configureInstrument(
        score: StorageScore,
        instrumentId: InstrumentId,
        kind: PlayerKind,
        playerCount: Int,
        playerAssignments: List<List<Int>> = emptyList(),
    ): StorageScore {
        val instrument = score.instruments.firstOrNull { it.id == instrumentId } ?: return score
        val current = score.orchestration ?: initializeFromInstruments(score)
        val oldPlayers = current.players.filter { player ->
            player.instruments.any { it.id == instrumentId }
        }
        val otherPlayers = current.players.filterNot { it in oldPlayers }
        val count = if (kind == PlayerKind.SECTION) 1 else playerCount.coerceIn(1, 32)
        val players = (1..count).map { number ->
            val previous = oldPlayers.getOrNull(number - 1)
            StoragePlayer(
                id = previous?.id ?: PlayerId.generate(),
                name = if (count == 1) instrument.name else "${instrument.name} $number",
                abbreviation = instrument.abbreviation,
                kind = kind,
                instruments = listOf(
                    StoragePlayerInstrument(
                        id = instrument.id,
                        name = instrument.name,
                        abbreviation = instrument.abbreviation,
                        playback = instrument.playback,
                    )
                ),
                holds = previous?.holds.orEmpty(),
            )
        }
        val validGroups = playerAssignments
            .takeIf {
                it.size == instrument.staffIds.size &&
                    it.all { group -> group.any { number -> number in 1..count } }
            }
            ?: if (kind == PlayerKind.SECTION) {
                List(instrument.staffIds.size) { listOf(1) }
            } else {
                defaultPlayerAssignments(instrument.staffIds.size, count)
            }
        val oldIds = oldPlayers.map { it.id }.toSet()
        val replacementByOldId = oldPlayers.mapIndexed { index, old ->
            old.id to players[index % players.size].id
        }.toMap()
        val preservedAssignments = current.staffAssignments.mapNotNull { assignment ->
            if (assignment.playerId !in oldIds) {
                assignment
            } else if (assignment.lineId != null) {
                replacementByOldId[assignment.playerId]?.let { assignment.copy(playerId = it) }
            } else {
                null
            }
        }
        val newAssignments = preservedAssignments + instrument.staffIds.flatMapIndexed { staffIndex, staffId ->
            validGroups[staffIndex].distinct().filter { it in 1..count }.map { number ->
                StorageStaffAssignment(
                    playerId = players[number - 1].id,
                    onset = TimeCode.ofMeasure(1),
                    staffId = staffId,
                )
            }
        }
        return score.copy(
            orchestration = current.copy(
                players = otherPlayers + players,
                performances = current.performances.map { performance ->
                    performance.copy(
                        playerIds = performance.playerIds.mapNotNull { playerId ->
                            if (playerId in oldIds) replacementByOldId[playerId] else playerId
                        }.distinct()
                    )
                },
                staffAssignments = newAssignments,
            )
        )
    }

    fun effectiveAssignment(
        orchestration: StorageOrchestration,
        playerId: PlayerId,
        time: TimeCode,
    ): StorageStaffAssignment? = orchestration.staffAssignments
        .filter { it.playerId == playerId && it.onset <= time }
        .maxByOrNull { it.onset }
}
