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
        // Filter before converting: this runs on every frame projection (via the structure view's
        // `rewriteSelectionAvailable`), so neither the time map nor the per-event conversion should
        // be paid for the whole score just to place a handful of selected notes.
        val selectedOnsets = score.getAllVoiceEvents()
            .filter { it.id in eventIds }
            .takeIf { it.isNotEmpty() }
            ?.let { events ->
                val timeMap = ScoreTimeMap.from(score)
                events.map { timeMap.absolute(it.onset) }
            }
            ?: return emptyList()
        val start = selectedOnsets.min()
        val end = selectedOnsets.max()
        return workspace.slots.filter { slot ->
            slot.onset <= end && slot.onset + slot.duration > start
        }.map { it.id }
    }
}
