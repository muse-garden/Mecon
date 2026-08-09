package com.mecon.renderer.layout

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.LayoutElement
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.elements.KeySignatureElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.elements.TimeSignatureElement
import com.mecon.renderer.layout.TimeCodeArithmetic.ticksTo
import com.mecon.renderer.layout.TimeCodeArithmetic.toAbsoluteTicks

/**
 * Voice identifier for proportional layout algorithm.
 *
 * Groups events by their voice for independent spacing calculation.
 */
sealed interface VoiceId {
    /**
     * A voice within a specific track (staff).
     * In Phase 1, elements on the same staff at the same time share nextEstimateX.
     */
    data class Track(val trackId: TrackId) : VoiceId

    /**
     * A plugin annotation track (e.g. chord symbols). Its members carry no width of
     * their own but reserve horizontal room to the **right** of their onset (the label
     * is left-aligned at the note and extends rightward), so consecutive labels in the
     * same track cannot overlap — pushing the shared global X and reflowing the notes.
     */
    data class Annotation(val trackId: TrackId) : VoiceId

    /**
     * System-wide events (barlines, etc.) that span all staves.
     */
    data object SystemWide : VoiceId
}

/**
 * Tracks the state of a voice during proportional layout computation.
 *
 * Note: All X positions represent the **right edge** of elements, not the left edge.
 * This ensures that the next element can be positioned correctly by adding spacing
 * to the previous element's right edge.
 */
internal data class VoiceState(
    val voiceId: VoiceId,
    /** Time of the last positioned element in this voice */
    var lastTime: TimeCode,
    /** X position of the last positioned element's **right edge** */
    var lastX: StaffSpace,
    /** Width of the last positioned time slot, from its left edge to [lastX]. */
    var lastWidth: StaffSpace,
    /** Duration of the last element (for calculating next spacing) */
    var lastDuration: Duration?,
    /**
     * Horizontal room the last positioned element reserves to its **right** before the
     * next element in this voice may begin (annotation labels; ZERO for notes/system
     * elements, whose spacing is duration-driven).
     */
    var lastTrailing: StaffSpace = StaffSpace.ZERO
)

/**
 * Global state tracking during proportional layout computation.
 *
 * Note: `maxCompleteX` represents the **right edge** of the rightmost element
 * at `maxCompleteTime`, not the left edge.
 */
internal data class GlobalState(
    /** The latest time for which all preceding elements have been positioned */
    var maxCompleteTime: TimeCode,
    /** X position of the **right edge** of elements at maxCompleteTime */
    var maxCompleteX: StaffSpace
)

/**
 * Event data needed for proportional layout calculation.
 *
 * Wraps a real [LayoutElement] (notes / barlines / clefs …) for spacing, or — when
 * [event] is null — a layout-only annotation spacing participant that only reserves
 * [trailingReservation] to its right (see [AnnotationSpacingParticipant]).
 */
internal data class LayoutElementData(
    val voiceId: VoiceId,
    val time: TimeCode,
    val duration: Duration?,
    /** Minimum width this participant occupies at its own onset (0 for annotations). */
    val minimumWidth: StaffSpace,
    /** How far it extends left of its anchor (accidentals; 0 for annotations). */
    val leftOverhang: StaffSpace,
    /** Priority for slot-internal X ordering. */
    val priority: Int,
    /** Room reserved to the right before the next same-voice element (annotations). */
    val trailingReservation: StaffSpace = StaffSpace.ZERO,
    /** Underlying render element; null for annotation spacing participants. */
    val event: LayoutElement? = null,
    /** Calculated X position (set during algorithm) */
    var calculatedX: StaffSpace = StaffSpace.ZERO
)

/**
 * Annotation label spacing participant fed into [ProportionalLayoutComputer.computeXPositions]
 * alongside the real layout elements. Each reserves [width] (label width + gap) to the right of
 * its [time] onset within track [trackId], so notes reflow to keep long chord symbols from
 * overflowing past the next label's anchor.
 */
data class AnnotationSpacingParticipant(
    val time: TimeCode,
    val trackId: TrackId,
    /** Reserved horizontal room to the right of the onset, in staff spaces (label width + gap). */
    val width: StaffSpace
)

/**
 * The trailing reservation a track's last label carries into the **next** measure's solve, so a chord
 * near a barline still constrains the first chord across it. [lastX] is that label's slot right edge and
 * [trailing] its reserved room (`labelWidth + gap`) — the seed for the next measure's annotation voice.
 */
data class AnnotationCarry(
    val lastX: StaffSpace,
    val trailing: StaffSpace
)

/**
 * Computes proportional horizontal spacing for layout events.
 *
 * This algorithm tracks each voice independently and uses proportional
 * interpolation to determine X positions. The result is more musically
 * accurate spacing where notes that occur simultaneously are aligned,
 * and spacing reflects rhythmic relationships.
 *
 * ## Algorithm Overview
 *
 * ### Phase 1: Global X Position Determination
 * 1. Initialize voice states and global state
 * 2. Iteratively select the next event to position based on score:
 *    - Events at maxCompleteTime get infinite score (prioritized for alignment)
 *    - Others: score = (nextEstimateX - maxCompleteX) / (nextTime - maxCompleteTime)
 * 3. Calculate final X using proportional interpolation
 * 4. Align all events at the same time to the same X
 * 5. Interpolate any intermediate events
 * 6. Repeat until all events are positioned
 *
 * ### Phase 2: Collision Detection (separate class)
 * Per-staff collision detection and adjustment.
 */
class ProportionalLayoutComputer(
    private val config: RenderLayoutConfig,
    private val horizontalComputer: HorizontalSpacingComputer
) {
    /**
     * Encapsulates all mutable state for the proportional layout algorithm.
     *
     * This consolidates the 4 independent maps (sortedEventsByVoice, voiceStates,
     * pendingIndices, timeToX) and globalState into a single object, reducing
     * parameter passing across methods.
     */
    private inner class AlgorithmState(
        eventDataList: List<LayoutElementData>,
        startX: StaffSpace,
        minTime: TimeCode,
        annotationSeed: Map<TrackId, AnnotationCarry>
    ) {
        val sortedEventsByVoice: Map<VoiceId, List<LayoutElementData>> =
            eventDataList.groupBy { it.voiceId }.mapValues { (_, voiceEvents) ->
                voiceEvents.sortedBy { it.time }.toMutableList()
            }

        val voiceStates: MutableMap<VoiceId, VoiceState> = sortedEventsByVoice.keys.associateWith { voiceId ->
            // Seed an annotation voice with the previous measure's last label so a chord straddling the
            // barline still constrains the first chord of this measure (cross-measure label overlap).
            val seed = (voiceId as? VoiceId.Annotation)?.let { annotationSeed[it.trackId] }
            VoiceState(
                voiceId = voiceId,
                lastTime = minTime,
                lastX = seed?.lastX ?: startX,
                lastWidth = StaffSpace.ZERO,
                lastDuration = null,
                lastTrailing = seed?.trailing ?: StaffSpace.ZERO
            )
        }.toMutableMap()

        val pendingIndices: MutableMap<VoiceId, Int> =
            sortedEventsByVoice.mapValues { 0 }.toMutableMap()

        val globalState: GlobalState = GlobalState(
            maxCompleteTime = minTime,
            maxCompleteX = startX
        )

        val timeToX: MutableMap<TimeCode, StaffSpace> = mutableMapOf()

        fun hasMoreEvents(): Boolean =
            pendingIndices.any { (voiceId, index) ->
                index < (sortedEventsByVoice[voiceId]?.size ?: 0)
            }

        fun selectNextEvent(): Pair<VoiceId, LayoutElementData>? {
            var bestVoiceId: VoiceId? = null
            var bestEventData: LayoutElementData? = null
            var bestScore = Double.NEGATIVE_INFINITY

            for ((voiceId, events) in sortedEventsByVoice) {
                val index = pendingIndices[voiceId] ?: continue
                if (index >= events.size) continue

                val eventData = events[index]
                val voiceState = voiceStates[voiceId] ?: continue

                val slotWidth = slotMinimumWidth(eventData.time)
                val eventWidth = maxOf(eventData.minimumWidth, slotWidth)
                val nextEstimateX = calculateNextEstimateX(
                    voiceState = voiceState,
                    eventWidth = eventWidth,
                    leftOverhang = eventData.leftOverhang
                )
                val score = calculateScore(
                    eventData.time,
                    nextEstimateX,
                    voiceState.lastX,
                    globalState
                )

                if (score > bestScore) {
                    bestScore = score
                    bestVoiceId = voiceId
                    bestEventData = eventData
                }
            }

            return if (bestVoiceId != null && bestEventData != null) {
                bestVoiceId to bestEventData
            } else {
                null
            }
        }

        fun findEventsAtTime(time: TimeCode): List<Pair<VoiceId, LayoutElementData>> {
            val result = mutableListOf<Pair<VoiceId, LayoutElementData>>()
            val targetTicks = time.toAbsoluteTicks()

            for ((voiceId, events) in sortedEventsByVoice) {
                var index = pendingIndices[voiceId] ?: continue
                while (index < events.size) {
                    val eventData = events[index]
                    if (eventData.time.toAbsoluteTicks() != targetTicks) break
                    result.add(voiceId to eventData)
                    index++
                }
            }

            return result
        }

        fun slotMinimumWidth(time: TimeCode): StaffSpace {
            val eventsAtSameTime = findEventsAtTime(time).map { it.second }
            if (eventsAtSameTime.isEmpty()) return StaffSpace.ZERO
            return slotMinimumWidth(eventsAtSameTime)
        }

        /**
         * The lower bound this slot's left edge must respect because the **previous chord in the same
         * annotation track** reserved room to its right (`lastX + lastTrailing`). Only annotation voices
         * that have a pending event *at [time]* contribute — i.e. the constraint binds only between
         * consecutive chord labels, never between a lone label and an intervening plain note (which the
         * label may freely overlap on its own band). ZERO when no label sits at [time].
         */
        fun annotationTrailingFloor(time: TimeCode): StaffSpace {
            var floor = StaffSpace.ZERO
            for ((voiceId, _) in findEventsAtTime(time)) {
                if (voiceId !is VoiceId.Annotation) continue
                val vs = voiceStates[voiceId] ?: continue
                if (vs.lastTrailing <= StaffSpace.ZERO) continue
                val candidate = vs.lastX + vs.lastTrailing
                if (candidate > floor) floor = candidate
            }
            return floor
        }

        fun positionEventsAtTime(
            selectedTime: TimeCode,
            leftEdgeX: StaffSpace
        ): StaffSpace {
            val eventsAtSameTime = findEventsAtTime(selectedTime)
            val slotWidth = slotMinimumWidth(eventsAtSameTime.map { it.second })

            val maxRightEdge = leftEdgeX + slotWidth
            for ((voiceId, eventData) in eventsAtSameTime) {
                eventData.calculatedX = maxRightEdge

                voiceStates[voiceId]?.apply {
                    lastTime = selectedTime
                    lastX = maxRightEdge
                    lastWidth = slotWidth
                    lastDuration = eventData.duration
                    lastTrailing = eventData.trailingReservation
                }
            }

            for ((voiceId, voiceEventsAtSameTime) in eventsAtSameTime.groupBy { it.first }) {
                val currentIndex = pendingIndices[voiceId]!!
                pendingIndices[voiceId] = currentIndex + voiceEventsAtSameTime.size
            }

            // Store the maximum right edge for ALL events at this time
            for ((_, eventData) in eventsAtSameTime) {
                timeToX[eventData.time] = maxRightEdge
            }

            return maxRightEdge
        }

        fun interpolateIntermediateEvents(
            startTime: TimeCode,
            endTime: TimeCode,
            startX: StaffSpace,
            endX: StaffSpace
        ) {
            val startTicks = startTime.toAbsoluteTicks()
            val endTicks = endTime.toAbsoluteTicks()
            val tickRange = endTicks - startTicks

            if (tickRange <= 0) return

            // Find all events in the time range (exclusive of endpoints)
            val intermediateEvents = mutableListOf<Triple<VoiceId, Int, LayoutElementData>>()

            for ((voiceId, events) in sortedEventsByVoice) {
                var index = pendingIndices[voiceId] ?: continue

                while (index < events.size) {
                    val eventData = events[index]
                    val eventTicks = eventData.time.toAbsoluteTicks()

                    if (eventTicks > startTicks && eventTicks < endTicks) {
                        intermediateEvents.add(Triple(voiceId, index, eventData))
                        index++
                    } else {
                        break
                    }
                }
            }

            // Sort intermediate events by time
            intermediateEvents.sortBy { it.third.time }

            // Group by time and interpolate
            val groupedByTime = intermediateEvents.groupBy { it.third.time.toAbsoluteTicks() }

            for ((eventTicks, eventsAtTime) in groupedByTime) {
                // Linear interpolation for right edge position
                val ratio = (eventTicks - startTicks).toFloat() / tickRange.toFloat()
                val interpolatedRightEdge = StaffSpace(startX.value + (endX.value - startX.value) * ratio)

                val time = eventsAtTime.first().third.time
                val intermediateSlotWidth = slotMinimumWidth(eventsAtTime.map { it.third })

                // Calculate maximum right edge among events at this time
                var maxRightEdge = interpolatedRightEdge
                for ((_, _, eventData) in eventsAtTime) {
                    val leftEdge = interpolatedRightEdge - eventData.minimumWidth
                    val rightEdge = leftEdge + eventData.minimumWidth
                    if (rightEdge > maxRightEdge) {
                        maxRightEdge = rightEdge
                    }
                }

                timeToX[time] = maxRightEdge

                // Update voice states and advance indices
                for ((voiceId, _, eventData) in eventsAtTime) {
                    val rightEdge = interpolatedRightEdge
                    eventData.calculatedX = rightEdge

                    voiceStates[voiceId]?.apply {
                        lastTime = time
                        lastX = rightEdge
                        lastWidth = intermediateSlotWidth
                        lastDuration = eventData.duration
                        lastTrailing = eventData.trailingReservation
                    }
                }

                for ((voiceId, _) in eventsAtTime.groupBy { it.first }) {
                    val events = sortedEventsByVoice[voiceId]!!
                    var currentIndex = pendingIndices[voiceId]!!
                    while (
                        currentIndex < events.size &&
                        events[currentIndex].time.toAbsoluteTicks() == eventTicks
                    ) {
                        currentIndex++
                    }
                    pendingIndices[voiceId] = currentIndex
                }
            }
        }
    }

    /**
     * Compute X positions for all layout events.
     *
     * The returned X positions represent the **right edge** of elements at each TimeCode.
     * This design ensures that:
     * 1. The next element can be positioned by adding spacing to the previous right edge
     * 2. Each element's relativeX (negative) can be used to find its actual start position
     *
     * @param events List of layout events to position
     * @param startX Starting X position (left edge of first element)
     * @param annotationSpacing Annotation label spacing participants (chord symbols etc.) that reserve
     *   room to the right of their onset so an adjacent label reflows notes rather than overlapping.
     * @param annotationSeed Per-track carry from the previous measure's last label (cross-barline
     *   overlap); empty for the first measure or when no label crosses in.
     * @return Map from TimeCode to X position (right edge of elements at that time)
     */
    fun computeXPositions(
        events: List<LayoutElement>,
        startX: StaffSpace,
        annotationSpacing: List<AnnotationSpacingParticipant> = emptyList(),
        annotationSeed: Map<TrackId, AnnotationCarry> = emptyMap()
    ): Map<TimeCode, StaffSpace> {
        if (events.isEmpty() && annotationSpacing.isEmpty()) return emptyMap()

        // Convert to internal event data with voice assignments. Annotation participants are
        // width-0 members of their own annotation voice that reserve trailing room to the right.
        val eventDataList = events.map { event ->
            LayoutElementData(
                voiceId = getVoiceId(event),
                time = event.time,
                duration = getDuration(event),
                minimumWidth = event.minimumWidth,
                leftOverhang = event.leftOverhang,
                priority = event.priority,
                event = event
            )
        } + annotationSpacing.map { participant ->
            LayoutElementData(
                voiceId = VoiceId.Annotation(participant.trackId),
                time = participant.time,
                duration = null,
                minimumWidth = StaffSpace.ZERO,
                leftOverhang = StaffSpace.ZERO,
                priority = LayoutElement.PRIORITY_NOTE,
                trailingReservation = participant.width
            )
        }

        if (eventDataList.isEmpty()) return emptyMap()

        val minTime = eventDataList.minOfOrNull { it.time } ?: return emptyMap()
        val state = AlgorithmState(eventDataList, startX, minTime, annotationSeed)

        // Main algorithm loop
        while (state.hasMoreEvents()) {
            val (selectedVoiceId, selectedEventData) = state.selectNextEvent() ?: break

            val selectedTime = selectedEventData.time

        // Calculate the left edge (start position) for this time slot
        val leftEdgeX = calculateLeftEdgeX(
            selectedVoiceId,
            selectedEventData,
            state.slotMinimumWidth(selectedTime),
            state.voiceStates,
            state.globalState
            )

            // Ensure accidentals (left overhang) don't overlap with preceding elements
            val eventsAtTime = state.findEventsAtTime(selectedTime)
            val maxLeftOverhang = eventsAtTime.maxOfOrNull { (_, ed) -> ed.leftOverhang } ?: StaffSpace.ZERO
            // A preceding chord label reserves room to its right — when another label sits at this time,
            // the aligned notes must not start before that floor, which is how two close labels reflow.
            val trailingFloor = state.annotationTrailingFloor(selectedTime)
            val adjustedLeftEdgeX = maxOf(
                leftEdgeX,
                state.globalState.maxCompleteX + maxLeftOverhang,
                trailingFloor
            )

            // Position all events at the same time
            val maxRightEdge = state.positionEventsAtTime(selectedTime, adjustedLeftEdgeX)

            // Interpolate any events between maxCompleteTime and selectedTime
            state.interpolateIntermediateEvents(
                state.globalState.maxCompleteTime,
                selectedTime,
                state.globalState.maxCompleteX,
                maxRightEdge
            )

            // Update global state with maximum right edge
            state.globalState.maxCompleteTime = selectedTime
            state.globalState.maxCompleteX = maxRightEdge
        }

        return state.timeToX
    }

    /**
     * Get the voice ID for a layout event.
     */
    private fun getVoiceId(event: LayoutElement): VoiceId = when (event) {
        // Cross-staff notes render on the target staff ([trackId]) but must be spaced in time
        // order with the rest of their home voice, so group by [voiceGroupTrackId] when present.
        is NoteElement -> VoiceId.Track(event.voiceGroupTrackId ?: event.trackId)
        is BarlineElement -> VoiceId.SystemWide
        is ClefElement -> VoiceId.SystemWide
        is KeySignatureElement -> VoiceId.SystemWide
        is TimeSignatureElement -> VoiceId.SystemWide
    }

    /**
     * Get the duration for a layout event (if applicable).
     */
    private fun getDuration(event: LayoutElement): Duration? = when (event) {
        is NoteElement -> event.duration
        else -> null
    }

    /**
     * Calculate the estimated next X position (right edge) for a voice.
     *
     * @param voiceState The current state of the voice (lastX is the right edge of last element)
     * @param eventWidth The minimum width of the next event
     * @param leftOverhang How far the next event extends left of its draw anchor (for accidentals)
     * @return Estimated right edge position of the next event
     */
    private fun calculateNextEstimateX(
        voiceState: VoiceState,
        eventWidth: StaffSpace,
        leftOverhang: StaffSpace = StaffSpace.ZERO
    ): StaffSpace {
        val durationSpacing = voiceState.lastDuration?.let { duration ->
            horizontalComputer.widthForDuration(duration)
        } ?: config.minimumNoteSpacing

        val spacing = maxOf(durationSpacing, leftOverhang)

        // lastX is the right edge of the previous element
        // Next element's right edge = previous right edge + spacing + element width
        return voiceState.lastX + spacing + eventWidth
    }

    /**
     * Calculate the selection score for an event.
     *
     * Events at maxCompleteTime get infinite score (prioritized for alignment).
     * Otherwise: score = (nextEstimateX - maxCompleteX) / (nextTime - maxCompleteTime)
     */
    private fun calculateScore(
        eventTime: TimeCode,
        nextEstimateX: StaffSpace,
        lastX: StaffSpace,
        globalState: GlobalState
    ): Double {
        val timeDiff = globalState.maxCompleteTime.ticksTo(eventTime)

        // Events at the same time as maxCompleteTime get infinite score
        if (timeDiff == 0L) {
            return Double.POSITIVE_INFINITY
        }

        // Avoid division by zero and negative time differences
        if (timeDiff <= 0) {
            return Double.NEGATIVE_INFINITY
        }

        val xDiff = nextEstimateX.value - globalState.maxCompleteX.value
        return xDiff.toDouble() / timeDiff.toDouble()
    }

    /**
     * Calculate the left edge (start position) for the selected event.
     *
     * Note: This returns the LEFT edge of the element, not the right edge.
     * The caller is responsible for adding the element width to get the right edge.
     *
     * @return The left edge X position where the element should start
     */
    private fun calculateLeftEdgeX(
        voiceId: VoiceId,
        eventData: LayoutElementData,
        slotWidth: StaffSpace,
        voiceStates: Map<VoiceId, VoiceState>,
        globalState: GlobalState
    ): StaffSpace {
        val voiceState = voiceStates[voiceId]!!
        val eventTime = eventData.time
        val eventWidth = maxOf(eventData.minimumWidth, slotWidth)

        // Check if this is the same time as maxCompleteTime
        val timeDiff = globalState.maxCompleteTime.ticksTo(eventTime)
        if (timeDiff == 0L) {
            // Same time as the reference point
            // For the first batch: maxCompleteX = startX, so left edge = startX
            // For subsequent batches at the same time: this shouldn't happen normally
            // because we process all events at the same time together
            return globalState.maxCompleteX
        }

        // Calculate the estimated right edge for proportional interpolation
        val nextEstimateRightEdge = calculateNextEstimateX(voiceState, eventWidth)
        val voiceTimeDiff = voiceState.lastTime.ticksTo(eventTime)

        if (voiceTimeDiff <= 0) {
            // Fallback: position after the global right edge with minimum spacing
            return globalState.maxCompleteX + config.minimumNoteSpacing
        }

        // Proportional interpolation based on time
        // xSpan is the difference in right edge positions
        val xSpan = nextEstimateRightEdge.value - voiceState.lastX.value
        val timeRatio = timeDiff.toFloat() / voiceTimeDiff.toFloat()
        val offsetX = xSpan * timeRatio

        // Calculate the right edge position
        val rightEdge = StaffSpace(globalState.maxCompleteX.value + offsetX)

        // Ensure minimum spacing from the previous right edge, accounting for left overhang
        val eventLeftOverhang = eventData.leftOverhang
        val minRightEdge = globalState.maxCompleteX + config.minimumNoteSpacing + eventLeftOverhang + eventWidth
        val finalRightEdge = maxOf(rightEdge, minRightEdge)

        // Return the left edge (right edge - element width)
        return finalRightEdge - eventWidth
    }

    private fun slotMinimumWidth(events: List<LayoutElementData>): StaffSpace {
        if (events.isEmpty()) return StaffSpace.ZERO
        val byPriority = events.groupBy { it.priority }.entries.sortedBy { it.key }
        var total = StaffSpace.ZERO
        for ((priority, priorityEvents) in byPriority) {
            val maxWidth = priorityEvents.maxOfOrNull { it.minimumWidth } ?: StaffSpace.ZERO
            total += maxWidth + spacingAfterPriority(priority)
        }
        return total
    }

    private fun spacingAfterPriority(priority: Int): StaffSpace = when (priority) {
        LayoutElement.PRIORITY_BARLINE -> config.spaceAfterBarline
        LayoutElement.PRIORITY_CLEF -> config.spaceAfterClef
        LayoutElement.PRIORITY_KEY_SIGNATURE -> config.spaceAfterKeySignature
        LayoutElement.PRIORITY_TIME_SIGNATURE -> config.spaceAfterTimeSignature
        else -> StaffSpace.ZERO
    }

    companion object {
        /**
         * Create with default configuration.
         */
        fun default(config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT): ProportionalLayoutComputer {
            return ProportionalLayoutComputer(
                config = config,
                horizontalComputer = HorizontalSpacingComputer(config)
            )
        }
    }
}
