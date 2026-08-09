package com.mecon.desktop

import com.mecon.api.interaction.MeasureStaffSection
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.events.RuntimePitchEvent
import com.mecon.api.runtime.events.RuntimeVoiceEvent
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CutCommandTest {

    @Test
    fun measureSelectionCutsItsNotesWithoutDeletingTheMeasure() {
        val empty = RuntimeScore.fromStorage(StorageScore.create(StorageScore.CreationOptions(title = "", measureCount = 2)))
        val staffTrackId = empty.staffTracks.keys.first()
        val voiceTrackId = empty.voiceTracks.keys.first()
        val pitchTrackId = empty.pitchTracks.keys.first()
        val onset = TimeCode.ofMeasure(1)
        val pitchEvent = RuntimePitchEvent(EventId("cut-pitch"), onset, listOf(Pitch.C4))
        val voiceEvent = RuntimeVoiceEvent(EventId("cut-note"), onset, pitchEvent, Duration.QUARTER)
        val runtime = empty
            .addPitchEvent(pitchTrackId, pitchEvent)
            .addVoiceEvent(voiceTrackId, voiceEvent)

        val deletions = buildCutDeletions(
            selection = setOf(MeasureStaffSection(staffTrackId, 1)),
            runtime = runtime,
            computed = computeScore(runtime),
        )

        assertEquals(1, deletions.size)
        assertEquals(voiceTrackId, deletions.single().voiceTrackId)
        assertEquals(voiceEvent.id, deletions.single().eventId)
        assertNull(deletions.single().pitchIndices, "whole note event should be cut, not the measure")
    }
}
