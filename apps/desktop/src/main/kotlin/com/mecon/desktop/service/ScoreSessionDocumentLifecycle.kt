package com.mecon.desktop.service

import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.state.ScoreStateManager
import com.mecon.api.storage.StorageScore
import com.mecon.core.engine.computeScore
import com.mecon.api.runtime.toStorage
import com.mecon.core.analysis.ReductionSyncEngine
import com.mecon.features.scoreediting.ScoreEditingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editing failures are atomic because commits happen only after compute succeeds. Surface the error
 * while retaining the preceding state instead of letting an uncaught coroutine failure tear down the
 * desktop scope. Cancellation remains cooperative and is never presented as an editing failure.
 */
internal fun ScoreSession.launchRecovering(
    operation: String = "编辑",
    block: suspend () -> Unit,
): Job = scope.launch {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        analysisMessage = "${operation}失败，已保留最近一次完整乐谱状态：${error.message ?: error::class.simpleName}"
        error.printStackTrace()
    }
}

suspend fun ScoreSession.replaceDocument(storage: StorageScore, file: File?, fileName: String) {
    val (runtime, computed) = withContext(Dispatchers.Default) {
        val rt = RuntimeScore.fromStorage(storage)
        rt to computeScore(rt)
    }
    installManager(ScoreStateManager(runtime, computed))
    currentFile = file
    currentFileName = fileName
    markCurrentStateSaved()
    documentVersion += 1
}

/** Record a successful manual save without reloading the document. */
fun ScoreSession.markCurrentStateSaved(file: File? = currentFile, fileName: String = currentFileName) {
    markRuntimeStateSaved(state?.runtimeScore, file, fileName)
}

/** Mark the exact immutable frame that was serialized, even if editing continued during I/O. */
fun ScoreSession.markRuntimeStateSaved(
    runtime: RuntimeScore?,
    file: File? = currentFile,
    fileName: String = currentFileName,
) {
    currentFile = file
    currentFileName = fileName
    savedRuntimeScore = runtime
    isModified = state?.runtimeScore !== runtime
}

/** A recovered autosave is intentionally dirty until the user explicitly saves it. */
fun ScoreSession.markRecoveredAsModified() {
    savedRuntimeScore = null
    isModified = state != null
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
    collectJob = scope.launch {
        mgr.currentStateFlow.collect {
            state = it
            isModified = it.runtimeScore !== savedRuntimeScore
        }
    }
}

// --- Edits --------------------------------------------------------------
/**
 * Commit an edited [RuntimeScore], recomputing the computed layer off the UI thread.
 */
