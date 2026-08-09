package com.mecon.desktop.input

import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.desktop.paletteInfoFor
import com.mecon.desktop.service.HarmonyPracticeScoreHost
import com.mecon.desktop.ui.components.NoteToolState
import com.mecon.desktop.ui.exploration.initialWorkspace
import com.mecon.exploration.VoicePlanScoreAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreSelectionEditorTest {
    @Test
    fun practiceSelectionUsesSharedDurationAndAccidentalEdits() = runBlocking {
        val workspace = initialWorkspace(4)
        val voiceId = workspace.voices.first().id
        val empty = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val inserted = NoteEditEngine.insert(
            empty,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = TimeCode.ofMeasure(1),
                duration = Duration.QUARTER,
                pitch = Pitch.C5,
            ),
            NoteEditEngine.InsertionPolicy.CHORDAL,
        )!!
        val eventId = inserted.insertedEventId!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HarmonyPracticeScoreHost(
            scope,
            inserted.score,
            computeScore(inserted.score),
            workspace,
        )
        var selection: Set<EventSection> = setOf(
            VoiceEventSection(host.computedScore!!.getComputedEvent(eventId)!!)
        )
        val commits = Channel<Unit>(Channel.UNLIMITED)
        val editor = ScoreSelectionEditor(
            host = host,
            noteTool = NoteToolState(),
            selection = { selection },
            selectionInfo = {
                paletteInfoFor(selection, host.runtimeScore, host.computedScore)
            },
            onAfterEdit = {
                selection = it
                commits.trySend(Unit)
            },
        )

        editor.editDurationBase(DurationBase.HALF)
        withTimeout(5_000) { commits.receive() }
        assertEquals(
            DurationBase.HALF,
            host.runtimeScore.voiceTracks.getValue(voiceId).events
                .first { !it.isRest }.duration.base,
        )

        editor.editAccidental(Accidental.SHARP)
        withTimeout(5_000) { commits.receive() }
        val editedEvent = selection.filterIsInstance<VoiceEventSection>().single().event
        assertEquals(
            Accidental.SHARP,
            editedEvent.pitchData.single().effectiveAccidental,
        )
        scope.cancel()
    }
}
