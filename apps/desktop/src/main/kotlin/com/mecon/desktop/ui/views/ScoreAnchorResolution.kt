package com.mecon.desktop.ui.views

import com.mecon.api.interaction.VoiceNoteSection
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.features.scoreediting.ScoreSnapTopology
import com.mecon.features.scoreediting.ScoreTargetingSpec
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.render.RenderElementType
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.render.TimeCodePosition
import com.mecon.renderer.render.edit.insertionBoundaries
import com.mecon.renderer.render.edit.nearestInsertionBoundary

/**
 * Pointer pixels → semantic anchors (`TrackId` + `TimeCode` + rendered anchor points).
 *
 * Shared by point/span placement (families P and S, see
 * [docs/ui/score-interaction-taxonomy.md]) and by the semantic handles of family H, so both resolve
 * a grabbed or hovered pixel to exactly the same musical target. Nothing here mutates a score:
 * callers turn the resolved anchor into an intent.
 */

/** Resolve expression insertion using staff Y bands and midpoint X separators between event slots. */
internal fun resolveExpressionAnchor(
    result: RenderResult,
    runtime: RuntimeScore,
    point: AbsolutePoint,
): Pair<TrackId, TimeCode>? {
    val resolved = resolveExpressionStaffAnchor(result, runtime, point) ?: return null
    return resolved.staffTrackId to resolved.time
}

private fun resolveExpressionStaffAnchor(
    result: RenderResult,
    runtime: RuntimeScore,
    point: AbsolutePoint,
): ResolvedStaffAnchor? {
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
    return ResolvedStaffAnchor(staffId, system.systemIndex, time)
}

private data class ResolvedStaffAnchor(
    val staffTrackId: TrackId,
    val systemIndex: Int,
    val time: TimeCode,
)

/** One shared hover/click result for the two pause tools. */
internal data class PauseInsertionTarget(
    val staffTrackId: TrackId,
    /** Storage/session anchor: the time immediately after the affected event(s). */
    val afterTime: TimeCode,
    /** Time used to place the glyph; fermata stays over the held event. */
    val ghostOnset: TimeCode,
    val absoluteX: Float,
    val systemIndex: Int,
)

/**
 * Resolve pause placement from the shared targeting descriptor. Fermata uses the active voice's
 * event column; breath uses the same note-gap/barline boundary candidates as clef insertion.
 */
internal fun resolvePauseInsertionTarget(
    result: RenderResult,
    runtime: RuntimeScore,
    point: AbsolutePoint,
    targeting: ScoreTargetingSpec,
    activeVoiceNumber: Int,
): PauseInsertionTarget? {
    val staffAnchor = resolveExpressionStaffAnchor(result, runtime, point) ?: return null
    return when (targeting.snapTopology) {
        ScoreSnapTopology.EVENT_TIME -> {
            val staff = runtime.staffTracks[staffAnchor.staffTrackId] ?: return null
            val voice = staff.voiceTracks.firstOrNull { it.voiceNumber == activeVoiceNumber }
                ?: return null
            val clicked = result.hitTest(point).allSections()
                .filterIsInstance<VoiceNoteSection>()
                .firstOrNull { result.eventVoiceTrackIds[it.event.id] == voice.id }
                ?.event
            val runtimeEvent = if (clicked == null) {
                voice.events.at(staffAnchor.time).firstOrNull { !it.isGrace } ?: return null
            } else null
            val eventOnset = clicked?.onset ?: runtimeEvent?.onset ?: return null
            val afterTime = clicked?.endTime
                ?: runtimeEvent?.let { it.onset + it.duration.toFraction() }
                ?: return null
            val visual = result.insertionPositionAt(eventOnset) ?: return null
            PauseInsertionTarget(
                staffTrackId = staff.id,
                afterTime = afterTime,
                ghostOnset = eventOnset,
                absoluteX = visual.x,
                systemIndex = staffAnchor.systemIndex,
            )
        }
        ScoreSnapTopology.INSERTION_BOUNDARY -> {
            val boundary = result.nearestInsertionBoundary(point.x.value, staffAnchor.systemIndex)
                ?: return null
            PauseInsertionTarget(
                staffTrackId = staffAnchor.staffTrackId,
                afterTime = boundary.time,
                ghostOnset = boundary.time,
                absoluteX = boundary.absoluteX,
                systemIndex = staffAnchor.systemIndex,
            )
        }
        else -> null
    }
}

/** Resolve X against one fixed system, independent of pointer Y during an attachment drag. */
internal fun resolveExpressionTime(
    result: RenderResult,
    absoluteX: Float,
    systemIndex: Int?,
): TimeCode? {
    val positions = result.timeCodePositions.values
        // Expression anchoring predates per-system slot indexing: when the requested system is not
        // in the index it deliberately falls back to every slot rather than resolving nothing.
        .filter { it.isOnSystem(result, systemIndex, missingSystemMatches = true) }
        .sortedBy { it.x }
    if (positions.isEmpty()) return null
    val index = (0 until positions.lastIndex).firstOrNull { i ->
        absoluteX < (positions[i].x + positions[i + 1].x) / 2f
    } ?: positions.lastIndex
    return positions[index].timeCode
}

/**
 * True when this slot's vertical middle falls inside [systemIndex]; always true when unconstrained.
 * [missingSystemMatches] decides the answer when [systemIndex] is not present in the spatial index.
 */
private fun TimeCodePosition.isOnSystem(
    result: RenderResult,
    systemIndex: Int?,
    missingSystemMatches: Boolean = false,
): Boolean {
    if (systemIndex == null) return true
    val system = result.spatialIndex.allSystems()
        .firstOrNull { it.systemIndex == systemIndex } ?: return missingSystemMatches
    val middleY = (topY + bottomY) / 2f
    val relativeY = result.transformerSnapshot.toRelative(
        AbsolutePoint(Pixels(x), Pixels(middleY))
    ).y
    return relativeY >= system.topY && relativeY <= system.bottomY
}

internal fun resolveBreathBoundaryTime(
    result: RenderResult,
    absoluteX: Float,
    systemIndex: Int?,
): TimeCode? = result.nearestInsertionBoundary(absoluteX, systemIndex)?.time

internal fun breathBoundaryAnchor(
    result: RenderResult,
    time: TimeCode,
    staffIndex: Int,
    systemIndex: Int?,
    symbol: AbsolutePoint,
): AbsolutePoint? {
    val candidate = result.insertionBoundaries(systemIndex)
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
 * Nearest note/barline boundary anchors used by annotation ranges. Unlike [resolveExpressionTime]
 * these snap to the *left* edge of a note group, because a tonal region starts where its first
 * note is drawn rather than at the slot's separator.
 */
internal fun resolveAnnotationBoundarySnap(
    result: RenderResult,
    absoluteX: Float,
    systemIndex: Int?,
): AnnotationBoundarySnap? {
    val slotCandidates = result.timeCodePositions.values.asSequence()
        .filter { it.isOnSystem(result, systemIndex) }
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
                    ?.takeIf { it.isOnSystem(result, systemIndex) }
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

internal data class AnnotationBoundarySnap(
    val time: TimeCode,
    val absoluteX: Float,
)
