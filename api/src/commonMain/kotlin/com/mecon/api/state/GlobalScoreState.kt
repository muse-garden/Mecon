package com.mecon.api.state

import com.mecon.api.computed.ComputedScore
import com.mecon.api.runtime.RuntimeScore

/**
 * Singleton holder for the global score state manager.
 *
 * Provides convenient access to the current score state without passing it
 * explicitly through deeply nested function calls (e.g., in UI interaction logic).
 */
object GlobalScoreState {
    private var manager: ScoreStateManager? = null

    /**
     * Initialize the global state manager. Should be called at app startup
     * or when a new score is loaded.
     */
    fun initialize(
        runtimeScore: RuntimeScore,
        computedScore: ComputedScore
    ) {
        manager = ScoreStateManager(runtimeScore, computedScore)
    }

    /**
     * Get the active ScoreStateManager.
     * Throws an exception if not initialized.
     */
    val activeManager: ScoreStateManager
        get() = manager ?: error("GlobalScoreState not initialized")

    /**
     * Convenience property to get the current ScoreState directly.
     */
    val currentState: ScoreState
        get() = activeManager.currentState
}
