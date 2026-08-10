package com.mecon.features.freepractice

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.exploration.PracticeHarmonicRole
import com.mecon.exploration.PracticeNoteConstraintState
import com.mecon.exploration.PracticeNoteheadRef
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceChordChoice

/** Shared projection and validation for Desktop, Web, catalog filters, and the writing pipeline. */
internal object PracticeNoteConstraintProjector {
    fun view(
        state: PracticeNoteConstraintState,
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
        chordCatalogFilterEnabled: Boolean,
        idiomCatalogFilterEnabled: Boolean,
    ): PracticeNoteConstraintView {
        val timeMap = ScoreTimeMap.from(score)
        val staffByVoice = score.staffTracks.values
            .flatMap { staff -> staff.voiceTracks.map { voice -> voice.id to staff.id } }
            .toMap()
        val items = score.voiceTracks.values.flatMap { voice ->
            voice.events.toList().flatMap { event ->
                val choice = chordAt(workspace, timeMap.absolute(event.onset))
                event.pitches.mapIndexed { pitchIndex, pitch ->
                    val ref = PracticeNoteheadRef(event.id, pitchIndex)
                    val inferred = choice?.let {
                        if (pitch.pitchClass.value in it.pitchClasses) PracticeHarmonicRole.CHORD_TONE
                        else PracticeHarmonicRole.NON_CHORD_TONE
                    }
                    val explicit = state.harmonicRole(ref)
                    PracticeNoteheadRoleView(
                        notehead = ref,
                        inferredRole = inferred,
                        explicitRole = explicit,
                        conflict = explicit != null && inferred != null && explicit != inferred,
                        locked = ref in state.lockedNoteheads ||
                            voice.id in state.lockedVoiceTrackIds ||
                            staffByVoice[voice.id] in state.lockedStaffTrackIds,
                    )
                }
            }
        }
        return PracticeNoteConstraintView(
            noteheads = items,
            chordCatalogFilterEnabled = chordCatalogFilterEnabled,
            idiomCatalogFilterEnabled = idiomCatalogFilterEnabled,
            lockedVoiceTrackIds = state.lockedVoiceTrackIds,
            lockedStaffTrackIds = state.lockedStaffTrackIds,
        )
    }

    fun constraintsAtSelectedSlot(
        state: PracticeNoteConstraintState,
        workspace: HarmonyWorkspaceState,
        score: RuntimeScore,
        selectedSlotId: com.mecon.theory.freepractice.WorkspaceSlotId?,
    ): Map<Int, PracticeHarmonicRole> {
        val slot = workspace.slots.firstOrNull { it.id == selectedSlotId } ?: return emptyMap()
        val timeMap = ScoreTimeMap.from(score)
        return score.voiceTracks.values.flatMap { it.events.toList() }
            .filter { timeMap.absolute(it.onset) >= slot.onset && timeMap.absolute(it.onset) < slot.onset + slot.duration }
            .flatMap { event -> event.pitches.mapIndexed { index, pitch -> PracticeNoteheadRef(event.id, index) to pitch.pitchClass.value } }
            .mapNotNull { (ref, pitchClass) -> state.harmonicRole(ref)?.let { pitchClass to it } }
            .toMap()
    }

    fun catalogConstraints(
        state: PracticeNoteConstraintState,
        score: RuntimeScore,
    ): List<PracticeHarmonicRoleConstraint> {
        val timeMap = ScoreTimeMap.from(score)
        return score.voiceTracks.values.flatMap { it.events.toList() }.flatMap { event ->
            event.pitches.mapIndexedNotNull { index, pitch ->
                state.harmonicRole(PracticeNoteheadRef(event.id, index))?.let { role ->
                    PracticeHarmonicRoleConstraint(
                        onset = timeMap.absolute(event.onset),
                        pitchClass = pitch.pitchClass.value,
                        role = role,
                    )
                }
            }
        }
    }

    fun accepts(choice: WorkspaceChordChoice, constraints: Map<Int, PracticeHarmonicRole>): Boolean =
        constraints.all { (pitchClass, role) ->
            (pitchClass in choice.pitchClasses) == (role == PracticeHarmonicRole.CHORD_TONE)
        }

    private fun chordAt(workspace: HarmonyWorkspaceState, onset: com.mecon.api.primitive.Fraction): WorkspaceChordChoice? =
        workspace.slots.firstOrNull { onset >= it.onset && onset < it.onset + it.duration }?.chordChoice

}
