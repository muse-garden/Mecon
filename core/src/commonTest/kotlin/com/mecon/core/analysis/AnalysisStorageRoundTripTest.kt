package com.mecon.core.analysis

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.storage.tracks.Clef
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.core.serializer.ScoreSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalysisStorageRoundTripTest {
    @Test
    fun reductionAndOrchestrationSurviveYamlAndJson() {
        val empty = StorageScore.create(StorageScore.CreationOptions(orchestrationEnabled = true))
        var reduction = ReductionEngine.createFixed(empty, "缩谱", listOf(Clef.TREBLE))
        val voice = reduction.notationScore.voiceTracks.values.first()
        val pitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val event = StorageVoiceEvent.create(pitch.onset, pitch.id, Duration.QUARTER)
        reduction = reduction.updateLayerScore(
            ReductionLayerKind.NOTATION,
            reduction.notationScore.addPitchEvent(voice.pitchTrackId, pitch).addVoiceEvent(voice.id, event),
        )
        val source = empty.copy(reductions = listOf(reduction))
        val score = OrchestrationFlowEngine.bindReductionSelection(
            source,
            OrchestrationFlowEngine.BindRequest(
                reductionId = reduction.id,
                reductionNotes = setOf(NoteRef(event.id, 0)),
                routes = listOf(
                    OrchestrationFlowEngine.PlayerRoute(
                        source.orchestration!!.players.single().id,
                        source.staffTracks.keys.single(),
                    )
                ),
            ),
        )!!.score

        listOf(
            ScoreSerializer.fromYaml(ScoreSerializer.toYaml(score)),
            ScoreSerializer.fromJson(ScoreSerializer.toJson(score)),
        ).forEach { restored ->
            assertEquals(1, restored.reductions.size)
            assertEquals(1, restored.reductions.single().links.size)
            assertNotNull(restored.orchestration)
            assertEquals(score.orchestration!!.players.size, restored.orchestration!!.players.size)
            assertEquals(1, restored.orchestration!!.lines.size)
            assertEquals(1, restored.orchestration!!.performances.size)
            assertEquals(1, restored.orchestration!!.links.size)
        }
    }
}
