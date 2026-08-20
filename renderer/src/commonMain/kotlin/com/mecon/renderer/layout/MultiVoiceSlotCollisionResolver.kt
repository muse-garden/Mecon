package com.mecon.renderer.layout

import com.mecon.renderer.elements.NoteBodyElement
import com.mecon.renderer.elements.NoteElement
import com.mecon.renderer.enums.StemDirection
import com.mecon.renderer.geometry.GlyphGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont

/**
 * Resolves simultaneous voices while a time slot is being assembled.
 *
 * The solver works per staff. It keeps mergeable opposite-stem unisons in the same column and
 * expresses every real clash as a left-to-right difference constraint. Solving the resulting DAG
 * by longest paths yields the smallest offsets for the chosen engraving order, rather than moving
 * the complete time slot in a later collision pass.
 */
context(BravuraFont)
internal class MultiVoiceSlotCollisionResolver(
    private val config: RenderLayoutConfig,
) {
    fun resolve(events: List<NoteElement>): List<NoteElement> {
        if (events.size < 2) return events

        val replacements = HashMap<com.mecon.api.primitive.EventId, NoteElement>()
        for (staffEvents in events.filterNot { it.isRest }.groupBy { it.staffIndex }.values) {
            if (staffEvents.map { it.voiceNumber }.distinct().size < 2) continue
            resolveStaff(staffEvents).forEach { replacements[it.eventId] = it }
        }
        if (replacements.isEmpty()) return events
        return events.map { replacements[it.eventId] ?: it }
    }

    private fun resolveStaff(events: List<NoteElement>): List<NoteElement> {
        val ordered = events.sortedWith(
            compareBy<NoteElement>(
                { stemOrder(it.resolvedStemDirection) },
                { pitchCenter(it) },
                { it.voiceNumber },
                { it.eventId.value },
            )
        )
        val bodies = ordered.map(::bodyItems)
        val mergeable = Array(ordered.size) { arrayOfNulls<Set<Int>>(ordered.size) }
        val separation = Array(ordered.size) { FloatArray(ordered.size) }

        for (right in ordered.indices) {
            for (left in 0 until right) {
                if (ordered[left].voiceNumber == ordered[right].voiceNumber) continue
                val sharedPositions = mergeablePositions(ordered[left], ordered[right])
                mergeable[left][right] = sharedPositions
                separation[left][right] = requiredSeparation(
                    leftEvent = ordered[left],
                    rightEvent = ordered[right],
                    leftItems = bodies[left],
                    rightItems = bodies[right],
                    mergeablePositions = sharedPositions,
                )
            }
        }

        // The engraving order is total, so all constraints point left-to-right. Longest paths give
        // the smallest coordinate of each voice that satisfies every earlier voice constraint.
        val offsets = FloatArray(ordered.size)
        for (right in ordered.indices) {
            var minimum = 0f
            for (left in 0 until right) {
                minimum = maxOf(minimum, offsets[left] + separation[left][right])
            }
            offsets[right] = minimum
        }
        val suppressedAccidentals = Array(ordered.size) { mutableSetOf<Int>() }
        for (later in ordered.indices) {
            for (earlier in 0 until later) {
                if (!sameColumn(offsets[earlier], offsets[later])) continue
                val sharedPositions = mergeable[earlier][later].orEmpty()
                if (sharedPositions.isEmpty()) continue
                suppressDuplicateAccidentals(
                    keeper = ordered[earlier],
                    duplicate = ordered[later],
                    sharedPositions = sharedPositions,
                    suppressedPitchIndices = suppressedAccidentals[later],
                )
            }
        }

        val withSharedAccidentals = layoutAccidentals(
            events = ordered.mapIndexed { index, event ->
                if (suppressedAccidentals[index].isEmpty()) {
                    event
                } else {
                    event.copy(noteBody = event.noteBody.withoutAccidentals(suppressedAccidentals[index]))
                }
            },
            offsets = offsets,
        )

        val clusterLeft = withSharedAccidentals.indices.minOf { index ->
            val event = withSharedAccidentals[index]
            offsets[index] + event.noteBody.leftExtent.value -
                if (event.arpeggioType != null) 1.25f else 0f
        }
        val clusterRight = withSharedAccidentals.indices.maxOf { index ->
            offsets[index] + withSharedAccidentals[index].noteBody.rightExtent.value
        }
        val clusterLeftOverhang = (-clusterLeft).coerceAtLeast(0f)

        return withSharedAccidentals.mapIndexed { index, event ->
            val body = event.noteBody
            event.copy(
                noteBody = body,
                // Keep the solver's zero column on the score-wide time anchor.  clusterLeft may
                // include staff-local accidental ink; using it as the coordinate origin shifts
                // every head on this staff away from simultaneous heads on other staves.
                relativeX = StaffSpace(offsets[index]),
                multiVoiceWidthExtension = StaffSpace(
                    (clusterRight - body.rightExtent.value).coerceAtLeast(0f)
                ),
                multiVoiceLeftOverhang = StaffSpace(clusterLeftOverhang),
            )
        }
    }

    /**
     * Return staff positions whose heads may share a column for this pair.
     *
     * The overlap interval is the intersection of both voices' pitch ranges. Every pitch in that
     * interval must occur in both voices, with the same displayed accidental. Unique notes outside
     * the interval do not prevent the common boundary notes from merging.
     */
    private fun mergeablePositions(first: NoteElement, second: NoteElement): Set<Int> {
        val firstDirection = first.resolvedStemDirection ?: return emptySet()
        val secondDirection = second.resolvedStemDirection ?: return emptySet()
        if (firstDirection == secondDirection) return emptySet()
        if (noteheadTypeFromDuration(first.duration) != noteheadTypeFromDuration(second.duration)) {
            return emptySet()
        }

        val firstByPosition = first.pitchData.groupBy { it.staffPosition }
        val secondByPosition = second.pitchData.groupBy { it.staffPosition }
        val overlapLow = maxOf(
            firstByPosition.keys.minOrNull() ?: return emptySet(),
            secondByPosition.keys.minOrNull() ?: return emptySet(),
        )
        val overlapHigh = minOf(
            firstByPosition.keys.maxOrNull() ?: return emptySet(),
            secondByPosition.keys.maxOrNull() ?: return emptySet(),
        )
        if (overlapLow > overlapHigh) return emptySet()

        val positionsInOverlap = (firstByPosition.keys + secondByPosition.keys)
            .filter { it in overlapLow..overlapHigh }
            .toSet()
        if (positionsInOverlap.isEmpty()) return emptySet()

        for (position in positionsInOverlap) {
            val firstPitches = firstByPosition[position] ?: return emptySet()
            val secondPitches = secondByPosition[position] ?: return emptySet()
            val firstAccidentals = firstPitches.map(::accidentalOrdinal).sorted()
            val secondAccidentals = secondPitches.map(::accidentalOrdinal).sorted()
            if (firstAccidentals != secondAccidentals) return emptySet()
        }
        return positionsInOverlap
    }

    private fun requiredSeparation(
        leftEvent: NoteElement,
        rightEvent: NoteElement,
        leftItems: List<BodyItem>,
        rightItems: List<BodyItem>,
        mergeablePositions: Set<Int>,
    ): Float {
        var required = interleavedRangeSeparation(
            leftEvent = leftEvent,
            rightEvent = rightEvent,
            mergeablePositions = mergeablePositions,
        )
        for (left in leftItems) {
            for (right in rightItems) {
                if (!verticalRangesOverlap(left.bounds, right.bounds)) continue
                if (mayOverlap(left, right, mergeablePositions)) continue

                required = maxOf(
                    required,
                    left.bounds.right.value + RenderConstants.MULTI_VOICE_COLLISION_GAP.value -
                        right.bounds.left.value,
                )
            }
        }
        return required.coerceAtLeast(0f)
    }

    /**
     * Chords can mesh without any pair of notehead bounding boxes touching. For example, the
     * second B-C may sit entirely inside the range F-F of the other voice: keeping both columns at
     * X=0 then puts their stems on (or nearly on) the same vertical line and hides the voice split.
     *
     * A fixed tiny meshing shift is not sufficient here: seconds can move a head to the other side
     * of its stem, leaving the two actual stem attachment columns almost coincident. Measure those
     * columns directly and keep at least one notehead width between them. A half-head gap still
     * leaves the four heads visually fused in nested-second configurations.
     */
    private fun interleavedRangeSeparation(
        leftEvent: NoteElement,
        rightEvent: NoteElement,
        mergeablePositions: Set<Int>,
    ): Float {
        if (mergeablePositions.isNotEmpty()) return 0f

        val leftLow = leftEvent.pitchData.minOfOrNull { it.staffPosition } ?: return 0f
        val leftHigh = leftEvent.pitchData.maxOfOrNull { it.staffPosition } ?: return 0f
        val rightLow = rightEvent.pitchData.minOfOrNull { it.staffPosition } ?: return 0f
        val rightHigh = rightEvent.pitchData.maxOfOrNull { it.staffPosition } ?: return 0f
        if (maxOf(leftLow, rightLow) > minOf(leftHigh, rightHigh)) return 0f

        val referenceHeadWidth = (
            leftEvent.noteBody.noteheads.firstOrNull()?.geometry?.bounds?.width
                ?: rightEvent.noteBody.noteheads.firstOrNull()?.geometry?.bounds?.width
                ?: return 0f
            ).value
        val leftStemX = stemColumnX(leftEvent) ?: return referenceHeadWidth * MIN_STEM_GAP_HEADS
        val rightStemX = stemColumnX(rightEvent) ?: return referenceHeadWidth * MIN_STEM_GAP_HEADS
        val existingStemGap = rightStemX - leftStemX
        return (referenceHeadWidth * MIN_STEM_GAP_HEADS - existingStemGap).coerceAtLeast(0f)
    }

    private fun stemColumnX(event: NoteElement): Float? = when (event.resolvedStemDirection) {
        StemDirection.UP -> event.stemUpAttachment.x.value
        StemDirection.DOWN -> event.stemDownAttachment.x.value
        null -> null
    }

    private fun mayOverlap(
        first: BodyItem,
        second: BodyItem,
        mergeablePositions: Set<Int>,
    ): Boolean {
        val position = first.staffPosition ?: return false
        if (position !in mergeablePositions || second.staffPosition != position) return false
        return when {
            first.kind == BodyKind.NOTEHEAD && second.kind == BodyKind.NOTEHEAD -> true
            first.kind == BodyKind.DOT && second.kind == BodyKind.DOT -> true
            else -> false
        }
    }

    private fun bodyItems(event: NoteElement): List<BodyItem> = buildList {
        for (head in event.noteBody.noteheads) {
            add(BodyItem(head.geometry.bounds, BodyKind.NOTEHEAD, head.staffPosition))
        }
        for (dot in event.noteBody.dots) {
            val pitch = event.pitchData.getOrNull(dot.pitchIndex) ?: continue
            add(BodyItem(dot.geometry.bounds, BodyKind.DOT, pitch.staffPosition))
        }
        // Ledger lines intentionally do not constrain voice columns. Coincident ledger lines are
        // conventionally shared, and a notehead is expected to sit on its own ledger line.
    }

    /**
     * Accidentals form staff-wide columns independent of the voice columns. Keeping their global
     * right edge to the left of the leftmost head avoids the common failure where shifting an upper
     * voice right carries its accidental through a lower voice's notehead.
     */
    private fun layoutAccidentals(
        events: List<NoteElement>,
        offsets: FloatArray,
    ): List<NoteElement> {
        data class Pending(
            val eventIndex: Int,
            val accidental: com.mecon.renderer.elements.AccidentalRenderInfo,
            val staffPosition: Int,
        )

        val pending = buildList {
            for ((eventIndex, event) in events.withIndex()) {
                for (accidental in event.noteBody.accidentals) {
                    val pitch = event.pitchData.getOrNull(accidental.pitchIndex) ?: continue
                    add(Pending(eventIndex, accidental, pitch.staffPosition))
                }
            }
        }.sortedWith(compareByDescending<Pending> { it.staffPosition }.thenBy { it.eventIndex })
        if (pending.isEmpty()) return events

        val leftmostHead = events.indices.minOf { eventIndex ->
            offsets[eventIndex] + (
                events[eventIndex].noteBody.noteheads.minOfOrNull { it.geometry.bounds.left.value }
                    ?: events[eventIndex].noteBody.leftExtent.value
                )
        }
        val columns = mutableListOf<MutableList<Pending>>()
        val padding = config.accidentalSpacing / 2f
        for (candidate in pending) {
            val bounds = candidate.accidental.geometry.bounds.expand(padding)
            val column = columns.firstOrNull { occupants ->
                occupants.none { occupant ->
                    verticalRangesOverlap(
                        bounds,
                        occupant.accidental.geometry.bounds.expand(padding),
                    )
                }
            } ?: mutableListOf<Pending>().also(columns::add)
            column += candidate
        }

        val replacements = Array(events.size) { mutableMapOf<Int, GlyphGeometry>() }
        var columnRight = leftmostHead - config.accidentalNoteheadSpacing.value
        for (column in columns) {
            for (candidate in column) {
                val geometry = candidate.accidental.geometry
                val targetLocalRight = columnRight - offsets[candidate.eventIndex]
                val dx = targetLocalRight - geometry.bounds.right.value
                replacements[candidate.eventIndex][candidate.accidental.pitchIndex] =
                    geometry.translatedX(dx)
            }
            columnRight -= column.maxOf { it.accidental.geometry.bounds.width.value } +
                config.accidentalSpacing.value
        }

        return events.mapIndexed { eventIndex, event ->
            val geometryByPitch = replacements[eventIndex]
            if (geometryByPitch.isEmpty()) {
                event
            } else {
                val accidentals = event.noteBody.accidentals.map { accidental ->
                    val geometry = geometryByPitch[accidental.pitchIndex] ?: accidental.geometry
                    accidental.copy(geometry = geometry)
                }
                event.copy(noteBody = event.noteBody.withAccidentals(accidentals))
            }
        }
    }

    private fun suppressDuplicateAccidentals(
        keeper: NoteElement,
        duplicate: NoteElement,
        sharedPositions: Set<Int>,
        suppressedPitchIndices: MutableSet<Int>,
    ) {
        val keeperSignatures = keeper.pitchData
            .filter { it.staffPosition in sharedPositions && it.effectiveAccidental != null }
            .groupingBy { it.staffPosition to it.effectiveAccidental }
            .eachCount()
            .toMutableMap()

        for (accidental in duplicate.noteBody.accidentals) {
            val pitch = duplicate.pitchData.getOrNull(accidental.pitchIndex) ?: continue
            val key = pitch.staffPosition to pitch.effectiveAccidental
            val available = keeperSignatures[key] ?: 0
            if (available > 0) {
                suppressedPitchIndices += accidental.pitchIndex
                keeperSignatures[key] = available - 1
            }
        }
    }

    private fun NoteBodyElement.withoutAccidentals(pitchIndices: Set<Int>): NoteBodyElement {
        val remaining = accidentals.filterNot { it.pitchIndex in pitchIndices }
        return withAccidentals(remaining)
    }

    private fun NoteBodyElement.withAccidentals(
        newAccidentals: List<com.mecon.renderer.elements.AccidentalRenderInfo>
    ): NoteBodyElement {
        val left = (noteheads.map { it.geometry.bounds.left } + newAccidentals.map { it.geometry.bounds.left })
            .minOrNull() ?: StaffSpace.ZERO
        return copy(accidentals = newAccidentals, leftExtent = left)
    }

    private fun GlyphGeometry.translatedX(dx: Float): GlyphGeometry = copy(
        position = RelativePoint(position.x + StaffSpace(dx), position.y),
        bounds = bounds.copy(
            origin = RelativePoint(bounds.origin.x + StaffSpace(dx), bounds.origin.y)
        ),
    )

    private fun stemOrder(direction: StemDirection?): Int = when (direction) {
        StemDirection.DOWN -> 0
        null -> 1
        StemDirection.UP -> 2
    }

    private fun pitchCenter(event: NoteElement): Float {
        val low = event.pitchData.minOfOrNull { it.staffPosition } ?: 0
        val high = event.pitchData.maxOfOrNull { it.staffPosition } ?: 0
        return (low + high) / 2f
    }

    private fun accidentalOrdinal(pitch: com.mecon.api.computed.ComputedPitchData): Int =
        pitch.effectiveAccidental?.ordinal ?: -1

    private fun verticalRangesOverlap(first: RelativeRect, second: RelativeRect): Boolean =
        first.top < second.bottom && first.bottom > second.top

    private fun sameColumn(first: Float, second: Float): Boolean =
        kotlin.math.abs(first - second) < 0.0001f

    private enum class BodyKind { NOTEHEAD, DOT }

    private data class BodyItem(
        val bounds: RelativeRect,
        val kind: BodyKind,
        val staffPosition: Int?,
    )

    private companion object {
        /** Minimum readable distance between distinct voice stems, in notehead widths. */
        const val MIN_STEM_GAP_HEADS = 1f
    }
}
