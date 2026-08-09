package com.mecon.desktop.service

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.state.ScoreStateManager
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import com.mecon.api.runtime.toStorage
import com.mecon.core.analysis.ReductionSyncEngine
import com.mecon.features.scoreediting.ScoreEditingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

suspend fun ScoreSession.replaceDocument(storage: StorageScore, file: File?, fileName: String) {
    val (runtime, computed) = withContext(Dispatchers.Default) {
        val rt = RuntimeScore.fromStorage(storage)
        rt to computeScore(rt)
    }
    installManager(ScoreStateManager(runtime, computed))
    currentFile = file
    currentFileName = fileName
    documentVersion += 1
}

/** Record the location a save landed in (no document reload). */
fun ScoreSession.markSavedAs(file: File, fileName: String) {
    currentFile = file
    currentFileName = fileName
}

private fun ScoreSession.installManager(mgr: ScoreStateManager) {
    collectJob?.cancel()
    // Re-apply the editor-state controllers to the fresh manager so undo/redo keeps restoring
    // them after a document swap.
    editorStateControllers.forEach { (key, controller) -> mgr.registerEditorState(key, controller) }
    mgr.beforeCommit = { previous, candidate, suppliedComputed ->
        val syncedStorage = ReductionSyncEngine.synchronize(previous.toStorage(), candidate.toStorage())
        if (syncedStorage == candidate.toStorage()) {
            candidate to suppliedComputed
        } else {
            val syncedRuntime = RuntimeScore.fromStorage(syncedStorage)
            syncedRuntime to computeScore(syncedRuntime)
        }
    }
    manager = mgr
    sharedEditingSession = ScoreEditingSession.open(mgr)
    state = mgr.currentState
    collectJob = scope.launch { mgr.currentStateFlow.collect { state = it } }
}

// --- Edits --------------------------------------------------------------
/**
 * Commit an edited [RuntimeScore], recomputing the computed layer off the UI thread.
 */
