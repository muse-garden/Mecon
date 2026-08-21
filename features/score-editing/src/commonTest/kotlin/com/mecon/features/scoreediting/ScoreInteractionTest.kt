package com.mecon.features.scoreediting

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.StorageScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScoreInteractionTest {
    @Test
    fun pointAndSpanToolsStayArmedButDiscardCommittedAnchors() {
        val fixture = sessionFixture()
        val point = ScoreEditIntent.SetClef(
            expectedRevision = 0,
            staffTrackId = fixture.score.staffTracks.keys.single(),
            onset = TimeCode.of(1, Fraction.ZERO),
            clef = com.mecon.api.storage.tracks.Clef.TREBLE,
        )
        val controller = ScoreInteractionController()
        controller.begin(ScoreInteractionCatalog.POINT_SYMBOL)
        controller.target(listOf(ScoreInteractionAnchor.StaffTime(fixture.score.staffTracks.keys.single(), TimeCode.of(1, Fraction.ZERO))))
        controller.accept(point, fixture.session.dispatch(point))

        assertEquals(ScoreInteractionCatalog.POINT_SYMBOL, controller.state.value.activeCommandId)
        assertEquals(ScoreInteractionPhase.ARMED, controller.state.value.phase)
        assertTrue(controller.state.value.anchors.isEmpty())
    }

    @Test
    fun emptyTupletAndSmallNoteGroupBothHandOffToOrdinaryEntry() {
        val fixture = sessionFixture()
        val start = TimeCode.of(1, Fraction.ZERO)
        val controller = ScoreInteractionController()
        val tuplet = ScoreEditIntent.CreateTupletRegion(0, fixture.voiceId, start, Duration.QUARTER, 3)
        controller.begin(ScoreInteractionCatalog.ENTRY_TUPLET_REGION)
        controller.accept(tuplet, fixture.session.dispatch(tuplet))
        assertEquals(ScoreInteractionCatalog.ENTRY_NOTE, controller.state.value.activeCommandId)
        assertEquals(start, controller.state.value.entryCursor?.position)

        val smallFixture = sessionFixture()
        val rest = smallFixture.session.dispatch(
            ScoreEditIntent.InsertNote(0, smallFixture.voiceId, start, Duration.QUARTER, isRest = true),
        )
        val restId = assertNotNull(rest.frame.selection.single().eventIdOrNull)
        val smallNotes = ScoreEditIntent.CreateSmallNoteRegions(
            expectedRevision = 1,
            targets = listOf(
                ScoreEditIntent.EventGroupTarget(smallFixture.voiceId, setOf(restId)),
            ),
        )
        controller.begin(ScoreInteractionCatalog.GROUP_SMALL_NOTES)
        controller.accept(smallNotes, smallFixture.session.dispatch(smallNotes))
        assertEquals(ScoreInteractionCatalog.ENTRY_NOTE, controller.state.value.activeCommandId)
        assertEquals(start, controller.state.value.entryCursor?.position)
        assertNotNull(controller.state.value.smallNoteAppendStartEventId)
    }

    @Test
    fun ordinaryEntryAdvancesAndNavigationUsesSemanticEventsAndMeasures() {
        val fixture = sessionFixture(measures = 2)
        val controller = ScoreInteractionController()
        val first = ScoreEditIntent.InsertNote(0, fixture.voiceId, TimeCode.of(1, Fraction.ZERO), Duration.QUARTER, Pitch.C4)
        controller.begin(
            ScoreInteractionCatalog.ENTRY_NOTE,
            ScoreEntryCursor(fixture.voiceId, first.start),
        )
        controller.accept(first, fixture.session.dispatch(first))
        assertEquals(TimeCode.of(1, Fraction.QUARTER), controller.state.value.entryCursor?.position)

        val second = ScoreEditIntent.InsertNote(1, fixture.voiceId, TimeCode.of(1, Fraction.HALF), Duration.QUARTER, Pitch.D4)
        controller.accept(second, fixture.session.dispatch(second))
        controller.moveEntryCursor(fixture.session.frame().runtimeScore, ScoreEntryCursorAction.PREVIOUS_NOTE)
        assertEquals(TimeCode.of(1, Fraction.HALF), controller.state.value.entryCursor?.position)
        controller.moveEntryCursor(fixture.session.frame().runtimeScore, ScoreEntryCursorAction.PREVIOUS_NOTE)
        assertEquals(TimeCode.of(1, Fraction.ZERO), controller.state.value.entryCursor?.position)
        controller.moveEntryCursor(fixture.session.frame().runtimeScore, ScoreEntryCursorAction.NEXT_MEASURE)
        assertEquals(TimeCode.of(2, Fraction.ZERO), controller.state.value.entryCursor?.position)

        val boundaryFixture = sessionFixture(measures = 2)
        val boundaryController = ScoreInteractionController()
        val whole = ScoreEditIntent.InsertNote(
            0,
            boundaryFixture.voiceId,
            TimeCode.of(1, Fraction.ZERO),
            Duration.WHOLE,
            Pitch.C4,
        )
        boundaryController.begin(
            ScoreInteractionCatalog.ENTRY_NOTE,
            ScoreEntryCursor(boundaryFixture.voiceId, whole.start),
        )
        boundaryController.accept(whole, boundaryFixture.session.dispatch(whole))
        assertEquals(TimeCode.of(2, Fraction.ZERO), boundaryController.state.value.entryCursor?.position)

        boundaryController.moveEntryCursor(
            boundaryFixture.session.frame().runtimeScore,
            ScoreEntryCursorAction.PREVIOUS_NOTE,
        )
        assertEquals(TimeCode.of(1, Fraction.ZERO), boundaryController.state.value.entryCursor?.position)
        boundaryController.moveEntryCursor(
            boundaryFixture.session.frame().runtimeScore,
            ScoreEntryCursorAction.NEXT_NOTE,
        )
        assertEquals(TimeCode.of(2, Fraction.ZERO), boundaryController.state.value.entryCursor?.position)
        assertEquals(null, boundaryController.state.value.entryCursor?.anchorEventId)
    }

    @Test
    fun catalogHasUniqueStableCommandsAndClassifiesNewRegionIntentAsEntry() {
        assertEquals(ScoreInteractionCatalog.specs.size, ScoreInteractionCatalog.specs.map { it.commandId }.toSet().size)
        assertTrue(ScoreInteractionCatalog.specs.all { it.targeting.isNotEmpty() })
        val fixture = sessionFixture()
        val intent = ScoreEditIntent.CreateTupletRegion(
            0,
            fixture.voiceId,
            TimeCode.of(1, Fraction.ZERO),
            Duration.QUARTER,
            3,
        )
        assertEquals(ScoreInteractionFamily.E, ScoreInteractionCatalog.family(intent))
    }

    @Test
    fun targetingSeparatesSnapTopologyFromCoordinateFreedom() {
        val fixture = sessionFixture()
        val staffId = fixture.score.staffTracks.keys.single()
        val time = TimeCode.of(1, Fraction.ZERO)

        val clef = ScoreInteractionCatalog.targeting(
            ScoreEditIntent.SetClef(0, staffId, time, com.mecon.api.storage.tracks.Clef.TREBLE),
        )
        val breath = ScoreInteractionCatalog.targeting(
            ScoreEditIntent.AddBreathMark(0, staffId, time),
        )
        val fermata = ScoreInteractionCatalog.targeting(ScoreEditIntent.AddFermata(0, time))

        assertEquals(ScoreSnapTopology.INSERTION_BOUNDARY, clef.snapTopology)
        assertEquals(ScoreCoordinateFreedom.FIXED_XY, clef.coordinateFreedom)
        assertEquals(ScoreSnapTopology.INSERTION_BOUNDARY, breath.snapTopology)
        assertEquals(ScoreCoordinateFreedom.ADJUSTABLE_XY, breath.coordinateFreedom)
        assertEquals(ScoreSnapTopology.EVENT_TIME, fermata.snapTopology)
        assertEquals(ScoreCoordinateFreedom.FIXED_XY, fermata.coordinateFreedom)
        assertTrue("snapTopology" in ScoreInteractionCatalog.encodeSpecs())
        assertTrue("coordinateFreedom" in ScoreInteractionCatalog.encodeSpecs())
    }

    @Test
    fun historyDoesNotDisarmAStickyPlacementTool() {
        val fixture = sessionFixture()
        val controller = ScoreInteractionController()
        controller.begin(ScoreInteractionCatalog.SPAN_SYMBOL)
        val undo = ScoreEditIntent.Undo(expectedRevision = 0)
        controller.accept(undo, fixture.session.dispatch(undo))
        assertEquals(ScoreInteractionCatalog.SPAN_SYMBOL, controller.state.value.activeCommandId)
        assertEquals(ScoreInteractionFamily.S, controller.state.value.family)
    }

    private data class Fixture(
        val score: StorageScore,
        val voiceId: com.mecon.api.primitive.TrackId,
        val session: ScoreEditingSession,
    )

    private fun sessionFixture(measures: Int = 1): Fixture {
        val score = StorageScore.create(StorageScore.CreationOptions(measureCount = measures))
        return Fixture(score, score.voiceTracks.keys.single(), ScoreEditingSession.open(score))
    }
}
