package com.mecon.theory.freepractice

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PracticeWritingScopePlannerTest {
    @Test
    fun automaticScopeExtendsAcrossSelectedEmptyPredecessorsButNeverIntoFuture() {
        val workspace = workspace(selected = setOf(0, 1, 2, 3, 4))
        val projection = WorkspaceMaterialProjection(
            stateBySlotId = mapOf(
                workspace.slots[0].id to WorkspaceSlotMaterialState.BOUNDARY_READY,
                workspace.slots[1].id to WorkspaceSlotMaterialState.EMPTY,
                workspace.slots[2].id to WorkspaceSlotMaterialState.EMPTY,
                workspace.slots[3].id to WorkspaceSlotMaterialState.EMPTY,
                workspace.slots[4].id to WorkspaceSlotMaterialState.BOUNDARY_READY,
            ),
            pitchesBySlotAndVoice = emptyMap(),
        )

        val scope = assertNotNull(
            PracticeWritingScopePlanner.automatic(
                workspace,
                projection,
                triggerSlotId = workspace.slots[3].id,
                configuredBacktrack = 0,
            ),
        )

        assertEquals(workspace.slots.subList(1, 4).map { it.id }, scope.slotIds)
        assertEquals(workspace.slots[0].id, scope.leftBoundarySlotId)
    }

    @Test
    fun configuredBacktrackMayIncludeExistingMaterialAndStopsAtUnselectedChord() {
        val workspace = workspace(selected = setOf(0, 2, 3, 4))
        val projection = WorkspaceMaterialProjection(
            stateBySlotId = workspace.slots.associate { it.id to WorkspaceSlotMaterialState.BOUNDARY_READY },
            pitchesBySlotAndVoice = emptyMap(),
        )

        val scope = assertNotNull(
            PracticeWritingScopePlanner.automatic(
                workspace,
                projection,
                triggerSlotId = workspace.slots[4].id,
                configuredBacktrack = 4,
            ),
        )

        assertEquals(workspace.slots.subList(2, 5).map { it.id }, scope.slotIds)
        assertEquals(null, scope.leftBoundarySlotId)
    }

    @Test
    fun idiomScopeAlwaysContainsEveryChangedChordEvenWhenOldMaterialStillExists() {
        val workspace = workspace(selected = setOf(0, 1, 2, 3, 4))
        val projection = WorkspaceMaterialProjection(
            stateBySlotId = workspace.slots.associate { it.id to WorkspaceSlotMaterialState.BOUNDARY_READY },
            pitchesBySlotAndVoice = emptyMap(),
        )

        val scope = assertNotNull(
            PracticeWritingScopePlanner.idiom(
                workspace,
                workspace.slots.subList(1, 4).map { it.id },
                projection,
            ),
        )

        assertEquals(workspace.slots.subList(1, 4).map { it.id }, scope.slotIds)
        assertEquals(workspace.slots[0].id, scope.leftBoundarySlotId)
        assertEquals(PracticeWritingTrigger.IDIOM_CHANGE, scope.trigger)
    }

    @Test
    fun idiomScopeAlsoWritesEmptySelectedTonicBeforeInsertedProgression() {
        val workspace = workspace(selected = setOf(0, 1, 2, 3))
        val projection = WorkspaceMaterialProjection(
            stateBySlotId = mapOf(
                workspace.slots[0].id to WorkspaceSlotMaterialState.EMPTY,
                workspace.slots[1].id to WorkspaceSlotMaterialState.BOUNDARY_READY,
                workspace.slots[2].id to WorkspaceSlotMaterialState.BOUNDARY_READY,
                workspace.slots[3].id to WorkspaceSlotMaterialState.BOUNDARY_READY,
                workspace.slots[4].id to WorkspaceSlotMaterialState.EMPTY,
            ),
            pitchesBySlotAndVoice = emptyMap(),
        )

        val scope = assertNotNull(
            PracticeWritingScopePlanner.idiom(
                workspace,
                workspace.slots.subList(1, 4).map { it.id },
                projection,
            ),
        )

        assertEquals(workspace.slots.subList(0, 4).map { it.id }, scope.slotIds)
        assertEquals(null, scope.leftBoundarySlotId)
        assertEquals(PracticeWritingTrigger.IDIOM_CHANGE, scope.trigger)
    }

    private fun workspace(selected: Set<Int>): HarmonyWorkspaceState {
        val choice = WorkspaceChordChoice.of(setOf(0, 4, 7))
        return HarmonyWorkspaceState(
            voices = listOf(
                WorkspaceVoiceSpec(
                    TrackId("upper"), 0, WorkspaceVoiceBoundary.UPPER_OUTER,
                    Pitch.fromName("C4"), Pitch.fromName("C6"),
                ),
                WorkspaceVoiceSpec(
                    TrackId("lower"), 1, WorkspaceVoiceBoundary.LOWER_OUTER,
                    Pitch.fromName("C2"), Pitch.fromName("C4"),
                ),
            ),
            slots = List(5) { index ->
                WorkspaceHarmonySlot(
                    id = WorkspaceSlotId("slot-$index"),
                    onset = Fraction(index, 4),
                    duration = Fraction.QUARTER,
                    chordChoice = choice.takeIf { index in selected },
                )
            },
        )
    }
}
