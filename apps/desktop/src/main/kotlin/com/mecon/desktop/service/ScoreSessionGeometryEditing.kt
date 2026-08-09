package com.mecon.desktop.service

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.state.RenderHint
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.ScoreGeometry
import com.mecon.core.engine.computeScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Replace the entire runtime document and recompute it off the UI thread. */
fun ScoreSession.applyRuntimeEdit(newRuntime: RuntimeScore) {
    val mgr = manager ?: return
    scope.launch {
        val computed = withContext(Dispatchers.Default) { computeScore(newRuntime) }
        mgr.commitNewState(newRuntime, computed)
    }
}

/** Persist a beam drag as render geometry without changing musical semantics. */
fun ScoreSession.applyBeamGeometry(groupId: String, geometry: BeamGeometry) {
    val current = runtimeScore ?: return
    val mgr = manager ?: return
    val previousComputed = mgr.currentState.computedScore
    val base = lastRenderedGeometry ?: current.geometry ?: ScoreGeometry.EMPTY
    val updated = current.copy(geometry = base.copy(beams = base.beams + (groupId to geometry)))
    val measures = previousComputed.getBeamGroups().entries
        .firstOrNull { it.key.value == groupId }
        ?.value
        ?.map { it.measurePosition.measure }
        .orEmpty()
    scope.launch {
        val computed = withContext(Dispatchers.Default) { computeScore(updated) }
        val hint = if (measures.isEmpty()) null else RenderHint(
            previousComputed,
            ComputeChangeSet.forRange(measures.min()..measures.max()),
        )
        mgr.commitNewState(updated, computed, hint)
    }
}
