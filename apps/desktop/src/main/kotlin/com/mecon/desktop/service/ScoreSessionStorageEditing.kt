package com.mecon.desktop.service

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.toStorage
import com.mecon.api.storage.StorageScore
import com.mecon.core.analysis.ReductionSyncEngine
import com.mecon.core.engine.computeScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Commit an analysis/orchestration mutation through the document undo stack. */
fun ScoreSession.applyStorageEdit(update: (StorageScore) -> StorageScore) {
    val current = runtimeScore ?: return
    val mgr = manager ?: return
    val previousStorage = current.toStorage()
    val updatedStorage = ReductionSyncEngine.synchronize(previousStorage, update(previousStorage))
    if (updatedStorage == previousStorage) return
    scope.launch {
        val updatedRuntime = withContext(Dispatchers.Default) {
            RuntimeScore.fromStorage(updatedStorage)
        }
        val computed = withContext(Dispatchers.Default) { computeScore(updatedRuntime) }
        mgr.commitNewState(updatedRuntime, computed)
    }
}
