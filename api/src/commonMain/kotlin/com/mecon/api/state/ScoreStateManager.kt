package com.mecon.api.state

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Editor-side state that should travel with the undo/redo history but is **not** part of the score
 * itself (e.g. the current selection). Each consumer registers one controller under a key via
 * [ScoreStateManager.registerEditorState]; the manager snapshots all of them per history entry and
 * restores them on undo/redo, so a step back also restores the selection (etc.) that was active then.
 *
 * Designed as a registry so new editor states can be added without changing [ScoreState] or the
 * history stack's element type — implement this interface and register it.
 */
interface EditorStateController {
    /** Capture the current editor state as an opaque, immutable snapshot. */
    fun capture(): Any?
    /** Restore editor state from a snapshot produced by [capture] (or null if none was recorded). */
    fun restore(snapshot: Any?)
}

/**
 * Manages the global score state, history (undo/redo), and plugin state injection.
 */
class ScoreStateManager(
    initialRuntimeScore: RuntimeScore,
    initialComputedScore: ComputedScore
) {
    /** Optional document-level normalization performed immediately before a history commit. */
    var beforeCommit: ((RuntimeScore, RuntimeScore, ComputedScore) -> Pair<RuntimeScore, ComputedScore>)? = null
    private val historyStack = mutableListOf<ScoreState>()
    private var currentIndex = 0

    // Editor-state controllers (keyed) and a per-history-entry snapshot bag kept parallel to
    // [historyStack]. The snapshots live here rather than on [ScoreState] so the history element type
    // is untouched and new editor states need no schema change — see [EditorStateController].
    private val editorControllers = LinkedHashMap<String, EditorStateController>()
    private val editorSnapshots = mutableListOf<MutableMap<String, Any?>>()

    private val _currentStateFlow = MutableStateFlow(
        ScoreState(initialRuntimeScore, initialComputedScore)
    )
    val currentStateFlow: StateFlow<ScoreState> = _currentStateFlow.asStateFlow()

    val currentState: ScoreState
        get() = _currentStateFlow.value

    init {
        historyStack.add(currentState)
        editorSnapshots.add(mutableMapOf())
    }

    /**
     * Register an [EditorStateController] under [key]. Idempotent per key (re-registering replaces).
     * Controllers are snapshotted into the current history entry whenever the manager leaves it
     * (commit / undo / redo) and restored when an entry is re-entered via undo/redo.
     */
    fun registerEditorState(key: String, controller: EditorStateController) {
        editorControllers[key] = controller
    }

    /** Snapshot every registered editor state into the entry at [index]. */
    private fun captureEditorInto(index: Int) {
        val bag = editorSnapshots[index]
        for ((key, controller) in editorControllers) bag[key] = controller.capture()
    }

    /** Restore every registered editor state from the entry at [index]. */
    private fun restoreEditorFrom(index: Int) {
        val bag = editorSnapshots[index]
        for ((key, controller) in editorControllers) controller.restore(bag[key])
    }

    /**
     * Submit a new score state (usually from an editing action) and push it to the history stack.
     *
     * [renderHint] (optional) lets the render pipeline update incrementally when this state was produced
     * by a bounded, incrementally-computed edit; leave it null for full-recompute commits. See [RenderHint].
     */
    fun commitNewState(
        runtimeScore: RuntimeScore,
        computedScore: ComputedScore,
        renderHint: RenderHint? = null,
        updateCompanionState: (() -> Unit)? = null,
    ) {
        // Snapshot the editor state of the entry we're leaving (e.g. the selection before this edit),
        // so undoing back to it restores that selection.
        captureEditorInto(currentIndex)

        // Discard any redos (both the score history and its parallel editor snapshots).
        if (currentIndex < historyStack.size - 1) {
            historyStack.subList(currentIndex + 1, historyStack.size).clear()
            editorSnapshots.subList(currentIndex + 1, editorSnapshots.size).clear()
        }

        val normalized = beforeCommit?.invoke(currentState.runtimeScore, runtimeScore, computedScore)
        val newState = ScoreState(
            normalized?.first ?: runtimeScore,
            normalized?.second ?: computedScore,
            renderHint,
        )

        // A compound editor transaction changes companion state only after the entry being left has
        // captured its old value, but before the new entry is seeded. This keeps score and companion
        // snapshots on the same undo/redo boundary.
        updateCompanionState?.invoke()
        historyStack.add(newState)
        editorSnapshots.add(mutableMapOf())
        currentIndex++

        // Cap history size to prevent OOM
        if (historyStack.size > MAX_HISTORY_SIZE) {
            historyStack.removeAt(0)
            editorSnapshots.removeAt(0)
            currentIndex--
        }

        // Seed the new entry's editor snapshot with the current editor state. The post-edit selection
        // (set by the caller after this returns) is re-captured the next time we leave this entry.
        captureEditorInto(currentIndex)

        _currentStateFlow.value = newState
    }

    /**
     * Only updates the plugin track state. This is designed for plugins 
     * that manage their own track state independently of the core score.
     * Plugin state updates do NOT clear the redo stack or create new history entries
     * for the main score by default, but this can be customized.
     * 
     * In this implementation, we simply update the current state in-place 
     * in the history stack, so that undoing a major action takes the plugin state with it,
     * but purely plugin-driven changes don't clutter the undo history. 
     * (A more complex implementation might separate plugin history from score history).
     */
    fun updatePluginTrackState(
        trackId: TrackId,
        update: (com.mecon.api.computed.tracks.ComputedPluginTrack<*>) -> com.mecon.api.computed.tracks.ComputedPluginTrack<*>
    ) {
        val current = currentState
        val existingTrack = current.computedScore.getPluginTrack(trackId) ?: return
        val newTrack = update(existingTrack)

        val newComputedScore = current.computedScore.updatePluginTrack(trackId, newTrack)
        // This is not an incremental note edit — drop any predecessor's render hint so the pipeline
        // renders this plugin-track change in full rather than mistaking it for a note-edit transition.
        val newState = current.copy(computedScore = newComputedScore, renderHint = null)

        // Replace the current state in history so that plugin states are accurately 
        // reflected in the current undo step without creating a new step.
        historyStack[currentIndex] = newState
        _currentStateFlow.value = newState
    }

    /**
     * Provide a completely new plugin track to the score.
     */
    fun addPluginTrack(
        runtimeTrack: com.mecon.api.runtime.tracks.RuntimePluginTrack<*>,
        computedTrack: com.mecon.api.computed.tracks.ComputedPluginTrack<*>
    ) {
        val current = currentState

        val newRuntime = current.runtimeScore.updatePluginTrack(runtimeTrack.id, runtimeTrack)
        val newComputed = current.computedScore.updatePluginTrack(computedTrack.id, computedTrack)

        val newState = ScoreState(newRuntime, newComputed)
        
        // Similar to updatePluginTrackState, we update in-place
        historyStack[currentIndex] = newState
        _currentStateFlow.value = newState
    }

    /**
     * Update the score's view preferences (e.g. page arrangement or measure numbers) in place.
     *
     * View preferences do not require recomputing musical data, so this skips the recompute and
     * replaces the current history entry rather than pushing a new one — a UI display toggle should
     * not clutter the undo stack. The runtime and computed runtime are kept in sync too: the final
     * renderer overlay reads the computed runtime, while [RuntimeScore.toStorage]
     * round-trips [RuntimeScore.viewPreferences], so a stale runtime copy would otherwise revert the
     * preference on the next edit.
     */
    fun updateViewPreferences(
        update: (com.mecon.api.storage.ScoreViewPreferences) -> com.mecon.api.storage.ScoreViewPreferences
    ) {
        val current = currentState
        val newViewPreferences = update(current.runtimeScore.viewPreferences)
        val newRuntime = current.runtimeScore.copy(viewPreferences = newViewPreferences)
        val newState = current.copy(
            runtimeScore = newRuntime,
            computedScore = current.computedScore.copy(runtime = newRuntime),
            renderHint = null,
        )
        historyStack[currentIndex] = newState
        _currentStateFlow.value = newState
    }

    fun canUndo(): Boolean = currentIndex > 0

    fun canRedo(): Boolean = currentIndex < historyStack.size - 1

    fun undo() {
        if (canUndo()) {
            captureEditorInto(currentIndex)   // save the current entry's editor state before leaving
            currentIndex--
            _currentStateFlow.value = historyStack[currentIndex]
            restoreEditorFrom(currentIndex)   // restore the selection (etc.) recorded at this step
        }
    }

    fun redo() {
        if (canRedo()) {
            captureEditorInto(currentIndex)
            currentIndex++
            _currentStateFlow.value = historyStack[currentIndex]
            restoreEditorFrom(currentIndex)
        }
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 50
    }
}
