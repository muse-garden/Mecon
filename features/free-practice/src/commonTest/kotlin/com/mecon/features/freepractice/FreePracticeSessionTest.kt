package com.mecon.features.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.exploration.PracticeHarmonicRole
import com.mecon.exploration.PracticeNoteheadRef
import com.mecon.features.scoreediting.ScoreEditEffectKind
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.features.scoreediting.eventIdOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceIdiomInstance
import com.mecon.theory.freepractice.WorkspaceIdiomInstanceId
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.freepractice.WorkspaceTonalLayoutId
import com.mecon.theory.freepractice.VoiceAssignmentSource
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey

class FreePracticeSessionTest {
    @Test
    fun harmonicRoleIsPersistentUndoableAndFiltersChordChoices() {
        val session = session()
        val initial = session.frame()
        val inserted = session.dispatch(FreePracticeIntent.Score(
            initial.revision,
            ScoreEditIntent.InsertNote(
                initial.score.revision,
                initial.document.workspace.voices.first().id,
                TimeCode.of(1, Fraction.ZERO),
                Duration.QUARTER,
                Pitch.C5,
            ),
        ))
        val eventId = requireNotNull(inserted.frame.score.selection.single().eventIdOrNull)
        val ref = PracticeNoteheadRef(eventId, 0)
        val marked = session.dispatch(FreePracticeIntent.SetHarmonicRole(
            inserted.frame.revision,
            setOf(ref),
            PracticeHarmonicRole.CHORD_TONE,
        ))

        assertEquals(PracticeHarmonicRole.CHORD_TONE, marked.frame.document.noteConstraints.harmonicRole(ref))
        assertFalse(marked.frame.noteConstraints.noteheads.single { it.notehead == ref }.conflict)

        val filtered = session.dispatch(FreePracticeIntent.SetHarmonicRoleFilters(
            marked.frame.revision,
            chordCatalogEnabled = true,
            idiomCatalogEnabled = false,
        ))
        assertTrue(filtered.frame.catalog.chordChoices.all { 0 in it.choice.pitchClasses })
        assertTrue(filtered.frame.plan.chordCatalogFilters
            .flatMap { it.chordGroups }
            .flatMap { it.choices }
            .all { 0 in it.choice.pitchClasses })

        val bothFilters = session.dispatch(FreePracticeIntent.SetHarmonicRoleFilters(
            filtered.frame.revision,
            chordCatalogEnabled = true,
            idiomCatalogEnabled = true,
        ))
        val catalogResult = PracticeTeachingCatalogExecutor.execute(bothFilters.catalogRequests.single())
        val appliedCatalog = session.applyTeachingCatalogResult(catalogResult)
        assertTrue(appliedCatalog.frame.plan.idiomCatalog.definitions
            .flatMap { it.variants }
            .all { variant -> 0 in variant.chordChoices[variant.anchorStepIndex].pitchClasses })

        val undone = session.dispatch(FreePracticeIntent.Undo(appliedCatalog.frame.revision))
        assertEquals(null, undone.frame.document.noteConstraints.harmonicRole(ref))
    }

    @Test
    fun voiceLockIsDynamicAndWritingKeepsDenseMelodyEvents() {
        val session = session()
        fun insert(start: Fraction, pitch: Pitch): FreePracticeDispatchResult {
            val before = session.frame()
            return session.dispatch(FreePracticeIntent.Score(
                before.revision,
                ScoreEditIntent.InsertNote(
                    before.score.revision,
                    before.document.workspace.voices.first().id,
                    TimeCode.of(1, start),
                    Duration.EIGHTH,
                    pitch,
                ),
            ))
        }
        val first = insert(Fraction.ZERO, Pitch.fromMidi(60))
        val voiceId = first.frame.document.workspace.voices.first().id
        session.dispatch(FreePracticeIntent.SetVoiceLock(
            first.frame.revision,
            voiceId,
            true,
        ))
        val second = insert(Fraction(1, 8), Pitch.fromMidi(79))
        val locked = second
        assertEquals(setOf(voiceId), locked.frame.document.noteConstraints.lockedVoiceTrackIds)
        assertTrue(locked.frame.noteConstraints.noteheads.filter { item ->
            locked.frame.score.runtimeScore.voiceTracks.getValue(voiceId).events.any {
                it.id == item.notehead.eventId
            }
        }.all { it.locked })

        val slotId = locked.frame.document.workspace.slots.single().id
        val requested = session.dispatch(FreePracticeIntent.RunWriting(locked.frame.revision, slotId))
        val request = requested.requests.single()
        val originalEvents = RuntimeScore.fromStorage(request.score).voiceTracks.getValue(voiceId).events
            .filterNot { it.isRest }.map { it.id to it.pitches }.toList()
        val solved = FreePracticeBackgroundExecutor.execute(request)
        assertIs<PracticeWritingOutcome.Solved>(solved.outcome)
        assertEquals(2, solved.candidates.single().frames.size)
        val applied = session.applyBackgroundResult(solved)
        val preserved = applied.frame.score.runtimeScore.voiceTracks.getValue(voiceId).events
            .filterNot { it.isRest }.map { it.id to it.pitches }.toList()
        assertEquals(originalEvents, preserved)
        locked.frame.document.workspace.voices.drop(1).forEach { accompaniment ->
            val events = applied.frame.score.runtimeScore.voiceTracks.getValue(accompaniment.id)
                .events.filterNot { it.isRest }.toList()
            assertEquals(1, events.size, "one accompaniment attack per chord slot")
            assertEquals(Duration.QUARTER, events.single().duration)
        }
    }

    @Test
    fun nativeSopranoLaneDoesNotProduceFalseA4RangeFinding() {
        val session = session()
        val before = session.frame()
        val inserted = session.dispatch(FreePracticeIntent.Score(
            before.revision,
            ScoreEditIntent.InsertNote(
                before.score.revision,
                before.document.workspace.voices.first().id,
                TimeCode.of(1, Fraction.ZERO),
                Duration.QUARTER,
                Pitch.fromName("A4"),
            ),
        ))

        val findings = PracticeFindingComputer.compute(
            inserted.frame.document.workspace,
            inserted.frame.score.runtimeScore,
            PracticeFindingComputer.fallbackKey(inserted.frame.document),
        )

        assertTrue(findings.none { it.messageKey == "freePractice.finding.voiceRange" })
    }

    @Test
    fun selectingSecondChordCompletesLockedMelodyInOpeningTonicSlot() {
        val preset = FreePracticePreset.document()
        val opening = preset.workspace.slots.single()
        val second = opening.copy(
            id = com.mecon.theory.freepractice.WorkspaceSlotId("slot-1"),
            onset = Fraction.QUARTER,
            chordChoice = null,
        )
        val session = session(preset.copy(workspace = preset.workspace.copy(slots = listOf(opening, second))))
        val before = session.frame()
        val inserted = session.dispatch(FreePracticeIntent.Score(
            before.revision,
            ScoreEditIntent.InsertNote(
                before.score.revision,
                before.document.workspace.voices.first().id,
                TimeCode.of(1, Fraction.ZERO),
                Duration.EIGHTH,
                Pitch.fromName("C5"),
            ),
        ))
        val locked = session.dispatch(FreePracticeIntent.SetVoiceLock(
            inserted.frame.revision,
            inserted.frame.document.workspace.voices.first().id,
            true,
        ))
        val dominant = com.mecon.theory.harmony.ChordSelectionCatalog
            .choices(ModulationKey(0, KeySignatureMode.MAJOR))
            .first { it.functionalSymbol == "V" }
        val selected = session.dispatch(FreePracticeIntent.ReplaceChord(
            locked.frame.revision,
            second.id,
            WorkspaceChordChoice.of(
                dominant.pitchClasses,
                dominant.origin,
                requireNotNull(dominant.confirmedInterpretationRef),
                dominant.rootPitchClass,
            ),
        ))

        val request = selected.requests.single()
        assertEquals(listOf(opening.id, second.id), request.scopeSlotIds)
        val solved = FreePracticeBackgroundExecutor.execute(request)
        assertIs<PracticeWritingOutcome.Solved>(solved.outcome)
        val applied = session.applyBackgroundResult(solved)
        val accompanimentIds = applied.frame.document.workspace.voices.drop(1).map { it.id }.toSet()
        val openingAccompaniment = applied.frame.score.runtimeScore.voiceTracks.values
            .filter { it.id in accompanimentIds }
            .flatMap { it.events.toList() }
            .filterNot { it.isRest }
            .filter { it.onset == TimeCode.of(1, Fraction.ZERO) }
        assertEquals(accompanimentIds.size, openingAccompaniment.size)
    }

    @Test
    fun nestedScoreInsertUpdatesManualAssignmentOnTheSameUndoBoundary() {
        val session = session()
        val before = session.frame()
        val voiceId = before.document.workspace.voices.first().id
        val inserted = session.dispatch(
            FreePracticeIntent.Score(
                expectedRevision = before.revision,
                inner = ScoreEditIntent.InsertNote(
                    expectedRevision = before.score.revision,
                    voiceTrackId = voiceId,
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration.QUARTER,
                    pitch = Pitch.C5,
                ),
            ),
        )

        assertEquals(FreePracticeEffectKind.APPLIED, inserted.effect.kind)
        assertEquals(listOf(72), assertIs<PracticeEditPlayback.Audition>(inserted.editPlayback).midiNumbers)
        val eventId = requireNotNull(inserted.frame.score.selection.single().eventIdOrNull)
        assertEquals(VoiceAssignmentSource.MANUAL, inserted.frame.document.workspace.voiceAssignmentSources[eventId])

        val undone = session.dispatch(FreePracticeIntent.Undo(inserted.frame.revision))
        assertFalse(eventId in undone.frame.document.workspace.voiceAssignmentSources)
        assertFalse(undone.frame.score.runtimeScore.getAllVoiceEvents().any { it.id == eventId })
    }

    @Test
    fun nestedScoreInsertRejectsPolyphonyOverflowBeforeHistoryCommit() {
        val session = session(FreePracticePreset.document(voiceCount = 3))
        val before = session.frame()
        val voiceId = before.document.workspace.voices.first().id
        val rejected = session.dispatch(
            FreePracticeIntent.Score(
                expectedRevision = before.revision,
                inner = ScoreEditIntent.InsertChord(
                    expectedRevision = before.score.revision,
                    voiceTrackId = voiceId,
                    start = TimeCode.of(1, Fraction.ZERO),
                    duration = Duration.QUARTER,
                    pitches = listOf(72, 76, 79, 83).map(Pitch::fromMidi),
                ),
            ),
        )

        assertEquals(FreePracticeEffectKind.INVALID, rejected.effect.kind)
        assertEquals("freePractice.polyphonyLimitExceeded", rejected.effect.messageKey)
        assertEquals(before.revision, rejected.frame.revision)
        assertFalse(rejected.frame.score.canUndo)
        assertFalse(rejected.frame.score.runtimeScore.getAllVoiceEvents().any { !it.isRest })
    }

    @Test
    fun findingsRunOutsideFrameAndApplyThroughNewestWinsChannel() {
        val session = session()
        val initial = session.initialUpdate()
        assertTrue(initial.findings.stale)
        val request = initial.findingRequests.single()
        val result = PracticeFindingExecutor.execute(request)
        val applied = session.applyFindingResult(result)

        assertEquals(FreePracticeEffectKind.FINDINGS_UPDATED, applied.effect.kind)
        assertFalse(applied.frame.findings.stale)
        assertEquals(request.fingerprint, result.fingerprint)
        assertTrue(applied.findingRequests.isEmpty())

        val stale = session.applyFindingResult(result)
        assertEquals(FreePracticeEffectKind.STALE_BACKGROUND_RESULT, stale.effect.kind)
    }

    @Test
    fun teachingCatalogResultDrivesStableIdIdiomInsertAndReplace() {
        val session = session()
        val initial = session.initialUpdate()
        val request = initial.catalogRequests.single()
        val slot = initial.document.workspace.slots.single()
        val choice = requireNotNull(slot.chordChoice)
        fun variant(id: String) = PracticeIdiomVariantView(
            id = id,
            title = id,
            durations = List(3) { slot.duration },
            chordIdentities = List(3) { "I" },
            chordChoices = List(3) { choice },
        )
        val catalogApplied = session.applyTeachingCatalogResult(
            PracticeTeachingCatalogResult(
                requestId = request.requestId,
                baseRevision = request.baseRevision,
                fingerprint = request.fingerprint,
                definitions = listOf(
                    PracticeIdiomDefinitionView(
                        id = "fixture.idiom",
                        title = "Fixture",
                        sourceExerciseId = "fixture-exercise",
                        sourceChapterId = "fixture-chapter",
                        availableByDefault = true,
                        variants = listOf(variant("variant-a"), variant("variant-b")),
                    )
                ),
            )
        )
        assertEquals(FreePracticeEffectKind.CATALOG_UPDATED, catalogApplied.effect.kind)
        assertEquals(2, catalogApplied.frame.plan.idiomCatalog.definitions.single().variants.size)

        val inserted = session.dispatch(
            FreePracticeIntent.InsertIdiom(0, slot.id, "fixture.idiom", "variant-a")
        )
        assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, inserted.effect.kind)
        assertTrue(inserted.requests.single().replayWholeScope)
        val instanceId = inserted.frame.document.workspace.idiomInstances.single().id
        val projectedIdiom = inserted.frame.timeline.idioms.single()
        assertEquals("variant-a", projectedIdiom.title)
        assertEquals(slot.onset, projectedIdiom.start)
        assertEquals(slot.onset + slot.duration * 3, projectedIdiom.end)
        assertTrue(inserted.frame.timeline.slots.none { it.capabilities.canTranslate })
        assertTrue(inserted.frame.timeline.slots.none { it.capabilities.canRemove })
        val writingRequest = inserted.requests.single()
        val voices = writingRequest.document.workspace.voices.sortedBy { it.order }
        val applied = session.applyBackgroundResult(
            PracticeBackgroundResult(
                requestId = writingRequest.requestId,
                baseRevision = writingRequest.baseRevision,
                scopeFingerprint = writingRequest.scopeFingerprint,
                kind = writingRequest.kind,
                candidates = listOf(
                    PracticeVoicingCandidate(
                        frames = writingRequest.scopeSlotIds.map { slotId ->
                            PracticeVoicingFrame(
                                slotId,
                                voices.mapIndexed { index, voice ->
                                    voice.id to Pitch.fromMidi(72 - index * 7)
                                }.toMap(),
                            )
                        },
                        diversityGroupKey = "idiom-playback",
                        score = 0.0,
                    )
                ),
                outcome = PracticeWritingOutcome.Solved(writingRequest.scopeSlotIds, null),
            )
        )
        val insertedSlotIds = applied.frame.document.workspace.idiomInstances.single().slotIds
        val replay = assertIs<PracticeEditPlayback.Excerpt>(applied.editPlayback).range
        assertEquals(insertedSlotIds.first(), replay.firstSlotId)
        assertEquals(insertedSlotIds.last(), replay.lastSlotId)
        val tailSelected = session.dispatch(
            FreePracticeIntent.SelectSlot(applied.frame.revision, insertedSlotIds.last()),
        )
        val replaced = session.dispatch(
            FreePracticeIntent.ReplaceIdiom(
                tailSelected.frame.revision,
                instanceId,
                "fixture.idiom",
                "variant-b",
            )
        )
        assertEquals("variant-b", replaced.frame.document.workspace.idiomInstances.single().variantId)
        assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, replaced.effect.kind)
    }

    @Test
    fun selectingAnIdiomTailInsertsAContinuationInsteadOfReplacingThePreviousIdiom() {
        val preset = FreePracticePreset.document()
        val template = preset.workspace.slots.single()
        val slots = List(3) { index ->
            template.copy(
                id = com.mecon.theory.freepractice.WorkspaceSlotId("slot-$index"),
                onset = Fraction.QUARTER * index,
                duration = Fraction.QUARTER,
            )
        }
        val firstIdiomId = WorkspaceIdiomInstanceId("idiom-first")
        val session = session(
            preset.copy(
                settings = preset.settings.copy(
                    writing = preset.settings.writing.copy(autoWritingEnabled = false),
                ),
                workspace = preset.workspace.copy(
                    slots = slots,
                    idiomInstances = listOf(
                        WorkspaceIdiomInstance(
                            id = firstIdiomId,
                            definitionId = "fixture.first",
                            variantId = "fixture.first.variant",
                            sourceExerciseId = "fixture-exercise",
                            sourceChapterId = "fixture-chapter",
                            slotIds = slots.take(2).map { it.id },
                        ),
                    ),
                ),
            ),
        )
        val initial = session.initialUpdate()
        assertEquals(firstIdiomId, initial.selection.idiomInstanceId)

        val catalogRequest = initial.catalogRequests.single()
        val choice = requireNotNull(slots[1].chordChoice)
        session.applyTeachingCatalogResult(
            PracticeTeachingCatalogResult(
                requestId = catalogRequest.requestId,
                baseRevision = catalogRequest.baseRevision,
                fingerprint = catalogRequest.fingerprint,
                definitions = listOf(
                    PracticeIdiomDefinitionView(
                        id = "fixture.continuation",
                        title = "Continuation",
                        sourceExerciseId = "fixture-exercise",
                        sourceChapterId = "fixture-chapter",
                        availableByDefault = true,
                        variants = listOf(
                            PracticeIdiomVariantView(
                                id = "fixture.continuation.variant",
                                title = "Continuation variant",
                                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
                                chordIdentities = listOf("I", "I"),
                                chordChoices = listOf(choice, choice),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val tailSelected = session.dispatch(
            FreePracticeIntent.SelectSlot(session.frame().revision, slots[1].id),
        )
        assertEquals(null, tailSelected.frame.selection.idiomInstanceId)

        val continued = session.dispatch(
            FreePracticeIntent.InsertIdiom(
                expectedRevision = tailSelected.frame.revision,
                anchorSlotId = slots[1].id,
                definitionId = "fixture.continuation",
                variantId = "fixture.continuation.variant",
            ),
        )
        assertEquals(FreePracticeEffectKind.APPLIED, continued.effect.kind)
        assertEquals(2, continued.frame.document.workspace.idiomInstances.size)
        assertEquals(
            slots.take(2).map { it.id },
            continued.frame.document.workspace.idiomInstances.first { it.id == firstIdiomId }.slotIds,
        )
        assertEquals(
            slots[1].id,
            continued.frame.document.workspace.idiomInstances.last().slotIds.first(),
        )
    }

    @Test
    fun tonalLayoutLifecycleUsesStableIdsAndOneSessionHistory() {
        val session = session()
        val initial = session.frame()
        val slotId = requireNotNull(initial.selectedSlotId)
        val baselineId = initial.document.workspace.tonalLayouts.single().id

        val inserted = session.dispatch(
            FreePracticeIntent.InsertTonalLayout(
                expectedRevision = initial.revision,
                fifths = 1,
                mode = com.mecon.theory.freepractice.WorkspaceKeyMode.MAJOR,
                start = Fraction.ZERO,
                end = Fraction.QUARTER,
            )
        )
        val insertedId = inserted.frame.document.workspace.tonalLayouts
            .single { it.id != baselineId }.id
        val selected = session.dispatch(
            FreePracticeIntent.SelectChordTonalLayout(inserted.frame.revision, slotId, insertedId)
        )
        assertEquals(insertedId, selected.frame.document.workspace.slots.single().tonalLayoutId)

        val removed = session.dispatch(
            FreePracticeIntent.RemoveTonalLayout(selected.frame.revision, insertedId)
        )
        assertEquals(listOf(baselineId), removed.frame.document.workspace.tonalLayouts.map { it.id })
        assertEquals(baselineId, removed.frame.document.workspace.slots.single().tonalLayoutId)

        val undone = session.dispatch(FreePracticeIntent.Undo(removed.frame.revision))
        assertTrue(undone.frame.document.workspace.tonalLayouts.any { it.id == insertedId })
    }

    @Test
    fun idiomCatalogTonalityIsIndependentFromChordAndTimelineSelection() {
        val session = session()
        val initial = session.frame()
        val slotId = requireNotNull(initial.selectedSlotId)
        val baselineId = initial.document.workspace.tonalLayouts.single().id
        val inserted = session.dispatch(
            FreePracticeIntent.InsertTonalLayout(
                expectedRevision = initial.revision,
                fifths = 6,
                mode = com.mecon.theory.freepractice.WorkspaceKeyMode.MAJOR,
                start = Fraction.ZERO,
                end = Fraction.QUARTER,
            ),
        )
        val laterId = inserted.frame.document.workspace.tonalLayouts.single { it.id != baselineId }.id
        val chordSelected = session.dispatch(
            FreePracticeIntent.SelectChordTonalLayout(inserted.frame.revision, slotId, laterId),
        )
        val idiomBaseline = session.dispatch(
            FreePracticeIntent.SelectIdiomTonalLayout(chordSelected.frame.revision, baselineId),
        )
        assertEquals(laterId, idiomBaseline.frame.selection.tonalLayoutId)
        assertEquals(laterId, idiomBaseline.frame.plan.chordCatalogFilters.single { it.selected }.tonalLayoutId)
        assertEquals(baselineId, idiomBaseline.frame.plan.idiomCatalogFilters.single { it.selected }.tonalLayoutId)
        assertEquals(0, idiomBaseline.catalogRequests.single().catalogKey.fifths)

        val idiomLater = session.dispatch(
            FreePracticeIntent.SelectIdiomTonalLayout(idiomBaseline.frame.revision, laterId),
        )
        assertEquals(laterId, idiomLater.frame.selection.tonalLayoutId)
        assertEquals(laterId, idiomLater.frame.plan.idiomCatalogFilters.single { it.selected }.tonalLayoutId)
        val request = idiomLater.catalogRequests.single()
        assertEquals(6, request.catalogKey.fifths)

        val choice = projectPracticeCatalog(ModulationKey(6, KeySignatureMode.MAJOR))
            .chordChoices.first().choice
        val catalogApplied = session.applyTeachingCatalogResult(
            PracticeTeachingCatalogResult(
                requestId = request.requestId,
                baseRevision = request.baseRevision,
                fingerprint = request.fingerprint,
                definitions = listOf(
                    PracticeIdiomDefinitionView(
                        id = "fixture.later-key",
                        title = "Later key",
                        sourceExerciseId = "fixture-exercise",
                        sourceChapterId = "fixture-chapter",
                        availableByDefault = true,
                        variants = listOf(
                            PracticeIdiomVariantView(
                                id = "fixture.later-key.variant",
                                title = "Later key variant",
                                durations = listOf(Fraction.QUARTER),
                                chordIdentities = listOf("I"),
                                chordChoices = listOf(choice),
                                availableByDefault = true,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val insertedIdiom = session.dispatch(
            FreePracticeIntent.InsertIdiom(
                catalogApplied.frame.revision,
                slotId,
                "fixture.later-key",
                "fixture.later-key.variant",
            ),
        )
        assertEquals(laterId, insertedIdiom.frame.selection.tonalLayoutId)
        assertEquals(
            laterId,
            insertedIdiom.requests.single().document.workspace.idiomInstances.single().tonalLayoutId,
        )
    }

    @Test
    fun removingAnInsertedIdiomUsesItsStableInstanceId() {
        val preset = FreePracticePreset.document()
        val slotId = preset.workspace.slots.single().id
        val idiomId = WorkspaceIdiomInstanceId("idiom-fixture")
        val document = preset.copy(
            workspace = preset.workspace.copy(
                idiomInstances = listOf(
                    WorkspaceIdiomInstance(
                        id = idiomId,
                        definitionId = "fixture.cadence",
                        variantId = "fixture.root",
                        sourceExerciseId = "fixture-exercise",
                        sourceChapterId = "fixture-chapter",
                        slotIds = listOf(slotId),
                    )
                )
            )
        )
        val session = session(document)
        assertEquals(idiomId, session.initialUpdate().timeline.idioms.single().id)
        assertFalse(session.initialUpdate().timeline.slots.single().capabilities.canResizeStart)
        assertFalse(session.initialUpdate().timeline.slots.single().capabilities.canResizeEnd)

        val removed = session.dispatch(FreePracticeIntent.RemoveIdiom(0, idiomId))
        assertTrue(removed.frame.document.workspace.idiomInstances.isEmpty())
        assertTrue(removed.frame.timeline.idioms.isEmpty())
        assertTrue(removed.frame.timeline.slots.single().capabilities.canTranslate)
    }

    @Test
    fun explicitLayoutAndIdiomSelectionRoundTripsThroughUndoRedo() {
        val preset = FreePracticePreset.document()
        val slotId = preset.workspace.slots.single().id
        val firstIdiomId = WorkspaceIdiomInstanceId("idiom-first")
        val secondIdiomId = WorkspaceIdiomInstanceId("idiom-second")
        fun idiom(id: WorkspaceIdiomInstanceId) = WorkspaceIdiomInstance(
            id = id,
            definitionId = "fixture.cadence",
            variantId = id.value,
            sourceExerciseId = "fixture-exercise",
            sourceChapterId = "fixture-chapter",
            slotIds = listOf(slotId),
        )
        val session = session(
            preset.copy(
                workspace = preset.workspace.copy(
                    idiomInstances = listOf(idiom(firstIdiomId), idiom(secondIdiomId)),
                ),
            ),
        )
        val initial = session.frame()
        val baselineId = initial.document.workspace.tonalLayouts.single().id
        val inserted = session.dispatch(
            FreePracticeIntent.InsertTonalLayout(
                expectedRevision = initial.revision,
                fifths = 1,
                mode = com.mecon.theory.freepractice.WorkspaceKeyMode.MAJOR,
                start = Fraction.ZERO,
                end = Fraction.QUARTER,
            ),
        )
        val insertedId = inserted.frame.document.workspace.tonalLayouts.single { it.id != baselineId }.id
        val layoutSelected = session.dispatch(
            FreePracticeIntent.SelectTonalLayout(inserted.frame.revision, insertedId),
        )
        val idiomSelected = session.dispatch(
            FreePracticeIntent.SelectIdiom(layoutSelected.frame.revision, secondIdiomId),
        )
        assertEquals(insertedId, idiomSelected.frame.selection.tonalLayoutId)
        assertEquals(secondIdiomId, idiomSelected.frame.selection.idiomInstanceId)

        val edited = session.dispatch(
            FreePracticeIntent.SetTonalLayoutKey(
                idiomSelected.frame.revision,
                insertedId,
                fifths = 2,
                mode = com.mecon.theory.freepractice.WorkspaceKeyMode.MAJOR,
            ),
        )
        val baselineSelected = session.dispatch(
            FreePracticeIntent.SelectTonalLayout(edited.frame.revision, baselineId),
        )
        val firstIdiomSelected = session.dispatch(
            FreePracticeIntent.SelectIdiom(baselineSelected.frame.revision, firstIdiomId),
        )

        val undone = session.dispatch(FreePracticeIntent.Undo(firstIdiomSelected.frame.revision))
        assertEquals(insertedId, undone.frame.selection.tonalLayoutId)
        assertEquals(secondIdiomId, undone.frame.selection.idiomInstanceId)

        val redone = session.dispatch(FreePracticeIntent.Redo(undone.frame.revision))
        assertEquals(baselineId, redone.frame.selection.tonalLayoutId)
        assertEquals(firstIdiomId, redone.frame.selection.idiomInstanceId)
    }

    @Test
    fun timelinePreviewIsPureAndCommitCreatesOneHistoryItem() {
        val session = session()
        val before = session.frame()
        val slotId = requireNotNull(before.selectedSlotId)
        val original = before.document.workspace.slots.single()
        val duration = original.duration + Fraction.of(1, 4)
        val preview = session.previewTimelineEdit(
            PracticeTimelinePreviewRequest(
                requestId = 7,
                baseRevision = before.revision,
                edit = PracticeTimelineEdit.PlaceChordRange(slotId, original.onset, duration),
            )
        )

        assertTrue(preview.accepted)
        assertEquals(duration, preview.timeline?.slots?.single()?.duration)
        assertEquals(before.revision, session.frame().revision)
        assertEquals(original.duration, session.frame().document.workspace.slots.single().duration)

        val committed = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                before.revision,
                PracticeTimelineEdit.PlaceChordRange(slotId, original.onset, duration),
            )
        )
        assertEquals(before.revision + 1, committed.frame.revision)
        assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, committed.effect.kind)
        assertEquals(duration, committed.frame.document.workspace.slots.single().duration)

        val cancelled = session.dispatch(FreePracticeIntent.CancelWriting(committed.frame.revision))
        val undone = session.dispatch(FreePracticeIntent.Undo(cancelled.frame.revision))
        assertEquals(original.duration, undone.frame.document.workspace.slots.single().duration)
    }

    /**
     * The edit that started auto-writing is not committed while the solve runs — it lives in the
     * pending request, and that is what every projection shows. Resolving further gestures against
     * the last committed workspace instead refused every preview outright and silently reverted the
     * pending edit on the next commit.
     */
    @Test
    fun timelineGesturesResolveAgainstThePendingWorkspaceWhileWritingRuns() {
        val session = session()
        val before = session.frame()
        val slotId = requireNotNull(before.selectedSlotId)
        val original = before.document.workspace.slots.single()
        val resized = original.duration + Fraction.of(1, 8)
        val committed = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                before.revision,
                PracticeTimelineEdit.PlaceChordRange(slotId, original.onset, resized),
            )
        )
        assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, committed.effect.kind)
        assertEquals(resized, committed.frame.document.workspace.slots.single().duration)

        val preview = session.previewTimelineEdit(
            PracticeTimelinePreviewRequest(
                requestId = 21,
                baseRevision = committed.frame.revision,
                edit = PracticeTimelineEdit.TranslateChordRange(slotId, Fraction.of(1, 8)),
            )
        )
        assertTrue(preview.accepted, preview.reasonKey)
        assertEquals(resized, preview.timeline?.slots?.single()?.duration)
        assertEquals(original.onset + Fraction.of(1, 8), preview.timeline?.slots?.single()?.onset)

        val second = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                committed.frame.revision,
                PracticeTimelineEdit.TranslateChordRange(slotId, Fraction.of(1, 8)),
            )
        )
        val moved = second.frame.document.workspace.slots.single()
        assertEquals(original.onset + Fraction.of(1, 8), moved.onset)
        assertEquals(resized, moved.duration)
    }

    @Test
    fun timelinePreviewRejectsStaleRevisionAndTargetWithoutMutation() {
        val session = session()
        val before = session.frame()
        val stale = session.previewTimelineEdit(
            PracticeTimelinePreviewRequest(
                requestId = 8,
                baseRevision = before.revision + 1,
                edit = PracticeTimelineEdit.TranslateChordRange(
                    requireNotNull(before.selectedSlotId),
                    Fraction.of(1, 4),
                ),
            )
        )
        val missing = session.previewTimelineEdit(
            PracticeTimelinePreviewRequest(
                requestId = 9,
                baseRevision = before.revision,
                edit = PracticeTimelineEdit.TranslateChordRange(
                    com.mecon.theory.freepractice.WorkspaceSlotId("missing"),
                    Fraction.of(1, 4),
                ),
            )
        )

        assertFalse(stale.accepted)
        assertEquals("freePractice.staleRevision", stale.reasonKey)
        assertFalse(missing.accepted)
        assertEquals("freePractice.staleTarget", missing.reasonKey)
        assertEquals(before.revision, session.frame().revision)
    }

    @Test
    fun innerNoOpPreservesBothRevisionsAndExactScoreEffect() {
        val session = session()
        val initial = session.initialUpdate()

        val result = session.dispatch(
            FreePracticeIntent.Score(
                initial.revision,
                ScoreEditIntent.Undo(initial.score.revision),
            )
        )
        val update = session.toWireUpdate(result)

        assertEquals(FreePracticeEffectKind.NO_OP, update.effect.kind)
        assertEquals(initial.revision, update.revision)
        assertEquals(initial.score.revision, update.score.revision)
        assertEquals(ScoreEditEffectKind.NO_OP, update.score.effect.kind)
        assertFalse(update.score.scoreChanged)
    }

    @Test
    fun innerSelectionPublishesExactSelectionUpdateWithoutClaimingScoreChange() {
        val session = session()
        val initial = session.initialUpdate()
        assertEquals(initial.selectedSlotId, initial.selection.slotId)
        assertEquals(initial.plan.editableTonalLayoutId, initial.selection.tonalLayoutId)
        assertEquals(initial.score.selection, initial.selection.scoreTargets)

        val result = session.dispatch(
            FreePracticeIntent.Score(
                initial.revision,
                ScoreEditIntent.SetSelection(initial.score.revision, emptyList()),
            )
        )
        val update = session.toWireUpdate(result)

        assertEquals(initial.revision + 1, update.revision)
        assertEquals(FreePracticeEffectKind.SELECTION_CHANGED, update.effect.kind)
        assertEquals(ScoreEditEffectKind.SELECTION_CHANGED, update.score.effect.kind)
        assertFalse(update.score.scoreChanged)
        assertEquals(initial.score.revision, update.score.baseRevision)
        assertEquals(update.score.selection, update.selection.scoreTargets)
    }

    @Test
    fun scoreNoteSelectionFocusesOnlyTheFilledChordAtItsOnset() {
        fun selectSecondSlot(secondHasChord: Boolean): Pair<FreePracticeDispatchResult, WorkspaceSlotId> {
            val preset = FreePracticePreset.document()
            val opening = preset.workspace.slots.single()
            val second = opening.copy(
                id = WorkspaceSlotId("slot-1"),
                onset = Fraction.QUARTER,
                chordChoice = opening.chordChoice.takeIf { secondHasChord },
            )
            val session = session(preset.copy(workspace = preset.workspace.copy(slots = listOf(opening, second))))
            val initial = session.frame()
            val inserted = session.dispatch(FreePracticeIntent.Score(
                initial.revision,
                ScoreEditIntent.InsertNote(
                    initial.score.revision,
                    initial.document.workspace.voices.first().id,
                    TimeCode.of(1, Fraction.QUARTER),
                    Duration.EIGHTH,
                    Pitch.C5,
                ),
            ))
            val target = ScoreSelectionTarget.Event(
                requireNotNull(inserted.frame.score.selection.single().eventIdOrNull),
                initial.document.workspace.voices.first().id,
            )
            val cleared = session.dispatch(FreePracticeIntent.Score(
                inserted.frame.revision,
                ScoreEditIntent.SetSelection(inserted.frame.score.revision, emptyList()),
            ))
            return session.dispatch(FreePracticeIntent.Score(
                cleared.frame.revision,
                ScoreEditIntent.SetSelection(cleared.frame.score.revision, listOf(target)),
            )) to opening.id
        }

        val (filled, openingId) = selectSecondSlot(secondHasChord = true)
        assertEquals(WorkspaceSlotId("slot-1"), filled.frame.selection.slotId)
        assertEquals(FreePracticeEffectKind.SELECTION_CHANGED, filled.effect.kind)
        assertFalse(filled.frame.score.scoreChanged)

        val (empty, emptyOpeningId) = selectSecondSlot(secondHasChord = false)
        assertEquals(emptyOpeningId, empty.frame.selection.slotId)
        assertEquals(openingId, emptyOpeningId)
        assertEquals(FreePracticeEffectKind.SELECTION_CHANGED, empty.effect.kind)
        assertFalse(empty.frame.score.scoreChanged)
    }

    @Test
    fun staleSlotIdIsRejectedWithoutClampingSelection() {
        val session = session()
        val initial = session.frame()

        val result = session.dispatch(
            FreePracticeIntent.SelectSlot(initial.revision, com.mecon.theory.freepractice.WorkspaceSlotId("missing"))
        )

        assertEquals(FreePracticeEffectKind.STALE_TARGET, result.effect.kind)
        assertEquals(initial.revision, result.frame.revision)
        assertEquals(initial.selectedSlotId, result.frame.selectedSlotId)
    }

    @Test
    fun solvedBackgroundResultCommitsScoreAndWorkspaceOnOneUndoBoundary() {
        val session = session()
        val before = session.frame()
        val slot = requireNotNull(before.selectedSlotId)
        val requested = session.dispatch(FreePracticeIntent.RunWriting(before.revision, slot))
        val request = requested.requests.single()
        val voices = request.document.workspace.voices.sortedBy { it.order }
        val candidate = PracticeVoicingCandidate(
            frames = listOf(
                PracticeVoicingFrame(
                    slot,
                    voices.mapIndexed { index, voice -> voice.id to Pitch.fromMidi(72 - index * 7) }.toMap(),
                )
            ),
            diversityGroupKey = "fixture-primary",
            score = 0.0,
        )
        val applied = session.applyBackgroundResult(
            PracticeBackgroundResult(
                requestId = request.requestId,
                baseRevision = request.baseRevision,
                scopeFingerprint = request.scopeFingerprint,
                kind = request.kind,
                candidates = listOf(candidate),
                outcome = PracticeWritingOutcome.Solved(listOf(slot), null),
            )
        )

        assertEquals(FreePracticeEffectKind.WRITING_APPLIED, applied.effect.kind)
        val replay = assertIs<PracticeEditPlayback.Excerpt>(applied.editPlayback).range
        assertEquals(slot, replay.firstSlotId)
        assertEquals(slot, replay.lastSlotId)
        assertIs<PracticeWritingOutcome.Solved>(applied.frame.writing.outcome)
        assertTrue(applied.frame.score.canUndo)
        assertTrue(applied.frame.score.runtimeScore.getAllVoiceEvents().any { !it.isRest })

        val undone = session.dispatch(FreePracticeIntent.Undo(applied.frame.revision))
        assertEquals(FreePracticeEffectKind.UNDONE, undone.effect.kind)
        assertFalse(undone.frame.score.runtimeScore.getAllVoiceEvents().any { !it.isRest })
        assertTrue(undone.frame.score.canRedo)
    }

    @Test
    fun staleBackgroundResultCannotPublishAfterCancellation() {
        val session = session()
        val slot = requireNotNull(session.frame().selectedSlotId)
        val requested = session.dispatch(FreePracticeIntent.RunWriting(session.frame().revision, slot))
        val request = requested.requests.single()
        val cancelled = session.dispatch(FreePracticeIntent.CancelWriting(requested.frame.revision))

        val stale = session.applyBackgroundResult(
            PracticeBackgroundResult(
                request.requestId,
                request.baseRevision,
                request.scopeFingerprint,
                request.kind,
                outcome = PracticeWritingOutcome.NoSolution,
            )
        )

        assertEquals(FreePracticeEffectKind.STALE_BACKGROUND_RESULT, stale.effect.kind)
        assertEquals(cancelled.frame.revision, stale.frame.revision)
    }

    /**
     * A crashed search worker used to leave the request active forever: the workbench stayed in
     * RUNNING with the uncommitted workspace showing and refused every further intent.
     */
    @Test
    fun crashedFirstSolveRollsBackToTheLastCommittedWorkspaceAndUnlocksTheWorkbench() {
        val session = session()
        val before = session.frame()
        val slot = requireNotNull(before.selectedSlotId)
        val choice = WorkspaceChordChoice.of(listOf(2, 5, 9))
        val requested = session.dispatch(FreePracticeIntent.ReplaceChord(before.revision, slot, choice))
        assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, requested.effect.kind)
        assertEquals(choice, requested.frame.document.workspace.slots.single().chordChoice)
        val request = requested.requests.single()

        val failed = session.applyBackgroundFailure(
            PracticeBackgroundFailure(request.requestId, "engine crashed")
        )

        assertEquals(FreePracticeEffectKind.INVALID, failed.effect.kind)
        assertEquals("freePractice.writing.failed", failed.effect.messageKey)
        assertEquals("engine crashed", failed.effect.arguments["reason"])
        assertEquals(PracticeWritingOutcome.Failed("engine crashed"), failed.frame.writing.outcome)
        assertEquals(PracticeWritingPhase.READY, failed.frame.writing.phase)
        // The pending chord was never committed, so dropping the request is the rollback.
        assertEquals(
            before.document.workspace.slots.single().chordChoice,
            failed.frame.document.workspace.slots.single().chordChoice,
        )
        assertFalse(failed.frame.score.runtimeScore.getAllVoiceEvents().any { !it.isRest })

        // The workbench must accept work again immediately.
        val afterFailure = session.dispatch(
            FreePracticeIntent.ReplaceChord(failed.frame.revision, slot, choice)
        )
        assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, afterFailure.effect.kind)
    }

    @Test
    fun crashedCandidateOptimizationKeepsTheAppliedWritingAndOnlyUnlocks() {
        val session = session()
        val slot = requireNotNull(session.frame().selectedSlotId)
        val requested = session.dispatch(FreePracticeIntent.RunWriting(session.frame().revision, slot))
        val request = requested.requests.single()
        val voices = request.document.workspace.voices.sortedBy { it.order }
        val applied = session.applyBackgroundResult(
            PracticeBackgroundResult(
                requestId = request.requestId,
                baseRevision = request.baseRevision,
                scopeFingerprint = request.scopeFingerprint,
                kind = request.kind,
                candidates = listOf(
                    PracticeVoicingCandidate(
                        frames = listOf(
                            PracticeVoicingFrame(
                                slot,
                                voices.mapIndexed { index, voice ->
                                    voice.id to Pitch.fromMidi(72 - index * 7)
                                }.toMap(),
                            )
                        ),
                        diversityGroupKey = "fixture-primary",
                        score = 0.0,
                    )
                ),
                outcome = PracticeWritingOutcome.Solved(listOf(slot), null),
            )
        )
        val optimize = applied.requests.single()

        val failed = session.applyBackgroundFailure(
            PracticeBackgroundFailure(optimize.requestId, "alternate crashed")
        )

        assertEquals(FreePracticeEffectKind.INVALID, failed.effect.kind)
        assertEquals("freePractice.writing.alternateFailed", failed.effect.messageKey)
        assertIs<PracticeWritingOutcome.Solved>(failed.frame.writing.outcome)
        assertTrue(failed.frame.score.runtimeScore.getAllVoiceEvents().any { !it.isRest })
    }

    @Test
    fun backgroundFailureForAnotherRequestIsRejectedAsStale() {
        val session = session()
        val slot = requireNotNull(session.frame().selectedSlotId)
        val requested = session.dispatch(FreePracticeIntent.RunWriting(session.frame().revision, slot))

        val stale = session.applyBackgroundFailure(
            PracticeBackgroundFailure(requested.requests.single().requestId + 1_000, "late crash")
        )

        assertEquals(FreePracticeEffectKind.STALE_BACKGROUND_RESULT, stale.effect.kind)
        assertEquals(PracticeWritingPhase.RUNNING, stale.frame.writing.phase)
        assertEquals(requested.frame.revision, stale.frame.revision)
    }

    /**
     * The pending request is what suppresses the next request for the same fingerprint, so a
     * crashed finding worker would otherwise freeze the feedback panel as permanently stale —
     * while re-issuing it immediately would spin on a reproducible crash.
     */
    @Test
    fun crashedFindingRequestDoesNotSpinAndIsReissuedWhenInputsChange() {
        val session = session()
        val request = session.initialUpdate().findingRequests.single()

        val failed = session.applyFindingFailure(PracticeBackgroundFailure(request.requestId, "boom"))
        assertEquals(FreePracticeEffectKind.INVALID, failed.effect.kind)
        assertEquals("freePractice.findings.failed", failed.effect.messageKey)
        assertTrue(failed.frame.findings.stale)
        assertTrue(failed.findingRequests.isEmpty(), "同一输入不得立刻重发，否则会在必现崩溃上空转")

        val slot = requireNotNull(session.frame().selectedSlotId)
        val changed = session.dispatch(
            FreePracticeIntent.ReplaceChord(failed.frame.revision, slot, WorkspaceChordChoice.of(listOf(2, 5, 9)))
        )
        assertEquals(1, changed.findingRequests.size)
    }

    @Test
    fun crashedTeachingCatalogRequestStopsLoadingAndReportsTheError() {
        val session = session()
        val request = session.initialUpdate().catalogRequests.single()

        val failed = session.applyTeachingCatalogFailure(
            PracticeBackgroundFailure(request.requestId, "catalog boom")
        )

        assertEquals(FreePracticeEffectKind.INVALID, failed.effect.kind)
        assertFalse(failed.frame.plan.idiomCatalog.loading)
        assertEquals("freePractice.catalog.failed", failed.frame.plan.idiomCatalog.errorKey)
    }

    @Test
    fun protocolRoundTripsTypedOutcomeWithoutLocalizedText() {
        val session = session()
        val update = session.initialUpdate()
        val decoded = FreePracticeCodec.decodeUpdate(FreePracticeCodec.encodeUpdate(update))

        assertEquals(update, decoded)
        assertFalse(FreePracticeCodec.encodeUpdate(update).contains("完成"))
    }

    @Test
    fun chordEditAutomaticallyRequestsWritingAndCancellationKeepsTheChordInOneHistoryItem() {
        val session = session()
        val before = session.frame()
        val slot = requireNotNull(before.selectedSlotId)
        val choice = WorkspaceChordChoice.of(listOf(2, 5, 9))

        val requested = session.dispatch(FreePracticeIntent.ReplaceChord(before.revision, slot, choice))
        assertEquals(FreePracticeEffectKind.WRITING_REQUESTED, requested.effect.kind)
        assertEquals(choice, requested.frame.document.workspace.slots.single().chordChoice)

        val cancelled = session.dispatch(FreePracticeIntent.CancelWriting(requested.frame.revision))
        assertEquals(FreePracticeEffectKind.WRITING_CANCELLED, cancelled.effect.kind)
        assertEquals(choice, session.workspaceState.slots.single().chordChoice)

        session.dispatch(FreePracticeIntent.Undo(cancelled.frame.revision))
        assertNotEquals(choice, session.workspaceState.slots.single().chordChoice)
    }

    /**
     * Every timeline gesture reaches the workspace through one mapping, so the committed state must
     * equal exactly what the preview of the same edit projected — including the shared-boundary
     * redistribution and the `includeFollowing` shift the platforms must never re-implement.
     */
    @Test
    fun everyTimelineEditCommitsExactlyWhatItsPreviewProjected() {
        val edits: List<(List<com.mecon.theory.freepractice.WorkspaceHarmonySlot>, WorkspaceTonalLayoutId) ->
        PracticeTimelineEdit> = listOf(
            { slots, _ ->
                PracticeTimelineEdit.TranslateChordRange(slots[1].id, Fraction.of(1, 4), includeFollowing = false)
            },
            { slots, _ ->
                PracticeTimelineEdit.TranslateChordRange(slots[1].id, Fraction.of(1, 4), includeFollowing = true)
            },
            { slots, _ ->
                PracticeTimelineEdit.MoveSharedBoundary(slots[0].id, slots[0].onset + Fraction.of(1, 8))
            },
            { slots, _ ->
                PracticeTimelineEdit.MoveBoundaryWithFollowing(slots[0].id, slots[0].onset + Fraction.of(1, 8))
            },
            { _, layoutId ->
                PracticeTimelineEdit.SetTonalLayoutBounds(layoutId, Fraction.ZERO, Fraction.of(1, 2))
            },
        )
        edits.forEach { build ->
            val session = threeSlotSession()
            val before = session.frame()
            val edit = build(
                before.document.workspace.slots,
                before.document.workspace.tonalLayouts.first().id,
            )
            val preview = session.previewTimelineEdit(
                PracticeTimelinePreviewRequest(requestId = 1, baseRevision = before.revision, edit = edit),
            )
            assertTrue(preview.accepted, "preview rejected for $edit")

            val committed = session.dispatch(FreePracticeIntent.TimelineEdit(before.revision, edit))

            assertEquals(FreePracticeEffectKind.APPLIED, committed.effect.kind, "$edit")
            assertEquals(preview.timeline, committed.frame.timeline, "$edit")
            assertEquals(before.revision + 1, committed.frame.revision, "$edit")

            val undone = session.dispatch(FreePracticeIntent.Undo(committed.frame.revision))
            assertEquals(before.timeline, undone.frame.timeline, "one history item for $edit")
        }
    }

    /**
     * A chord box over an empty measure has nothing to realize, but its geometry is still workspace
     * state: the gesture used to be dropped together with the writing request it could not plan.
     */
    @Test
    fun timelineEditOnAChordlessSlotCommitsWithoutWriting() {
        val preset = FreePracticePreset.document()
        val template = preset.workspace.slots.single()
        val session = session(
            preset.copy(
                workspace = preset.workspace.copy(
                    slots = listOf(
                        template.copy(chordChoice = null),
                        template.copy(
                            id = com.mecon.theory.freepractice.WorkspaceSlotId("slot-1"),
                            onset = Fraction.QUARTER,
                            chordChoice = null,
                        ),
                    ),
                ),
            ),
        )
        val before = session.frame()
        assertTrue(before.document.settings.writing.autoWritingEnabled)
        val slot = before.document.workspace.slots.first()

        val moved = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                before.revision,
                PracticeTimelineEdit.TranslateChordRange(slot.id, Fraction.of(1, 8)),
            ),
        )

        assertEquals(FreePracticeEffectKind.APPLIED, moved.effect.kind)
        assertEquals(
            slot.onset + Fraction.of(1, 8),
            moved.frame.document.workspace.slots.first { it.id == slot.id }.onset,
        )
        assertTrue(moved.requests.isEmpty(), "a chordless slot must not request writing")

        val undone = session.dispatch(FreePracticeIntent.Undo(moved.frame.revision))
        assertEquals(slot.onset, undone.frame.document.workspace.slots.first { it.id == slot.id }.onset)
    }

    @Test
    fun timelineEditReportsStaleTargetForMissingSlotAndTonalLayout() {
        val session = threeSlotSession()
        val revision = session.frame().revision
        val missingSlot = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                revision,
                PracticeTimelineEdit.MoveBoundaryWithFollowing(
                    com.mecon.theory.freepractice.WorkspaceSlotId("missing"),
                    Fraction.of(1, 4),
                ),
            ),
        )
        val missingLayout = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                revision,
                PracticeTimelineEdit.SetTonalLayoutBounds(
                    WorkspaceTonalLayoutId("missing"),
                    Fraction.ZERO,
                    Fraction.of(1, 2),
                ),
            ),
        )

        assertEquals(FreePracticeEffectKind.STALE_TARGET, missingSlot.effect.kind)
        assertEquals(FreePracticeEffectKind.STALE_TARGET, missingLayout.effect.kind)
        assertEquals(revision, session.frame().revision)
    }

    /**
     * Regression for the sticky `scoreChanged`: the workspace commits score material outside
     * `ScoreEditingSession.dispatch`, and before the fix every frame after the first such commit
     * claimed the score had changed — which made each selection re-lay out the whole score on Web.
     */
    @Test
    fun selectionFramesAfterAWorkspaceCommitReportNoScoreChange() {
        val session = threeSlotSession()
        val slots = session.frame().document.workspace.slots
        val committed = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                session.frame().revision,
                PracticeTimelineEdit.TranslateChordRange(slots[1].id, Fraction.of(1, 4)),
            ),
        )
        assertEquals(FreePracticeEffectKind.APPLIED, committed.effect.kind)

        val first = session.dispatch(FreePracticeIntent.SelectSlot(committed.frame.revision, slots[2].id))
        val second = session.dispatch(FreePracticeIntent.SelectSlot(first.frame.revision, slots[0].id))

        assertFalse(session.toWireUpdate(first).score.scoreChanged)
        assertFalse(session.toWireUpdate(second).score.scoreChanged)
    }

    /**
     * Findings and the teaching catalog are keyed on their inputs, not on the revision: selecting a
     * chord slot used to bump the revision and therefore re-run both analyses — on Web, one engine
     * cold start per click.
     */
    @Test
    fun selectionsDoNotRequestNewFindingsAndKeepAnInFlightAnswerValid() {
        val session = threeSlotSession()
        val initial = session.initialUpdate()
        val findingRequest = initial.findingRequests.single()
        val slots = initial.document.workspace.slots

        val selected = session.dispatch(FreePracticeIntent.SelectSlot(initial.revision, slots[1].id))
        assertEquals(FreePracticeEffectKind.SELECTION_CHANGED, selected.effect.kind)
        assertTrue(selected.findingRequests.isEmpty(), "a selection changes no finding input")

        // The answer was computed before the selection and must still apply.
        val applied = session.applyFindingResult(PracticeFindingExecutor.execute(findingRequest))
        assertEquals(FreePracticeEffectKind.FINDINGS_UPDATED, applied.effect.kind)
        assertFalse(applied.frame.findings.stale)

        val edited = session.dispatch(
            FreePracticeIntent.TimelineEdit(
                applied.frame.revision,
                PracticeTimelineEdit.TranslateChordRange(slots[1].id, Fraction.of(1, 4)),
            ),
        )
        assertEquals(1, edited.findingRequests.size, "a workspace edit does change the inputs")
    }

    private fun threeSlotSession(): FreePracticeSession {
        val preset = FreePracticePreset.document()
        val template = preset.workspace.slots.single()
        return session(
            preset.copy(
                settings = preset.settings.copy(
                    writing = preset.settings.writing.copy(autoWritingEnabled = false),
                ),
                workspace = preset.workspace.copy(
                    slots = List(3) { index ->
                        template.copy(
                            id = com.mecon.theory.freepractice.WorkspaceSlotId("slot-$index"),
                            onset = Fraction.QUARTER * index,
                            duration = Fraction.QUARTER,
                        )
                    },
                ),
            ),
        )
    }

    private fun session(
        document: com.mecon.exploration.FreePracticeDocument = FreePracticePreset.document(),
    ): FreePracticeSession {
        val storage = VoicePlanScoreAssembler.emptyPracticeScore(
            document.workspace,
            document.settings.initialKey.let { key ->
                if (key.mode == com.mecon.exploration.KeyModeSpec.MAJOR) {
                    com.mecon.api.primitive.KeySignature.majorByFifths(key.fifths)
                } else {
                    com.mecon.api.primitive.KeySignature.minorByFifths(key.fifths)
                }
            },
            document.settings.staffVoices,
        )
        return FreePracticeSession.open(document, RuntimeScore.fromStorage(storage))
    }
}
