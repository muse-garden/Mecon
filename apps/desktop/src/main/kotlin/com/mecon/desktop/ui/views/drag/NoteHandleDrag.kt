package com.mecon.desktop.ui.views.drag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.mecon.api.interaction.EventSection
import com.mecon.core.engine.edit.NoteEditEngine
import com.mecon.renderer.geometry.AbsolutePoint
import kotlin.math.roundToInt

/**
 * Family H — the note/rest semantic handle: drag a notehead to transpose it, drag a rest to move its
 * staff position. One staff position (or diatonic step) per half staff space, positive upwards.
 *
 * The drag only re-engraves a preview; the single `TransposeNotes` / `MoveRests` edit is dispatched
 * on release. Determinstic alternative: the pitch/position keyboard nudges.
 */
internal class NoteHandleDragHandler : ScoreDragHandler {
    private var startRelY = 0f

    /**
     * Marquee mode: only a note/rest that is already selected can be dragged, and it always moves
     * the whole selection. Anything else must fall through to the rubber band.
     */
    fun startWithinSelection(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        if (!pick.grabsMovable) return null
        if (pick.section !in context.selection.current) return null
        return engage(context, pick, context.selection.current)
    }

    /** Select mode: a single grab outside the selection selects just it, then drags it. */
    fun startFromPick(context: ScoreDragContext, pick: ScoreDragPick): ScoreDragHandler? {
        if (!pick.grabsMovable) return null
        val picked = pick.section ?: return null
        if (picked !in context.selection.current && !context.viewport.shiftHeld) {
            context.actions.selection.selectionChange(setOf(picked))
        }
        val sections = resolveMoveSections(picked, context.selection.current, context.viewport.shiftHeld)
        return engage(context, pick, sections)
    }

    private fun engage(
        context: ScoreDragContext,
        pick: ScoreDragPick,
        sections: Set<EventSection>,
    ): ScoreDragHandler? = startTranspose(context, pick, sections) ?: startRestMove(context, pick, sections)

    private fun startTranspose(
        context: ScoreDragContext,
        pick: ScoreDragPick,
        sections: Set<EventSection>,
    ): ScoreDragHandler? {
        val targets = buildTransposeTargets(sections, context.document.currentScore)
        if (targets.isEmpty()) return null
        val point = pick.point ?: return null
        startRelY = context.relativeY(point)
        context.previews.transpose = TransposeDragState(
            previewTargets = targets.associate { it.eventId to it.pitchIndices },
            transposeTargets = targets,
            auditionTarget = pick.section?.dragAuditionTarget(targets),
        )
        return this
    }

    private fun startRestMove(
        context: ScoreDragContext,
        pick: ScoreDragPick,
        sections: Set<EventSection>,
    ): ScoreDragHandler? {
        val info = buildRestMoveInfo(sections, context.document.currentScore)
        if (info.targets.isEmpty()) return null
        val point = pick.point ?: return null
        startRelY = context.relativeY(point)
        context.previews.transpose = TransposeDragState(
            previewTargets = info.targets.associate { it.eventId to null },
            transposeTargets = emptyList(),
            restMove = info,
        )
        return this
    }

    override fun drag(context: ScoreDragContext, change: PointerInputChange, dragAmount: Offset) {
        if (context.previews.transpose?.restMove != null) dragRest(context, change)
        else dragTranspose(context, change)
        change.consume()
    }

    private fun dragTranspose(context: ScoreDragContext, change: PointerInputChange) {
        val current = context.previews.transpose ?: return
        val point = context.toAbsolute(change.position) ?: return
        val delta = stepDelta(context, point)
        if (delta == current.stepDelta) return
        val runtime = context.document.currentScore
        val computed = context.document.computed
        val engine = context.document.engine
        val preview = if (delta != 0 && runtime != null && computed != null && engine != null) {
            engine.computeTransposePreview(
                context.result, runtime, computed, current.previewTargets, delta,
            )
        } else null
        val effectiveDelta = if (runtime != null && computed != null) {
            clampTransposeDelta(runtime, computed, current.previewTargets, delta)
        } else delta
        context.previews.transpose = current.copy(
            stepDelta = delta,
            preview = preview,
            auditionStepDelta = effectiveDelta,
        )
        if (preview != null && effectiveDelta != current.auditionStepDelta) {
            current.auditionTarget?.let { target ->
                context.actions.selection.auditionNote(
                    target.event,
                    target.soundingPitchIndices,
                    target.transposedPitchIndices,
                    effectiveDelta,
                )
            }
        }
    }

    private fun dragRest(context: ScoreDragContext, change: PointerInputChange) {
        val current = context.previews.transpose ?: return
        val info = current.restMove ?: return
        val point = context.toAbsolute(change.position) ?: return
        val delta = stepDelta(context, point)
        if (delta == current.stepDelta) return
        val computed = context.document.computed
        val engine = context.document.engine
        val preview = if (delta != 0 && computed != null && engine != null) {
            engine.computeRestMovePreview(
                context.result,
                computed,
                info.targets.associate { it.eventId to (it.startPosition + delta) },
            )
        } else null
        context.previews.transpose = current.copy(stepDelta = delta, preview = preview)
    }

    /** One staff position step per half staff space (positive = up). */
    private fun stepDelta(context: ScoreDragContext, point: AbsolutePoint) =
        ((startRelY - context.relativeY(point)) * 2f).roundToInt()

    override fun end(context: ScoreDragContext) {
        val drag = context.previews.transpose
        if (drag == null || drag.stepDelta == 0 || drag.preview == null) {
            context.previews.transpose = null
            return
        }
        val restMove = drag.restMove
        // Capture the displayed frame before starting the edit. A fast render can otherwise land
        // before the committing LaunchedEffect starts.
        context.previews.transpose = drag.copy(
            committing = true,
            commitBaseline = context.result,
            commitStartedAtNanos = System.nanoTime(),
        )
        if (restMove != null) {
            context.actions.notes.moveRest(
                restMove.targets.map { target ->
                    val newPosition = target.startPosition + drag.stepDelta
                    NoteEditEngine.RestMoveTarget(
                        voiceTrackId = target.voiceTrackId,
                        eventId = target.eventId,
                        // Back at the type default → clear the override (store null).
                        staffPosition = if (newPosition == target.defaultPosition) null else newPosition,
                    )
                }
            )
        } else {
            context.actions.notes.transpose(drag.transposeTargets, drag.stepDelta)
        }
    }
}
