package com.mecon.desktop.ui.exploration

import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.desktop.ui.views.TICKS_PER_QUARTER
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.ResolvedTimeAxis
import com.mecon.renderer.layout.TimeAxisAnchor
import com.mecon.theory.harmony.ChordSelectionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FreePracticePianoRollProjectionTest {
    @Test
    fun alignedAxisUsesPhysicalPixelsAtHighDensity() {
        val density = 1.5f
        val position = StaffSpace(10f)

        val screenPixels = freePracticeAxisScreenPixels(position, density)

        assertEquals(120f, screenPixels)
        assertEquals(position, freePracticeAxisStaffSpace(screenPixels, density))
    }

    @Test
    fun alignedRequestKeepsLogicalBeatWidthAcrossDensities() {
        val workspace = initialWorkspace(4)
        val score = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )

        val densityOne = freePracticeAlignedTimeAxisRequest(
            workspace = workspace,
            score = score,
            beatWidthPx = 144f,
            renderDensity = 1f,
            revision = 1,
        )
        val densityOneAndHalf = freePracticeAlignedTimeAxisRequest(
            workspace = workspace,
            score = score,
            beatWidthPx = 216f,
            renderDensity = 1.5f,
            revision = 2,
        )

        assertEquals(
            densityOne.segments.single().preferredWidth,
            densityOneAndHalf.segments.single().preferredWidth,
        )
        assertEquals(StaffSpace(18f), densityOne.segments.single().preferredWidth)
        assertEquals(StaffSpace(0.75f), densityOne.notationContentStartGap)
    }

    @Test
    fun settledAxisExtendsNewWorkspaceTailAtTheRequestedBeatWidth() {
        val workspace = initialWorkspace(4)
        val score = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val axis = ResolvedTimeAxis(
            anchors = listOf(
                TimeAxisAnchor(TimeCode.of(1, Fraction.ZERO), Fraction.ZERO, StaffSpace.ZERO),
                TimeAxisAnchor(
                    TimeCode.of(1, Fraction.QUARTER),
                    Fraction.QUARTER,
                    StaffSpace(18f),
                ),
                TimeAxisAnchor(
                    TimeCode.of(1, Fraction.HALF),
                    Fraction.HALF,
                    StaffSpace(19f),
                ),
            ),
            contentEndX = StaffSpace(19f),
            revision = 1,
            scoreTimeMap = ScoreTimeMap.from(score),
        )

        val appendedEndX = freePracticeExtendedAxisX(
            axis = axis,
            settledEndTime = Fraction.QUARTER,
            absoluteTime = Fraction.HALF,
            beatWidthPx = 144f,
            renderDensity = 1f,
        )

        assertEquals(288f, appendedEndX)
        assertEquals(
            Fraction.HALF,
            freePracticeExtendedAxisTime(
                axis,
                Fraction.QUARTER,
                appendedEndX,
                144f,
                1f,
            ),
        )
    }

    @Test
    fun workspaceStartsWithOnlyTheTonicChord() {
        listOf(
            initialWorkspace(4),
            initialWorkspace(4, ModulationKey(0, KeySignatureMode.MINOR)),
        ).forEach { workspace ->
            assertNotNull(workspace.slots.single().chordChoice?.pinnedInterpretationRef)
            assertNotNull(workspace.slots.single().chordChoice?.pitchClasses)
            assertNull(workspace.slots.single().chordInterpretationRef)
            assertNull(workspace.slots.single().chordIdentity)
        }
    }

    @Test
    fun projectsChordLabelsAndToneClassesFromTheory() {
        val initial = initialWorkspace(4)
        val dominantChoice = ChordSelectionCatalog
            .choices(ModulationKey(0, KeySignatureMode.MAJOR))
            .first { it.functionalSymbol == "V" }
        val dominantRef = requireNotNull(dominantChoice.confirmedInterpretationRef)
        val workspace = initial.copy(
            slots = listOf(
                initial.slots.single(),
                initial.slots.single().copy(
                    id = com.mecon.theory.freepractice.WorkspaceSlotId("slot-1"),
                    onset = com.mecon.api.primitive.Fraction.QUARTER,
                    chordChoice = com.mecon.theory.freepractice.WorkspaceChordChoice.of(
                        dominantChoice.pitchClasses,
                        dominantChoice.origin,
                        dominantRef,
                    ),
                ),
            )
        )

        val spans = freePracticeChordSpans(workspace)

        assertEquals(listOf("I", "V"), spans.map { it.symbol })
        assertEquals(listOf(0, 4, 7), spans[0].pitchClasses)
        assertEquals(listOf(2, 7, 11), spans[1].pitchClasses)
        assertEquals(spans[0].endTicks, spans[1].onsetTicks)
    }

    @Test
    fun dottedGridRestartsAtEachMeasureInsteadOfDrifting() {
        val secondMeasureStart = TICKS_PER_QUARTER * 2L

        val snapped = freePracticeGridTime(
            hitTicks = secondMeasureStart + TICKS_PER_QUARTER / 2L,
            gridDuration = Fraction(3, 8),
        )

        assertEquals(secondMeasureStart, snapped.absoluteTicks)
        assertEquals(TimeCode.of(2, Fraction.ZERO), snapped.timeCode)
    }

    @Test
    fun rejectsNotesStartingAtOrExtendingPastScoreEnd() {
        val workspace = initialWorkspace(4)
        val score = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val scoreEnd = TICKS_PER_QUARTER * 2L

        assertNotNull(
            freePracticeGridTimeWithinScore(
                hitTicks = scoreEnd - TICKS_PER_QUARTER,
                gridDuration = Fraction.QUARTER,
                score = score,
            )
        )
        assertNull(
            freePracticeGridTimeWithinScore(
                hitTicks = scoreEnd,
                gridDuration = Fraction.QUARTER,
                score = score,
            )
        )
        assertNull(
            freePracticeGridTimeWithinScore(
                hitTicks = scoreEnd - TICKS_PER_QUARTER / 2L,
                gridDuration = Fraction(3, 8),
                score = score,
            )
        )
    }

    @Test
    fun draggedRangeCoversEveryGridCellInEitherDirection() {
        val workspace = initialWorkspace(4)
        val score = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(workspace, KeySignature.C_MAJOR)
        )
        val forward = freePracticeGridRangeWithinScore(
            firstTicks = 20,
            secondTicks = TICKS_PER_QUARTER + 20L,
            gridDuration = Fraction.EIGHTH,
            score = score,
        )
        val backward = freePracticeGridRangeWithinScore(
            firstTicks = TICKS_PER_QUARTER + 20L,
            secondTicks = 20,
            gridDuration = Fraction.EIGHTH,
            score = score,
        )

        assertEquals(Fraction(3, 8), forward?.duration)
        assertEquals(forward, backward)
    }

    @Test
    fun chordTonePitchSnapUsesNearestHighlightedRow() {
        assertEquals(64, nearestChordToneMidi(65, setOf(0, 4, 7)))
        assertEquals(65, nearestChordToneMidi(65, emptySet()))
    }
}
