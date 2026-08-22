package com.mecon.desktop

import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.MeasureStaffSection
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.service.ScoreSession
import com.mecon.desktop.service.replaceDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteCommandTest {

    @Test
    fun measureSelectionDeleteClearsNotesWithoutDeletingMeasure() = runBlocking {
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
            val voiceTrackId = storage.voiceTracks.keys.single()
            val session = ScoreSession(desktopScope)
            session.replaceDocument(storage, file = null, fileName = "Delete.mecon")
            session.applyNoteEdit(
                NoteEditEngine.Insertion(
                    voiceTrackId = voiceTrackId,
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration.QUARTER,
                    pitch = Pitch.C4,
                ),
            )
            withTimeout(5_000) {
                while (session.runtimeScore?.getVoiceTrack(voiceTrackId)?.events?.toList()
                        ?.any { !it.isRest } != true
                ) {
                    kotlinx.coroutines.delay(10)
                }
            }

            val staffTrackId = session.runtimeScore!!.staffTracks.keys.single()
            val selectionAfterDelete = CompletableDeferred<Set<EventSection>>()
            deleteScoreSelection(
                session = session,
                selection = setOf(MeasureStaffSection(staffTrackId, 1)),
                onSelectionChange = selectionAfterDelete::complete,
                onAnnotationSelectionChange = {},
                onApplyExpressionResult = {},
            )
            withTimeout(5_000) { selectionAfterDelete.await() }

            assertEquals(2, session.runtimeScore!!.measures.size)
            assertTrue(
                session.runtimeScore!!.getVoiceTrack(voiceTrackId)!!.events.toList()
                    .none { !it.isRest && it.onset.measure == 1 },
            )
        } finally {
            desktopScope.cancel()
        }
    }
}
