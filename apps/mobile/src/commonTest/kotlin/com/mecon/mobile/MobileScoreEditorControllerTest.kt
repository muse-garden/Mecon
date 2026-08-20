package com.mecon.mobile

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.DurationBase
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Accidental
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeSignature
import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.storage.StorageScore
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.ArpeggioType
import com.mecon.api.storage.Articulation
import com.mecon.api.storage.BeamingInfo
import com.mecon.api.storage.events.HairpinType
import com.mecon.api.storage.events.OctaveShiftType
import com.mecon.api.storage.tracks.Clef
import com.mecon.features.scoreediting.ScoreEditIntent
import com.mecon.features.scoreediting.ScoreEditDispatchResult
import com.mecon.features.scoreediting.ScoreEntryCursorAction
import com.mecon.features.scoreediting.ScoreInteractionCatalog
import com.mecon.features.scoreediting.ScoreInteractionFamily
import com.mecon.features.scoreediting.ScoreInteractionAnchor
import com.mecon.features.scoreediting.ScorePointerKind
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.features.scoreediting.ScoreInputCapabilities
import com.mecon.features.scoreediting.ScoreToolGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MobileScoreEditorControllerTest {
    @Test
    fun semanticStructureHandlesResizeVoltaAndMoveNavigationWithOneIntentEach() {
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 5)),
        )
        controller.dispatch(ScoreEditIntent.SetBarline(0, 2, BarlineType.REPEAT_RIGHT))
        val volta = controller.dispatch(ScoreEditIntent.ToggleVoltaPair(1, boundaryMeasure = 0))
        val ending = volta.frame.computedScore.voltaEndings.last { 2 in it.numbers }
        controller.setSelection(
            listOf(ScoreSelectionTarget.VoltaEnding(ending.startMeasure, ending.endMeasure, ending.numbers)),
        )
        controller.activate(ScoreInteractionCatalog.HANDLE)
        val voltaRevision = controller.state.value.frame.revision
        val resized = assertNotNull(controller.resizeSelectedVolta(1))
        assertEquals(voltaRevision + 1, resized.frame.revision)
        assertEquals(setOf(2), resized.frame.runtimeScore.getMeasure(4)?.voltaNumbers)

        val navigation = controller.dispatch(
            ScoreEditIntent.ToggleNavigationMark(
                controller.state.value.frame.revision,
                boundaryMeasure = 4,
                mark = NavigationMark.CODA,
            ),
        )
        assertTrue(navigation.frame.selection.single() is ScoreSelectionTarget.NavigationMark)
        controller.activate(ScoreInteractionCatalog.HANDLE)
        val navigationRevision = controller.state.value.frame.revision
        val nudged = assertNotNull(controller.nudgeSelectedNavigation(boundaryDelta = -1, dy = -0.5f))
        assertEquals(navigationRevision + 1, nudged.frame.revision)
        assertTrue(NavigationMark.CODA in nudged.frame.runtimeScore.getMeasure(3)!!.navigationMarks)
        assertEquals(-0.5f, nudged.frame.runtimeScore.getMeasure(3)!!.navigationMarkOffsets.getValue(NavigationMark.CODA).dy)
    }

    @Test
    fun propertyDraftAdaptersSubmitTempoAndPerformanceAsSingleIntents() {
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 1)),
        )
        val tempo = controller.addTempoMark(TimeCode.of(1, Fraction.ZERO), 96f)
        assertTrue(tempo.frame.selection.single() is ScoreSelectionTarget.Attachment)
        controller.activate(ScoreInteractionCatalog.PROPERTY)
        val tempoRevision = controller.state.value.frame.revision
        val updatedTempo = assertNotNull(controller.updateSelectedTempo(108f))
        assertEquals(tempoRevision + 1, updatedTempo.frame.revision)
        assertEquals(108f, updatedTempo.frame.computedScore.tempoKeyframes.single().effectiveBpm)

        val fermata = controller.addFermata(TimeCode.of(1, Fraction.QUARTER))
        assertTrue(fermata.frame.selection.single() is ScoreSelectionTarget.Attachment)
        controller.activate(ScoreInteractionCatalog.PROPERTY)
        val performanceRevision = controller.state.value.frame.revision
        val performance = assertNotNull(controller.updateSelectedPerformance(Fraction(3, 2)))
        assertEquals(performanceRevision + 1, performance.frame.revision)
    }

    @Test
    fun multiSelectionCanBecomeOneSmallNoteGroup() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val controller = MobileScoreEditorController.open(score)
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            com.mecon.features.scoreediting.ScoreEntryCursor(
                voiceId,
                TimeCode.of(1, Fraction.ZERO),
            ),
        )
        val first = assertNotNull(controller.insertRest(Duration.EIGHTH))
        val firstTarget = first.frame.selection.single() as ScoreSelectionTarget.Event
        val second = assertNotNull(controller.insertRest(Duration.EIGHTH))
        val secondTarget = second.frame.selection.single() as ScoreSelectionTarget.Event

        controller.activate(ScoreInteractionCatalog.NAVIGATION)
        controller.setSelection(
            listOf(
                firstTarget.copy(voiceTrackId = voiceId),
                secondTarget.copy(voiceTrackId = voiceId),
            ),
        )
        val grouped = assertNotNull(controller.createSmallNoteRegionFromSelection())

        assertEquals(ScoreInteractionCatalog.ENTRY_NOTE, controller.state.value.interaction.activeCommandId)
        assertNotNull(controller.state.value.interaction.smallNoteAppendStartEventId)
        assertNotNull(grouped.noteInputTransition)
    }

    @Test
    fun structureBoundaryActionsUseSharedStructureIntents() {
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 2)),
        )
        controller.activate(ScoreInteractionCatalog.STRUCTURE)
        controller.target(listOf(ScoreInteractionAnchor.Boundary(1)), preview = true)

        val inserted = controller.insertMeasures(afterMeasure = 1)
        assertEquals(3, inserted.frame.runtimeScore.measures.size)
        val deleted = assertNotNull(controller.deleteMeasures(setOf(2)))
        assertEquals(2, deleted.frame.runtimeScore.measures.size)
        val barline = controller.setBarline(1, BarlineType.DOUBLE)
        assertTrue(barline.frame.selection.single() is ScoreSelectionTarget.Barline)
    }

    @Test
    fun selectedEventTransformsEachDispatchOneSharedIntent() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val controller = MobileScoreEditorController.open(score)
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            com.mecon.features.scoreediting.ScoreEntryCursor(
                voiceId,
                TimeCode.of(1, Fraction.ZERO),
            ),
        )
        controller.insertMidiNote(60, Duration.QUARTER)
        val eventId = controller.state.value.frame.runtimeScore.getVoiceTrack(voiceId)!!.events.first().id

        controller.activate(ScoreInteractionCatalog.NAVIGATION)
        controller.setSelection(listOf(ScoreSelectionTarget.Event(eventId, voiceId, setOf(0))))
        assertEquals(listOf(eventId), controller.state.value.frame.selection.map { (it as ScoreSelectionTarget.Event).eventId })
        val selectedRevision = controller.state.value.frame.revision

        val transposed = assertNotNull(controller.transposeSelection(1))
        assertEquals(selectedRevision + 1, transposed.frame.revision)
        val resized = assertNotNull(controller.setSelectionDuration(Duration.HALF))
        assertEquals(transposed.frame.revision + 1, resized.frame.revision)
        assertEquals(
            Duration.HALF,
            resized.frame.runtimeScore.getVoiceTrack(voiceId)!!.events.first().duration,
        )
        val deleted = assertNotNull(controller.deleteSelection())
        assertEquals(resized.frame.revision + 1, deleted.frame.revision)
        assertTrue(
            deleted.frame.runtimeScore.getVoiceTrack(voiceId)!!.events
                .none { !it.isRest && it.pitches.isNotEmpty() },
        )
    }

    @Test
    fun chordAndNotationTransformsUseOneSharedIntentPerAction() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val voiceId = score.voiceTracks.keys.single()
        val controller = MobileScoreEditorController.open(score)
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            com.mecon.features.scoreediting.ScoreEntryCursor(voiceId, TimeCode.of(1, Fraction.ZERO)),
        )

        val chord = assertNotNull(
            controller.insertChord(
                listOf(Pitch(0), Pitch(2), Pitch(4)),
                Duration.EIGHTH,
            ),
        )
        assertEquals(1, chord.frame.revision)
        val event = chord.frame.runtimeScore.getVoiceTrack(voiceId)!!.events.first { !it.isRest }
        controller.activate(ScoreInteractionCatalog.NAVIGATION)
        controller.setSelection(listOf(ScoreSelectionTarget.Event(event.id, voiceId, setOf(0, 1, 2))))

        var revision = controller.state.value.frame.revision
        fun assertOne(result: ScoreEditDispatchResult?) {
            val committed = assertNotNull(result)
            assertEquals(++revision, committed.frame.revision)
        }
        assertOne(controller.setSelectionAccidental(Accidental.SHARP))
        assertOne(controller.setSelectionTies(true))
        assertOne(controller.setSelectionBeaming(BeamingInfo.NONE))
        assertOne(controller.toggleSelectionArticulation(Articulation.STACCATO))
        assertOne(controller.setSelectionArpeggio(ArpeggioType.NORMAL))
        assertOne(controller.copySelection())

        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            com.mecon.features.scoreediting.ScoreEntryCursor(voiceId, TimeCode.of(2, Fraction.ZERO)),
        )
        assertOne(controller.pasteAtEntryCursor())
        assertTrue(
            controller.state.value.frame.runtimeScore.getVoiceTrack(voiceId)!!.events
                .any { !it.isRest && it.onset.measure == 2 },
        )
    }

    @Test
    fun structureRangeAdaptersCoverRepeatVoltaNavigationBreakAndVisibility() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 5))
        val staffId = score.staffTracks.keys.single()
        val controller = MobileScoreEditorController.open(score)
        controller.activate(ScoreInteractionCatalog.STRUCTURE)

        var revision = controller.state.value.frame.revision
        fun assertOne(result: ScoreEditDispatchResult?) {
            val committed = assertNotNull(result)
            assertEquals(++revision, committed.frame.revision)
        }
        assertOne(controller.setBarline(2, BarlineType.REPEAT_RIGHT))
        assertOne(controller.setBarlineRepeatCount(2, 3))
        assertOne(controller.toggleVoltaPair(0))
        assertOne(controller.toggleNavigationMark(4, NavigationMark.SEGNO))
        assertOne(controller.setLayoutBreak(3, LayoutBreakKind.SYSTEM))
        assertOne(controller.setStaffVisibility(setOf(staffId), 2, 3, hidden = true))

        assertEquals(3, controller.state.value.frame.runtimeScore.getMeasure(2)!!.repeatCount)
        assertTrue(NavigationMark.SEGNO in controller.state.value.frame.runtimeScore.getMeasure(4)!!.navigationMarks)
        assertTrue(3 in controller.state.value.frame.runtimeScore.forcedSystemBreaks)
        assertTrue(controller.state.value.frame.runtimeScore.staffTracks.getValue(staffId).isHidden(2))
    }

    @Test
    fun phoneCreatesTupletRegionThenEntersAndAdvancesUsingSessionResults() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val voiceId = score.voiceTracks.keys.single()
        val controller = MobileScoreEditorController.open(
            score,
            ScoreInputCapabilities(pointerKinds = setOf(ScorePointerKind.TOUCH)),
        )
        val start = TimeCode.of(1, Fraction.ZERO)

        controller.createTupletRegion(voiceId, start, Duration.QUARTER, count = 3)
        assertEquals(ScoreInteractionCatalog.ENTRY_NOTE, controller.state.value.interaction.activeCommandId)
        assertEquals(ScoreInteractionFamily.E, controller.state.value.interaction.family)
        assertEquals(start, controller.state.value.interaction.entryCursor?.position)

        val inserted = assertNotNull(controller.insertMidiNote(60, Duration.EIGHTH))
        assertEquals(2, inserted.frame.revision)
        assertEquals(TimeCode.of(1, Fraction(1, 12)), controller.state.value.interaction.entryCursor?.position)

        controller.moveEntryCursor(ScoreEntryCursorAction.NEXT_MEASURE)
        assertEquals(TimeCode.of(2, Fraction.ZERO), controller.state.value.interaction.entryCursor?.position)
    }

    @Test
    fun activitySwitchCancelsOnlyTheTransientRunAndKeepsTheSession() {
        val controller = MobileScoreEditorController.open(
            StorageScore.create(StorageScore.CreationOptions(measureCount = 1)),
        )
        controller.activate(ScoreInteractionCatalog.SPAN_SYMBOL)
        controller.switchActivity(MobileScoreActivity.EDIT)

        assertEquals(MobileScoreActivity.EDIT, controller.state.value.activity)
        assertEquals(ScoreToolGroup.SPANS, controller.state.value.activeToolGroup)
        assertEquals(ScoreInteractionCatalog.SPAN_SYMBOL, controller.state.value.interaction.activeCommandId)
        assertEquals(0, controller.state.value.frame.revision)
    }

    @Test
    fun pointAndSpanWorkflowsDispatchOneSharedIntentPerConfirmation() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 2))
        val staffId = score.staffTracks.keys.single()
        val controller = MobileScoreEditorController.open(score)
        val start = TimeCode.of(1, Fraction.ZERO)
        val end = TimeCode.of(1, Fraction.HALF)

        controller.activate(ScoreInteractionCatalog.POINT_SYMBOL)
        controller.target(listOf(ScoreInteractionAnchor.StaffTime(staffId, start)), preview = true)
        val clef = controller.setClef(staffId, start, Clef.BASS)
        assertEquals(1, clef.frame.revision)
        assertEquals(ScoreInteractionFamily.P, controller.state.value.interaction.family)
        assertTrue(controller.state.value.interaction.anchors.isEmpty())

        controller.setKeySignature(start, KeySignature.G_MAJOR)
        controller.setTimeSignature(1, TimeSignature(4, 4))
        assertEquals(3, controller.state.value.frame.revision)

        controller.activate(ScoreInteractionCatalog.SPAN_SYMBOL)
        controller.target(
            listOf(
                ScoreInteractionAnchor.StaffTime(staffId, start),
                ScoreInteractionAnchor.StaffTime(staffId, end),
            ),
            preview = true,
        )
        controller.addHairpin(staffId, start, end, HairpinType.CRESCENDO)
        controller.addOctaveShift(staffId, start, end, OctaveShiftType.OTTAVA)
        assertEquals(5, controller.state.value.frame.revision)
        assertEquals(ScoreInteractionFamily.S, controller.state.value.interaction.family)
        assertTrue(controller.state.value.interaction.anchors.isEmpty())
    }

    @Test
    fun pointAndSpanSuccessKeepTheirPhoneToolGroupButResetEveryAnchor() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val staffId = score.staffTracks.keys.single()
        val controller = MobileScoreEditorController.open(score)

        controller.activate(ScoreInteractionCatalog.POINT_SYMBOL)
        controller.target(
            listOf(ScoreInteractionAnchor.StaffTime(staffId, TimeCode.of(1, Fraction.ZERO))),
            preview = true,
        )
        controller.dispatch(
            ScoreEditIntent.SetClef(0, staffId, TimeCode.of(1, Fraction.ZERO), Clef.BASS),
        )
        assertEquals(ScoreToolGroup.POINT_SYMBOLS, controller.state.value.activeToolGroup)
        assertEquals(ScoreInteractionCatalog.POINT_SYMBOL, controller.state.value.interaction.activeCommandId)
        assertTrue(controller.state.value.interaction.anchors.isEmpty())

        controller.activate(ScoreInteractionCatalog.SPAN_SYMBOL)
        controller.target(
            listOf(
                ScoreInteractionAnchor.StaffTime(staffId, TimeCode.of(1, Fraction.ZERO)),
                ScoreInteractionAnchor.StaffTime(staffId, TimeCode.of(1, Fraction.QUARTER)),
            ),
            preview = true,
        )
        controller.dispatch(
            ScoreEditIntent.AddHairpin(
                1,
                staffId,
                TimeCode.of(1, Fraction.ZERO),
                TimeCode.of(1, Fraction.QUARTER),
                HairpinType.CRESCENDO,
            ),
        )
        assertEquals(ScoreToolGroup.SPANS, controller.state.value.activeToolGroup)
        assertEquals(ScoreInteractionCatalog.SPAN_SYMBOL, controller.state.value.interaction.activeCommandId)
        assertTrue(controller.state.value.interaction.anchors.isEmpty())
    }

    @Test
    fun durationAndDotSelectionDrivePianoAndRestEntryWithoutPlatformMath() {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = 1))
        val voiceId = score.voiceTracks.keys.single()
        val controller = MobileScoreEditorController.open(score)
        controller.activate(
            ScoreInteractionCatalog.ENTRY_NOTE,
            com.mecon.features.scoreediting.ScoreEntryCursor(
                voiceId,
                TimeCode.of(1, Fraction.ZERO),
            ),
        )

        controller.selectDuration(DurationBase.EIGHTH)
        controller.toggleDot()
        assertEquals(Duration.DOTTED_EIGHTH, controller.state.value.noteInput.duration)

        assertNotNull(controller.insertMidiNote(60))
        assertEquals(
            TimeCode.of(1, Fraction(3, 16)),
            controller.state.value.interaction.entryCursor?.position,
        )

        controller.setRestMode(true)
        assertNotNull(controller.insertRest())
        assertEquals(2, controller.state.value.frame.revision)
        // The shared session chooses the next editable empty slot; the platform must not advance by
        // adding the selected duration itself (the surrounding rest structure may choose 1/4 here).
        assertEquals(
            TimeCode.of(1, Fraction.QUARTER),
            controller.state.value.interaction.entryCursor?.position,
        )
        assertTrue(controller.state.value.noteInput.restMode)
    }
}
