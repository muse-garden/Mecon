package com.mecon.core.analysis

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.ReductionId
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.NoteRef
import com.mecon.api.storage.ReductionLayerKind
import com.mecon.api.storage.StorageReduction
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.events.StoragePitchEvent
import com.mecon.api.storage.events.StorageVoiceEvent
import com.mecon.api.storage.tracks.Clef
import com.mecon.core.serializer.ScoreSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReductionWorkspaceEngineTest {
    @Test
    fun legacyScoreMigratesIntoCompleteNotationWorkspace() {
        val nested = StorageScore.create()
        val legacy = StorageReduction(
            id = ReductionId("legacy"),
            title = "旧缩谱",
            legacyScore = nested,
        )

        val migrated = legacy.migrated()

        assertNull(migrated.legacyScore)
        assertEquals(ReductionLayerKind.entries, migrated.layers.map { it.kind })
        assertEquals(nested, migrated.notationScore)
        assertEquals(false, migrated.layer(ReductionLayerKind.SKELETON)?.visible)
    }

    @Test
    fun legacyYamlScoreFieldMigratesOnLoad() {
        val nested = StorageScore.create(StorageScore.CreationOptions(title = "旧缩谱内容"))
        val legacy = StorageScore.create().copy(
            reductions = listOf(
                StorageReduction(
                    id = ReductionId("legacy-yaml"),
                    title = "旧缩谱",
                    legacyScore = nested,
                )
            )
        )
        val rawYaml = Yaml(
            configuration = YamlConfiguration(
                encodeDefaults = false,
                strictMode = false,
                polymorphismStyle = PolymorphismStyle.Property,
            )
        ).encodeToString(StorageScore.serializer(), legacy)

        val loaded = ScoreSerializer.fromYaml(rawYaml).reductions.single()

        assertNull(loaded.legacyScore)
        assertEquals(nested, loaded.notationScore)
        assertEquals(ReductionLayerKind.entries, loaded.layers.map { it.kind })
    }

    @Test
    fun capturesLocalFragmentAndPlacesIndependentCopy() {
        val source = StorageScore.create(StorageScore.CreationOptions(measureCount = 4))
        var reduction = ReductionEngine.createFixed(source, "缩谱", listOf(Clef.TREBLE))
        val voice = reduction.notationScore.voiceTracks.values.single()
        val pitch = StoragePitchEvent.single(TimeCode.of(1, Fraction.ZERO), Pitch.C4)
        val event = StorageVoiceEvent.create(pitch.onset, pitch.id, Duration.QUARTER)
        reduction = reduction.updateLayerScore(
            ReductionLayerKind.NOTATION,
            reduction.notationScore
                .addPitchEvent(voice.pitchTrackId, pitch)
                .addVoiceEvent(voice.id, event),
        )

        val fragment = assertNotNull(
            ReductionWorkspaceEngine.captureFragment(
                reduction,
                setOf(NoteRef(event.id, 0)),
                "主题 A",
            )
        )
        assertEquals(1, fragment.score.voiceTracks.values.sumOf { it.events.size })
        assertEquals(1, fragment.score.voiceTracks.values.flatMap { it.events }.single().onset.measure)
        assertEquals(1, fragment.sourceMetadata?.originalRange?.start?.measure)

        reduction = reduction.copy(materialTray = listOf(fragment))
        assertNull(ReductionWorkspaceEngine.placeFragment(reduction, fragment.id, 1))
        val placed = assertNotNull(ReductionWorkspaceEngine.placeFragment(reduction, fragment.id, 2))
        val events = placed.reduction.notationScore.voiceTracks.values.flatMap { it.events }.sortedBy { it.onset }
        assertEquals(listOf(1, 2), events.map { it.onset.measure })
        assertNotEquals(events[0].id, events[1].id)
        assertEquals(1, placed.copiedEvents)
        assertEquals(listOf(fragment), placed.reduction.materialTray)
    }
}
