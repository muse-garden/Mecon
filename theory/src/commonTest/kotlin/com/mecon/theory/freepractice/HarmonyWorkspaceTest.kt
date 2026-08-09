package com.mecon.theory.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import com.mecon.theory.Key
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.NaturalTriads
import com.mecon.theory.VoiceBoundary
import com.mecon.theory.VoicePlan
import com.mecon.theory.VoiceRange
import com.mecon.theory.VoiceSpec
import com.mecon.theory.constraint.HarmonicPatterns
import com.mecon.theory.harmony.ChordInterpretationRef
import com.mecon.theory.harmony.InterpretationId
import com.mecon.theory.harmony.SonorityId
import com.mecon.theory.harmony.ChordSelectionCatalog
import com.mecon.theory.textbook.TextbookTriadPosition
import com.mecon.theory.textbook.TextbookTriadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HarmonyWorkspaceTest {
    private val voicePlan = VoicePlan(
        listOf(
            VoiceSpec(
                TrackId("upper"),
                0,
                VoiceBoundary.UPPER_OUTER,
                VoiceRange(Pitch.fromName("C4"), Pitch.fromName("G5")),
            ),
            VoiceSpec(
                TrackId("middle"),
                1,
                VoiceBoundary.INNER,
                VoiceRange(Pitch.fromName("G3"), Pitch.fromName("D5")),
            ),
            VoiceSpec(
                TrackId("lower"),
                2,
                VoiceBoundary.LOWER_OUTER,
                VoiceRange(Pitch.fromName("C3"), Pitch.fromName("C4")),
            ),
        )
    )

    @Test
    fun invalidCommandReportsErrorAndKeepsExactPreviousState() {
        val initial = workspace()
        var displayedError: String? = null

        val actual = HarmonyWorkspaceEditor.apply(
            state = initial,
            command = HarmonyWorkspaceCommand.ReplaceChord(-1, "V"),
            onError = { displayedError = it },
        )

        assertSame(initial, actual)
        assertEquals("The selected harmony slot no longer exists", displayedError)
    }

    @Test
    fun workspaceControllerDoesNotPublishRejectedCommand() {
        val initial = workspace()
        val controller = HarmonyWorkspaceStateController(initial)

        val actual = controller.apply(
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.ZERO,
                duration = Fraction.QUARTER,
            )
        )

        assertSame(initial, actual)
        assertSame(initial, controller.state)
        assertEquals(
            "Inserted harmony range 0/1..1/4 overlaps slot-0 0/1..1/4",
            controller.lastErrorMessage,
        )
    }

    @Test
    fun splitChangesHarmonyGridWithoutMovingNotes() {
        val initial = workspace()
        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertChord(
                index = 0,
                mode = InsertChordMode.SPLIT_SPAN,
                chordIdentity = "V",
                splitOffset = Fraction.EIGHTH,
            )
        )

        assertEquals(3, edited.slots.size)
        assertEquals(listOf(Fraction.EIGHTH, Fraction.EIGHTH), edited.slots.take(2).map { it.duration })
        assertEquals(initial.notes, edited.notes)
    }

    @Test
    fun rippleInsertAndDeleteTransformSlotsAndNotesReversibly() {
        val initial = workspace()
        val inserted = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertChord(
                index = 1,
                mode = InsertChordMode.RIPPLE,
                chordIdentity = "ii",
                duration = Fraction.EIGHTH,
            )
        )
        assertEquals(Fraction(3, 8), inserted.slots.last().onset)
        assertEquals(Fraction(3, 8), inserted.notes.single().onset)

        val restored = HarmonyWorkspaceEditor.apply(
            inserted,
            HarmonyWorkspaceCommand.DeleteChord(1, DeleteChordMode.RIPPLE_SPAN),
        )
        assertEquals(initial.slots.map { it.onset to it.duration }, restored.slots.map { it.onset to it.duration })
        assertEquals(initial.notes.map { it.onset }, restored.notes.map { it.onset })
    }

    @Test
    fun symbolOnlyDeletePreservesTimeAndNotes() {
        val initial = workspace()
        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.DeleteChord(0, DeleteChordMode.SYMBOL_ONLY),
        )

        assertNull(edited.slots.first().chordIdentity)
        assertEquals(initial.slots.map { it.onset to it.duration }, edited.slots.map { it.onset to it.duration })
        assertEquals(initial.notes, edited.notes)
    }

    @Test
    fun placingRangeTrimsOccupiedHarmonyAndLeavesVacatedGap() {
        val initial = workspace()
        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.PlaceChordRange(
                index = 0,
                onset = Fraction.EIGHTH,
                duration = Fraction.QUARTER,
            ),
        )

        assertEquals(
            listOf(
                Triple("I", Fraction.EIGHTH, Fraction.QUARTER),
                Triple("V", Fraction(3, 8), Fraction.EIGHTH),
            ),
            edited.slots.map { Triple(it.chordIdentity, it.onset, it.duration) },
        )
    }

    @Test
    fun sharedBoundaryResizesBothAdjacentChords() {
        val edited = HarmonyWorkspaceEditor.apply(
            workspace(),
            HarmonyWorkspaceCommand.MoveSharedBoundary(
                leftIndex = 0,
                boundary = Fraction.EIGHTH,
            ),
        )

        assertEquals(
            listOf(
                Fraction.ZERO to Fraction.EIGHTH,
                Fraction.EIGHTH to Fraction(3, 8),
            ),
            edited.slots.map { it.onset to it.duration },
        )
    }

    @Test
    fun ctrlTranslationKeepsFollowingHarmonySpacing() {
        val initial = workspace()
        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.TranslateChordRange(
                index = 0,
                delta = Fraction.EIGHTH,
                includeFollowing = true,
            ),
        )

        assertEquals(
            listOf(
                Fraction.EIGHTH to Fraction.QUARTER,
                Fraction(3, 8) to Fraction.QUARTER,
            ),
            edited.slots.map { it.onset to it.duration },
        )
    }

    @Test
    fun insertingEmptyChordFillsExactGap() {
        val initial = workspace().copy(
            slots = listOf(
                workspace().slots.first().copy(duration = Fraction.EIGHTH),
                workspace().slots.last(),
            ),
        )
        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.EIGHTH,
                duration = Fraction.EIGHTH,
            ),
        )

        assertEquals(3, edited.slots.size)
        assertEquals(Fraction.EIGHTH, edited.slots[1].onset)
        assertEquals(Fraction.EIGHTH, edited.slots[1].duration)
        assertNull(edited.slots[1].chordIdentity)
    }

    @Test
    fun ctrlBoundaryDragMovesFollowingHarmonyAsOneGroup() {
        val initial = workspace()
        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.MoveBoundaryWithFollowing(
                leftIndex = 0,
                boundary = Fraction(3, 8),
            ),
        )

        assertEquals(Fraction(3, 8), edited.slots[0].duration)
        assertEquals(Fraction(3, 8), edited.slots[1].onset)
        assertEquals(Fraction.QUARTER, edited.slots[1].duration)
    }

    @Test
    fun removingChordLeavesItsTimelineRangeEmpty() {
        val initial = workspace()
        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.RemoveChordRange(0),
        )

        assertEquals(listOf("V"), edited.slots.map { it.chordIdentity })
        assertEquals(Fraction.QUARTER, edited.slots.single().onset)
    }

    @Test
    fun voiceCountIsConfigurableButFixedInsideWorkspace() {
        val state = workspace()
        assertEquals(3, state.voicePlan.voices.size)
        assertEquals(voicePlan.voices.map { it.id }, state.voicePlan.voices.map { it.id })
    }

    @Test
    fun missingChordKeepsPatternProgressUndetermined() {
        val key = Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR)
        val triads = NaturalTriads.inKey(key)
        val dominant = TextbookTriadTarget(
            triads.first { it.degree == 5 },
            TextbookTriadPosition.ROOT_POSITION,
        )
        val tonic = TextbookTriadTarget(
            triads.first { it.degree == 1 },
            TextbookTriadPosition.ROOT_POSITION,
        )
        val state = workspace().copy(
            slots = workspace().slots.mapIndexed { index, slot ->
                slot.copy(chordIdentity = if (index == 0) dominant.identityKey() else null)
            },
            patternChoices = listOf(
                WorkspacePatternChoice(
                    requirementId = "cadence",
                    patternId = HarmonicPatterns.AUTHENTIC_CADENCE.id.value,
                    order = 0,
                )
            ),
        )
        val progress = HarmonyWorkspaceProjector.patternProgress(
            state,
            targetsByIdentity = mapOf(
                dominant.identityKey() to dominant,
                tonic.identityKey() to tonic,
            ),
            patternsById = mapOf(
                HarmonicPatterns.AUTHENTIC_CADENCE.id to HarmonicPatterns.AUTHENTIC_CADENCE
            ),
        ).single()

        assertEquals(WorkspaceCheckTruth.UNDETERMINED, progress.truth)
    }

    @Test
    fun tonalLayoutsCanOverlapAndChordSelectsOneReading() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val withTarget = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertTonalLayout(
                key = ModulationKey(1, KeySignatureMode.MAJOR),
                start = Fraction.EIGHTH,
                end = Fraction(3, 8),
            ),
        )
        val target = withTarget.tonalLayouts.last()
        val selected = HarmonyWorkspaceEditor.apply(
            withTarget,
            HarmonyWorkspaceCommand.SelectChordTonalLayout(1, target.id),
        )

        assertEquals(2, selected.activeTonalLayouts(Fraction.QUARTER).size)
        assertEquals(target.id, selected.slots[1].tonalLayoutId)
        assertTrue(selected.tonalLayouts.first().isBaseline)
        assertNull(selected.tonalLayouts.first().end)
    }

    @Test
    fun insertingTonalLayoutCanTerminatePreviousAfterOverlappingPivotChord() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val inserted = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertTonalLayout(
                key = ModulationKey(1, KeySignatureMode.MAJOR),
                start = Fraction.QUARTER,
                terminatePreviousAt = Fraction.HALF,
            ),
        )

        assertEquals(Fraction.HALF, inserted.tonalLayouts.first().end)
        assertEquals(2, inserted.activeTonalLayouts(Fraction.QUARTER).size)
        assertEquals(1, inserted.activeTonalLayouts(Fraction.HALF).size)
        assertEquals(ModulationKey(1, KeySignatureMode.MAJOR), inserted.activeTonalLayouts(Fraction.HALF).single().key)
    }

    @Test
    fun baselineRightEndpointCanMoveWhileItsOriginRemainsFixed() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val baseline = initial.tonalLayouts.single()
        val shortened = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.SetTonalLayoutBounds(
                id = baseline.id,
                start = Fraction.ZERO,
                end = Fraction.QUARTER,
            ),
        )

        assertEquals(Fraction.QUARTER, shortened.tonalLayouts.single().end)
        assertNull(shortened.slots.last().tonalLayoutId)
        val rejected = HarmonyWorkspaceEditor.applyResult(
            initial,
            HarmonyWorkspaceCommand.SetTonalLayoutBounds(
                id = baseline.id,
                start = Fraction.EIGHTH,
                end = Fraction.QUARTER,
            ),
        )
        assertEquals(initial, rejected.state)
        assertEquals(
            "The initial tonal-layout baseline must remain anchored at the workspace origin",
            rejected.errorMessage,
        )
    }

    @Test
    fun insertedCustomaryProgressionOwnsAndLocksItsSlots() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val layoutId = initial.tonalLayouts.single().id
        val inserted = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "schoenberg.authentic-cadence",
                variantId = "V-I",
                sourceExerciseId = "schoenberg.cadence",
                sourceChapterId = "schoenberg.cadence",
                tonalLayoutId = layoutId,
                chordIdentities = listOf("V", "I"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
            ),
        )

        val instance = inserted.idiomInstances.single()
        assertEquals(instance.slotIds, inserted.slots.map { it.id })
        assertTrue(inserted.slots.all { it.id in instance.slotIds })
        val rejected = HarmonyWorkspaceEditor.applyResult(
            inserted,
            HarmonyWorkspaceCommand.ReplaceChord(0, "ii"),
        )
        assertEquals(inserted, rejected.state)
        assertEquals(
            "Customary progression slots can only be adjusted from the plan panel",
            rejected.errorMessage,
        )

        val removed = HarmonyWorkspaceEditor.apply(
            inserted,
            HarmonyWorkspaceCommand.RemoveIdiom(instance.id),
        )
        assertTrue(removed.idiomInstances.isEmpty())
        assertEquals(inserted.slots, removed.slots)
    }

    @Test
    fun removingLongCustomaryProgressionAtTailPreservesHarmonyTimelineChords() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val inserted = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.QUARTER,
                definitionId = "schoenberg.complete-cadence",
                variantId = "IV-I64-V-I",
                sourceExerciseId = "schoenberg.cadence",
                sourceChapterId = "schoenberg.cadence",
                tonalLayoutId = initial.tonalLayouts.single().id,
                chordIdentities = listOf("IV", "I64", "V", "I"),
                durations = List(4) { Fraction.QUARTER },
            ),
        )
        val instance = inserted.idiomInstances.single()

        val removed = HarmonyWorkspaceEditor.apply(
            inserted,
            HarmonyWorkspaceCommand.RemoveIdiom(instance.id),
        )

        assertTrue(removed.idiomInstances.isEmpty())
        assertEquals(inserted.slots, removed.slots)
        assertEquals(listOf("I", "IV", "I64", "V", "I"), removed.slots.map { it.chordIdentity })
    }

    @Test
    fun shorterIdiomReplacementRemovesUneditedGeneratedTail() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val inserted = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.QUARTER,
                definitionId = "long-cadence",
                variantId = "IV-I64-V-I",
                sourceExerciseId = "cadence",
                sourceChapterId = "cadence",
                tonalLayoutId = initial.tonalLayouts.single().id,
                chordIdentities = listOf("IV", "I64", "V", "I"),
                durations = List(4) { Fraction.QUARTER },
            ),
        )
        val instance = inserted.idiomInstances.single()

        val replaced = HarmonyWorkspaceEditor.apply(
            inserted,
            HarmonyWorkspaceCommand.ReplaceIdiom(
                id = instance.id,
                definitionId = "short-cadence",
                sourceExerciseId = "short-exercise",
                sourceChapterId = "short-chapter",
                variantId = "V-I",
                chordIdentities = listOf("V", "I"),
                durations = List(2) { Fraction.QUARTER },
                lastUserEditedOnset = inserted.slots.first().onset,
            ),
        )

        assertEquals(listOf("I", "V", "I"), replaced.slots.map { it.chordIdentity })
        assertEquals("short-cadence", replaced.idiomInstances.single().definitionId)
        assertEquals("short-exercise", replaced.idiomInstances.single().sourceExerciseId)
    }

    @Test
    fun shorterIdiomReplacementPreservesTailReachedByUserEditing() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val inserted = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.QUARTER,
                definitionId = "long-cadence",
                variantId = "IV-I64-V-I",
                sourceExerciseId = "cadence",
                sourceChapterId = "cadence",
                tonalLayoutId = initial.tonalLayouts.single().id,
                chordIdentities = listOf("IV", "I64", "V", "I"),
                durations = List(4) { Fraction.QUARTER },
            ),
        )
        val instance = inserted.idiomInstances.single()

        val replaced = HarmonyWorkspaceEditor.apply(
            inserted,
            HarmonyWorkspaceCommand.ReplaceIdiom(
                id = instance.id,
                variantId = "V-I",
                chordIdentities = listOf("V", "I"),
                durations = List(2) { Fraction.QUARTER },
                lastUserEditedOnset = inserted.slots.last().onset,
            ),
        )

        assertEquals(listOf("I", "V", "I", "V", "I"), replaced.slots.map { it.chordIdentity })
    }

    @Test
    fun customaryProgressionsCanShareACompatibleChordAndRemovalPreservesTheOtherInstance() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val layoutId = initial.tonalLayouts.single().id
        val first = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "secondary-predominant",
                variantId = "V-of-V-V",
                sourceExerciseId = "secondary",
                sourceChapterId = "secondary",
                tonalLayoutId = layoutId,
                chordIdentities = listOf("V/V", "V"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
            ),
        )
        val combined = HarmonyWorkspaceEditor.apply(
            first,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.QUARTER,
                definitionId = "authentic-cadence",
                variantId = "V-I",
                sourceExerciseId = "cadence",
                sourceChapterId = "cadence",
                tonalLayoutId = layoutId,
                chordIdentities = listOf("V", "I"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
            ),
        )

        assertEquals(listOf("V/V", "V", "I"), combined.slots.map { it.chordIdentity })
        val firstInstance = combined.idiomInstances.first()
        val cadence = combined.idiomInstances.last()
        assertEquals(firstInstance.slotIds.last(), cadence.slotIds.first())
        assertEquals(2, combined.idiomInstancesForSlot(cadence.slotIds.first()).size)

        val removed = HarmonyWorkspaceEditor.apply(
            combined,
            HarmonyWorkspaceCommand.RemoveIdiom(firstInstance.id),
        )

        assertEquals(listOf("V/V", "V", "I"), removed.slots.map { it.chordIdentity })
        assertEquals(cadence.slotIds, removed.idiomInstances.single().slotIds)
    }

    @Test
    fun overlappingIdiomInsertionSplitsCompatibleLongChordAtIncomingBoundaries() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val layoutId = initial.tonalLayouts.single().id
        val long = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "long-cadence",
                variantId = "long-V-I",
                sourceExerciseId = "cadence",
                sourceChapterId = "cadence",
                tonalLayoutId = layoutId,
                chordIdentities = listOf("V", "I"),
                durations = listOf(Fraction.HALF, Fraction.QUARTER),
            ),
        )
        val overlapped = HarmonyWorkspaceEditor.apply(
            long,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.QUARTER,
                definitionId = "short-cadence",
                variantId = "short-V-I",
                sourceExerciseId = "cadence",
                sourceChapterId = "cadence",
                tonalLayoutId = layoutId,
                chordIdentities = listOf("V", "I"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
            ),
        )

        assertEquals(
            listOf(
                Triple("V", Fraction.ZERO, Fraction.QUARTER),
                Triple("V", Fraction.QUARTER, Fraction.QUARTER),
                Triple("I", Fraction.HALF, Fraction.QUARTER),
            ),
            overlapped.slots.map { Triple(it.chordIdentity, it.onset, it.duration) },
        )
        assertEquals(3, overlapped.idiomInstances.first().slotIds.size)
        assertEquals(
            overlapped.idiomInstances.first().slotIds.takeLast(2),
            overlapped.idiomInstances.last().slotIds,
        )
    }

    @Test
    fun replacingAnIdiomCannotRewriteAChordSharedWithAnotherIdiom() {
        val initial = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val layoutId = initial.tonalLayouts.single().id
        val first = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                Fraction.ZERO, "secondary", "V-of-V-V", "secondary", "secondary", layoutId,
                listOf("V/V", "V"), listOf(Fraction.QUARTER, Fraction.QUARTER),
            ),
        )
        val combined = HarmonyWorkspaceEditor.apply(
            first,
            HarmonyWorkspaceCommand.InsertIdiom(
                Fraction.QUARTER, "cadence", "V-I", "cadence", "cadence", layoutId,
                listOf("V", "I"), listOf(Fraction.QUARTER, Fraction.QUARTER),
            ),
        )
        val firstInstance = combined.idiomInstances.first()

        val rejected = HarmonyWorkspaceEditor.applyResult(
            combined,
            HarmonyWorkspaceCommand.ReplaceIdiom(
                id = firstInstance.id,
                variantId = "V-of-V-IV",
                chordIdentities = listOf("V/V", "IV"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
            ),
        )

        assertSame(combined, rejected.state)
        assertTrue(rejected.errorMessage?.contains("harmonically identical") == true)
    }

    @Test
    fun rippleInsertionMovesTonalLayoutBoundariesWithLaterHarmony() {
        val baseline = workspace().withTonalLayoutBaseline(
            ModulationKey(0, KeySignatureMode.MAJOR)
        )
        val withLayout = HarmonyWorkspaceEditor.apply(
            baseline,
            HarmonyWorkspaceCommand.InsertTonalLayout(
                key = ModulationKey(-4, KeySignatureMode.MAJOR),
                start = Fraction.QUARTER,
                end = Fraction.HALF,
            ),
        )
        val inserted = HarmonyWorkspaceEditor.apply(
            withLayout,
            HarmonyWorkspaceCommand.InsertChord(
                index = 1,
                mode = InsertChordMode.RIPPLE,
                chordIdentity = "IV",
                duration = Fraction.EIGHTH,
            ),
        )

        assertEquals(Fraction(3, 8), inserted.tonalLayouts.last().start)
        assertEquals(Fraction(5, 8), inserted.tonalLayouts.last().end)
    }

    @Test
    fun exactInterpretationReplacementClearsLegacySymbolAtomically() {
        val ref = ChordInterpretationRef(
            SonorityId("sonority.c-natural.e-natural.g-natural"),
            InterpretationId("diatonic.test.1.3"),
        )

        val edited = HarmonyWorkspaceEditor.apply(
            workspace(),
            HarmonyWorkspaceCommand.ReplaceChord(
                index = 0,
                chordIdentity = null,
                chordInterpretationRef = ref,
            ),
        )

        assertEquals(ref, edited.slots.first().chordInterpretationRef)
        assertNull(edited.slots.first().chordIdentity)
    }

    @Test
    fun freeChoiceCanBePinnedAndRestoredAsThreeAtomicReplacements() {
        val state = workspace().withTonalLayoutBaseline(ModulationKey(0, KeySignatureMode.MAJOR))
        val choice = ChordSelectionCatalog.choices(ModulationKey(0, KeySignatureMode.MAJOR))
            .first { it.functionalSymbol == "V" }
        val pinnedRef = requireNotNull(choice.confirmedInterpretationRef)
        val free = WorkspaceChordChoice.of(choice.pitchClasses, choice.origin)
        val pinned = free.copy(pinnedInterpretationRef = pinnedRef)

        val freelySelected = HarmonyWorkspaceEditor.apply(
            state,
            HarmonyWorkspaceCommand.ReplaceChord(index = 0, chordChoice = free),
        )
        val locked = HarmonyWorkspaceEditor.apply(
            freelySelected,
            HarmonyWorkspaceCommand.ReplaceChord(index = 0, chordChoice = pinned),
        )
        val restored = HarmonyWorkspaceEditor.apply(
            locked,
            HarmonyWorkspaceCommand.ReplaceChord(index = 0, chordChoice = free),
        )

        assertEquals(free, freelySelected.slots.first().chordChoice)
        assertEquals(pinned, locked.slots.first().chordChoice)
        assertEquals(free, restored.slots.first().chordChoice)
    }

    @Test
    fun reducerRejectsPinnedInterpretationWhoseAudiblePitchClassesDoNotMatch() {
        val state = workspace().withTonalLayoutBaseline(ModulationKey(0, KeySignatureMode.MAJOR))
        val tonic = ChordSelectionCatalog.choices(ModulationKey(0, KeySignatureMode.MAJOR))
            .first { it.functionalSymbol == "I" }
        val invalid = WorkspaceChordChoice.of(
            pitchClasses = setOf(1, 2, 3),
            origin = tonic.origin,
            pinnedInterpretationRef = requireNotNull(tonic.confirmedInterpretationRef),
        )

        val result = HarmonyWorkspaceEditor.applyResult(
            state,
            HarmonyWorkspaceCommand.ReplaceChord(index = 0, chordChoice = invalid),
        )

        assertSame(state, result.state)
        assertTrue(result.errorMessage?.contains("does not match") == true)
    }

    @Test
    fun idiomBassCanChangeUnlessTheChapterRuleLocksItsInversion() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val initial = workspace().withTonalLayoutBaseline(key)
        val layoutId = initial.tonalLayouts.single().id
        val dominant = ChordSelectionCatalog.choices(key).first { it.functionalSymbol == "V" }
        val openChoice = WorkspaceChordChoice.of(
            dominant.pitchClasses,
            dominant.origin,
            requireNotNull(dominant.confirmedInterpretationRef),
        )
        val rootBass = dominant.rootPitchClass

        val open = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "derived-progression",
                variantId = "V-of-V-V",
                sourceExerciseId = "secondary",
                sourceChapterId = "secondary",
                tonalLayoutId = layoutId,
                chordIdentities = emptyList(),
                durations = listOf(Fraction.QUARTER),
                chordChoices = listOf(openChoice),
                fixedInversionStepIndices = emptySet(),
            ),
        )
        val changed = HarmonyWorkspaceEditor.apply(
            open,
            HarmonyWorkspaceCommand.SetChordBass(0, rootBass),
        )
        assertEquals(rootBass, changed.slots.first().chordChoice?.bassPitchClass)

        val fixed = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "authentic-cadence",
                variantId = "V-I",
                sourceExerciseId = "cadence",
                sourceChapterId = "cadence",
                tonalLayoutId = layoutId,
                chordIdentities = emptyList(),
                durations = listOf(Fraction.QUARTER),
                chordChoices = listOf(openChoice.copy(bassPitchClass = rootBass)),
                fixedInversionStepIndices = setOf(0),
            ),
        )
        val rejected = HarmonyWorkspaceEditor.applyResult(
            fixed,
            HarmonyWorkspaceCommand.SetChordBass(0, null),
        )
        assertSame(fixed, rejected.state)
        assertTrue(rejected.errorMessage?.contains("fixed by its source rule") == true)
    }

    @Test
    fun unlockedIdiomKeepsCustomaryBassButDoesNotBlockOverlappingIdiom() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val initial = workspace().withTonalLayoutBaseline(key)
        val layoutId = initial.tonalLayouts.single().id
        val dominant = ChordSelectionCatalog.choices(key).first { it.functionalSymbol == "V" }
        val dominantChoice = WorkspaceChordChoice.of(
            dominant.pitchClasses,
            dominant.origin,
            requireNotNull(dominant.confirmedInterpretationRef),
            bassPitchClass = dominant.rootPitchClass,
        )
        val alternateBass = dominant.pitchClasses.first { it != dominant.rootPitchClass }

        val first = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "first-predominant",
                variantId = "V",
                sourceExerciseId = "first",
                sourceChapterId = "first",
                tonalLayoutId = layoutId,
                chordIdentities = emptyList(),
                durations = listOf(Fraction.QUARTER),
                chordChoices = listOf(dominantChoice),
                fixedInversionStepIndices = emptySet(),
            ),
        )
        assertEquals(dominant.rootPitchClass, first.slots.first().chordChoice?.bassPitchClass)
        val edited = HarmonyWorkspaceEditor.apply(
            first,
            HarmonyWorkspaceCommand.SetChordBass(0, alternateBass),
        )

        val overlapped = HarmonyWorkspaceEditor.applyResult(
            edited,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "second-predominant",
                variantId = "V",
                sourceExerciseId = "second",
                sourceChapterId = "second",
                tonalLayoutId = layoutId,
                chordIdentities = emptyList(),
                durations = listOf(Fraction.QUARTER),
                chordChoices = listOf(dominantChoice),
                fixedInversionStepIndices = emptySet(),
            ),
        )

        assertTrue(overlapped.succeeded, overlapped.errorMessage)
        assertEquals(2, overlapped.state.idiomInstances.size)
        assertEquals(alternateBass, overlapped.state.slots.first().chordChoice?.bassPitchClass)

        val openFirst = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "open-predominant",
                variantId = "V",
                sourceExerciseId = "open",
                sourceChapterId = "open",
                tonalLayoutId = layoutId,
                chordIdentities = emptyList(),
                durations = listOf(Fraction.QUARTER),
                chordChoices = listOf(dominantChoice.copy(bassPitchClass = null)),
                fixedInversionStepIndices = emptySet(),
            ),
        )
        val filledFromSuggestion = HarmonyWorkspaceEditor.apply(
            openFirst,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "suggested-predominant",
                variantId = "V",
                sourceExerciseId = "suggested",
                sourceChapterId = "suggested",
                tonalLayoutId = layoutId,
                chordIdentities = emptyList(),
                durations = listOf(Fraction.QUARTER),
                chordChoices = listOf(dominantChoice),
                fixedInversionStepIndices = emptySet(),
            ),
        )
        assertEquals(
            dominant.rootPitchClass,
            filledFromSuggestion.slots.first().chordChoice?.bassPitchClass,
        )
    }

    @Test
    fun consecutiveTonicizationsRewriteSharedChordWithoutAccumulatingTheOriginalKey() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val d = ModulationKey(2, KeySignatureMode.MAJOR)
        val e = ModulationKey(4, KeySignatureMode.MAJOR)
        val initial = workspace().withTonalLayoutBaseline(c)
        val first = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "c-to-d",
                variantId = "X-Y",
                sourceExerciseId = "modulation",
                sourceChapterId = "modulation",
                chordIdentities = listOf("X", "Y"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
                tonalities = listOf(tonality(d, c), tonality(d)),
            ),
        )
        val combined = HarmonyWorkspaceEditor.apply(
            first,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.QUARTER,
                definitionId = "d-to-e",
                variantId = "Y-Z",
                sourceExerciseId = "modulation",
                sourceChapterId = "modulation",
                chordIdentities = listOf("Y", "Z"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
                tonalities = listOf(tonality(e, d), tonality(e)),
            ),
        )

        assertEquals(listOf("X", "Y", "Z"), combined.slots.map { it.chordIdentity })
        assertEquals(
            listOf(d, e),
            requireNotNull(combined.slots[1].tonality).readings.map { it.key }.sortedBy { it.fifths },
        )
        assertEquals(e, combined.continuationKey(combined.slots[1]))
        assertTrue(c !in requireNotNull(combined.slots[1].tonality).readings.map { it.key })

        val spans = combined.derivedTonalSpans()
        assertTrue(spans.any { it.key == d && it.start == Fraction.ZERO && it.end == Fraction.HALF })
        assertTrue(spans.any {
            it.key == e && it.start == Fraction.QUARTER && it.end == Fraction(3, 4)
        })
    }

    @Test
    fun removingIdiomPreservesChordTonalMarkersAndFollowingChordInheritsPrimary() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val d = ModulationKey(2, KeySignatureMode.MAJOR)
        val initial = workspace().withTonalLayoutBaseline(c)
        val inserted = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.InsertIdiom(
                onset = Fraction.ZERO,
                definitionId = "c-to-d",
                variantId = "X-Y",
                sourceExerciseId = "modulation",
                sourceChapterId = "modulation",
                chordIdentities = listOf("X", "Y"),
                durations = listOf(Fraction.QUARTER, Fraction.QUARTER),
                tonalities = listOf(tonality(d, c), tonality(d)),
            ),
        )
        val tonalitiesBeforeRemoval = inserted.slots.map { it.tonality }
        val removed = HarmonyWorkspaceEditor.apply(
            inserted,
            HarmonyWorkspaceCommand.RemoveIdiom(inserted.idiomInstances.single().id),
        )
        val continued = HarmonyWorkspaceEditor.apply(
            removed,
            HarmonyWorkspaceCommand.InsertChord(
                index = removed.slots.size,
                mode = InsertChordMode.RIPPLE,
                chordIdentity = "ii",
            ),
        )

        assertEquals(tonalitiesBeforeRemoval, removed.slots.map { it.tonality })
        assertEquals(d, continued.slots.last().tonality?.primary?.key)
        assertEquals(initial.tonalLayouts, continued.tonalLayouts)
    }

    @Test
    fun explicitOriginalKeySelectionReturnsNaturallyAfterTonicization() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val e = ModulationKey(4, KeySignatureMode.MAJOR)
        val state = workspace().withTonalLayoutBaseline(c).copy(
            slots = workspace().slots.mapIndexed { index, slot ->
                slot.copy(tonality = if (index == 0) tonality(e, c) else tonality(e))
            },
        )
        val returned = HarmonyWorkspaceEditor.apply(
            state,
            HarmonyWorkspaceCommand.InsertChord(
                index = state.slots.size,
                mode = InsertChordMode.RIPPLE,
                chordIdentity = "I",
                tonality = tonality(c),
            ),
        )

        assertEquals(c, returned.slots.last().tonality?.primary?.key)
        assertTrue(returned.slots.last().tonality?.alternates.orEmpty().isEmpty())
        assertEquals(c, returned.continuationKey(returned.slots.last()))
    }

    @Test
    fun sharedIdiomResolverGivesOnlyTheFirstChordASecondKeyReading() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val d = ModulationKey(2, KeySignatureMode.MAJOR)
        val cChoices = ChordSelectionCatalog.choices(c)
        val targetChoices = ChordSelectionCatalog.choices(d)
        val pivot = targetChoices.first { target ->
            target.confirmedInterpretationRef != null &&
                cChoices.any { source ->
                    source.pitchClasses == target.pitchClasses &&
                        source.confirmedInterpretationRef != null
                }
        }
        val targetOnly = targetChoices.first { it.confirmedInterpretationRef != null }
        val choices = listOf(pivot, targetOnly).map { choice ->
            WorkspaceChordChoice.of(
                pitchClasses = choice.pitchClasses,
                origin = choice.origin,
                pinnedInterpretationRef = requireNotNull(choice.confirmedInterpretationRef),
            )
        }

        val tonalities = requireNotNull(
            workspace().withTonalLayoutBaseline(c).resolveIdiomTonalities(
                onset = Fraction.ZERO,
                chordChoices = choices,
                sourceKey = c,
                targetKey = d,
            )
        )

        assertEquals(listOf(d, c), tonalities.first().readings.map { it.key })
        assertEquals(listOf(d), tonalities.last().readings.map { it.key })
    }

    @Test
    fun tonalOptionsAreOrderedBySignatureChangeAndCanCreateDoubleTonalityDirectly() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val catalogChoice = ChordSelectionCatalog.choices(c).first { choice ->
            choice.confirmedInterpretationRef != null
        }
        val committed = WorkspaceChordChoice.of(
            pitchClasses = catalogChoice.pitchClasses,
            origin = catalogChoice.origin,
            pinnedInterpretationRef = requireNotNull(catalogChoice.confirmedInterpretationRef),
        )
        val initial = workspace().withTonalLayoutBaseline(c).let { state ->
            state.copy(slots = state.slots.mapIndexed { index, slot ->
                if (index == 0) {
                    slot.copy(
                        chordIdentity = null,
                        chordInterpretationRef = null,
                        chordChoice = committed,
                    )
                } else slot
            })
        }
        val options = initial.slots.first().tonalityOptions(c)
        val cOption = requireNotNull(options.firstOrNull { it.key == c })
        val alternate = requireNotNull(options.firstOrNull { it.key != c })

        assertTrue(options.zipWithNext().all { (left, right) ->
            kotlin.math.abs(left.key.fifths - c.fifths) <=
                kotlin.math.abs(right.key.fifths - c.fifths)
        })

        val edited = HarmonyWorkspaceEditor.apply(
            initial,
            HarmonyWorkspaceCommand.SetChordTonality(
                index = 0,
                tonality = WorkspaceChordTonality(
                    primary = cOption.toReading(),
                    alternates = listOf(alternate.toReading()),
                ),
            ),
        )

        assertEquals(listOf(c, alternate.key), edited.slots.first().tonality?.readings?.map { it.key })
    }

    @Test
    fun emptyChordCanChooseWhetherToContinueTemporaryTonality() {
        val c = ModulationKey(0, KeySignatureMode.MAJOR)
        val d = ModulationKey(2, KeySignatureMode.MAJOR)
        val source = workspace().withTonalLayoutBaseline(c).let { state ->
            state.copy(slots = state.slots.mapIndexed { index, slot ->
                if (index == state.slots.lastIndex) slot.copy(tonality = tonality(d)) else slot
            })
        }
        val inserted = HarmonyWorkspaceEditor.apply(
            source,
            HarmonyWorkspaceCommand.InsertChordRange(
                onset = Fraction.HALF,
                duration = Fraction.QUARTER,
            ),
        )
        val emptyIndex = inserted.slots.lastIndex

        assertNull(inserted.slots[emptyIndex].chordChoice)
        assertEquals(d, inserted.slots[emptyIndex].tonality?.primary?.key)

        val dChoice = ChordSelectionCatalog.choices(d).first { choice ->
            val ref = choice.confirmedInterpretationRef
            ref != null && ChordSelectionCatalog.choices(c).none {
                it.pitchClasses == choice.pitchClasses
            }
        }
        val filled = HarmonyWorkspaceEditor.applyResult(
            inserted,
            HarmonyWorkspaceCommand.ReplaceChord(
                index = emptyIndex,
                chordChoice = WorkspaceChordChoice.of(
                    pitchClasses = dChoice.pitchClasses,
                    origin = dChoice.origin,
                    pinnedInterpretationRef = requireNotNull(dChoice.confirmedInterpretationRef),
                ),
            ),
        )
        assertTrue(filled.succeeded, filled.errorMessage)
        assertEquals(d, filled.state.slots[emptyIndex].tonality?.primary?.key)
        assertEquals(
            dChoice.confirmedInterpretationRef,
            filled.state.slots[emptyIndex].tonality?.primary?.interpretationRef,
        )

        val returned = HarmonyWorkspaceEditor.apply(
            filled.state,
            HarmonyWorkspaceCommand.SetChordTonality(emptyIndex, null),
        )
        assertNull(returned.slots[emptyIndex].tonality)
        assertNull(returned.slots[emptyIndex].chordChoice)
        assertEquals(c, returned.continuationKey(returned.slots[emptyIndex]))
    }

    private fun tonality(
        primary: ModulationKey,
        alternate: ModulationKey? = null,
    ): WorkspaceChordTonality = WorkspaceChordTonality(
        primary = WorkspaceChordTonalReading.of(primary),
        alternates = listOfNotNull(alternate?.let(WorkspaceChordTonalReading::of)),
    )

    private fun workspace(): HarmonyWorkspaceState =
        HarmonyWorkspaceState(
            voices = voicePlan.voices.map(WorkspaceVoiceSpec::fromTheory),
            slots = listOf(
                WorkspaceHarmonySlot(WorkspaceSlotId("slot-0"), Fraction.ZERO, Fraction.QUARTER, "I"),
                WorkspaceHarmonySlot(WorkspaceSlotId("slot-1"), Fraction.QUARTER, Fraction.QUARTER, "V"),
            ),
            notes = listOf(
                WorkspaceNote(
                    WorkspaceNoteId("note-0"),
                    TrackId("upper"),
                    Fraction.QUARTER,
                    Fraction.QUARTER,
                    Pitch.fromName("G4"),
                )
            ),
        )
}
