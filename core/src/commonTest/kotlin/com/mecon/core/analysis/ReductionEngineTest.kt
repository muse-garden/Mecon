package com.mecon.core.analysis

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.InstrumentTemplate
import com.mecon.api.storage.StaffTemplate
import com.mecon.api.storage.PlayerKind
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.StoragePitchTrack
import com.mecon.api.storage.tracks.StorageVoiceTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReductionEngineTest {
    @Test
    fun bindsReductionThroughContentLineAndPlayerBeforeWrittenScore() {
        val fixture = fixture()
        val player = fixture.score.orchestration!!.players.single()
        val staff = fixture.score.staffTracks.values.single()
        val result = OrchestrationFlowEngine.bindReductionSelection(
            fixture.score,
            OrchestrationFlowEngine.BindRequest(
                reductionId = fixture.reductionId,
                reductionNotes = setOf(fixture.reductionRef),
                lineName = "主题",
                routes = listOf(OrchestrationFlowEngine.PlayerRoute(player.id, staff.id)),
            ),
        )
        assertNotNull(result)
        val reduction = result.score.getReduction(fixture.reductionId)!!
        val orchestration = result.score.orchestration!!
        assertEquals(1, orchestration.lines.size)
        assertEquals(1, reduction.links.size)
        assertEquals(1, orchestration.performances.size)
        assertEquals(1, orchestration.links.size)
        val staffVoiceIds = result.score.staffTracks.values.flatMap { it.voiceTrackIds }.toSet()
        val lineEventId = reduction.links.single().source.eventId
        assertTrue(result.score.voiceTracks.values.first { voice ->
            voice.events.any { it.id == lineEventId }
        }.id !in staffVoiceIds)
        assertTrue(orchestration.links.single().source == reduction.links.single().source)
        val report = ReductionEngine.consistency(result.score, reduction)
        assertEquals(ReductionEngine.LinkStatus.OK, report.links.single().status)
        assertTrue(report.unmappedSource.isEmpty())
        assertTrue(report.unrealizedTargets.isEmpty())
    }

    @Test
    fun fixedReductionStartsWithoutAnyMapping() {
        val score = StorageScore.create(StorageScore.CreationOptions(orchestrationEnabled = true))
        val reduction = ReductionEngine.createFixed(score, "缩谱", listOf(Clef.TREBLE, Clef.BASS))
        assertTrue(reduction.links.isEmpty())
        assertTrue(score.orchestration!!.lines.isEmpty())
        assertTrue(score.orchestration!!.links.isEmpty())
    }

    @Test
    fun extractsWrittenSelectionToReductionThroughContentLine() {
        val base = StorageScore.create(StorageScore.CreationOptions(orchestrationEnabled = true))
        val writtenVoice = base.voiceTracks.values.first()
        val writtenPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val writtenEvent = StorageVoiceEvent.create(writtenPitch.onset, writtenPitch.id, Duration.QUARTER)
        var score = base
            .addPitchEvent(writtenVoice.pitchTrackId, writtenPitch)
            .addVoiceEvent(writtenVoice.id, writtenEvent)
        val reduction = ReductionEngine.createFixed(score, "缩谱", listOf(Clef.TREBLE))
        score = score.copy(reductions = listOf(reduction))
        val player = score.orchestration!!.players.single()
        val writtenStaff = score.staffTracks.values.single()
        val reductionStaff = reduction.notationScore.staffTracks.values.single()

        val result = OrchestrationFlowEngine.bindReductionSelection(
            score,
            OrchestrationFlowEngine.BindRequest(
                reductionId = reduction.id,
                writtenNotes = setOf(NoteRef(writtenEvent.id, 0)),
                targetReductionStaffId = reductionStaff.id,
                lineName = "主旋律",
                routes = listOf(OrchestrationFlowEngine.PlayerRoute(player.id, writtenStaff.id)),
            ),
        )
        assertNotNull(result)
        assertEquals(OrchestrationFlowEngine.BindingDirection.WRITTEN_TO_REDUCTION, result.direction)
        val updatedReduction = result.score.getReduction(reduction.id)!!
        val orchestration = result.score.orchestration!!
        assertEquals(1, updatedReduction.links.size)
        assertEquals(1, orchestration.links.size)
        val lineRef = updatedReduction.links.single().source
        assertEquals(lineRef, orchestration.links.single().source)
        assertEquals(NoteRef(writtenEvent.id, 0), orchestration.links.single().target)
        assertEquals(
            Pitch.C4.midiNumber,
            pitchAt(updatedReduction.notationScore, updatedReduction.links.single().target).midiNumber,
        )

        val changedWritten = updateRefPitch(result.score, NoteRef(writtenEvent.id, 0), Pitch.E4)
        val synchronized = ReductionSyncEngine.synchronize(result.score, changedWritten)
        assertEquals(
            Pitch.E4.midiNumber,
            pitchAt(
                synchronized.getReduction(reduction.id)!!.notationScore,
                updatedReduction.links.single().target,
            ).midiNumber,
        )
    }

    @Test
    fun togglingLastPlayerDeletesSynchronizationGroupButKeepsNotation() {
        val fixture = fixture()
        val player = fixture.score.orchestration!!.players.single()
        val staff = fixture.score.staffTracks.values.single()
        val request = OrchestrationFlowEngine.BindRequest(
            reductionId = fixture.reductionId,
            reductionNotes = setOf(fixture.reductionRef),
            routes = listOf(OrchestrationFlowEngine.PlayerRoute(player.id, staff.id)),
        )
        val added = OrchestrationFlowEngine.toggleReductionSelectionPlayer(fixture.score, request)
        assertNotNull(added)
        assertTrue(added.playerAdded)
        assertEquals(1, added.playerCount)

        val removed = OrchestrationFlowEngine.toggleReductionSelectionPlayer(added.score, request)
        assertNotNull(removed)
        assertTrue(!removed.playerAdded)
        assertTrue(removed.groupDeleted)
        assertEquals(0, removed.playerCount)
        assertTrue(removed.lineId !in removed.score.voiceTracks)
        assertTrue(removed.lineId !in removed.score.orchestration!!.lines)
        assertTrue(removed.score.getReduction(fixture.reductionId)!!.links.isEmpty())
        assertNotNull(
            removed.score.getReduction(fixture.reductionId)!!
                .notationScore.findVoiceEvent(fixture.reductionRef.eventId)
        )
    }

    @Test
    fun extractsWrittenSelectionToExplicitReductionVoice() {
        val base = StorageScore.create(StorageScore.CreationOptions(orchestrationEnabled = true))
        val writtenVoice = base.voiceTracks.values.first()
        val writtenPitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val writtenEvent = StorageVoiceEvent.create(writtenPitch.onset, writtenPitch.id, Duration.QUARTER)
        var score = base
            .addPitchEvent(writtenVoice.pitchTrackId, writtenPitch)
            .addVoiceEvent(writtenVoice.id, writtenEvent)
        var reduction = ReductionEngine.createFixed(score, "缩谱", listOf(Clef.TREBLE))
        val reductionStaff = reduction.notationScore.staffTracks.values.single()
        val secondPitchTrack = StoragePitchTrack.create("Voice 2 Notes")
        val secondVoice = StorageVoiceTrack.create("Voice 2", 2, secondPitchTrack.id)
        reduction = reduction.updateLayerScore(
            ReductionLayerKind.NOTATION,
            reduction.notationScore
                .addPitchTrack(secondPitchTrack)
                .addVoiceTrack(secondVoice)
                .updateStaffTrack(reductionStaff.id) {
                    it.copy(voiceTrackIds = it.voiceTrackIds + secondVoice.id)
                },
        )
        score = score.copy(reductions = listOf(reduction))

        val result = OrchestrationFlowEngine.bindReductionSelection(
            score,
            OrchestrationFlowEngine.BindRequest(
                reductionId = reduction.id,
                writtenNotes = setOf(NoteRef(writtenEvent.id, 0)),
                targetReductionStaffId = reductionStaff.id,
                targetReductionVoiceId = secondVoice.id,
                routes = listOf(
                    OrchestrationFlowEngine.PlayerRoute(
                        score.orchestration!!.players.single().id,
                        score.staffTracks.values.single().id,
                    )
                ),
            ),
        )

        assertNotNull(result)
        val updatedReduction = result.score.getReduction(reduction.id)!!
        assertTrue(updatedReduction.notationScore.voiceTracks[secondVoice.id]!!.events.isNotEmpty())
        val firstVoiceId = reductionStaff.voiceTrackIds.single()
        assertTrue(updatedReduction.notationScore.voiceTracks[firstVoiceId]!!.events.isEmpty())
    }

    @Test
    fun migratesLegacyInstrumentsToPlayersAndAssignments() {
        val score = StorageScore.create()
        val orchestration = OrchestrationEngine.initializeFromInstruments(score)
        assertEquals(score.instruments.size, orchestration.players.size)
        assertEquals(score.staffTracks.size, orchestration.staffAssignments.size)
        assertTrue(orchestration.players.all { it.instruments.isNotEmpty() })
    }

    @Test
    fun createsFixedReductionWithAlignedTimelineAndClefs() {
        val source = StorageScore.create(StorageScore.CreationOptions(measureCount = 6))
        val reduction = ReductionEngine.createFixed(source, "导奏缩谱", listOf(Clef.TREBLE, Clef.BASS))
        assertEquals(2, reduction.notationScore.staffTracks.size)
        assertEquals(source.measures, reduction.notationScore.measures)
        assertEquals(source.globalTrack, reduction.notationScore.globalTrack)
        assertEquals(Clef.TREBLE, reduction.notationScore.staffTracks.values.first().clef)
        assertEquals(Clef.BASS, reduction.notationScore.staffTracks.values.last().clef)
    }

    @Test
    fun configuresSoloPlayersAndPerStaffDistribution() {
        val score = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "圆号",
                    staves = listOf(StaffTemplate("圆号上", Clef.TREBLE), StaffTemplate("圆号下", Clef.BASS)),
                    catalogId = "horn",
                    playerCount = 4,
                )
            ),
            orchestrationEnabled = true,
        ))
        val instrument = score.instruments.single()
        val createdOrchestration = score.orchestration!!
        val numberById = createdOrchestration.players.mapIndexed { index, player -> player.id to index + 1 }.toMap()
        val createdGroups = instrument.staffIds.map { staffId ->
            createdOrchestration.staffAssignments
                .filter { it.staffId == staffId }
                .mapNotNull { numberById[it.playerId] }
                .sorted()
        }
        assertEquals(listOf(listOf(1, 3), listOf(2, 4)), createdGroups)
        val configured = OrchestrationEngine.configureInstrument(
            score,
            instrument.id,
            PlayerKind.SINGLE,
            4,
            listOf(listOf(1, 3), listOf(2, 4)),
        )
        val orchestration = configured.orchestration!!
        assertEquals(4, orchestration.players.size)
        assertEquals(4, orchestration.staffAssignments.size)
    }

    @Test
    fun reconfiguringPlayerCountKeepsExistingContentLineRoutable() {
        val base = StorageScore.create(StorageScore.CreationOptions(
            instrumentTemplates = listOf(
                InstrumentTemplate(
                    name = "圆号",
                    staves = listOf(StaffTemplate("圆号上", Clef.TREBLE), StaffTemplate("圆号下", Clef.BASS)),
                    playerCount = 4,
                )
            ),
            orchestrationEnabled = true,
        ))
        var reduction = ReductionEngine.createFixed(base, "缩谱", listOf(Clef.TREBLE))
        val reductionVoice = reduction.notationScore.voiceTracks.values.first()
        val pitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val event = StorageVoiceEvent.create(pitch.onset, pitch.id, Duration.QUARTER)
        reduction = reduction.updateLayerScore(
            ReductionLayerKind.NOTATION,
            reduction.notationScore
                .addPitchEvent(reductionVoice.pitchTrackId, pitch)
                .addVoiceEvent(reductionVoice.id, event),
        )
        val score = base.copy(reductions = listOf(reduction))
        val oldPlayers = score.orchestration!!.players
        val targetStaff = score.staffTracks.values.first()
        val bound = OrchestrationFlowEngine.bindReductionSelection(
            score,
            OrchestrationFlowEngine.BindRequest(
                reductionId = reduction.id,
                reductionNotes = setOf(NoteRef(event.id, 0)),
                routes = listOf(OrchestrationFlowEngine.PlayerRoute(oldPlayers[2].id, targetStaff.id)),
                realizeNow = false,
            ),
        )!!

        val configured = OrchestrationEngine.configureInstrument(
            bound.score,
            score.instruments.single().id,
            PlayerKind.SINGLE,
            2,
            listOf(listOf(1), listOf(2)),
        )
        val orchestration = configured.orchestration!!
        val effectivePlayers = orchestration.performances.single().playerIds
        assertEquals(1, effectivePlayers.size)
        assertTrue(effectivePlayers.single() in orchestration.players.map { it.id })
        assertTrue(orchestration.staffAssignments.any {
            it.lineId == bound.lineId &&
                it.playerId == effectivePlayers.single() &&
                it.staffId == targetStaff.id
        })
        val realized = OrchestrationFlowEngine.realizeLine(configured, bound.lineId)
        assertEquals(1, realized.realizedNotes)
        assertEquals(0, realized.unresolvedNotes)
    }

    @Test
    fun editsSynchronizeAcrossReductionLineAndWrittenScore() {
        val fixture = fixture()
        val player = fixture.score.orchestration!!.players.single()
        val staff = fixture.score.staffTracks.values.single()
        val bound = OrchestrationFlowEngine.bindReductionSelection(
            fixture.score,
            OrchestrationFlowEngine.BindRequest(
                reductionId = fixture.reductionId,
                reductionNotes = setOf(fixture.reductionRef),
                routes = listOf(OrchestrationFlowEngine.PlayerRoute(player.id, staff.id)),
            ),
        )!!.score

        val changedReduction = bound.copy(reductions = bound.reductions.map { reduction ->
            if (reduction.id != fixture.reductionId) {
                reduction
            } else {
                reduction.updateLayerScore(
                    ReductionLayerKind.NOTATION,
                    updateRefPitch(reduction.notationScore, fixture.reductionRef, Pitch.E4),
                )
            }
        })
        val fromReduction = ReductionSyncEngine.synchronize(bound, changedReduction)
        val lineRef = fromReduction.getReduction(fixture.reductionId)!!.links.single().source
        val writtenRef = fromReduction.orchestration!!.links.single().target
        assertEquals(Pitch.E4.midiNumber, pitchAt(fromReduction, lineRef).midiNumber)
        assertEquals(Pitch.E4.midiNumber, pitchAt(fromReduction, writtenRef).midiNumber)

        val changedWritten = updateRefPitch(fromReduction, writtenRef, Pitch.G4)
        val fromWritten = ReductionSyncEngine.synchronize(fromReduction, changedWritten)
        assertEquals(Pitch.G4.midiNumber, pitchAt(fromWritten, lineRef).midiNumber)
        assertEquals(
            Pitch.G4.midiNumber,
            pitchAt(
                fromWritten.getReduction(fixture.reductionId)!!.notationScore,
                fixture.reductionRef,
            ).midiNumber,
        )
    }

    private data class Fixture(
        val score: StorageScore,
        val reductionId: com.mecon.api.primitive.ReductionId,
        val reductionRef: NoteRef,
    )

    private fun fixture(): Fixture {
        val source = StorageScore.create(StorageScore.CreationOptions(orchestrationEnabled = true))
        var reduction = ReductionEngine.createFixed(source, "缩谱", listOf(Clef.TREBLE))
        val voice = reduction.notationScore.voiceTracks.values.first()
        val pitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val event = StorageVoiceEvent.create(pitch.onset, pitch.id, Duration.QUARTER)
        reduction = reduction.updateLayerScore(
            ReductionLayerKind.NOTATION,
            reduction.notationScore.addPitchEvent(voice.pitchTrackId, pitch).addVoiceEvent(voice.id, event),
        )
        return Fixture(
            score = source.copy(reductions = listOf(reduction)),
            reductionId = reduction.id,
            reductionRef = NoteRef(event.id, 0),
        )
    }

    private fun updateRefPitch(score: StorageScore, ref: NoteRef, pitch: Pitch): StorageScore {
        val voice = score.voiceTracks.values.first { track -> track.events.any { it.id == ref.eventId } }
        val event = voice.events.first { it.id == ref.eventId }
        val pitchEvent = score.findPitchEvent(event.pitchEventId)!!
        val pitches = pitchEvent.pitches.toMutableList().also { it[ref.pitchIndex] = pitch }
        return score.updatePitchTrack(voice.pitchTrackId) { track ->
            track.copy(events = track.events.map {
                if (it.id == pitchEvent.id) it.copy(pitches = pitches) else it
            })
        }
    }

    private fun pitchAt(score: StorageScore, ref: NoteRef): Pitch {
        val event = score.findVoiceEvent(ref.eventId)!!
        return score.findPitchEvent(event.pitchEventId)!!.pitches[ref.pitchIndex]
    }
}
