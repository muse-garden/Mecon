package com.mecon.renderer.render

import com.mecon.api.computed.ComputedPitchData
import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.storage.ScoreGeometry
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.SlurCurveBuilder
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.TieLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Computes [TieLayout]s by resolving each [com.mecon.api.computed.ComputedTieTarget]
 * against the post-layout positions of its source and target noteheads.
 *
 * Mirrors the structure of [BeamGroupProcessor]: a single pass after the main
 * layout that turns Computed-layer references (event IDs, pitch indices) into
 * concrete relative coordinates the renderer can draw.
 *
 * Direction rules (LilyPond-inspired, simplified):
 * - Single tied note: the curve bows opposite the stem direction. Whole notes
 *   (no stem) bow away from the closer staff edge — above if the note sits
 *   below the middle line, below otherwise.
 * - Chord with multiple tied notes: the topmost tied pitch bows above and
 *   the bottommost bows below. Inner pitches follow the closer chord half.
 *
 * Let-ring ties have no target. The end point is synthesized [LET_RING_LENGTH]
 * staff spaces to the right of the source, at the same height, so the curve
 * tapers off into the next slot without colliding with following notes.
 */
internal class TieLayoutComputer(
    private val config: RenderLayoutConfig
) {
    private data class ApexRequirement(
        val base: Float,
        val required: Float,
    ) {
        val inflation: Float get() = (required - base).coerceAtLeast(0f)
    }

    private data class DirectionCandidate(
        val layouts: List<TieLayout>,
        val collisionInflation: Float,
    )

    /**
     * Build all tie layouts for the given score and unified layout.
     *
     * Returns an empty list when no pitch carries a tie. Ties whose source
     * notehead can't be located in the layout (e.g. the event was filtered
     * out for some reason) are silently skipped.
     */
    fun computeTieLayouts(
        computedScore: ComputedScore,
        query: LayoutQuery,
        measureFilter: IntRange? = null,
        geometry: ScoreGeometry? = null,
    ): List<TieLayout> {
        val tieLayouts = mutableListOf<TieLayout>()

        // Windowed passes (paginated splice) range-query only the affected measures; the full pass scans
        // every event. The per-event guard below stays for the range query's inclusive-end overscan.
        val events = if (measureFilter != null) {
            computedScore.eventsInMeasureRange(measureFilter)
        } else {
            computedScore.computedEvents.tieSourceEventIds
                .mapNotNull(computedScore::getComputedEvent)
                .sortedBy { it.onset }
        }
        for (event in events) {
            if (measureFilter != null && event.onset.measure !in measureFilter) continue
            if (!event.hasTies) continue

            val sourceEnv = query.environment(event.id) ?: continue

            for ((pitchIndex, pitchData) in event.pitchData.withIndex()) {
                val tieTarget = pitchData.tieTarget ?: continue

                val sourceNotehead = sourceEnv.notehead(pitchIndex) ?: continue
                val stored = geometry?.ties?.get(event.id)
                    ?.firstOrNull { it.sourcePitchIndex == pitchIndex }

                val directionLocked = stored?.directionOnly == true || stored?.directionLocked == true
                val preferredDirection = stored?.takeIf { directionLocked }?.let {
                    if (it.above) SlurDirection.ABOVE else SlurDirection.BELOW
                } ?: resolveTieDirection(event, pitchData, sourceEnv.stemDirection)

                fun layoutOf(
                    start: RelativePoint,
                    end: RelativePoint,
                    direction: SlurDirection,
                    systemIndex: Int,
                    staffIndex: Int,
                ): Pair<TieLayout, Float> {
                    // Relative coordinates belong to the side on which they were captured.
                    // When automatic avoidance flips the tie, recompute anchors on the new
                    // side instead of carrying the old side's offsets across the staff.
                    val shape = stored?.takeIf {
                        it.autoEndpoints && it.above == (direction == SlurDirection.ABOVE)
                    }
                    val adjustedStart = if (shape == null) start else RelativePoint(
                        start.x + StaffSpace(shape.startDx),
                        start.y + StaffSpace(shape.startDy),
                    )
                    val adjustedEnd = if (shape == null) end else RelativePoint(
                        end.x + StaffSpace(shape.endDx),
                        end.y + StaffSpace(shape.endDy),
                    )
                    val apex = collisionAwareApex(
                        sourceEventId = event.id,
                        targetEventId = tieTarget.targetEventId,
                        start = adjustedStart,
                        end = adjustedEnd,
                        direction = direction,
                        systemIndex = systemIndex,
                        staffIndex = staffIndex,
                        query = query,
                    )
                    return TieLayout(
                        sourceEventId = event.id,
                        sourcePitchIndex = pitchIndex,
                        targetEventId = tieTarget.targetEventId,
                        start = adjustedStart,
                        end = adjustedEnd,
                        minApexHeight = StaffSpace(max(apex.required, shape?.minApex ?: apex.required)),
                        maxApexHeight = StaffSpace(max(
                            max(TIE_MAX_AUTO_APEX, apex.required),
                            shape?.maxApex ?: apex.required,
                        )),
                        slopeDamping = shape?.slopeDamping ?: 1f,
                        middleStraightening = shape?.middleStraightening ?: 0f,
                        direction = direction,
                        isLetRing = tieTarget.isLetRing,
                        staffIndex = staffIndex,
                        trackId = sourceEnv.voiceLayout.trackId,
                        measureNumber = sourceEnv.voiceLayout.measureNumber
                    ) to apex.inflation
                }

                val resolvedStored = stored?.takeUnless { it.directionOnly || it.autoEndpoints }?.let { storedGeometry ->
                    GeometryProjector.resolveTie(storedGeometry, event, pitchData, query)
                }
                if (resolvedStored != null) {
                    tieLayouts.add(resolvedStored)
                    continue
                }

                fun candidate(direction: SlurDirection): DirectionCandidate? {
                    if (tieTarget.isLetRing) {
                        val (start, end) = computeLetRingEndpoints(sourceEnv, sourceNotehead, direction)
                        val (layout, inflation) = layoutOf(
                            start,
                            end,
                            direction,
                            sourceEnv.systemIndex,
                            sourceEnv.staffLayout.staffIndex,
                        )
                        return DirectionCandidate(listOf(layout), inflation)
                    }

                    val targetEventId = tieTarget.targetEventId ?: return null
                    val targetPitchIndex = tieTarget.targetPitchIndex ?: return null
                    val targetEnv = query.environment(targetEventId) ?: return null
                    val targetNotehead = targetEnv.notehead(targetPitchIndex) ?: return null
                    val (start, end) =
                        computeNormalEndpoints(sourceEnv, sourceNotehead, targetEnv, targetNotehead, direction)

                    if (sourceEnv.systemIndex == targetEnv.systemIndex) {
                        val (layout, inflation) = layoutOf(
                            start,
                            end,
                            direction,
                            sourceEnv.systemIndex,
                            sourceEnv.staffLayout.staffIndex,
                        )
                        return DirectionCandidate(listOf(layout), inflation)
                    }

                    // Tie crosses a system break: draw a stub at the end of the source
                    // line and another at the start of the target line.
                    val srcEnd = query.systemBounds(sourceEnv.systemIndex)?.second ?: end.x
                    val tgtStart = query.systemBounds(targetEnv.systemIndex)?.first ?: start.x
                    val (sourceLayout, sourceInflation) = layoutOf(
                        start,
                        RelativePoint(srcEnd, start.y),
                        direction,
                        sourceEnv.systemIndex,
                        sourceEnv.staffLayout.staffIndex,
                    )
                    val (targetLayout, targetInflation) = layoutOf(
                        RelativePoint(tgtStart, end.y),
                        end,
                        direction,
                        targetEnv.systemIndex,
                        targetEnv.staffLayout.staffIndex,
                    )
                    return DirectionCandidate(
                        listOf(sourceLayout, targetLayout),
                        max(sourceInflation, targetInflation),
                    )
                }

                val preferred = candidate(preferredDirection) ?: continue
                val selected = if (directionLocked || tieTarget.isLetRing ||
                    preferred.collisionInflation <= DIRECTION_CLEARANCE_EPSILON
                ) {
                    preferred
                } else {
                    val alternate = candidate(preferredDirection.opposite()) ?: preferred
                    when {
                        alternate.collisionInflation <= DIRECTION_CLEARANCE_EPSILON -> alternate
                        alternate.collisionInflation + DIRECTION_CLEARANCE_EPSILON <
                            preferred.collisionInflation -> alternate
                        else -> preferred
                    }
                }
                tieLayouts.addAll(selected.layouts)
            }
        }

        return tieLayouts
    }

    private fun computeNormalEndpoints(
        sourceEnv: EventEnvironment,
        sourceNotehead: NoteheadAnchor,
        targetEnv: EventEnvironment,
        targetNotehead: NoteheadAnchor,
        direction: SlurDirection
    ): Pair<RelativePoint, RelativePoint> {
        val sign = directionSign(direction)
        val verticalGap = config.tieVerticalGap.value * sign

        // Grace notes sit very close to the principal note; the visual gap between
        // their noteheads is only ~0.7 ss, so the standard inset would leave almost
        // no room for the arc.  Use zero inset on both sides for any grace-note tie.
        val graceTransition = sourceEnv.noteElement.time.grace != null ||
                targetEnv.noteElement.time.grace != null
        val startInset = if (graceTransition) StaffSpace.ZERO else config.tieHorizontalInset
        val endInset   = if (graceTransition) StaffSpace.ZERO else config.tieHorizontalInset

        val startX = sourceEnv.slotX + sourceEnv.noteElement.relativeX +
            sourceNotehead.rightEdge + startInset
        val startY = sourceEnv.staffLayout.centerY + sourceNotehead.centerY +
            StaffSpace(verticalGap)

        val endX = targetEnv.slotX + targetEnv.noteElement.relativeX +
            targetNotehead.leftEdge - endInset
        val endY = targetEnv.staffLayout.centerY + targetNotehead.centerY +
            StaffSpace(verticalGap)

        return RelativePoint(startX, startY) to RelativePoint(endX, endY)
    }

    private fun computeLetRingEndpoints(
        sourceEnv: EventEnvironment,
        sourceNotehead: NoteheadAnchor,
        direction: SlurDirection
    ): Pair<RelativePoint, RelativePoint> {
        val sign = directionSign(direction)
        val verticalGap = config.tieVerticalGap.value * sign

        val startX = sourceEnv.slotX + sourceEnv.noteElement.relativeX +
            sourceNotehead.rightEdge + config.tieHorizontalInset
        val startY = sourceEnv.staffLayout.centerY + sourceNotehead.centerY +
            StaffSpace(verticalGap)

        val endX = startX + LET_RING_LENGTH
        val endY = startY

        return RelativePoint(startX, startY) to RelativePoint(endX, endY)
    }

    /**
     * Pick the bow direction for a single tied pitch.
     *
     * staffPosition convention: larger = higher on staff (positive above middle line).
     *
     * Chord rules (when multiple pitches are tied):
     * - Stem UP:   topmost pitch (max staffPosition) bows ABOVE; all others bow BELOW.
     * - Stem DOWN: bottommost pitch (min staffPosition) bows BELOW; all others bow ABOVE.
     * - No stem:   topmost ABOVE, bottommost BELOW, inner toward closer outer.
     *
     * Single-pitch rules:
     * - Stem UP → BELOW (opposite stem), Stem DOWN → ABOVE, no stem → away from middle line.
     */
    private fun resolveTieDirection(
        event: ComputedVoiceEvent,
        pitch: ComputedPitchData,
        stemDirection: StemDirection?
    ): SlurDirection {
        val tied = event.pitchData.filter { it.tieTarget != null }

        if (tied.size > 1) {
            // larger staffPosition = higher on staff
            val topStaffPos = tied.maxOf { it.staffPosition }
            val bottomStaffPos = tied.minOf { it.staffPosition }
            return when (stemDirection) {
                StemDirection.UP -> {
                    if (pitch.staffPosition == topStaffPos) SlurDirection.ABOVE
                    else SlurDirection.BELOW
                }
                StemDirection.DOWN -> {
                    if (pitch.staffPosition == bottomStaffPos) SlurDirection.BELOW
                    else SlurDirection.ABOVE
                }
                null -> when (pitch.staffPosition) {
                    topStaffPos -> SlurDirection.ABOVE
                    bottomStaffPos -> SlurDirection.BELOW
                    else -> {
                        val center = (topStaffPos + bottomStaffPos) / 2.0
                        if (pitch.staffPosition >= center) SlurDirection.ABOVE else SlurDirection.BELOW
                    }
                }
            }
        }

        return when (stemDirection) {
            StemDirection.UP -> SlurDirection.BELOW
            StemDirection.DOWN -> SlurDirection.ABOVE
            null -> {
                if (pitch.staffPosition > 0) SlurDirection.ABOVE else SlurDirection.BELOW
            }
        }
    }

    private fun directionSign(direction: SlurDirection): Float =
        if (direction == SlurDirection.ABOVE) -1f else 1f

    /**
     * Raise a short tie just enough to clear noteheads from every voice on the same staff.
     * The calculation mirrors slur collision sampling but intentionally ignores stems and beams:
     * ties live close to noteheads and the reported multi-voice failure is notehead intrusion.
     */
    private fun collisionAwareApex(
        sourceEventId: EventId,
        targetEventId: EventId?,
        start: RelativePoint,
        end: RelativePoint,
        direction: SlurDirection,
        systemIndex: Int,
        staffIndex: Int,
        query: LayoutQuery,
    ): ApexRequirement {
        val dx = end.x.value - start.x.value
        val span = abs(dx).coerceAtLeast(0.0001f)
        val base = (SlurCurveBuilder.DEFAULT_BASE_CURVATURE * sqrt(span))
            .coerceIn(TIE_MIN_APEX, TIE_MAX_AUTO_APEX)
        if (dx <= 0f) return ApexRequirement(base, base)

        val sign = directionSign(direction)
        var required = base
        for (voiceLayout in query.voiceLayoutsOnStaffInXRange(
            systemIndex, staffIndex, start.x, end.x, StaffSpace(1.2f),
        )) {
            if (voiceLayout.eventId == sourceEventId || voiceLayout.eventId == targetEventId) continue
            val env = query.environment(voiceLayout.eventId) ?: continue
            for (head in env.noteElement.noteBody.noteheads) {
                val bounds = head.geometry.bounds
                val x = env.slotX.value + env.noteElement.relativeX.value +
                    bounds.origin.x.value + bounds.width.value / 2f
                if (x <= start.x.value || x >= end.x.value) continue
                val t = ((x - start.x.value) / dx).coerceIn(0f, 1f)
                val baselineY = start.y.value + (end.y.value - start.y.value) * t
                val outerY = env.staffLayout.centerY.value + if (direction == SlurDirection.ABOVE) {
                    bounds.origin.y.value
                } else {
                    bounds.origin.y.value + bounds.height.value
                }
                val intrusion = (outerY - baselineY) * sign + TIE_COLLISION_MARGIN
                if (intrusion <= 0f) continue
                val weight = (3f * t * (1f - t)).coerceAtLeast(0.55f)
                required = max(required, intrusion / weight)
            }
        }
        return ApexRequirement(base, required)
    }

    companion object {
        /** Length of the synthesized let-ring trailing curve (staff spaces). */
        val LET_RING_LENGTH = StaffSpace(1.6f)
        private const val TIE_MIN_APEX = 0.5f
        private const val TIE_MAX_AUTO_APEX = 1.4f
        private const val TIE_COLLISION_MARGIN = 0.35f
        private const val DIRECTION_CLEARANCE_EPSILON = 0.01f
    }
}

private fun SlurDirection.opposite(): SlurDirection = when (this) {
    SlurDirection.ABOVE -> SlurDirection.BELOW
    SlurDirection.BELOW -> SlurDirection.ABOVE
}
