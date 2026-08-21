package com.mecon.desktop.ui.views.drag

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mecon.api.interaction.EventSection
import com.mecon.api.interaction.EventSectionId
import com.mecon.api.interaction.VoiceBeamSection
import com.mecon.renderer.interaction.OrderedOverride
import com.mecon.api.interaction.StyleOverride
import com.mecon.renderer.interaction.StyleSnapshot

/**
 * Hide the settled notation that an in-progress drag is standing in for.
 *
 * A drag draws its own transient shape (see `drawScoreDragOverlays`); without this the engraved
 * original would show through underneath it. The hide is *view-local*: it is merged into the
 * snapshot this Canvas consumes rather than pushed into an engine-global style track, so a committed
 * frame can drop the hide and its preview in a single recomposition instead of waiting for
 * `StyleOverrideManager.snapshotFlow` to round-trip and forcing an extra notes-Picture recording.
 *
 * Each drag stops contributing as soon as its committed frame is displayed — one composition earlier
 * than the cleanup effect — so the new page is never drawn once with the old frame's hide still on.
 */
@Composable
internal fun rememberScoreDragHideSnapshot(
    base: StyleSnapshot,
    previews: ScoreDragPreviewState,
    hold: ScoreDragCommitHold,
    selection: Set<EventSection>,
): StyleSnapshot {
    val movedEventIds = previews.transpose.value
        ?.takeIf { it.preview != null && !hold.transpose.committedFrameDisplayed }
        ?.previewTargets?.keys.orEmpty()
    val draggedBeamSection = previews.beam.value
        ?.takeUnless { hold.beam.committedFrameDisplayed }
        ?.let { drag ->
            selection.filterIsInstance<VoiceBeamSection>()
                .firstOrNull { it.groupId.value == drag.groupId }
        }
    val draggedBeamEventIds = draggedBeamSection?.events?.mapTo(LinkedHashSet()) { it.id }.orEmpty()
    val draggedBeamGroupId = draggedBeamSection?.groupId?.value
    val draggedAttachmentId = previews.attachment.value
        ?.takeUnless { hold.attachment.committedFrameDisplayed }
        ?.id
    val draggedNavigationSectionId = previews.navigation.value
        ?.takeUnless { hold.navigation.committedFrameDisplayed }
        ?.sectionId
    val draggedCurveSectionId = previews.curve.value
        ?.takeUnless { hold.curve.committedFrameDisplayed }
        ?.sectionId
    return remember(
        base,
        movedEventIds,
        draggedBeamEventIds,
        draggedBeamGroupId,
        draggedAttachmentId,
        draggedNavigationSectionId,
        draggedCurveSectionId,
    ) {
        if (movedEventIds.isEmpty() && draggedBeamGroupId == null &&
            draggedAttachmentId == null && draggedNavigationSectionId == null &&
            draggedCurveSectionId == null
        ) {
            base
        } else {
            val merged = HashMap(base.overrides)
            var order = (merged.values.maxOfOrNull { it.order } ?: -1) + 1
            fun hide(sectionId: EventSectionId) {
                merged[sectionId] = OrderedOverride(
                    order = order++,
                    override = StyleOverride(hidden = true),
                )
            }
            movedEventIds.forEach { hide(EventSectionId.voiceEvent(it)) }
            draggedBeamGroupId?.let { groupId ->
                hide(EventSectionId.voiceBeam(groupId))
                // Stems move with the beam, so they are re-drawn by the preview too.
                draggedBeamEventIds.forEach { hide(EventSectionId.voiceStem(it)) }
            }
            draggedAttachmentId?.let { hide(EventSectionId.staffAttachment(it)) }
            draggedNavigationSectionId?.let { hide(it) }
            draggedCurveSectionId?.let { hide(it) }
            StyleSnapshot(merged)
        }
    }
}
