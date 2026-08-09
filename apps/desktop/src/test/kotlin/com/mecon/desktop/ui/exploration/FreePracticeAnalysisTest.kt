package com.mecon.desktop.ui.exploration

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.exploration.VoicePlanScoreAssembler
import com.mecon.features.freepractice.PracticeFindingComputer
import com.mecon.features.freepractice.PracticeFindingSeverity as SharedPracticeFindingSeverity
import com.mecon.theory.KeySignatureMode
import com.mecon.theory.ModulationKey
import com.mecon.theory.freepractice.WorkspaceChordChoice
import com.mecon.theory.freepractice.WorkspaceSlotId
import com.mecon.theory.harmony.ChordSelectionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreePracticeAnalysisTest {
    @Test
    fun solverFindingIsShownWithSourceAnchors() {
        val key = ModulationKey(0, KeySignatureMode.MAJOR)
        val initial = initialWorkspace(4, key)
        val secondChoice = ChordSelectionCatalog.choices(key)
            .first { it.functionalSymbol == "ii" }
        val second = initial.slots.single().copy(
            id = WorkspaceSlotId("slot-1"),
            onset = Fraction.QUARTER,
            chordChoice = WorkspaceChordChoice.of(
                pitchClasses = secondChoice.pitchClasses,
                origin = secondChoice.origin,
                pinnedInterpretationRef = secondChoice.confirmedInterpretationRef,
            ),
        )
        val workspace = initial.copy(slots = initial.slots + second)
        val empty = RuntimeScore.fromStorage(
            VoicePlanScoreAssembler.emptyPracticeScore(
                workspace,
                KeySignature.C_MAJOR,
            )
        )
        val voiceIds = workspace.voices.sortedBy { it.order }.map { it.id }
        val pitches = listOf(
            listOf("G4", "E4", "C4", "C3"),
            listOf("A4", "F4", "D4", "D3"),
        )
        val notes = pitches.flatMapIndexed { slotIndex, frame ->
            frame.mapIndexed { voiceIndex, pitch ->
                NoteEditEngine.RangeNote(
                    voiceTrackId = voiceIds[voiceIndex],
                    start = TimeCode.of(1, Fraction.of(slotIndex, 4)),
                    duration = Fraction.QUARTER,
                    pitch = Pitch.fromName(pitch),
                )
            }
        }
        val replaced = NoteEditEngine.replaceRange(
            runtime = empty,
            voiceTrackIds = voiceIds.toSet(),
            start = TimeCode.of(1, Fraction.ZERO),
            end = TimeCode.of(1, Fraction.of(2, 4)),
            notes = notes,
        )

        val findings = PracticeFindingComputer.compute(workspace, replaced.score, key)
        val parallel = findings.first { it.ruleId == "free.counterpoint.parallel-perfect" }

        assertEquals(SharedPracticeFindingSeverity.WARNING, parallel.severity)
        assertEquals("出现平行纯五度或纯八度；自由写作中保留为可调软偏好。", parallel.message)
        assertTrue(parallel.anchors.isNotEmpty())
        assertTrue(parallel.anchors.all { it in replaced.insertedEventIdsByNoteIndex.values.flatten() })
    }
}
