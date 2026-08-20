package com.mecon.renderer.layout

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.ScoreTimeMap
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class TimeAxisSegmentRequest(
    val start: TimeCode,
    val end: TimeCode,
    val preferredWidth: StaffSpace,
) {
    init {
        require(start < end) { "Aligned time-axis segment must have positive duration" }
        require(preferredWidth > StaffSpace.ZERO) {
            "Aligned time-axis segment must have positive preferred width"
        }
    }
}

@Serializable
data class AlignedTimeAxisRequest(
    val segments: List<TimeAxisSegmentRequest>,
    val extraAnchors: Set<TimeCode> = emptySet(),
    val leadingInset: StaffSpace = StaffSpace.ZERO,
    /**
     * Optional visual gap between an aligned time boundary and the left edge of the notation content
     * starting there. Structural elements keep their exact boundary positions.
     */
    val notationContentStartGap: StaffSpace? = null,
    val revision: Long = 0,
) {
    init {
        require(segments.isNotEmpty()) { "Aligned time axis requires at least one segment" }
        require(leadingInset >= StaffSpace.ZERO) { "Aligned time-axis inset cannot be negative" }
        require(notationContentStartGap == null || notationContentStartGap >= StaffSpace.ZERO) {
            "Aligned notation-content gap cannot be negative"
        }
    }
}

data class TimeAxisAnchor(
    val time: TimeCode,
    val absoluteTime: Fraction,
    val x: StaffSpace,
)

/** Final, collision-safe horizontal projection shared by notation and external time surfaces. */
data class ResolvedTimeAxis(
    val anchors: List<TimeAxisAnchor>,
    val contentEndX: StaffSpace,
    val revision: Long,
    private val scoreTimeMap: ScoreTimeMap,
    /** Engraved right edge of each closing barline, keyed by its real musical boundary. */
    val measureBoundaries: List<TimeAxisAnchor> = emptyList(),
) {
    init {
        require(anchors.isNotEmpty()) { "Resolved time axis requires anchors" }
    }

    fun xAt(time: TimeCode): StaffSpace {
        val target = scoreTimeMap.absolute(time)
        val (left, right) = surroundingByTime(target)
        if (left.absoluteTime == right.absoluteTime) return left.x
        val ratio = (
            (target - left.absoluteTime).toDouble() /
                (right.absoluteTime - left.absoluteTime).toDouble()
            ).toFloat()
        return left.x + (right.x - left.x) * ratio
    }

    /** Canonical measure-local spelling of [time], including positions exactly on a barline. */
    fun canonicalTimeAt(time: TimeCode): TimeCode =
        scoreTimeMap.timeCodeAt(scoreTimeMap.absolute(time))

    fun timeAt(x: StaffSpace): TimeCode {
        val (left, right) = surroundingByX(x)
        if (left.x == right.x) return left.time
        val ratio = ((x.value - left.x.value) / (right.x.value - left.x.value))
        val ratioFraction = Fraction((ratio * 1_000_000f).roundToInt(), 1_000_000)
        val absolute = left.absoluteTime +
            (right.absoluteTime - left.absoluteTime) * ratioFraction
        return scoreTimeMap.timeCodeAt(absolute)
    }

    private fun surroundingByTime(target: Fraction): Pair<TimeAxisAnchor, TimeAxisAnchor> {
        if (anchors.size == 1) return anchors.first() to anchors.first()
        if (target <= anchors.first().absoluteTime) return anchors[0] to anchors[1]
        if (target >= anchors.last().absoluteTime) {
            return anchors[anchors.lastIndex - 1] to anchors.last()
        }
        var low = 0
        var high = anchors.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) ushr 1
            if (anchors[middle].absoluteTime <= target) low = middle else high = middle
        }
        return anchors[low] to anchors[high]
    }

    private fun surroundingByX(target: StaffSpace): Pair<TimeAxisAnchor, TimeAxisAnchor> {
        if (anchors.size == 1) return anchors.first() to anchors.first()
        if (target <= anchors.first().x) return anchors[0] to anchors[1]
        if (target >= anchors.last().x) {
            return anchors[anchors.lastIndex - 1] to anchors.last()
        }
        var low = 0
        var high = anchors.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) ushr 1
            if (anchors[middle].x <= target) low = middle else high = middle
        }
        return anchors[low] to anchors[high]
    }
}

internal data class AlignedTimeAxisProjection(
    val slots: UnifiedTimeSlotMap,
    val axis: ResolvedTimeAxis,
)

/**
 * Longest-path difference-constraint pass applied after intrinsic proportional spacing.
 *
 * Request widths and intrinsic slot distances are both lower bounds. The resulting projection is
 * therefore the smallest left-to-right expansion satisfying the external ruler without ever
 * compressing engraving geometry.
 */
internal object AlignedTimeAxisResolver {
    fun resolve(
        runtime: RuntimeScore,
        intrinsic: UnifiedTimeSlotMap,
        request: AlignedTimeAxisRequest,
        systemStartX: StaffSpace,
    ): AlignedTimeAxisProjection {
        val timeMap = ScoreTimeMap.from(runtime)
        val timeByAbsolute = linkedMapOf<Fraction, TimeCode>()
        fun add(time: TimeCode) {
            val absolute = timeMap.absolute(time)
            if (absolute !in timeByAbsolute) timeByAbsolute[absolute] = time
        }
        fun intrinsicAbsolute(slot: UnifiedTimeSlot): Fraction {
            val endingMeasure = slot.events
                .filterIsInstance<BarlineElement>()
                .maxOfOrNull(BarlineElement::measureNumber)
            return if (endingMeasure != null && endingMeasure > 0) {
                // Computed barlines use a synthetic post-measure beat so TimeCode ordering can place
                // them after events ending the old measure and before the next measure's events.
                // That beat is structural, not musical time (in 2/4 it is a whole quarter note).
                // Collapse it to the actual next-measure boundary for external time projection.
                timeMap.absolute(TimeCode.of(endingMeasure + 1, Fraction.ZERO))
            } else {
                timeMap.absolute(slot.time)
            }
        }
        request.segments.forEach { segment ->
            add(segment.start)
            add(segment.end)
        }
        request.extraAnchors.forEach(::add)
        intrinsic.all().forEach { slot ->
            val absolute = intrinsicAbsolute(slot)
            if (absolute !in timeByAbsolute) {
                timeByAbsolute[absolute] = timeMap.timeCodeAt(absolute)
            }
        }

        val ordered = timeByAbsolute.entries.sortedBy { it.key }
        val indexByAbsolute = ordered.mapIndexed { index, entry -> entry.key to index }.toMap()
        val lowerBounds = FloatArray(ordered.size) { Float.NEGATIVE_INFINITY }
        val boundaryVisualOffsets = arrayOfNulls<Float>(ordered.size)
        val edges = Array(ordered.size) { mutableListOf<Pair<Int, Float>>() }
        intrinsic.all().forEach { slot ->
            val index = indexByAbsolute.getValue(intrinsicAbsolute(slot))
            lowerBounds[index] = maxOf(lowerBounds[index], slot.x.value)
            slot.events.filterIsInstance<BarlineElement>()
                .filter { it.measureNumber > 0 }
                .forEach { barline ->
                    val visualRight = (barline.relativeX + barline.minimumWidth).value
                    boundaryVisualOffsets[index] = boundaryVisualOffsets[index]
                        ?.let { maxOf(it, visualRight) }
                        ?: visualRight
                }
        }
        val firstRequested = request.segments.minOf { timeMap.absolute(it.start) }
        indexByAbsolute[firstRequested]?.let { index ->
            lowerBounds[index] = maxOf(
                lowerBounds[index],
                (systemStartX + request.leadingInset).value,
            )
        }
        intrinsic.all().zipWithNext().forEach { (left, right) ->
            val start = intrinsicAbsolute(left)
            val end = intrinsicAbsolute(right)
            val duration = end - start
            if (!duration.isPositive) return@forEach
            val width = (right.x.value - left.x.value).coerceAtLeast(0f)
            val contained = ordered.indices.filter { index ->
                ordered[index].key >= start && ordered[index].key <= end
            }
            // External harmony boundaries can subdivide one sparse notation interval (for example
            // whole-measure rest → barline). Split the intrinsic lower bound across those boundaries
            // by musical duration; a single edge from left to right would dump all remaining width
            // into the final harmony segment and make equal-duration chords alternate short/long.
            contained.zipWithNext().forEach { (from, to) ->
                val share = (
                    (ordered[to].key - ordered[from].key).toDouble() /
                        duration.toDouble()
                    ).toFloat()
                edges[from] += to to (width * share)
            }
        }
        request.segments.forEach { segment ->
            val start = timeMap.absolute(segment.start)
            val end = timeMap.absolute(segment.end)
            val duration = end - start
            val contained = ordered.indices.filter { index ->
                ordered[index].key >= start && ordered[index].key <= end
            }
            contained.zipWithNext().forEach { (from, to) ->
                val share = (
                    (ordered[to].key - ordered[from].key).toDouble() /
                        duration.toDouble()
                    ).toFloat()
                val fromOffset = boundaryVisualOffsets[from] ?: 0f
                val toOffset = boundaryVisualOffsets[to] ?: 0f
                // Segment widths describe visible musical boundaries. A closing barline is drawn
                // left inside its padded unified slot, so its slot must travel farther right for
                // the engraved rule itself to land at the requested boundary.
                edges[from] += to to (
                    segment.preferredWidth.value * share + fromOffset - toOffset
                    )
            }
        }

        val resolved = FloatArray(ordered.size) { Float.NEGATIVE_INFINITY }
        ordered.indices.forEach { index ->
            val monotonic = if (index == 0) {
                (systemStartX + request.leadingInset).value
            } else {
                resolved[index - 1]
            }
            resolved[index] = maxOf(resolved[index], lowerBounds[index], monotonic)
            edges[index].forEach { (target, width) ->
                resolved[target] = maxOf(resolved[target], resolved[index] + width)
            }
        }
        val projectedSlots = intrinsic.withSlots(
            intrinsic.all().map { slot ->
                val index = indexByAbsolute.getValue(intrinsicAbsolute(slot))
                // TimeCode.ZERO's score-opening barline and measure 1 beat 0 share the same
                // absolute musical time. The latter is pushed right by the initial clef/key/time
                // header and defines the external time-axis anchor; moving the opening structural
                // slot to that anchor would detach its barline from the left end of the staff.
                val scoreOpeningSlot = slot.events.any { event ->
                    event is BarlineElement && event.measureNumber == 0
                }
                val projectedX = if (scoreOpeningSlot) slot.x else StaffSpace(resolved[index])
                val contentStartGap = request.notationContentStartGap
                val noteEvents = slot.events.filterIsInstance<NoteElement>()
                if (contentStartGap == null || noteEvents.isEmpty()) {
                    slot.copy(x = projectedX)
                } else {
                    // A unified slot X is the right edge of its event group, so notes normally extend
                    // left from the exact time boundary. Move the complete same-time notation cluster
                    // together until its leftmost origin sits just inside the following time range.
                    // Barline/clef/key/time-signature offsets remain untouched at the boundary.
                    val leftmostNoteX = noteEvents.minOf { it.relativeX.value }
                    val noteShift = StaffSpace(contentStartGap.value - leftmostNoteX)
                    slot.copy(
                        x = projectedX,
                        events = slot.events.map { event ->
                            if (event is NoteElement) {
                                event.copy(relativeX = event.relativeX + noteShift)
                            } else {
                                event
                            }
                        },
                    )
                }
            }
        )
        val anchors = ordered.mapIndexed { index, (absolute, time) ->
            TimeAxisAnchor(
                time,
                absolute,
                StaffSpace(resolved[index] + (boundaryVisualOffsets[index] ?: 0f)),
            )
        }
        val measureBoundaries = anchors.filterIndexed { index, _ ->
            boundaryVisualOffsets[index] != null
        }
        return AlignedTimeAxisProjection(
            slots = projectedSlots,
            axis = ResolvedTimeAxis(
                anchors = anchors,
                contentEndX = anchors.last().x,
                revision = request.revision,
                scoreTimeMap = timeMap,
                measureBoundaries = measureBoundaries,
            ),
        )
    }
}
