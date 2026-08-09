package com.mecon.desktop.service

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.StaffLayoutPreset
import com.mecon.api.runtime.orderedStaffs
import com.mecon.core.engine.edit.NoteEditEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScoreSessionSharedEditingTest {
    @Test
    fun desktopInsertionAndTransposeUseSharedSessionAndOneHistory() = runBlocking {
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
            val voiceId = storage.voiceTracks.keys.single()
            val session = ScoreSession(desktopScope)
            session.replaceDocument(storage, file = null, fileName = "Shared.mecon")
            assertNotNull(session.sharedEditingSession)

            session.applyNoteEdit(
                NoteEditEngine.Insertion(
                    voiceTrackId = voiceId,
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration.QUARTER,
                    pitch = Pitch.C4,
                ),
            )
            await {
                session.runtimeScore?.voiceTracks?.get(voiceId)?.events?.toList()
                    ?.any { !it.isRest && it.pitches.singleOrNull() == Pitch.C4 } == true
            }
            val event = session.runtimeScore!!.voiceTracks.getValue(voiceId).events.toList()
                .first { !it.isRest && it.pitches.singleOrNull() == Pitch.C4 }
            assertEquals(Pitch.C4, event.pitches.single())
            assertTrue(session.canUndo)

            session.applyNoteTranspose(
                targets = listOf(NoteEditEngine.TransposeTarget(voiceId, event.id)),
                stepDelta = 1,
            )
            await {
                session.runtimeScore?.voiceTracks?.get(voiceId)?.events?.toList()
                    ?.any { !it.isRest && it.pitches.singleOrNull() == Pitch.D4 } == true
            }

            session.undo()
            await {
                session.runtimeScore?.voiceTracks?.get(voiceId)?.events?.toList()
                    ?.any { !it.isRest && it.pitches.singleOrNull() == Pitch.C4 } == true
            }
        } finally {
            desktopScope.cancel()
        }
    }

    @Test
    fun desktopCopyPasteUsesSharedSessionClipboard() = runBlocking {
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
            val voiceId = storage.voiceTracks.keys.single()
            val session = ScoreSession(desktopScope)
            session.replaceDocument(storage, file = null, fileName = "Clipboard.mecon")
            session.applyNoteEdit(
                NoteEditEngine.Insertion(
                    voiceTrackId = voiceId,
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration.QUARTER,
                    pitch = Pitch.C4,
                ),
            )
            await {
                session.runtimeScore?.voiceTracks?.get(voiceId)?.events?.toList()
                    ?.count { !it.isRest && it.pitches.singleOrNull() == Pitch.C4 } == 1
            }
            val source = session.runtimeScore!!.voiceTracks.getValue(voiceId).events.toList()
                .first { !it.isRest }
            val copied = CompletableDeferred<Boolean>()
            session.applySharedNoteCopy(
                listOf(NoteEditEngine.CopyTarget(voiceId, source.id)),
                copied::complete,
            )
            assertTrue(copied.await())

            val pasted = CompletableDeferred<Unit>()
            session.applySharedNotePaste(
                NoteEditEngine.PasteTarget(voiceId, TimeCode.of(2, Fraction.ZERO)),
            ) { pasted.complete(Unit) }
            pasted.await()
            assertEquals(
                2,
                session.runtimeScore!!.voiceTracks.getValue(voiceId).events.toList()
                    .count { !it.isRest && it.pitches.singleOrNull() == Pitch.C4 },
            )

            session.undo()
            await {
                session.runtimeScore?.voiceTracks?.get(voiceId)?.events?.toList()
                    ?.count { !it.isRest && it.pitches.singleOrNull() == Pitch.C4 } == 1
            }
        } finally {
            desktopScope.cancel()
        }
    }

    @Test
    fun desktopVoiceMoveUsesSharedSessionAcrossStaves() = runBlocking {
        val desktopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = StorageScore.create(
                StorageScore.CreationOptions(layout = StaffLayoutPreset.PIANO_GRAND, measureCount = 1),
            )
            val session = ScoreSession(desktopScope)
            session.replaceDocument(storage, file = null, fileName = "VoiceMove.mecon")
            val (upper, lower) = session.runtimeScore!!.orderedStaffs()
            val upperVoice = upper.voiceTracks.single()
            val lowerVoice = lower.voiceTracks.single()
            session.applyNoteEdit(
                NoteEditEngine.Insertion(
                    voiceTrackId = upperVoice.id,
                    start = TimeCode.ofMeasure(1),
                    duration = Duration.QUARTER,
                    pitch = Pitch.C4,
                ),
            )
            await { upperVoice.id.let { id ->
                session.runtimeScore?.getVoiceTrack(id)?.events?.toList()?.any { !it.isRest } == true
            } }
            val source = session.runtimeScore!!.getVoiceTrack(upperVoice.id)!!.events.toList().first { !it.isRest }
            val moved = CompletableDeferred<Unit>()
            session.applyVoiceMove(
                listOf(
                    NoteEditEngine.VoiceMoveTarget(
                        upperVoice.id,
                        source.id,
                        lowerVoice.voiceNumber,
                        targetStaffId = lower.id,
                    ),
                ),
            ) { moved.complete(Unit) }
            moved.await()

            assertTrue(session.runtimeScore!!.getVoiceTrack(upperVoice.id)!!.events.toList().none { !it.isRest })
            assertEquals(
                listOf(Pitch.C4),
                session.runtimeScore!!.getVoiceTrack(lowerVoice.id)!!.events.toList()
                    .filterNot { it.isRest }
                    .flatMap { it.pitches },
            )
        } finally {
            desktopScope.cancel()
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }
}
