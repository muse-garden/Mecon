package com.mecon.renderer.render

import com.mecon.api.computed.ComputedScore
import com.mecon.api.computed.ComputedSlur
import com.mecon.api.computed.ComputedVoiceEvent
import com.mecon.api.primitive.EventId
import com.mecon.api.storage.ScoreGeometry
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.SlurCurveBuilder
import com.mecon.renderer.geometry.SlurDirection
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.ArticulationLayout
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.layout.SlurLayout
import com.mecon.renderer.smufl.EngravingDefaults
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Resolves [ComputedSlur]s into concrete [SlurLayout]s using post-layout
 * notehead positions.
 *
 * Mirrors [TieLayoutComputer] in shape but differs on two axes:
 *
 *  1. **Notehead-side anchors are vertically above or below the chord**,
 *     not offset horizontally like ties. A slur bowing above anchors on the
 *     *top* of the chord; below anchors on the *bottom*.
 *  2. **Apex height is pre-inflated** for two reasons before handing to
 *     [SlurCurveBuilder]:
 *      - **Nesting**: each level of enclosing slur adds
 *        [RenderLayoutConfig.slurNestedGap] to keep the outer slur clearly
 *        above (or below) the inner one.
 *      - **Long-slur collision**: the computer scans every notehead between
 *        the slur's start and end on the same staff and lifts the apex above
 *        (or below) the most intrusive one with
 *        [RenderLayoutConfig.slurCollisionMargin] clearance.
 *
 * Direction policy:
 *  - In a multi-voice staff, voice 1 (upper) bows above; voice 2 (lower)
 *    bows below; higher voice numbers default to above. This keeps slurs in
 *    different voices on opposite sides of the staff.
 *  - For voice 1 only (single-voice case), the bow opposes the start event's
 *    stem direction so it doesn't cross the stem.
 */
internal class SlurLayoutComputer(
    private val config: RenderLayoutConfig
) {
    private data class ObstacleVisitRequest(
        val startId: EventId,
        val endId: EventId,
        val start: RelativePoint,
        val end: RelativePoint,
        val direction: SlurDirection,
        val query: LayoutQuery,
        val articulationByEvent: Map<EventId, ArticulationLayout>,
        val stemAdjustments: Map<EventId, StaffSpace>,
        val beamGroups: List<BeamGroupProcessor.BeamGroupRenderData>,
        val obstacleAnchorId: EventId = startId,
    )

    fun computeSlurLayouts(
        computedScore: ComputedScore,
        query: LayoutQuery,
        articulationByEvent: Map<EventId, ArticulationLayout> = emptyMap(),
        stemAdjustments: Map<EventId, StaffSpace> = emptyMap(),
        beamGroups: List<BeamGroupProcessor.BeamGroupRenderData> = emptyList(),
        geometry: ScoreGeometry? = null,
        measureFilter: IntRange? = null,
    ): List<SlurLayout> {
        if (computedScore.slurs.isEmpty()) return emptyList()

        // Map voiceTrackId → number of voices on the staff it sits on. Used by
        // resolveDirection to decide between "single-voice" (opposite-of-stem)
        // and "multi-voice" (voice 1 above, voice 2+ below) policy.
        val result = mutableListOf<SlurLayout>()

        for (slur in computedScore.slurs) {
            if (measureFilter != null) {
                // Skip slurs whose entire span falls outside the filter. Use the start/end event
                // onset measures — if either endpoint is within [measureFilter] or the slur spans
                // across it, we must include it. If both endpoints are outside on the same side, skip.
                val startMeasure = query.event(slur.startEventId)?.onset?.measure
                val endMeasure = query.event(slur.endEventId)?.onset?.measure
                if (startMeasure != null && endMeasure != null) {
                    val slurLo = minOf(startMeasure, endMeasure)
                    val slurHi = maxOf(startMeasure, endMeasure)
                    if (slurHi < measureFilter.first || slurLo > measureFilter.last) continue
                }
            }
            // Persisted geometry (when present) is the source of truth; fall back
            // to auto layout when there is no stored entry or it can't resolve
            // (e.g. the slur now spans two systems).
            val stored = geometry?.slurs?.get(slur.slurId)
            val resolved = stored?.takeUnless { it.directionOnly || it.autoEndpoints }
                ?.let { GeometryProjector.resolveSlur(it, slur, query) }
            if (resolved != null) {
                result.add(resolved)
            } else {
                val automatic = buildLayout(
                    slur,
                    query,
                    computedScore.runtime,
                    articulationByEvent,
                    stemAdjustments,
                    beamGroups,
                    directionOverride = stored?.takeIf { it.directionOnly || it.directionLocked }?.let {
                        if (it.above) SlurDirection.ABOVE else SlurDirection.BELOW
                    },
                )
                val shapeMatchesDirection = automatic.firstOrNull()?.direction?.let { direction ->
                    stored?.above == (direction == SlurDirection.ABOVE)
                } == true
                result.addAll(if (stored?.autoEndpoints == true && shapeMatchesDirection) {
                    val applyEndpointOffsets = automatic.size == 1
                    automatic.map { layout ->
                        layout.copy(
                            start = if (!applyEndpointOffsets) layout.start else RelativePoint(
                                layout.start.x + StaffSpace(stored.startDx),
                                layout.start.y + StaffSpace(stored.startDy),
                            ),
                            end = if (!applyEndpointOffsets) layout.end else RelativePoint(
                                layout.end.x + StaffSpace(stored.endDx),
                                layout.end.y + StaffSpace(stored.endDy),
                            ),
                            minApexHeight = StaffSpace(maxOf(layout.minApexHeight.value, stored.minApex)),
                            maxApexHeight = StaffSpace(maxOf(layout.maxApexHeight.value, stored.maxApex)),
                            slopeDamping = stored.slopeDamping,
                            middleStraightening = stored.middleStraightening,
                        )
                    }
                } else automatic)
            }
        }

        return result
    }

    private fun buildLayout(
        slur: ComputedSlur,
        query: LayoutQuery,
        runtimeScore: com.mecon.api.runtime.RuntimeScore,
        articulationByEvent: Map<EventId, ArticulationLayout>,
        stemAdjustments: Map<EventId, StaffSpace>,
        beamGroups: List<BeamGroupProcessor.BeamGroupRenderData>,
        directionOverride: SlurDirection? = null,
    ): List<SlurLayout> {
        val startEnv = query.environment(slur.startEventId)
            ?: return emptyList()
        val endEnv = query.environment(slur.endEventId)
            ?: return emptyList()

        val startEvent = query.event(slur.startEventId) ?: return emptyList()
        val endEvent = query.event(slur.endEventId) ?: return emptyList()

        if (startEvent.isRest || endEvent.isRest) return emptyList()

        // VoiceEventLayout.trackId is the owning staff id, so this remains an
        // indexed lookup instead of rebuilding a whole-score voice map.
        val voiceCount = runtimeScore.staffTracks[startEnv.voiceLayout.trackId]
            ?.voiceTracks
            ?.size
            ?: 1
        val preferredDirection = directionOverride ?: resolveDirection(slur, startEnv, endEnv, voiceCount)
        if (directionOverride == null) {
            // Direction is the first collision-avoidance degree of freedom. Both
            // candidates use the same staff-local X-window query, so this remains
            // bounded by the slur span rather than the score size.
            val preferred = buildLayout(
                slur, query, runtimeScore, articulationByEvent, stemAdjustments, beamGroups,
                directionOverride = preferredDirection,
            )
            val preferredInflation = collisionInflation(preferred, slur.nestingLevel)
            if (preferredInflation <= DIRECTION_CLEARANCE_EPSILON) return preferred

            val alternate = buildLayout(
                slur, query, runtimeScore, articulationByEvent, stemAdjustments, beamGroups,
                directionOverride = preferredDirection.opposite(),
            )
            val alternateInflation = collisionInflation(alternate, slur.nestingLevel)
            return when {
                alternateInflation <= DIRECTION_CLEARANCE_EPSILON -> alternate
                alternateInflation + DIRECTION_CLEARANCE_EPSILON < preferredInflation -> alternate
                else -> preferred
            }
        }
        val direction = preferredDirection

        // Anchor on outermost pitch in bow direction.
        val startPitchIndex = pickAnchorPitchIndex(startEvent, direction)
        val endPitchIndex = pickAnchorPitchIndex(endEvent, direction)

        val startNotehead = startEnv.notehead(startPitchIndex) ?: return emptyList()
        val endNotehead = endEnv.notehead(endPitchIndex) ?: return emptyList()

        val (anchorStartPoint, anchorEndPoint) = computeEndpoints(
            startEnv, startNotehead, endEnv, endNotehead, direction, stemAdjustments
        )

        val sameSystem = startEnv.systemIndex == endEnv.systemIndex
        val sameStaff = startEnv.staffLayout.staffIndex == endEnv.staffLayout.staffIndex
        val (rawStartPoint, rawEndPoint) = if (sameSystem && sameStaff) {
            shapeEndpointLine(
                startEvent.id, endEvent.id, anchorStartPoint, anchorEndPoint, direction, query, articulationByEvent
            )
        } else {
            anchorStartPoint to anchorEndPoint
        }

        // Lift endpoints clear of any articulation sitting on the bow side of the
        // start / end note, so the slur doesn't start on top of a staccato dot etc.
        val startPoint = clearEndpointArticulation(rawStartPoint, slur.startEventId, direction, articulationByEvent)
        val endPoint = clearEndpointArticulation(rawEndPoint, slur.endEventId, direction, articulationByEvent)

        fun make(
            start: RelativePoint,
            end: RelativePoint,
            apex: Float,
            staffIndex: Int,
            systemIndex: Int,
            measureNumber: Int,
            collisionApex: Float = 0f,
        ): SlurLayout {
            val nestingGap = effectiveNestingGap(start, end, slur.nestingLevel)
            val maxApex = maxApexHeight(start, end, collisionApex)
            val slopeDamping = slopeDamping(start, end)
            val middleStraightening = middleStraightening(start, end)
            return SlurLayout(
                slurId = slur.slurId,
                startEventId = slur.startEventId,
                startPitchIndex = startPitchIndex,
                endEventId = slur.endEventId,
                endPitchIndex = endPitchIndex,
                start = start,
                end = end,
                minApexHeight = StaffSpace(apex.coerceAtMost(maxApex)),
                maxApexHeight = StaffSpace(maxApex),
                slopeDamping = slopeDamping,
                middleStraightening = middleStraightening,
                direction = direction,
                nestingLevel = slur.nestingLevel,
                staffIndex = staffIndex,
                systemIndex = systemIndex,
                trackId = startEnv.voiceLayout.trackId,
                measureNumber = measureNumber,
            )
        }

        // Cross-system slur: clip into a stub on the start line and another on the
        // end line. Endpoints already carry their system's Y offset (env.staffLayout
        // is system-resolved), so each stub draws at the right height.
        if (!sameSystem) {
            val srcEnd = RelativePoint(
                query.systemBounds(startEnv.systemIndex)?.second ?: endPoint.x, startPoint.y
            )
            val tgtStart = RelativePoint(
                query.systemBounds(endEnv.systemIndex)?.first ?: startPoint.x, endPoint.y
            )
            val srcBaseApex = baseApexHeight(startPoint, srcEnd) +
                effectiveNestingGap(startPoint, srcEnd, slur.nestingLevel)
            val srcCollisionApex = collisionRequiredApex(
                startEvent.id, endEvent.id, startPoint, srcEnd,
                direction, query, articulationByEvent, stemAdjustments, beamGroups,
                obstacleAnchorId = startEvent.id
            )
            val tgtBaseApex = baseApexHeight(tgtStart, endPoint) +
                effectiveNestingGap(tgtStart, endPoint, slur.nestingLevel)
            val tgtCollisionApex = collisionRequiredApex(
                startEvent.id, endEvent.id, tgtStart, endPoint,
                direction, query, articulationByEvent, stemAdjustments, beamGroups,
                obstacleAnchorId = endEvent.id
            )
            return listOf(
                make(
                    startPoint,
                    srcEnd,
                    max(srcBaseApex, srcCollisionApex),
                    startEnv.staffLayout.staffIndex,
                    startEnv.systemIndex,
                    startEnv.voiceLayout.measureNumber,
                    srcCollisionApex
                ),
                make(
                    tgtStart,
                    endPoint,
                    max(tgtBaseApex, tgtCollisionApex),
                    endEnv.staffLayout.staffIndex,
                    endEnv.systemIndex,
                    endEnv.voiceLayout.measureNumber,
                    tgtCollisionApex
                ),
            )
        }

        val baseApex = baseApexHeight(startPoint, endPoint)
        val nestingApex = baseApex + effectiveNestingGap(startPoint, endPoint, slur.nestingLevel)
        val collisionApex = if (sameStaff) {
            collisionRequiredApex(
                startEvent.id, endEvent.id, startPoint, endPoint,
                direction, query, articulationByEvent, stemAdjustments, beamGroups
            )
        } else {
            0f
        }
        val finalApex = max(nestingApex, collisionApex)

        return listOf(
            make(
                startPoint,
                endPoint,
                finalApex,
                startEnv.staffLayout.staffIndex,
                startEnv.systemIndex,
                startEnv.voiceLayout.measureNumber,
                collisionApex,
            )
        )
    }

    /**
     * Pick bow direction.
     *
     *  - **Multi-voice staff** (voice 1 shares its staff with another voice):
     *    voice 1 bows above, voice ≥ 2 bows below — keeps the voices clear of
     *    each other regardless of stems.
     *  - **Single-voice staff**: bow opposes the stems. If both endpoints
     *    have stems UP the bow goes BELOW; both DOWN → ABOVE. Mixed/unknown
     *    stems fall back to ABOVE.
     */
    private fun resolveDirection(
        slur: ComputedSlur,
        startEnv: EventEnvironment,
        endEnv: EventEnvironment,
        voiceCountOnStaff: Int
    ): SlurDirection {
        if (voiceCountOnStaff >= 2) {
            return if (slur.voiceNumber >= 2) SlurDirection.BELOW else SlurDirection.ABOVE
        }
        val startStem = startEnv.stemDirection
        val endStem = endEnv.stemDirection
        return when {
            startStem == StemDirection.UP && endStem == StemDirection.UP -> SlurDirection.BELOW
            startStem == StemDirection.DOWN && endStem == StemDirection.DOWN -> SlurDirection.ABOVE
            else -> SlurDirection.ABOVE
        }
    }

    private fun pickAnchorPitchIndex(event: ComputedVoiceEvent, direction: SlurDirection): Int {
        // Larger staffPosition = higher on staff. Above → top pitch; below → bottom.
        val indices = event.pitchData.indices.toList()
        if (indices.isEmpty()) return 0
        return when (direction) {
            SlurDirection.ABOVE -> indices.maxBy { event.pitchData[it].staffPosition }
            SlurDirection.BELOW -> indices.minBy { event.pitchData[it].staffPosition }
        }
    }

    /**
     * Endpoints sit just outside the chord, lifted off it by
     * [RenderLayoutConfig.slurVerticalGap].
     *
     * Endpoint anchoring depends on which side of the note the bow lives:
     *
     *  - **Notehead side** (bow opposes the stem, e.g. stem UP + bow BELOW):
     *    anchor at the outermost notehead's outer edge.
     *  - **Stem side** (bow on the same side as the stem, e.g. stem UP +
     *    bow ABOVE): anchor at the **stem tip** so the slur encloses both
     *    the notehead and stem as one shape, instead of starting at the
     *    notehead and crossing the stem.
     */
    private fun computeEndpoints(
        startEnv: EventEnvironment,
        startNotehead: NoteheadAnchor,
        endEnv: EventEnvironment,
        endNotehead: NoteheadAnchor,
        direction: SlurDirection,
        stemAdjustments: Map<EventId, StaffSpace>,
    ): Pair<RelativePoint, RelativePoint> {
        val (startX, startY) = endpointFor(startEnv, startNotehead, direction, stemAdjustments)
        val (endX, endY) = endpointFor(endEnv, endNotehead, direction, stemAdjustments)
        return RelativePoint(startX, startY) to RelativePoint(endX, endY)
    }

    private fun endpointFor(
        env: EventEnvironment,
        notehead: NoteheadAnchor,
        direction: SlurDirection,
        stemAdjustments: Map<EventId, StaffSpace>,
    ): Pair<StaffSpace, StaffSpace> {
        val sign = directionSign(direction)
        val verticalGap = StaffSpace(config.slurVerticalGap.value * sign)
        val stem = env.voiceLayout.stem
        val onStemSide = stem != null && (
            (direction == SlurDirection.ABOVE && stem.direction == StemDirection.UP) ||
            (direction == SlurDirection.BELOW && stem.direction == StemDirection.DOWN)
        )

        return if (onStemSide) {
            // Stem-side: anchor at the stem tip, slightly past it in the bow
            // direction so the curve clears the stem.
            val x = env.slotX + env.noteElement.relativeX + stem.relativeX
            val rawTipY = env.staffLayout.centerY + stem.tipY
            val tipY = adjustedStemTipY(env.voiceLayout.eventId, rawTipY, stemAdjustments)
            val attachY = env.staffLayout.centerY + stem.attachmentY
            val outerY = if (direction == SlurDirection.ABOVE) minOf(tipY, attachY) else maxOf(tipY, attachY)
            val y = outerY + verticalGap
            x to y
        } else {
            // Notehead-side: anchor directly above/below the notehead, unlike ties
            // which start/end from the horizontal edges of their noteheads.
            val centerX = notehead.leftEdge + (notehead.rightEdge - notehead.leftEdge) * 0.5f
            val x = env.slotX + env.noteElement.relativeX + centerX
            val outerY = if (direction == SlurDirection.ABOVE) notehead.topY else notehead.bottomY
            val y = env.staffLayout.centerY + outerY + verticalGap
            x to y
        }
    }

    /** Default apex height the curve builder would use without any pre-inflation. */
    private fun baseApexHeight(start: RelativePoint, end: RelativePoint): Float {
        val dx = end.x.value - start.x.value
        val span = abs(dx).coerceAtLeast(0.0001f)
        return (SlurCurveBuilder.DEFAULT_BASE_CURVATURE * sqrt(span))
            .coerceIn(SlurCurveBuilder.DEFAULT_MIN_HEIGHT.value, SlurCurveBuilder.DEFAULT_MAX_HEIGHT.value)
    }

    private fun maxApexHeight(start: RelativePoint, end: RelativePoint, collisionApex: Float): Float {
        val span = abs(end.x.value - start.x.value)
        val longSpanFlattening = (span / LONG_SLUR_FLATTEN_SPAN).coerceIn(0f, 1f) * LONG_SLUR_FLATTEN_AMOUNT
        val legacyCap = SHORT_SLUR_MAX_APEX - longSpanFlattening
        val noCollisionCap = minOf(legacyCap, NO_COLLISION_MAX_APEX)
            .coerceAtLeast(MIN_SLUR_MAX_APEX)
        return max(noCollisionCap, collisionApex)
    }

    private fun collisionInflation(layouts: List<SlurLayout>, nestingLevel: Int): Float {
        if (layouts.isEmpty()) return Float.POSITIVE_INFINITY
        return layouts.maxOf { layout ->
            val naturalApex = baseApexHeight(layout.start, layout.end) +
                effectiveNestingGap(layout.start, layout.end, nestingLevel)
            (layout.minApexHeight.value - naturalApex).coerceAtLeast(0f)
        }
    }

    private fun effectiveNestingGap(start: RelativePoint, end: RelativePoint, nestingLevel: Int): Float {
        if (nestingLevel <= 0) return 0f
        val span = abs(end.x.value - start.x.value)
        val factor = ((span - NESTING_GAP_RAMP_START) / NESTING_GAP_RAMP_RANGE).coerceIn(0f, 1f)
        return nestingLevel * config.slurNestedGap.value * factor
    }

    private fun slopeDamping(start: RelativePoint, end: RelativePoint): Float {
        val span = abs(end.x.value - start.x.value).coerceAtLeast(0.0001f)
        val slope = abs(end.y.value - start.y.value) / span
        val longSpan = ((span - LONG_SLOPE_DAMPING_START) / LONG_SLOPE_DAMPING_RANGE).coerceIn(0f, 1f)
        val steepSpan = ((slope - STEEP_SLOPE_DAMPING_START) / STEEP_SLOPE_DAMPING_RANGE).coerceIn(0f, 1f)
        val damping = 1f - max(longSpan * LONG_SLOPE_DAMPING_AMOUNT, steepSpan * STEEP_SLOPE_DAMPING_AMOUNT)
        return damping.coerceIn(MIN_SLOPE_DAMPING, 1f)
    }

    private fun middleStraightening(start: RelativePoint, end: RelativePoint): Float {
        val span = abs(end.x.value - start.x.value)
        return ((span - LONG_MIDDLE_STRAIGHTENING_START) / LONG_MIDDLE_STRAIGHTENING_RANGE)
            .coerceIn(0f, 1f)
    }

    private fun adjustedStemTipY(
        eventId: EventId,
        rawTipY: StaffSpace,
        stemAdjustments: Map<EventId, StaffSpace>,
    ): StaffSpace {
        val adjusted = stemAdjustments[eventId] ?: return rawTipY
        return if (abs(adjusted.value - rawTipY.value) <= MAX_REASONABLE_STEM_ADJUSTMENT) {
            adjusted
        } else {
            rawTipY
        }
    }

    /**
     * Mature engravers nudge endpoints for wide slurs before asking the Bezier
     * apex to clear everything. This keeps the baseline close to the melodic
     * envelope and prevents one intervening high/low note from inflating the
     * whole curve.
     */
    private fun shapeEndpointLine(
        startId: EventId,
        endId: EventId,
        start: RelativePoint,
        end: RelativePoint,
        direction: SlurDirection,
        query: LayoutQuery,
        articulationByEvent: Map<EventId, ArticulationLayout>
    ): Pair<RelativePoint, RelativePoint> {
        val dx = end.x.value - start.x.value
        if (dx <= MIN_ENDPOINT_SHAPING_SPAN) return start to end

        val sign = directionSign(direction)
        var nearStart = 0f
        var nearEnd = 0f
        val margin = config.slurCollisionMargin.value

        fun consider(absX: Float, absY: Float) {
            if (absX <= start.x.value || absX >= end.x.value) return
            val t = (absX - start.x.value) / dx
            val baselineY = start.y.value + (end.y.value - start.y.value) * t
            val intrusion = (absY - baselineY) * sign + margin
            if (intrusion <= 0f) return

            val endpointWeight = when {
                t < 0.5f -> ((0.5f - t) / 0.5f).coerceIn(0f, 1f)
                else -> ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
            }
            val shift = (intrusion * ENDPOINT_SHAPING_FRACTION * endpointWeight).coerceAtMost(MAX_ENDPOINT_Y_SHIFT)
            if (t < 0.5f) nearStart = max(nearStart, shift) else nearEnd = max(nearEnd, shift)
        }

        visitInterveningObstacles(
            ObstacleVisitRequest(
                startId, endId, start, end, direction, query, articulationByEvent,
                stemAdjustments = emptyMap(),
                beamGroups = emptyList(),
            ),
            consider = ::consider,
        )

        return RelativePoint(start.x, StaffSpace(start.y.value + sign * nearStart)) to
            RelativePoint(end.x, StaffSpace(end.y.value + sign * nearEnd))
    }

    /**
     * Walk every voice event between the slur's start and end on the same
     * staff (any voice on that staff so cross-voice noteheads also count)
     * and return the minimum apex height needed to keep the curve clear of
     * intervening notehead extrema by [RenderLayoutConfig.slurCollisionMargin].
     *
     * Returns 0 if no intervening event poses a risk (short slurs).
     */
    private fun collisionRequiredApex(
        startId: EventId,
        endId: EventId,
        start: RelativePoint,
        end: RelativePoint,
        direction: SlurDirection,
        query: LayoutQuery,
        articulationByEvent: Map<EventId, ArticulationLayout>,
        stemAdjustments: Map<EventId, StaffSpace>,
        beamGroups: List<BeamGroupProcessor.BeamGroupRenderData>,
        obstacleAnchorId: EventId = startId,
    ): Float {
        val sign = directionSign(direction)
        val margin = config.slurCollisionMargin.value

        // Baseline is a straight line between endpoints; we measure the
        // signed vertical excursion required at each intervening notehead.
        val dx = end.x.value - start.x.value
        if (dx <= 0f) return 0f

        var requiredExcursion = 0f

        // Required apex contribution for one intruding point (absolute score-relative
        // X and the point's outer Y in the bow direction).
        fun consider(absX: Float, absY: Float) {
            if (absX <= start.x.value || absX >= end.x.value) return

            // Linear baseline value at this X.
            val t = (absX - start.x.value) / dx
            val baselineY = start.y.value + (end.y.value - start.y.value) * t

            // Signed gap between point and baseline in the bow direction.
            // sign = -1 for ABOVE (intrusion when absY < baselineY, i.e. the point
            //   sticks up past the baseline between the high stem tips),
            //        +1 for BELOW (intrusion when absY > baselineY).
            val signedGap = (absY - baselineY) * sign
            if (signedGap <= -margin) return   // safely on the music side

            // Cubic-Bezier perpendicular peak at parameter s is
            //   3·s·(1-s)·outerOffset  (= 0.75·outerOffset at s=0.5).
            // To clear the point by `margin`, the outer offset must be at least
            // (signedGap + margin) / weight at this t. Clamp weight so
            // near-endpoint intrusions don't blow up.
            val weight = (3f * t * (1f - t)).coerceAtLeast(0.55f)
            val needed = (signedGap + margin) / weight
            if (needed > requiredExcursion) {
                requiredExcursion = needed
            }
        }

        visitInterveningObstacles(
            ObstacleVisitRequest(
                startId, endId, start, end, direction, query, articulationByEvent,
                stemAdjustments = stemAdjustments,
                beamGroups = beamGroups,
                obstacleAnchorId = obstacleAnchorId,
            ),
            consider = ::consider,
        )

        return requiredExcursion
    }

    private fun visitInterveningObstacles(
        request: ObstacleVisitRequest,
        consider: (absX: Float, absY: Float) -> Unit,
    ) {
        val (
            startId,
            endId,
            start,
            end,
            direction,
            query,
            articulationByEvent,
            stemAdjustments,
            beamGroups,
            obstacleAnchorId,
        ) = request
        val startEnv = query.environment(obstacleAnchorId) ?: return
        val staffIndex = startEnv.staffLayout.staffIndex

        // Only events on the same staff with X within the slur span can intrude. Query the
        // staff-local X window instead of scanning the whole score; consider() re-checks the
        // precise (start, end) bounds per notehead, so the inclusion margin is harmless.
        for (voiceLayout in query.voiceLayoutsOnStaffInXRange(startEnv.systemIndex, staffIndex, start.x, end.x)) {
            val eventId = voiceLayout.eventId
            if (eventId == startId || eventId == endId) continue

            val event = query.event(eventId) ?: continue
            if (event.isRest) continue

            val env = query.environment(eventId) ?: continue
            val noteElement = env.noteElement
            val staffLayout = env.staffLayout

            for (head in noteElement.noteBody.noteheads) {
                val absX = env.slotX.value + noteElement.relativeX.value +
                    (head.geometry.bounds.origin.x.value + head.geometry.bounds.width.value * 0.5f)
                val noteheadOuterY = if (direction == SlurDirection.ABOVE) {
                    head.geometry.bounds.origin.y.value
                } else {
                    head.geometry.bounds.origin.y.value + head.geometry.bounds.height.value
                }
                consider(absX, staffLayout.centerY.value + noteheadOuterY)
            }

            // Articulation marks of intervening events also have to be cleared.
            // Their bounds are already in score-relative coordinates (centerY and
            // slot X folded in), so feed the outer edge directly.
            articulationByEvent[eventId]?.marks?.forEach { mark ->
                val absX = mark.bounds.center.x.value
                val outerY = if (direction == SlurDirection.ABOVE) mark.bounds.top.value
                             else mark.bounds.bottom.value
                consider(absX, outerY)
            }

            considerStemObstacle(env, direction, stemAdjustments, consider)
            considerFlagObstacle(env, direction, consider)
        }

        considerBeamObstacles(
            systemIndex = startEnv.systemIndex,
            staffIndex = staffIndex,
            direction = direction,
            query = query,
            beamGroups = beamGroups,
            consider = consider
        )
    }

    private fun considerStemObstacle(
        env: EventEnvironment,
        direction: SlurDirection,
        stemAdjustments: Map<EventId, StaffSpace>,
        consider: (absX: Float, absY: Float) -> Unit,
    ) {
        val stem = env.voiceLayout.stem ?: return
        val absX = env.slotX.value + env.noteElement.relativeX.value + stem.relativeX.value
        var topY = env.staffLayout.centerY + stem.topY
        var bottomY = env.staffLayout.centerY + stem.bottomY
        val adjustedTip = stemAdjustments[env.voiceLayout.eventId]
        if (adjustedTip != null) {
            if (stem.direction == StemDirection.UP) topY = adjustedTip else bottomY = adjustedTip
        }
        val outerY = if (direction == SlurDirection.ABOVE) {
            minOf(topY.value, bottomY.value)
        } else {
            maxOf(topY.value, bottomY.value)
        }
        consider(absX, outerY)
    }

    private fun considerFlagObstacle(
        env: EventEnvironment,
        direction: SlurDirection,
        consider: (absX: Float, absY: Float) -> Unit,
    ) {
        val flag = env.voiceLayout.flag ?: return
        if (env.voiceLayout.beamInfo != null) return

        val absX = env.slotX.value + env.noteElement.relativeX.value + flag.relativeX.value +
            if (flag.direction == StemDirection.UP) FLAG_OBSTACLE_X else -FLAG_OBSTACLE_X
        val flagY = env.staffLayout.centerY.value + flag.relativeY.value
        val outerY = when (direction) {
            SlurDirection.ABOVE -> flagY - FLAG_OUTER_HEIGHT
            SlurDirection.BELOW -> flagY + FLAG_OUTER_HEIGHT
        }
        consider(absX, outerY)
    }

    private fun considerBeamObstacles(
        systemIndex: Int,
        staffIndex: Int,
        direction: SlurDirection,
        query: LayoutQuery,
        beamGroups: List<BeamGroupProcessor.BeamGroupRenderData>,
        consider: (absX: Float, absY: Float) -> Unit,
    ) {
        for (group in beamGroups) {
            if (group.staffIndex != staffIndex) continue
            val notes = group.beamNoteInfos.sortedBy { it.x.value }
            if (notes.size < 2) continue
            val groupSystem = notes.firstNotNullOfOrNull { note ->
                note.eventId?.let { query.environment(it)?.systemIndex }
            } ?: continue
            if (groupSystem != systemIndex) continue

            val slope = if (notes.last().x.value != notes.first().x.value) {
                (notes.last().stemTipY.value - notes.first().stemTipY.value) /
                    (notes.last().x.value - notes.first().x.value)
            } else {
                0f
            }
            val maxBeamLevel = notes.maxOf { it.beamInfo.totalBeamCount }
            val scale = group.noteScale.value
            val beamThickness = EngravingDefaults.BRAVURA.beamThickness.value * scale
            val beamSpacing = EngravingDefaults.BRAVURA.beamSpacing.value * scale
            val halfBeamThickness = beamThickness / 2f

            fun yAt(note: BeamNoteInfo, x: Float): Float =
                note.stemTipY.value + slope * (x - note.x.value)

            fun levelOffset(level: Int): Float {
                val offset = level * (beamThickness + beamSpacing)
                return if (group.stemDirection == StemDirection.UP) offset else -offset
            }

            fun beamOuterY(y: Float): Float =
                if (direction == SlurDirection.ABOVE) y - halfBeamThickness else y + halfBeamThickness

            for (level in 0 until maxBeamLevel) {
                val beamLevel = level + 1
                for (index in 0 until notes.lastIndex) {
                    val left = notes[index]
                    val right = notes[index + 1]
                    if (left.beamInfo.beamsRight < beamLevel || right.beamInfo.beamsLeft < beamLevel) continue

                    val offset = levelOffset(level)
                    consider(left.x.value, beamOuterY(yAt(left, left.x.value) + offset))
                    consider(right.x.value, beamOuterY(yAt(left, right.x.value) + offset))
                    val midX = (left.x.value + right.x.value) / 2f
                    consider(midX, beamOuterY(yAt(left, midX) + offset))
                }
            }
        }
    }

    /**
     * If a slur endpoint sits on the same side as an articulation mark of its
     * anchor note, push the endpoint just past that mark so the curve doesn't
     * begin (or end) on top of it.
     */
    private fun clearEndpointArticulation(
        point: RelativePoint,
        eventId: EventId,
        direction: SlurDirection,
        articulationByEvent: Map<EventId, ArticulationLayout>
    ): RelativePoint {
        val marks = articulationByEvent[eventId]?.marks ?: return point
        val gap = config.slurVerticalGap.value
        var y = point.y.value
        for (mark in marks) {
            if (direction == SlurDirection.ABOVE && mark.above) {
                y = minOf(y, mark.bounds.top.value - gap)
            } else if (direction == SlurDirection.BELOW && !mark.above) {
                y = maxOf(y, mark.bounds.bottom.value + gap)
            }
        }
        return RelativePoint(point.x, StaffSpace(y))
    }

    private fun directionSign(direction: SlurDirection): Float =
        if (direction == SlurDirection.ABOVE) -1f else 1f

    private companion object {
        private const val SHORT_SLUR_MAX_APEX = 3.0f
        private const val MIN_SLUR_MAX_APEX = 1.7f
        private const val NO_COLLISION_MAX_APEX = 2.0f
        private const val LONG_SLUR_FLATTEN_SPAN = 36f
        private const val LONG_SLUR_FLATTEN_AMOUNT = 0.9f
        private const val NESTING_GAP_RAMP_START = 8f
        private const val NESTING_GAP_RAMP_RANGE = 16f
        private const val MIN_ENDPOINT_SHAPING_SPAN = 5f
        private const val ENDPOINT_SHAPING_FRACTION = 0.45f
        private const val MAX_ENDPOINT_Y_SHIFT = 1.25f
        private const val LONG_SLOPE_DAMPING_START = 14f
        private const val LONG_SLOPE_DAMPING_RANGE = 28f
        private const val LONG_SLOPE_DAMPING_AMOUNT = 0.45f
        private const val STEEP_SLOPE_DAMPING_START = 0.12f
        private const val STEEP_SLOPE_DAMPING_RANGE = 0.2f
        private const val STEEP_SLOPE_DAMPING_AMOUNT = 0.5f
        private const val MIN_SLOPE_DAMPING = 0.35f
        private const val LONG_MIDDLE_STRAIGHTENING_START = 18f
        private const val LONG_MIDDLE_STRAIGHTENING_RANGE = 30f
        private const val MAX_REASONABLE_STEM_ADJUSTMENT = 12f
        private const val FLAG_OBSTACLE_X = 0.7f
        private const val FLAG_OUTER_HEIGHT = 0.25f
        private const val DIRECTION_CLEARANCE_EPSILON = 0.01f
    }
}

private fun SlurDirection.opposite(): SlurDirection = when (this) {
    SlurDirection.ABOVE -> SlurDirection.BELOW
    SlurDirection.BELOW -> SlurDirection.ABOVE
}
