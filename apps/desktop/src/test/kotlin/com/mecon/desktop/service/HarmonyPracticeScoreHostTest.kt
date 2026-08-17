package com.mecon.desktop.service

import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.features.freepractice.PracticeTimelineEdit
import com.mecon.desktop.ui.exploration.initialWorkspace
import com.mecon.desktop.voiceTrackIdOf
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.exploration.FreePracticeDocument
import com.mecon.exploration.FreePracticeSettings
import com.mecon.exploration.PracticeNoteConstraintState
import com.mecon.theory.freepractice.HarmonyWorkspaceCommand
import com.mecon.theory.freepractice.HarmonyWorkspaceEditor
import com.mecon.theory.freepractice.FreePracticeMaterialProjector
import com.mecon.theory.freepractice.VoiceAssignmentSource
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.freepractice.WorkspaceChordChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarmonyPracticeScoreHostTest {
    @Test
    fun openingHostPreservesTheCompletePracticeDocument() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val initial = initialWorkspace(4, ModulationKey(0, KeySignatureMode.MAJOR))
            val runtime = RuntimeScore.fromStorage(
                VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR),
            )
            val document = FreePracticeDocument(
                settings = FreePracticeSettings(
                    polyphonyLimit = 4,
                    defaultChordDuration = Fraction.HALF,
                ),
                workspace = initial,
                noteConstraints = PracticeNoteConstraintState(
                    lockedVoiceTrackIds = setOf(initial.voices.first().id),
                ),
            )

            val host = HarmonyPracticeScoreHost(
                scope,
                runtime,
                computeScore(runtime),
                initial,
                initialDocument = document,
            )

            assertEquals(document, host.practiceDocument)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun initialTimelinePublishesTheEmptyBeatBeforeTheFinalBarline() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val initial = initialWorkspace(4, ModulationKey(0, KeySignatureMode.MAJOR))
            val runtime = RuntimeScore.fromStorage(
                VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR),
            )
            val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), initial)

            assertEquals(Fraction.QUARTER, host.practiceTimeline.slots.single().duration)
            assertEquals(Fraction.HALF, host.practiceTimeline.end)
            assertEquals(
                listOf(Fraction.QUARTER to Fraction.QUARTER),
                host.practiceTimeline.emptySlots.map { it.onset to it.duration },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun insertingAuthenticCadenceRewritesEveryIdiomChordInsteadOfKeepingOldTonic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val key = ModulationKey(0, KeySignatureMode.MAJOR)
            val initial = initialWorkspace(4, key)
            val runtime = RuntimeScore.fromStorage(
                VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR),
            )
            val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), initial)
            val initialCompleted = CompletableDeferred<String?>()
            host.commitWorkspaceWithAutoWriting(
                workspace = initial,
                triggerSlotId = initial.slots.single().id,
                configuredBacktrack = 0,
                fallbackKey = key,
                onComplete = initialCompleted::complete,
            )
            assertEquals("自动写作完成", withTimeout(20_000) { initialCompleted.await() })
            val beforeIdiomWorkspace = host.workspace
            val beforeIdiomScore = host.runtimeScore

            val choices = ChordSelectionCatalog.choices(key)
            val dominant = choices.first { it.functionalSymbol == "V" }
            val tonic = choices.first { it.functionalSymbol == "I" }
            val inserted = HarmonyWorkspaceEditor.apply(
                host.workspace,
                HarmonyWorkspaceCommand.InsertIdiom(
                    onset = Fraction.ZERO,
                    definitionId = "schoenberg.authentic-cadence",
                    variantId = "V-I",
                    sourceExerciseId = "schoenberg.cadence",
                    sourceChapterId = "schoenberg.cadence",
                    tonalLayoutId = host.workspace.tonalLayouts.single().id,
                    chordIdentities = emptyList(),
                    durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
                    chordChoices = listOf(exactChoice(dominant), exactChoice(tonic)),
                ),
            )
            val idiom = inserted.idiomInstances.single()
            val completed = CompletableDeferred<String?>()

            host.commitWorkspaceWithAutoWriting(
                workspace = inserted,
                triggerSlotId = idiom.slotIds.last(),
                configuredBacktrack = 0,
                fallbackKey = key,
                requiredSlotIds = idiom.slotIds,
                onComplete = completed::complete,
            )
            assertEquals("自动写作完成", withTimeout(20_000) { completed.await() })

            val projection = FreePracticeMaterialProjector.project(host.workspace, host.runtimeScore)
            listOf(dominant, tonic).forEachIndexed { index, expected ->
                val actual = projection.pitchesBySlotAndVoice.getValue(host.workspace.slots[index].id)
                    .values
                    .mapTo(linkedSetOf()) { it.pitchClass.value }
                assertTrue(actual.isNotEmpty())
                assertTrue(actual.all { it in expected.pitchClasses }, "slot=$index actual=$actual")
            }
            assertEquals(idiom.slotIds, host.practiceWritingState.lastScope)

            host.undo()

            assertEquals(beforeIdiomWorkspace, host.workspace)
            assertEquals(beforeIdiomScore, host.runtimeScore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun insertingIdiomAfterBlankChordAlsoWritesInitialTonic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val key = ModulationKey(0, KeySignatureMode.MAJOR)
            val initial = initialWorkspace(4, key)
            val runtime = RuntimeScore.fromStorage(
                VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR),
            )
            val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), initial)
            val withBlankChord = HarmonyWorkspaceEditor.apply(
                initial,
                HarmonyWorkspaceCommand.InsertChordRange(
                    onset = Fraction.QUARTER,
                    duration = Fraction.QUARTER,
                ),
            )
            val choices = ChordSelectionCatalog.choices(key)
            val dominant = choices.first { it.functionalSymbol == "V" }
            val tonic = choices.first { it.functionalSymbol == "I" }
            val inserted = HarmonyWorkspaceEditor.apply(
                withBlankChord,
                HarmonyWorkspaceCommand.InsertIdiom(
                    onset = Fraction.QUARTER,
                    definitionId = "schoenberg.authentic-cadence",
                    variantId = "V-I",
                    sourceExerciseId = "schoenberg.cadence",
                    sourceChapterId = "schoenberg.cadence",
                    tonalLayoutId = initial.tonalLayouts.single().id,
                    chordIdentities = emptyList(),
                    durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
                    chordChoices = listOf(exactChoice(dominant), exactChoice(tonic)),
                ),
            )
            val idiom = inserted.idiomInstances.single()
            val completed = CompletableDeferred<String?>()

            host.commitWorkspaceWithAutoWriting(
                workspace = inserted,
                triggerSlotId = idiom.slotIds.last(),
                configuredBacktrack = 0,
                fallbackKey = key,
                requiredSlotIds = idiom.slotIds,
                onComplete = completed::complete,
            )
            assertEquals("自动写作完成", withTimeout(20_000) { completed.await() })

            val projection = FreePracticeMaterialProjector.project(host.workspace, host.runtimeScore)
            val firstSlot = host.workspace.slots.first()
            val initialPitches = projection.pitchesBySlotAndVoice.getValue(firstSlot.id)
                .values
                .mapTo(linkedSetOf()) { it.pitchClass.value }
            assertTrue(initialPitches.isNotEmpty())
            assertTrue(initialPitches.all { it in tonic.pitchClasses })
            assertEquals(host.workspace.slots.map { it.id }, host.practiceWritingState.lastScope)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun insertingChordWritesInitialPinnedTonicAndNewChordAsOneUndoEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val initial = initialWorkspace(4)
            val runtime = RuntimeScore.fromStorage(
                VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR),
            )
            val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), initial)
            val key = ModulationKey(0, KeySignatureMode.MAJOR)
            val dominant = ChordSelectionCatalog.choices(key).first { it.functionalSymbol == "V" }
            val updated = HarmonyWorkspaceEditor.apply(
                initial,
                HarmonyWorkspaceCommand.InsertChordRange(
                    onset = Fraction.QUARTER,
                    duration = Fraction.QUARTER,
                    chordChoice = WorkspaceChordChoice.of(dominant.pitchClasses, dominant.origin),
                ),
            )
            val completed = CompletableDeferred<String?>()

            host.commitWorkspaceWithAutoWriting(
                workspace = updated,
                triggerSlotId = updated.slots.last().id,
                configuredBacktrack = 0,
                fallbackKey = key,
                onComplete = completed::complete,
            )
            assertEquals("自动写作完成", withTimeout(20_000) { completed.await() })

            assertEquals(dominant.pitchClasses.sorted(), host.workspace.slots.last().chordChoice?.pitchClasses)
            assertTrue(host.runtimeScore.getAllVoiceEvents().any { !it.isRest })
            assertTrue(host.canUndo)

            host.undo()

            assertEquals(initial, host.workspace)
            assertTrue(host.runtimeScore.getAllVoiceEvents().none { !it.isRest })
            assertFalse(host.canUndo)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun workspaceOnlyCommitPublishesUndoAndRedo() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initialWorkspace = initialWorkspace(4)
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initialWorkspace, KeySignature.C_MAJOR)
        )
        val host = HarmonyPracticeScoreHost(
            parentScope = scope,
            initialRuntimeScore = runtime,
            initialComputedScore = computeScore(runtime),
            initialWorkspace = initialWorkspace,
        )
        val updated = initialWorkspace.copy(
            slots = initialWorkspace.slots.mapIndexed { index, slot ->
                if (index == 0) slot.copy(
                    chordInterpretationRef = dominantRef(),
                    chordChoice = null,
                ) else slot
            }
        )

        host.commit(runtime, host.computedScore, updated)
        assertTrue(host.canUndo)
        assertEquals(dominantRef(), host.workspace.slots.first().chordInterpretationRef)

        host.undo()
        assertEquals(initialWorkspace.slots.first().chordIdentity, host.workspace.slots.first().chordIdentity)
        assertFalse(host.canUndo)
        assertTrue(host.canRedo)

        host.redo()
        assertEquals(dominantRef(), host.workspace.slots.first().chordInterpretationRef)
        scope.cancel()
    }

    /** Every gesture that follows a committed drag must still preview, undo included. */
    @Test
    fun timelinePreviewSurvivesCommitsAndUndo() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = initialWorkspace(4)
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )
        val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), initial)
        val inserted = requireNotNull(host.insertChordRange(Fraction.QUARTER, Fraction.QUARTER))
        val translate = PracticeTimelineEdit.TranslateChordRange(inserted, Fraction(1, 8), false)

        assertTrue(host.previewTimelineEdit(translate).accepted)
        assertTrue(host.commitTimelineEdit(translate))
        assertTrue(host.previewTimelineEdit(translate).accepted)

        host.undo()
        val afterUndo = host.previewTimelineEdit(translate)
        assertTrue(afterUndo.accepted, afterUndo.reasonKey)
        scope.cancel()
    }

    /**
     * The shell renders [HarmonyPracticeScoreHost.practiceWorkspace], so it must be the workspace the
     * session edits against: the pending auto-writing edit while a solve runs, and the committed
     * state again the moment an undo discards that solve. Following the committed workspace alone
     * left the timeline drawing chords the session had dropped, and the next drag's preview then
     * resolved against a different origin and looked frozen.
     */
    @Test
    fun practiceWorkspaceFollowsTheSessionEditBaseWhileWritingIsPending() {
        val scope = CoroutineScope(SupervisorJob() + NeverRunningDispatcher)
        val initial = initialWorkspace(4)
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )
        val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), initial)
        val slotId = initial.slots.single().id
        val translate = PracticeTimelineEdit.TranslateChordRange(slotId, Fraction(1, 8), false)

        assertTrue(host.commitTimelineEdit(translate))
        val moved = initial.slots.single().onset + Fraction(1, 8)
        assertEquals(moved, host.practiceWorkspace.slots.single().onset)
        assertEquals(initial.slots.single().onset, host.workspace.slots.single().onset)
        assertTrue(host.hasPendingWorkspaceCommit)

        host.undo()
        assertEquals(host.workspace, host.practiceWorkspace)
        assertEquals(initial.slots.single().onset, host.practiceWorkspace.slots.single().onset)
        scope.cancel()
    }

    /** Keeps background work queued so a pending solve can be observed from the test thread. */
    private object NeverRunningDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
        override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = true
    }

    @Test
    fun practiceHostsKeepIndependentHistoryStacks() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initialWorkspace = initialWorkspace(4)
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initialWorkspace, KeySignature.C_MAJOR)
        )
        val computed = computeScore(runtime)
        val first = HarmonyPracticeScoreHost(scope, runtime, computed, initialWorkspace)
        val second = HarmonyPracticeScoreHost(scope, runtime, computed, initialWorkspace)
        val updated = initialWorkspace.copy(
            slots = initialWorkspace.slots.mapIndexed { index, slot ->
                if (index == 0) slot.copy(
                    chordInterpretationRef = dominantRef(),
                    chordChoice = null,
                ) else slot
            }
        )

        first.commit(runtime, computed, updated)

        assertTrue(first.canUndo)
        assertFalse(second.canUndo)
        second.undo()
        assertEquals(
            initialWorkspace.slots.first().chordIdentity,
            second.workspace.slots.first().chordIdentity,
        )
        assertEquals(dominantRef(), first.workspace.slots.first().chordInterpretationRef)
        scope.cancel()
    }

    @Test
    fun multiGridBoundaryDragIsCommittedAsOneUndoStep() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seeded = HarmonyWorkspaceEditor.apply(
            initialWorkspace(4),
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.QUARTER,
                duration = Fraction.QUARTER,
                chordIdentity = "V",
            ),
        )
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(seeded, KeySignature.C_MAJOR)
        )
        val computed = computeScore(runtime)
        val host = HarmonyPracticeScoreHost(scope, runtime, computed, seeded)
        val finalPreview = HarmonyWorkspaceEditor.apply(
            seeded,
            HarmonyWorkspaceCommand.MoveBoundaryWithFollowing(
                leftIndex = 0,
                boundary = Fraction(3, 8),
            ),
        )

        host.commit(runtime, computed, finalPreview)
        assertEquals(Fraction(3, 8), host.workspace.slots[0].duration)

        host.undo()
        assertEquals(seeded.slots, host.workspace.slots)
        assertFalse(host.canUndo)
        scope.cancel()
    }

    /**
     * The timeline keeps its gesture preview on screen until the commit reaches it, because the
     * workspace is only synchronised back to the shell by a later effect. That handover needs the
     * commit to say whether it was accepted, or a rejected edit would leave the preview frozen.
     */
    @Test
    fun timelineCommitReportsWhetherTheSessionAcceptedTheEdit() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seeded = HarmonyWorkspaceEditor.apply(
            initialWorkspace(4),
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.QUARTER,
                duration = Fraction.QUARTER,
                chordIdentity = "V",
            ),
        )
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(seeded, KeySignature.C_MAJOR)
        )
        val host = HarmonyPracticeScoreHost(scope, runtime, computeScore(runtime), seeded)
        val moved = host.commitTimelineEdit(
            PracticeTimelineEdit.PlaceChordRange(
                seeded.slots[1].id,
                Fraction(3, 8),
                Fraction.QUARTER,
            ),
        )
        assertTrue(moved)
        assertEquals(Fraction(3, 8), host.workspace.slots[1].onset)

        val missing = host.commitTimelineEdit(
            PracticeTimelineEdit.PlaceChordRange(
                com.mecon.theory.freepractice.WorkspaceSlotId("no-such-slot"),
                Fraction.ZERO,
                Fraction.QUARTER,
            ),
        )
        assertFalse(missing)
        scope.cancel()
    }

    @Test
    fun consecutiveChordInsertionsUndoOneAtATime() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = initialWorkspace(4)
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )
        val computed = computeScore(runtime)
        val host = HarmonyPracticeScoreHost(scope, runtime, computed, initial)
        val afterFirst = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.QUARTER,
                duration = Fraction.QUARTER,
                chordIdentity = "V",
            ),
        )
        host.commit(runtime, computed, afterFirst)
        val afterSecond = HarmonyWorkspaceEditor.apply(
            afterFirst,
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.HALF,
                duration = Fraction.QUARTER,
                chordIdentity = "I",
            ),
        )
        host.commit(runtime, computed, afterSecond)

        host.undo()

        assertEquals(afterFirst.slots, host.workspace.slots)
        assertTrue(host.canUndo)
        scope.cancel()
    }

    @Test
    fun identicalPracticeTransactionDoesNotCreateEmptyUndoEntry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = initialWorkspace(4)
        val runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )
        val computed = computeScore(runtime)
        val host = HarmonyPracticeScoreHost(scope, runtime, computed, initial)
        val updated = initial.copy(
            slots = initial.slots.mapIndexed { index, slot ->
                if (index == 0) slot.copy(
                    chordInterpretationRef = dominantRef(),
                    chordChoice = null,
                ) else slot
            }
        )

        host.commit(runtime, computed, updated)
        host.commit(runtime, computed, updated)
        host.undo()

        assertEquals(initial, host.workspace)
        assertFalse(host.canUndo)
        scope.cancel()
    }

    @Test
    fun reassignVoicesSwapsEventsAcrossSingleVoiceStavesWithoutChangingIds() {
        val workspace = initialWorkspace(4)
        var runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val upperVoice = workspace.voices[0].id
        val lowerVoice = workspace.voices[1].id
        val upper = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = upperVoice,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C5,
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )!!
        runtime = upper.score
        val lower = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = lowerVoice,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )!!

        val swapped = NoteEditEngine.reassignVoices(
            lower.score,
            mapOf(
                upper.insertedEventId!! to lowerVoice,
                lower.insertedEventId!! to upperVoice,
            ),
        )!!

        assertTrue(
            swapped.score.voiceTracks.getValue(lowerVoice).events
                .any { it.id == upper.insertedEventId }
        )
        assertTrue(
            swapped.score.voiceTracks.getValue(upperVoice).events
                .any { it.id == lower.insertedEventId }
        )
    }

    @Test
    fun deletingSelectedPianoRollNoteReplacesItWithRestAndClearsAssignmentSource() = runBlocking {
        val initial = initialWorkspace(4)
        val upperVoice = initial.voices[0].id
        val emptyRuntime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(initial, KeySignature.C_MAJOR)
        )
        val inserted = NoteEditEngine.insert(
            emptyRuntime,
            NoteEditEngine.Insertion(
                voiceTrackId = upperVoice,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C5,
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )!!
        val eventId = inserted.insertedEventId!!
        val seeded = initial.copy(
            voiceAssignmentSources = mapOf(eventId to VoiceAssignmentSource.MANUAL),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HarmonyPracticeScoreHost(scope, inserted.score, computeScore(inserted.score), seeded)
        val completed = CompletableDeferred<Unit>()

        host.applyNoteDeletes(
            listOf(NoteEditEngine.Deletion(upperVoice, eventId)),
        ) { completed.complete(Unit) }
        withTimeout(5_000) { completed.await() }

        assertTrue(host.runtimeScore.voiceTracks.getValue(upperVoice).events.any { it.isRest })
        assertTrue(host.runtimeScore.getAllVoiceEvents().none { it.id == eventId })
        assertFalse(eventId in host.workspace.voiceAssignmentSources)
        scope.cancel()
    }

    @Test
    fun clickingAnotherVoiceSwapsOverlappingNotes() = runBlocking {
        val workspace = initialWorkspace(4)
        val upperVoice = workspace.voices[0].id
        val lowerVoice = workspace.voices[1].id
        var runtime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val upper = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = upperVoice,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C5,
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )!!
        runtime = upper.score
        val lower = NoteEditEngine.insert(
            runtime,
            NoteEditEngine.Insertion(
                voiceTrackId = lowerVoice,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C4,
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HarmonyPracticeScoreHost(
            scope,
            lower.score,
            computeScore(lower.score),
            workspace,
        )
        val completed = CompletableDeferred<Unit>()

        host.swapEventVoice(upper.insertedEventId!!, lowerVoice) {
            completed.complete(Unit)
        }
        withTimeout(5_000) { completed.await() }

        assertEquals(lowerVoice, host.runtimeScore.voiceTrackIdOf(upper.insertedEventId!!))
        assertEquals(upperVoice, host.runtimeScore.voiceTrackIdOf(lower.insertedEventId!!))
        scope.cancel()
    }

    @Test
    fun staffTransposePublishesToSharedScoreAndUndoHistory() = runBlocking {
        val workspace = initialWorkspace(4)
        val voiceId = workspace.voices[0].id
        val emptyRuntime = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val inserted = NoteEditEngine.insert(
            emptyRuntime,
            NoteEditEngine.Insertion(
                voiceTrackId = voiceId,
                start = TimeCode.of(1, Fraction.ZERO),
                duration = Duration.QUARTER,
                pitch = Pitch.C5,
            ),
            NoteEditEngine.InsertionPolicy.MONODIC,
        )!!
        val eventId = inserted.insertedEventId!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HarmonyPracticeScoreHost(
            scope,
            inserted.score,
            computeScore(inserted.score),
            workspace,
        )
        val completed = CompletableDeferred<Unit>()

        host.applyNoteTranspose(
            targets = listOf(NoteEditEngine.TransposeTarget(voiceId, eventId)),
            stepDelta = 1,
        ) { completed.complete(Unit) }
        withTimeout(5_000) { completed.await() }

        val movedPitch = host.runtimeScore.voiceTracks.getValue(voiceId).events
            .first { it.id == eventId }
            .pitches.single()
        assertEquals(Pitch.fromMidi(74), movedPitch)
        assertTrue(host.canUndo)

        host.undo()

        val restoredPitch = host.runtimeScore.voiceTracks.getValue(voiceId).events
            .first { it.id == eventId }
            .pitches.single()
        assertEquals(Pitch.C5, restoredPitch)
        scope.cancel()
    }

    private fun dominantRef() = requireNotNull(
        ChordSelectionCatalog
            .choices(ModulationKey(0, KeySignatureMode.MAJOR))
            .first { it.functionalSymbol == "V" }
            .confirmedInterpretationRef
    )

    private fun exactChoice(choice: com.mecon.theory.harmony.ChordSelectionChoice) =
        WorkspaceChordChoice.of(
            pitchClasses = choice.pitchClasses,
            origin = choice.origin,
            pinnedInterpretationRef = requireNotNull(choice.confirmedInterpretationRef),
        )
}
