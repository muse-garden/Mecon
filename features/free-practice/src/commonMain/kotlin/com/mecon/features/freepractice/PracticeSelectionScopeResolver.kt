package com.mecon.features.freepractice

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.features.scoreediting.ScoreSelectionTarget
import com.mecon.theory.freepractice.HarmonyWorkspaceState
import com.mecon.theory.freepractice.WorkspaceSlotId

/** Pure mapping from stable score selection ids to the contiguous harmony slots they overlap. */
internal object PracticeSelectionScopeResolver {
    fun slotIds(
        selection: List<ScoreSelectionTarget>,
        score: RuntimeScore,
        workspace: HarmonyWorkspaceState,
    ): List<WorkspaceSlotId> {
        val eventIds = selection.mapNotNullTo(linkedSetOf()) { target ->
            (target as? ScoreSelectionTarget.Event)?.eventId
        }
        if (eventIds.isEmpty()) return emptyList()
        val timeMap = ScoreTimeMap.from(score)
        val selectedOnsets = score.getAllVoiceEvents().mapNotNull { event ->
            timeMap.absolute(event.onset).takeIf { event.id in eventIds }
        }
        if (selectedOnsets.isEmpty()) return emptyList()
        val start = selectedOnsets.min()
        val end = selectedOnsets.max()
        return workspace.slots.filter { slot ->
            slot.onset <= end && slot.onset + slot.duration > start
        }.map { it.id }
    }
}
