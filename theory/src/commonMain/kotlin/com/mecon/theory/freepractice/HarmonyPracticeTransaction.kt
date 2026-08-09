package com.mecon.theory.freepractice

import com.mecon.api.computed.ComputedScore
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.state.EditorStateController
import com.mecon.api.state.ScoreStateManager

/**
 * Companion-state controller registered with [ScoreStateManager]. Workspace intent follows the
 * same undo/redo entries as RuntimeScore without being copied into score storage.
 */
class HarmonyWorkspaceStateController(
    initialState: HarmonyWorkspaceState,
) : EditorStateController {
    var state: HarmonyWorkspaceState = initialState
        private set

    var lastErrorMessage: String? = null
        private set

    fun replace(value: HarmonyWorkspaceState) {
        state = value
        lastErrorMessage = null
    }

    fun apply(command: HarmonyWorkspaceCommand): HarmonyWorkspaceState {
        val result = HarmonyWorkspaceEditor.applyResult(state, command)
        lastErrorMessage = result.errorMessage
        if (result.succeeded) state = result.state
        return state
    }

    override fun capture(): Any = state

    override fun restore(snapshot: Any?) {
        state = snapshot as? HarmonyWorkspaceState ?: state
        lastErrorMessage = null
    }
}

/**
 * Commits score material and companion workspace state on one history boundary.
 */
class HarmonyPracticeTransaction(
    private val scoreStateManager: ScoreStateManager,
    private val workspaceController: HarmonyWorkspaceStateController,
) {
    init {
        scoreStateManager.registerEditorState(EDITOR_STATE_KEY, workspaceController)
    }

    fun commit(
        runtimeScore: RuntimeScore,
        computedScore: ComputedScore,
        workspaceState: HarmonyWorkspaceState,
    ): Boolean {
        val current = scoreStateManager.currentState
        if (
            runtimeScore === current.runtimeScore &&
            computedScore === current.computedScore &&
            workspaceState == workspaceController.state
        ) {
            return false
        }
        scoreStateManager.commitNewState(
            runtimeScore = runtimeScore,
            computedScore = computedScore,
            updateCompanionState = { workspaceController.replace(workspaceState) },
        )
        return true
    }

    companion object {
        const val EDITOR_STATE_KEY = "schoenberg-free-practice"
    }
}
