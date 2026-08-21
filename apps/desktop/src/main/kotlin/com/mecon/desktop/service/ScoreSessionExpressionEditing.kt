package com.mecon.desktop.service

import com.mecon.api.computed.ComputeChangeSet
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.StaffAttachmentSection
import com.mecon.api.interaction.VoiceEventSection
import com.mecon.api.state.RenderHint
import com.mecon.core.engine.computeScore
import com.mecon.core.engine.edit.ExpressionEditEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Commit an expression edit and resolve its requested selection in the new frame. */
internal fun ScoreSession.commitExpressionEdit(
    result: ExpressionEditEngine.Result,
    onAfter: (Set<EventSection>) -> Unit = {},
) {
    val mgr = manager ?: return
    val previousComputed = mgr.currentState.computedScore
    launchRecovering {
        val computed = withContext(Dispatchers.Default) { computeScore(result.score) }
        mgr.commitNewState(
            result.score,
            computed,
            RenderHint(previousComputed, ComputeChangeSet.forRange(result.affectedMeasures)),
        )
        val sections = buildSet<EventSection> {
            result.selectedEventIds.mapNotNull(computed::getComputedEvent)
                .forEach { add(VoiceEventSection(it)) }
            (computed.staffAttachments + computed.tempoKeyframes)
                .distinctBy { it.id }
                .filter { it.id in result.selectedAttachmentIds }
                .forEach { add(StaffAttachmentSection(it)) }
        }
        onAfter(sections)
    }
}
