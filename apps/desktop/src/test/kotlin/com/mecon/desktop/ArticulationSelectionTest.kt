package com.mecon.desktop

import com.mecon.api.interaction.VoiceArticulationSection
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticulationSelectionTest {

    @Test
    fun selectedArticulationResolvesBackToItsOwningNote() {
        val empty = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 1)))
        val voiceTrackId = empty.voiceTracks.keys.first()
        val pitchTrackId = empty.pitchTracks.keys.first()
        val onset = TimeCode.ofMeasure(1)
        val pitchEvent = RuntimePitchEvent(
            EventId("articulation-pitch"), onset, listOf(Pitch.C4), listOf(Articulation.STACCATO),
        )
        val voiceEvent = RuntimeVoiceEvent(
            EventId("articulation-note"), onset, pitchEvent, Duration.QUARTER,
        )
        val runtime = empty
            .addPitchEvent(pitchTrackId, pitchEvent)
            .addVoiceEvent(voiceTrackId, voiceEvent)
        val computed = computeScore(runtime)
        val event = computed.getComputedEvent(voiceEvent.id)!!

        val selected = selectedEvents(
            selection = setOf(VoiceArticulationSection(event, index = 0)),
            runtime = runtime,
            computed = computed,
        )

        assertEquals(listOf(event.id), selected.map { it.id })
    }
}
