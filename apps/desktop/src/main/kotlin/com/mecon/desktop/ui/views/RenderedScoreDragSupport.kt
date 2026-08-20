package com.mecon.desktop.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Size
import com.mecon.api.primitive.EventId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.Pitch
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.storage.BeamGeometry
import com.mecon.api.storage.CrossStaffBeamBase
import com.mecon.api.storage.NavigationMark
import com.mecon.api.storage.NavigationMarkOffset
import com.mecon.api.interaction.*
import com.mecon.renderer.geometry.*
import com.mecon.renderer.render.*
import com.mecon.renderer.smufl.EngravingDefaults

/**
 * Select the most specific (highest priority) section from a list of hit sections.
 * Finer-grained sections like individual noteheads take precedence over
 * coarser sections like whole voice events.
 */
internal fun List<EventSection>.selectByPriority(): EventSection? {
    return this.minByOrNull { section ->
        when (section) {
            is VoiceNoteSection -> 0
            is VoiceArticulationSection -> 1
            is VoiceStemSection -> 2
            is VoiceFlagSection -> 3
            is VoiceBeamSection -> 4
            is VoiceEventSection -> 5
            is VoiceTupletSection -> 6
            is VoiceTieSection -> 7
            is VoiceSlurSection -> 7
            is LayoutBreakSection -> 8
            is VoltaEndingSection -> 8
            is NavigationMarkSection -> 8
            is BarlineSection -> 9
            is ClefSection -> 10
            is KeySignatureSection -> 11
            is TimeSignatureSection -> 12
            is StaffAttachmentSection -> 1
            is MeasureStaffSection -> 13
            is HiddenStaffSection -> 14
        }
    }
}

/** Which behaviour a mouse-drag gesture resolved to at its start (see the unified drag handler). */
internal enum class DragMode {
    NONE, PAN, TRANSPOSE, REST_MOVE, BEAM, ATTACHMENT, CURVE, VOLTA, NAVIGATION,
    ANNOTATION_RANGE, MARQUEE
}

internal data class AnnotationRangeEndpointHit(
    val eventId: EventId,
    val endpoint: AnnotationRangeEndpoint,
    val point: AbsolutePoint,
    val systemIndex: Int,
)

internal data class AnnotationRangeDragState(
    val eventId: EventId,
    val endpoint: AnnotationRangeEndpoint,
    val originalPoint: AbsolutePoint,
    val currentPoint: AbsolutePoint,
    val sourceSystemIndex: Int,
    val candidateTime: TimeCode? = null,
)

internal data class AnnotationBoundarySnap(
    val time: TimeCode,
    val absoluteX: Float,
)

internal fun annotationDragTargetSystem(
    sourceSystemIndex: Int,
    sourceRawY: Float?,
    pointerRawY: Float,
    nearestSystemIndex: Int?,
    sourceRowLockPx: Float,
): Int = if (
    sourceRawY != null && kotlin.math.abs(pointerRawY - sourceRawY) <= sourceRowLockPx
) {
    sourceSystemIndex
} else {
    nearestSystemIndex ?: sourceSystemIndex
}

internal fun annotationRangeEndpointPoints(
    result: RenderResult,
    eventId: EventId,
): List<AnnotationRangeEndpointHit> {
    val elements = result.elements.filter {
        it.type == RenderElementType.TEXT_ANNOTATION && it.eventId == eventId &&
            it.hitBox.width.value > 0f && it.hitBox.height.value > 0f
    }
    if (elements.isEmpty()) return emptyList()
    // A Range keeps the original whole-range measure metadata after the annotation layout splits
    // it across systems. Use the fragment's system ownership to expose only the two true outer
    // endpoints; otherwise every line start/end becomes a handle for the whole musical range.
    val firstSystem = elements.minOf { it.systemIndex ?: 0 }
    val lastSystem = elements.maxOf { it.systemIndex ?: 0 }
    val firstSystemElements = elements.filter { (it.systemIndex ?: 0) == firstSystem }
    val lastSystemElements = elements.filter { (it.systemIndex ?: 0) == lastSystem }
    val startX = firstSystemElements.minOf { it.hitBox.origin.x.value }
    val endX = lastSystemElements.maxOf { it.hitBox.bottomRight.x.value }
    return buildList {
        firstSystemElements.filter {
            kotlin.math.abs(it.hitBox.origin.x.value - startX) <= ENDPOINT_ALIGNMENT_TOLERANCE_PX
        }.forEach { element ->
            add(
                AnnotationRangeEndpointHit(
                    eventId,
                    AnnotationRangeEndpoint.START,
                    AbsolutePoint(element.hitBox.origin.x, element.center.y),
                    element.systemIndex ?: firstSystem,
                )
            )
        }
        lastSystemElements.filter {
            kotlin.math.abs(it.hitBox.bottomRight.x.value - endX) <= ENDPOINT_ALIGNMENT_TOLERANCE_PX
        }.forEach { element ->
            add(
                AnnotationRangeEndpointHit(
                    eventId,
                    AnnotationRangeEndpoint.END,
                    AbsolutePoint(element.hitBox.bottomRight.x, element.center.y),
                    element.systemIndex ?: lastSystem,
                )
            )
        }
    }
}

/** Endpoint hit testing for split annotation ranges, including duplicated double-tonality lines. */
internal fun annotationRangeEndpointAt(
    result: RenderResult,
    point: AbsolutePoint,
    resizableEventIds: Set<EventId>,
    radius: Float,
): AnnotationRangeEndpointHit? = resizableEventIds.asSequence()
    .flatMap { eventId -> annotationRangeEndpointPoints(result, eventId).asSequence() }
    .filter { hit ->
        val dx = hit.point.x.value - point.x.value
        val dy = hit.point.y.value - point.y.value
        dx * dx + dy * dy <= radius * radius
    }.minByOrNull { hit ->
        val dx = hit.point.x.value - point.x.value
        val dy = hit.point.y.value - point.y.value
        dx * dx + dy * dy
    }

/** Snap a tonal-range endpoint to the nearest visible note/barline boundary in one system. */
internal fun resolveAnnotationBoundaryTime(
    result: RenderResult,
    absoluteX: Float,
    systemIndex: Int?,
): TimeCode? = resolveAnnotationBoundarySnap(result, absoluteX, systemIndex)?.time

internal fun resolveAnnotationBoundarySnap(
    result: RenderResult,
    absoluteX: Float,
    systemIndex: Int?,
): AnnotationBoundarySnap? {
    val targetSystem = systemIndex?.let { target ->
        result.spatialIndex.allSystems().firstOrNull { it.systemIndex == target }
    }
    fun isOnTargetSystem(position: TimeCodePosition): Boolean {
        if (systemIndex == null) return true
        val system = targetSystem ?: return false
        val middleY = (position.topY + position.bottomY) / 2f
        val relativeY = result.transformerSnapshot.toRelative(
            AbsolutePoint(Pixels(position.x), Pixels(middleY))
        ).y
        return relativeY >= system.topY && relativeY <= system.bottomY
    }
    val slotCandidates = result.timeCodePositions.values.asSequence()
        .filter(::isOnTargetSystem)
        // Annotation ranges anchor to the left edge of the note group, not the slot's right edge.
        .map { it.timeCode to it.leftX }
    val measureCandidates = result.measureBounds.asSequence()
        .filter { systemIndex == null || it.systemIndex == systemIndex }
        .flatMap { bounds ->
            val leftX = result.transformerSnapshot.toAbsolute(
                RelativePoint(bounds.leftX, StaffSpace.ZERO)
            ).x.value
            val rightX = result.transformerSnapshot.toAbsolute(
                RelativePoint(bounds.rightX, StaffSpace.ZERO)
            ).x.value
            fun renderedAnchor(time: TimeCode, boundaryX: Float): Pair<TimeCode, Float> {
                val slotX = result.timeCodePositions[time]
                    ?.takeIf(::isOnTargetSystem)
                    ?.leftX
                return time to (slotX ?: boundaryX)
            }
            sequenceOf(
                renderedAnchor(TimeCode.of(bounds.measureNumber, Fraction.ZERO), leftX),
                renderedAnchor(TimeCode.of(bounds.measureNumber + 1, Fraction.ZERO), rightX),
            )
        }
    return (slotCandidates + measureCandidates)
        .distinctBy { it.first to it.second }
        .minByOrNull { kotlin.math.abs(it.second - absoluteX) }
        ?.let { (time, x) -> AnnotationBoundarySnap(time, x) }
}

/** Timeout safety net: if no committed re-render arrives this long after release, drop the hold anyway. */
internal const val COMMIT_HOLD_TIMEOUT_MS = 5000L
private const val ENDPOINT_ALIGNMENT_TOLERANCE_PX = 0.5f
internal const val ANNOTATION_SOURCE_ROW_LOCK_DP = 28f
internal const val BEAM_CONTROL_SIZE_DP = 8f
internal const val BEAM_CONTROL_STROKE_DP = 1.5f
internal const val BEAM_CONTROL_HIT_RADIUS = 4f
internal const val ATTACHMENT_CONTROL_HIT_RADIUS = 8f
internal const val VOLTA_CONTROL_HIT_RADIUS = 18f
internal const val VOLTA_CONTROL_SIZE_DP = 12f

internal enum class VoltaEndpoint { START, END }
internal enum class CurveKind { TIE, SLUR }

internal data class CurveDragState(
    val kind: CurveKind,
    val sectionId: EventSectionId,
    val elementIds: List<RenderElementId>,
    val above: Boolean,
    val startApex: Float,
    val currentApex: Float,
    val slopeDamping: Float,
    val middleStraightening: Float,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

internal data class CurveDragPreviewSegment(
    val commands: List<RenderCommand>,
    val anchor: AbsolutePoint,
)

internal data class CurveDragPreview(
    val segments: List<CurveDragPreviewSegment>,
)

internal data class NavigationDragState(
    val sectionId: EventSectionId,
    val elementId: RenderElementId,
    val boundaryMeasure: Int,
    val mark: NavigationMark,
    val start: NavigationMarkOffset,
    val current: NavigationMarkOffset,
    val targetBoundaryMeasure: Int,
    val startVisualCenterX: Float,
    val targetAnchorX: Float = startVisualCenterX - start.dx,
    val startAnchorY: Float,
    val targetAnchorY: Float,
    val previewDx: Float = 0f,
    val previewDy: Float = 0f,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

/** Remove the source-to-target system distance from a drag's accumulated Y offset. */
internal fun navigationOffsetYAfterSnap(
    currentOffsetY: Float,
    sourceAnchorY: Float,
    targetAnchorY: Float,
): Float = currentOffsetY + sourceAnchorY - targetAnchorY

internal data class AttachmentDragState(
    val id: EventId,
    val endpoint: String,
    val isHairpin: Boolean,
    val isBreath: Boolean,
    val start: com.mecon.api.storage.AttachmentGeometry,
    val current: com.mecon.api.storage.AttachmentGeometry,
    val staffId: TrackId,
    val startTime: TimeCode,
    val endTime: TimeCode?,
    val originalStartTime: TimeCode,
    val originalEndTime: TimeCode?,
    val elementId: RenderElementId,
    val originalStartPoint: AbsolutePoint,
    val originalEndPoint: AbsolutePoint?,
    val systemIndex: Int?,
    val topLimit: Float,
    val bottomLimit: Float,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

/** Resolve expression insertion using staff Y bands and midpoint X separators between event slots. */
internal fun resolveExpressionAnchor(
    result: RenderResult,
    runtime: RuntimeScore,
    point: AbsolutePoint,
): Pair<TrackId, TimeCode>? {
    val relative = result.transformerSnapshot.toRelative(point)
    val system = result.spatialIndex.allSystems()
        .filter { relative.y >= it.topY && relative.y <= it.bottomY }
        .minByOrNull { candidate ->
            candidate.staffRegions.minOfOrNull { kotlin.math.abs(it.centerY.value - relative.y.value) }
                ?: Float.MAX_VALUE
        } ?: return null
    val staffRegion = system.staffRegions.minByOrNull {
        kotlin.math.abs(it.centerY.value - relative.y.value)
    } ?: return null
    val staffId = runtime.orderedStaffs().getOrNull(staffRegion.staffIndex)?.id ?: return null
    val time = resolveExpressionTime(result, point.x.value, system.systemIndex) ?: return null
    return staffId to time
}

/** Resolve X against one fixed system, independent of pointer Y during an attachment drag. */
internal fun resolveExpressionTime(
    result: RenderResult,
    absoluteX: Float,
    systemIndex: Int?,
): TimeCode? {
    val system = systemIndex?.let { index ->
        result.spatialIndex.allSystems().firstOrNull { it.systemIndex == index }
    }
    val positions = result.timeCodePositions.values
        .filter { position ->
            if (system == null) true else {
                val middleY = (position.topY + position.bottomY) / 2f
                val relativeY = result.transformerSnapshot.toRelative(
                    AbsolutePoint(Pixels(position.x), Pixels(middleY))
                ).y
                relativeY >= system.topY && relativeY <= system.bottomY
            }
        }
        .sortedBy { it.x }
    if (positions.isEmpty()) return null
    val index = (0 until positions.lastIndex).firstOrNull { i ->
        absoluteX < (positions[i].x + positions[i + 1].x) / 2f
    } ?: positions.lastIndex
    return positions[index].timeCode
}

private data class BreathBoundaryCandidate(
    val time: TimeCode,
    val absoluteX: Float,
)

/**
 * Breath marks live on boundaries, not note columns. Candidates are exact
 * barlines plus the visual midpoint between adjacent note slots.
 */
private fun breathBoundaryCandidates(
    result: RenderResult,
    systemIndex: Int?,
): List<BreathBoundaryCandidate> {
    val positions = result.timeCodePositions.values
        .filter { position ->
            if (systemIndex == null) true else {
                val middleY = (position.topY + position.bottomY) / 2f
                val relativeY = result.transformerSnapshot.toRelative(
                    AbsolutePoint(Pixels(position.x), Pixels(middleY))
                ).y
                result.spatialIndex.allSystems()
                    .firstOrNull { it.systemIndex == systemIndex }
                    ?.let { relativeY >= it.topY && relativeY <= it.bottomY } == true
            }
        }
        .sortedBy { it.x }
    val midpoints = positions.zipWithNext { left, right ->
        BreathBoundaryCandidate(
            time = right.timeCode,
            absoluteX = (left.x + right.x) / 2f,
        )
    }
    val barlines = result.elements.asSequence()
        .filter { it.type == RenderElementType.BARLINE }
        .filter { systemIndex == null || it.systemIndex == systemIndex }
        .flatMap { element ->
            result.sectionIndex.sectionsFor(element.id).asSequence()
                .filterIsInstance<BarlineSection>()
                .map { section ->
                    BreathBoundaryCandidate(section.barline.time, element.center.x.value)
                }
        }
        .distinctBy { it.time to it.absoluteX }
        .toList()
    return (midpoints + barlines).distinctBy { it.time to it.absoluteX }
}

internal fun resolveBreathBoundaryTime(
    result: RenderResult,
    absoluteX: Float,
    systemIndex: Int?,
): TimeCode? = breathBoundaryCandidates(result, systemIndex)
    .minByOrNull { kotlin.math.abs(it.absoluteX - absoluteX) }
    ?.time

internal fun breathBoundaryAnchor(
    result: RenderResult,
    time: TimeCode,
    staffIndex: Int,
    systemIndex: Int?,
    symbol: AbsolutePoint,
): AbsolutePoint? {
    val candidate = breathBoundaryCandidates(result, systemIndex)
        .filter { it.time == time }
        .minByOrNull { kotlin.math.abs(it.absoluteX - symbol.x.value) }
        ?: return null
    val system = result.spatialIndex.allSystems()
        .firstOrNull { it.systemIndex == systemIndex } ?: return null
    val staffCenter = system.staffRegions.firstOrNull { it.staffIndex == staffIndex }?.centerY
        ?: return null
    val absoluteY = result.transformerSnapshot.toAbsolute(
        RelativePoint(StaffSpace.ZERO, staffCenter)
    ).y
    return AbsolutePoint(Pixels(candidate.absoluteX), absoluteY)
}

internal fun deriveAttachmentGeometry(
    result: RenderResult,
    section: StaffAttachmentSection,
    element: RenderElement,
): com.mecon.api.storage.AttachmentGeometry? {
    val attachment = section.attachment
    val startPos = result.timeCodePositions[attachment.time] ?: return null
    val startAnchor = result.transformerSnapshot.toRelative(
        AbsolutePoint(Pixels(startPos.x), element.center.y)
    )
    val origin = result.transformerSnapshot.toRelative(element.hitBox.origin)
    val center = result.transformerSnapshot.toRelative(element.center)
    val system = result.spatialIndex.allSystems().firstOrNull { it.systemIndex == element.systemIndex }
    val staffCenter = system?.staffRegions?.firstOrNull { it.staffIndex == attachment.staffIndex }?.centerY?.value ?: 0f
    val endTime = when (attachment) {
        is com.mecon.api.computed.ComputedHairpin -> attachment.endTime
        is com.mecon.api.computed.ComputedOctaveShift -> attachment.endTime
        is com.mecon.api.computed.ComputedOrnamentMark -> attachment.endTime
        is com.mecon.api.computed.ComputedTempoKeyframe ->
            attachment.nextTime.takeIf { attachment.isGradual }
        else -> null
    }
    val endDx = endTime?.let { time ->
        val pos = result.timeCodePositions[time] ?: return@let null
        val end = result.transformerSnapshot.toRelative(
            AbsolutePoint(element.hitBox.bottomRight.x, element.center.y)
        )
        val anchor = result.transformerSnapshot.toRelative(
            AbsolutePoint(Pixels(pos.x), element.center.y)
        )
        end.x.value - anchor.x.value
    }
    return com.mecon.api.storage.AttachmentGeometry(
        startDx = origin.x.value - startAnchor.x.value,
        startDy = center.y.value - staffCenter,
        endDx = endDx,
        endDy = endDx?.let { center.y.value - staffCenter },
    )
}

/** Return the closest rendered notehead at [time] on [staffIndex] to the attachment endpoint. */
internal fun nearestNoteheadAnchor(
    result: RenderResult,
    time: TimeCode,
    staffIndex: Int,
    systemIndex: Int?,
    symbol: AbsolutePoint,
): AbsolutePoint? {
    val candidates = result.elements.asSequence()
        .filter { it.type == RenderElementType.NOTEHEAD }
        .flatMap { element ->
            result.sectionIndex.sectionsFor(element.id).asSequence()
                .filterIsInstance<VoiceNoteSection>()
                .map { section ->
                    NoteheadAnchorCandidate(
                        center = element.center,
                        time = section.event.onset,
                        staffIndex = element.staffIndex,
                        systemIndex = element.systemIndex,
                    )
                }
        }
        .toList()
    return chooseNearestNoteheadAnchor(candidates, time, staffIndex, systemIndex, symbol)
}

internal data class NoteheadAnchorCandidate(
    val center: AbsolutePoint,
    val time: TimeCode,
    val staffIndex: Int?,
    val systemIndex: Int?,
)

internal fun chooseNearestNoteheadAnchor(
    candidates: List<NoteheadAnchorCandidate>,
    time: TimeCode,
    staffIndex: Int,
    systemIndex: Int?,
    symbol: AbsolutePoint,
): AbsolutePoint? = candidates.asSequence()
    .filter { candidate ->
        candidate.time == time &&
            candidate.staffIndex == staffIndex &&
            (systemIndex == null || candidate.systemIndex == systemIndex)
    }
    .minByOrNull { candidate ->
        val dx = candidate.center.x.value - symbol.x.value
        val dy = candidate.center.y.value - symbol.y.value
        dx * dx + dy * dy
    }
    ?.center

/**
 * Warp an attachment's existing draw commands between transient endpoint positions. Point dynamics
 * translate as one unit; line/text attachment commands interpolate the two endpoint displacements.
 */
internal fun warpAttachmentCommands(
    commands: List<RenderCommand>,
    originalStart: AbsolutePoint,
    originalEnd: AbsolutePoint?,
    currentStart: AbsolutePoint,
    currentEnd: AbsolutePoint?,
): List<RenderCommand> {
    fun warp(point: AbsolutePoint): AbsolutePoint {
        val end0 = originalEnd
        val end1 = currentEnd
        val fraction = if (end0 == null || end1 == null || end0.x.value == originalStart.x.value) 0f else
            ((point.x.value - originalStart.x.value) / (end0.x.value - originalStart.x.value)).coerceIn(0f, 1f)
        val dx0 = currentStart.x.value - originalStart.x.value
        val dy0 = currentStart.y.value - originalStart.y.value
        val dx1 = if (end0 != null && end1 != null) end1.x.value - end0.x.value else dx0
        val dy1 = if (end0 != null && end1 != null) end1.y.value - end0.y.value else dy0
        return AbsolutePoint(
            Pixels(point.x.value + dx0 + (dx1 - dx0) * fraction),
            Pixels(point.y.value + dy0 + (dy1 - dy0) * fraction),
        )
    }

    return commands.map { command ->
        when (command) {
            is DrawLine -> command.copy(start = warp(command.start), end = warp(command.end))
            is DrawGlyph -> command.copy(position = warp(command.position))
            is DrawText -> command.copy(position = warp(command.position))
            else -> command
        }
    }
}

/** A rest being moved: where it sits now (its effective position) and its type default (so a drag
 *  back to the default normalizes the stored override to null). */
internal data class RestTargetInfo(
    val voiceTrackId: TrackId,
    val eventId: EventId,
    val startPosition: Int,
    val defaultPosition: Int,
)

/** The rests grabbed by a drag-to-move-rest gesture; null on a note-transpose drag. */
internal data class RestMoveInfo(val targets: List<RestTargetInfo>)

internal data class BeamDragState(
    val groupId: String,
    val endpoint: String?,
    val start: BeamGeometry,
    val current: BeamGeometry,
    val staffCenters: Map<Int, Float> = emptyMap(),
    val deltaY: Float = 0f,
    val committing: Boolean = false,
    val commitBaseline: RenderResult? = null,
)

internal data class VoltaDragState(
    val endpoint: VoltaEndpoint,
    val originalStartMeasure: Int,
    val currentStartMeasure: Int,
    val originalEndMeasure: Int,
    val currentEndMeasure: Int,
)

internal data class BeamDragPreview(
    val commands: List<RenderCommand>,
    val start: AbsolutePoint,
    val end: AbsolutePoint,
)

/**
 * Rebuild only the selected tie/slur paths from the displayed frame's endpoints.
 * This is transient Canvas geometry: no score mutation, compute pass, or layout pass.
 */
internal fun createCurveDragPreview(
    result: RenderResult,
    drag: CurveDragState,
): CurveDragPreview {
    val direction = if (drag.above) SlurDirection.ABOVE else SlurDirection.BELOW
    val thickness = when (drag.kind) {
        CurveKind.TIE -> EngravingDefaults.BRAVURA.tieMidpointThickness
        CurveKind.SLUR -> EngravingDefaults.BRAVURA.slurMidpointThickness
    }
    val apex = StaffSpace(drag.currentApex)
    val segments = drag.elementIds.mapNotNull { elementId ->
        val element = result.elementById(elementId) ?: return@mapNotNull null
        val original = element.commands.filterIsInstance<DrawPath>().firstOrNull()
            ?: return@mapNotNull null
        val pathSegments = original.path.segments
        val startAbsolute = (pathSegments.firstOrNull() as? AbsolutePathSegment.MoveTo)?.point
            ?: return@mapNotNull null
        val cubics = pathSegments.filterIsInstance<AbsolutePathSegment.CubicTo>()
        if (cubics.size < 2) return@mapNotNull null
        // The first half traces the outer curve from start to end; the second half
        // returns along the inner curve. This also covers straightened multi-cubic slurs.
        val endAbsolute = cubics[cubics.size / 2 - 1].end
        val start = result.transformerSnapshot.toRelative(startAbsolute)
        val end = result.transformerSnapshot.toRelative(endAbsolute)
        val relativePath = SlurCurveBuilder.buildLensPath(
            start = start,
            end = end,
            direction = direction,
            midpointThickness = thickness,
            minHeight = apex,
            maxHeight = apex,
            slopeDamping = drag.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = drag.middleStraightening,
        )
        val relativeBounds = SlurCurveBuilder.lensBounds(
            start = start,
            end = end,
            direction = direction,
            midpointThickness = thickness,
            minHeight = apex,
            maxHeight = apex,
            slopeDamping = drag.slopeDamping,
            heightUsesHorizontalSpan = true,
            middleStraightening = drag.middleStraightening,
        )
        CurveDragPreviewSegment(
            commands = listOf(original.copy(
                path = result.transformerSnapshot.toAbsolute(relativePath),
                bounds = result.transformerSnapshot.toAbsolute(relativeBounds),
            )),
            anchor = startAbsolute,
        )
    }
    return CurveDragPreview(segments)
}

/** Build a transient beam + stem preview from the displayed frame; no document mutation. */
internal fun createBeamDragPreview(
    result: RenderResult,
    controls: BeamControlPoints,
    drag: BeamDragState,
): BeamDragPreview {
    val zeroY = result.transformerSnapshot.toAbsolute(
        RelativePoint(StaffSpace.ZERO, StaffSpace.ZERO)
    ).y.value
    val deltaPixels = result.transformerSnapshot.toAbsolute(
        RelativePoint(StaffSpace.ZERO, StaffSpace(drag.deltaY))
    ).y.value - zeroY
    val startShift = if (drag.endpoint == "end") 0f else deltaPixels
    val endShift = if (drag.endpoint == "start") 0f else deltaPixels
    val x0 = controls.start.x.value
    val x1 = controls.end.x.value

    fun yShiftAt(x: Float): Float {
        if (x1 == x0) return startShift
        val fraction = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        return startShift + (endShift - startShift) * fraction
    }
    fun move(point: AbsolutePoint): AbsolutePoint = point.copy(
        y = Pixels(point.y.value + yShiftAt(point.x.value))
    )
    fun movePath(path: AbsolutePath): AbsolutePath = AbsolutePath(path.segments.map { segment ->
        when (segment) {
            is AbsolutePathSegment.MoveTo -> AbsolutePathSegment.MoveTo(move(segment.point))
            is AbsolutePathSegment.LineTo -> AbsolutePathSegment.LineTo(move(segment.point))
            is AbsolutePathSegment.QuadTo -> AbsolutePathSegment.QuadTo(
                move(segment.control), move(segment.end)
            )
            is AbsolutePathSegment.CubicTo -> AbsolutePathSegment.CubicTo(
                move(segment.control1), move(segment.control2), move(segment.end)
            )
            AbsolutePathSegment.Close -> AbsolutePathSegment.Close
        }
    })

    val commands = ArrayList<RenderCommand>()
    result.sectionIndex.elementsFor(controls.section).elementIds
        .mapNotNull(result::elementById)
        .flatMapTo(commands) { element ->
            element.commands.mapNotNull { command ->
                (command as? DrawPath)?.let { it.copy(path = movePath(it.path)) }
            }
        }
    controls.section.events.mapNotNull { event ->
        result.elementsForEvent(event.id).firstOrNull { it.type == RenderElementType.STEM }
    }.distinctBy { it.id }.forEach { stem ->
        val line = stem.commands.firstOrNull() as? DrawLine ?: return@forEach
        val expectedBeamY = controls.start.y.value +
            (controls.end.y.value - controls.start.y.value) *
            (if (x1 == x0) 0f else ((line.start.x.value - x0) / (x1 - x0)).coerceIn(0f, 1f))
        val startIsBeamTip = kotlin.math.abs(line.start.y.value - expectedBeamY) <=
            kotlin.math.abs(line.end.y.value - expectedBeamY)
        commands += if (startIsBeamTip) {
            line.copy(start = move(line.start))
        } else {
            line.copy(end = move(line.end))
        }
    }

    return BeamDragPreview(
        commands = commands,
        start = move(controls.start),
        end = move(controls.end),
    )
}

internal data class BeamControlPoints(
    val section: VoiceBeamSection,
    val start: AbsolutePoint,
    val end: AbsolutePoint,
    val staffCenters: Map<Int, Float>,
)

/** Derive endpoint controls from stem tips without adding editor chrome to renderer output. */
internal fun findBeamControlPoints(
    result: RenderResult,
    section: VoiceBeamSection,
): BeamControlPoints? {
    val stems = section.events.mapNotNull { event ->
        result.elementsForEvent(event.id).firstOrNull { element ->
            element.type == RenderElementType.STEM && element.commands.firstOrNull() is DrawLine
        }
    }.distinctBy { it.id }
    if (stems.size < 2) return null

    val systemIndex = stems.firstNotNullOfOrNull { it.systemIndex }
    val system = result.spatialIndex.allSystems().firstOrNull { it.systemIndex == systemIndex }
    fun beamTip(stem: RenderElement): AbsolutePoint {
        val line = stem.commands.first() as DrawLine
        val centerY = system?.staffRegions
            ?.firstOrNull { it.staffIndex == stem.staffIndex }
            ?.let { region ->
                result.transformerSnapshot.toAbsolute(
                    RelativePoint(StaffSpace.ZERO, region.centerY)
                ).y.value
            }
        if (centerY == null) return line.start
        return if (kotlin.math.abs(line.start.y.value - centerY) >=
            kotlin.math.abs(line.end.y.value - centerY)
        ) line.start else line.end
    }

    val tips = stems.map { beamTip(it) }.sortedBy { it.x.value }
    val usedStaffIndices = stems.mapNotNull { it.staffIndex }.distinct().sorted()
    val staffCenters = if (system != null && usedStaffIndices.size >= 2) {
        val range = usedStaffIndices.first()..usedStaffIndices.last()
        system.staffRegions.filter { it.staffIndex in range }.associate { region ->
            region.staffIndex to result.transformerSnapshot.toAbsolute(
                RelativePoint(StaffSpace.ZERO, region.centerY)
            ).y.value
        }
    } else {
        emptyMap()
    }
    return BeamControlPoints(section, tips.first(), tips.last(), staffCenters)
}

internal fun hitBeamControlPoint(
    point: AbsolutePoint,
    controls: BeamControlPoints,
    radius: Float,
): String? = hitBeamControlPoint(point, controls.start, controls.end, radius)

internal fun hitBeamControlPoint(
    point: AbsolutePoint,
    start: AbsolutePoint,
    end: AbsolutePoint,
    radius: Float,
): String? {
    val separationX = end.x.value - start.x.value
    val separationY = end.y.value - start.y.value
    // Reserve a central body-drag zone even for very short beams. Endpoint hit circles
    // may consume at most 20% of the endpoint separation from either side.
    val effectiveRadius = minOf(
        radius,
        kotlin.math.sqrt(separationX * separationX + separationY * separationY) * 0.2f,
    )
    fun hits(target: AbsolutePoint): Boolean {
        val dx = point.x.value - target.x.value
        val dy = point.y.value - target.y.value
        return dx * dx + dy * dy <= effectiveRadius * effectiveRadius
    }
    return when {
        hits(start) -> "start"
        hits(end) -> "end"
        else -> null
    }
}

internal fun normalizeCrossStaffBeamGeometry(
    geometry: BeamGeometry,
    staffCenters: Map<Int, Float>,
): BeamGeometry {
    val sorted = staffCenters.keys.sorted()
    if (sorted.size < 2) return geometry
    val defaultPairStart = ((sorted.size - 1) / 2).coerceAtMost(sorted.lastIndex - 1)
    val defaultUpper = sorted[defaultPairStart]
    val defaultLower = sorted[defaultPairStart + 1]
    val storedPairIsValid = sorted.zipWithNext().any { (upper, lower) ->
        geometry.betweenStaffUpperIndex == upper && geometry.betweenStaffLowerIndex == lower
    }
    return when (geometry.crossStaffBase) {
        null -> geometry.copy(
            crossStaffBase = CrossStaffBeamBase.BETWEEN_STAFFS,
            crossStaffOffset = geometry.crossStaffOffset ?: 0f,
            betweenStaffUpperIndex = defaultUpper,
            betweenStaffLowerIndex = defaultLower,
        )
        CrossStaffBeamBase.BETWEEN_STAFFS -> geometry.copy(
            crossStaffOffset = geometry.crossStaffOffset ?: 0f,
            betweenStaffUpperIndex = if (storedPairIsValid) geometry.betweenStaffUpperIndex else defaultUpper,
            betweenStaffLowerIndex = if (storedPairIsValid) geometry.betweenStaffLowerIndex else defaultLower,
        )
        else -> geometry.copy(
            crossStaffOffset = geometry.crossStaffOffset ?: 0f,
            betweenStaffUpperIndex = null,
            betweenStaffLowerIndex = null,
        )
    }
}

/** Apply a pointer delta and, for a whole cross-staff beam drag, re-anchor to the nearest stable line. */
internal fun relocateBeamGeometry(
    original: BeamGeometry,
    endpoint: String?,
    deltaY: Float,
    staffCenters: Map<Int, Float>,
): BeamGeometry {
    val geometry = normalizeCrossStaffBeamGeometry(original, staffCenters)
    if (endpoint == "start") return geometry.copy(startDy = geometry.startDy + deltaY)
    if (endpoint == "end") return geometry.copy(endDy = geometry.endDy + deltaY)
    val sortedCenters = staffCenters.toSortedMap()
    if (geometry.crossStaffBase == null || sortedCenters.size < 2) {
        return geometry.copy(startDy = geometry.startDy + deltaY, endDy = geometry.endDy + deltaY)
    }

    data class Candidate(
        val base: CrossStaffBeamBase,
        val y: Float,
        val upperIndex: Int? = null,
        val lowerIndex: Int? = null,
    )

    val entries = sortedCenters.entries.toList()
    val defaultUpper = entries[(entries.size - 1) / 2].key
    val defaultLower = entries[((entries.size - 1) / 2) + 1].key
    val currentBaseY = when (geometry.crossStaffBase) {
        CrossStaffBeamBase.TOP_STAFF_MIDLINE -> entries.first().value
        CrossStaffBeamBase.BOTTOM_STAFF_MIDLINE -> entries.last().value
        CrossStaffBeamBase.BETWEEN_STAFFS -> {
            val upperY = sortedCenters[geometry.betweenStaffUpperIndex ?: defaultUpper]
                ?: sortedCenters.getValue(defaultUpper)
            val lowerY = sortedCenters[geometry.betweenStaffLowerIndex ?: defaultLower]
                ?: sortedCenters.getValue(defaultLower)
            (upperY + lowerY) / 2f
        }
        null -> return geometry.copy(startDy = geometry.startDy + deltaY, endDy = geometry.endDy + deltaY)
    }
    val targetY = currentBaseY + (geometry.crossStaffOffset ?: 0f) + deltaY
    val candidates = buildList {
        add(Candidate(CrossStaffBeamBase.TOP_STAFF_MIDLINE, entries.first().value))
        entries.zipWithNext().forEach { (upper, lower) ->
            add(Candidate(
                CrossStaffBeamBase.BETWEEN_STAFFS,
                (upper.value + lower.value) / 2f,
                upper.key,
                lower.key,
            ))
        }
        add(Candidate(CrossStaffBeamBase.BOTTOM_STAFF_MIDLINE, entries.last().value))
    }
    val nearest = candidates.minBy { kotlin.math.abs(targetY - it.y) }
    return geometry.copy(
        crossStaffBase = nearest.base,
        crossStaffOffset = targetY - nearest.y,
        betweenStaffUpperIndex = nearest.upperIndex,
        betweenStaffLowerIndex = nearest.lowerIndex,
    )
}

/** Exact sound implied by the grabbed/clicked glyph: notehead = one pitch, stem = full chord. */
