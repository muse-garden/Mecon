package com.mecon.core.analysis

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.ScoreFragmentId
import com.mecon.api.primitive.ScoreId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.ScoreFragmentKind
import com.mecon.api.storage.StorageReduction
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.StorageScoreFragment
import com.mecon.api.storage.StorageScoreFragmentSource
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent

/**
 * Storage-only operations for the first reduction-workspace interaction pass.
 * Placement intentionally copies independent note data; source-placement links are deferred.
 */
object ReductionWorkspaceEngine {
    data class PlacementResult(
        val reduction: StorageReduction,
        val copiedEvents: Int,
    )

    fun initializeScoreLayer(
        reduction: StorageReduction,
        kind: ReductionLayerKind,
    ): StorageReduction {
        require(kind == ReductionLayerKind.SKELETON || kind == ReductionLayerKind.NOTATION)
        if (reduction.scoreFor(kind) != null) return reduction.migrated()
        val source = reduction.notationScore
        val empty = source.copy(
            id = ScoreId.generate(),
            metadata = source.metadata.copy(title = "${reduction.title} · ${kind.displayName()}"),
            pitchTracks = source.pitchTracks.mapValues { (_, track) -> track.copy(events = emptyList()) },
            voiceTracks = source.voiceTracks.mapValues { (_, track) ->
                track.copy(events = emptyList(), slurs = emptyList())
            },
            pluginTracks = emptyMap(),
            reductions = emptyList(),
            orchestration = null,
            geometry = null,
        )
        return reduction.updateLayerScore(kind, empty)
    }

    fun captureFragment(
        reduction: StorageReduction,
        selectedNotes: Set<NoteRef>,
        name: String,
    ): StorageScoreFragment? {
        if (selectedNotes.isEmpty()) return null
        val source = reduction.notationScore
        val refsByEvent = selectedNotes.groupBy(NoteRef::eventId)
        val selectedEvents = source.voiceTracks.values
            .flatMap { voice -> voice.events }
            .filter { it.id in refsByEvent }
        if (selectedEvents.isEmpty()) return null

        val firstMeasure = selectedEvents.minOf { it.onset.measure }
        val lastMeasure = selectedEvents.maxOf { it.onset.measure }
        val pitchEventsByTrack = linkedMapOf<com.mecon.api.primitive.TrackId, MutableList<StoragePitchEvent>>()
        val voiceEventsByTrack = linkedMapOf<com.mecon.api.primitive.TrackId, MutableList<StorageVoiceEvent>>()

        source.voiceTracks.values.forEach { voice ->
            voice.events.filter { it.id in refsByEvent }.forEach { event ->
                val pitch = source.findPitchEvent(event.pitchEventId) ?: return@forEach
                val indices = refsByEvent.getValue(event.id).map(NoteRef::pitchIndex).distinct().sorted()
                val selectedPitches = indices.mapNotNull(pitch.pitches::getOrNull)
                if (selectedPitches.isEmpty()) return@forEach
                val onset = event.onset.shiftMeasures(1 - firstMeasure)
                val pitchCopy = pitch.copy(
                    id = EventId.generate(),
                    onset = onset,
                    pitches = selectedPitches,
                )
                val voiceCopy = event.copy(
                    id = EventId.generate(),
                    onset = onset,
                    pitchEventId = pitchCopy.id,
                    ties = emptyList(),
                    tupletSpan = null,
                    slurStarts = 0,
                    slurEnds = 0,
                )
                pitchEventsByTrack.getOrPut(voice.pitchTrackId) { mutableListOf() } += pitchCopy
                voiceEventsByTrack.getOrPut(voice.id) { mutableListOf() } += voiceCopy
            }
        }

        val copiedScore = source.copy(
            id = ScoreId.generate(),
            metadata = source.metadata.copy(title = name),
            measures = source.measures
                .filter { it.number in firstMeasure..lastMeasure }
                .map { it.copy(number = it.number - firstMeasure + 1) },
            pitchTracks = source.pitchTracks.mapValues { (id, track) ->
                track.copy(events = pitchEventsByTrack[id].orEmpty().sortedBy(StoragePitchEvent::onset))
            },
            voiceTracks = source.voiceTracks.mapValues { (id, track) ->
                track.copy(
                    events = voiceEventsByTrack[id].orEmpty().sortedBy(StorageVoiceEvent::onset),
                    slurs = emptyList(),
                )
            },
            pluginTracks = emptyMap(),
            reductions = emptyList(),
            orchestration = null,
            geometry = null,
        )
        val melodic = copiedScore.voiceTracks.values.count { it.events.isNotEmpty() } == 1 &&
            copiedScore.pitchTracks.values.flatMap { it.events }.all { it.pitches.size == 1 }
        return StorageScoreFragment(
            id = ScoreFragmentId.generate(),
            name = name,
            kind = if (melodic) ScoreFragmentKind.MELODIC else ScoreFragmentKind.NORMAL,
            score = copiedScore,
            sourceMetadata = StorageScoreFragmentSource(
                sourceScoreId = source.id,
                sourceReductionId = reduction.id,
                originalRange = TimeRange(
                    selectedEvents.minOf(StorageVoiceEvent::onset),
                    selectedEvents.maxOf(StorageVoiceEvent::onset),
                ),
            ),
        )
    }

    fun placeFragment(
        reduction: StorageReduction,
        fragmentId: ScoreFragmentId,
        targetMeasure: Int,
    ): PlacementResult? {
        val fragment = reduction.materialTray.firstOrNull { it.id == fragmentId } ?: return null
        val destination = reduction.notationScore
        val lastMeasure = destination.measures.maxOfOrNull { it.number } ?: return null
        if (targetMeasure !in 1..lastMeasure) return null

        val fragmentStaffs = fragment.score.staffTracks.values.toList()
        val destinationStaffs = destination.staffTracks.values.toList()
        if (destinationStaffs.isEmpty()) return null
        val pitchTracks = destination.pitchTracks.toMutableMap()
        val voiceTracks = destination.voiceTracks.toMutableMap()
        var copied = 0

        data class Pending(
            val targetVoiceId: com.mecon.api.primitive.TrackId,
            val targetPitchTrackId: com.mecon.api.primitive.TrackId,
            val pitch: StoragePitchEvent,
            val voice: StorageVoiceEvent,
        )

        val pending = buildList<Pending> pendingList@ {
            fragmentStaffs.forEachIndexed { staffIndex, fragmentStaff ->
                val destinationStaff = destinationStaffs[staffIndex.coerceAtMost(destinationStaffs.lastIndex)]
                fragmentStaff.voiceTrackIds.forEach { fragmentVoiceId ->
                    val fragmentVoice = fragment.score.voiceTracks[fragmentVoiceId] ?: return@forEach
                    val targetVoice = destinationStaff.voiceTrackIds
                        .mapNotNull(destination.voiceTracks::get)
                        .firstOrNull { it.voiceNumber == fragmentVoice.voiceNumber }
                        ?: destinationStaff.voiceTrackIds.firstNotNullOfOrNull(destination.voiceTracks::get)
                        ?: return@forEach
                    fragmentVoice.events.forEach { event ->
                        val sourcePitch = fragment.score.findPitchEvent(event.pitchEventId) ?: return@forEach
                        val onset = event.onset.shiftMeasures(targetMeasure - 1)
                        if (onset.measure > lastMeasure) return null
                        if (targetVoice.events.any { it.onset == onset } ||
                            this@pendingList.any { pendingItem ->
                                pendingItem.targetVoiceId == targetVoice.id &&
                                    pendingItem.voice.onset == onset
                            }
                        ) {
                            return null
                        }
                        val pitchCopy = sourcePitch.copy(id = EventId.generate(), onset = onset)
                        val voiceCopy = event.copy(
                            id = EventId.generate(),
                            onset = onset,
                            pitchEventId = pitchCopy.id,
                            ties = emptyList(),
                            tupletSpan = null,
                            slurStarts = 0,
                            slurEnds = 0,
                        )
                        add(Pending(targetVoice.id, targetVoice.pitchTrackId, pitchCopy, voiceCopy))
                    }
                }
            }
        }

        pending.forEach { item ->
            pitchTracks[item.targetPitchTrackId] = pitchTracks.getValue(item.targetPitchTrackId).let { track ->
                track.copy(events = (track.events + item.pitch).sortedBy(StoragePitchEvent::onset))
            }
            voiceTracks[item.targetVoiceId] = voiceTracks.getValue(item.targetVoiceId).let { track ->
                track.copy(events = (track.events + item.voice).sortedBy(StorageVoiceEvent::onset))
            }
            copied++
        }
        if (copied == 0) return null
        val updatedScore = destination.copy(pitchTracks = pitchTracks, voiceTracks = voiceTracks)
        return PlacementResult(
            reduction = reduction.updateLayerScore(ReductionLayerKind.NOTATION, updatedScore),
            copiedEvents = copied,
        )
    }

    private fun TimeCode.shiftMeasures(delta: Int): TimeCode =
        TimeCode(listOf(Fraction(measure + delta, 1)) + components.drop(1))

    private fun ReductionLayerKind.displayName(): String = when (this) {
        ReductionLayerKind.FORM -> "曲式"
        ReductionLayerKind.HARMONY -> "和声进行"
        ReductionLayerKind.SKELETON -> "骨架"
        ReductionLayerKind.NOTATION -> "缩谱记谱"
        ReductionLayerKind.ORCHESTRATION -> "配器"
    }
}
