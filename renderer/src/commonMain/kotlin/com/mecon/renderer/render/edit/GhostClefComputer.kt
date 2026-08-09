package com.mecon.renderer.render.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.tracks.Clef
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativeLine
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.layout.RenderLayoutConfig
import com.mecon.renderer.render.DrawLine
import com.mecon.renderer.render.RenderCommand
import com.mecon.renderer.render.RenderHelpers
import com.mecon.renderer.render.RenderResult
import com.mecon.renderer.smufl.BravuraFont
import kotlin.math.abs

/** A snapped clef-change preview: insertion staff/time plus absolute render commands. */
data class GhostClef(
    val staffTrackId: TrackId,
    val onset: TimeCode,
    val clef: Clef,
    val commands: List<RenderCommand>,
    val anchor: AbsolutePoint,
)

context(BravuraFont)
class GhostClefComputer(private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT) {
    fun compute(
        result: RenderResult,
        runtime: RuntimeScore,
        point: AbsolutePoint,
        clef: Clef,
    ): GhostClef? {
        val transformer = result.transformerSnapshot
        val relPoint = transformer.toRelative(point)
        val staffHit = result.spatialIndex.staffAt(relPoint) ?: return null
        val staffTrack = runtime.orderedStaffs().getOrNull(staffHit.staffIndex) ?: return null
        val centerY = staffHit.centerY
        val absCenterY = transformer.toAbsolute(RelativePoint(relPoint.x, centerY)).y.value
        val (onset, snapAbsX) = snapOnset(result, runtime, point.x.value, absCenterY) ?: return null
        // The insertion line sits at the LEFT edge of the note the clef will govern — the slot's leftmost
        // element (see [TimeCodePosition.leftX]). At a measure downbeat that leftmost element is the
        // barline, so the line coincides with it; mid-measure it lands just left of the note. Falls back
        // to the slot's right edge (snapAbsX) for synthesized beats that have no laid-out slot.
        val lineAbsX = result.timeCodePositions[onset]?.leftX ?: snapAbsX
        val snapRelX = transformer.toRelative(AbsolutePoint(Pixels(lineAbsX), Pixels(0f))).x

        val commands = mutableListOf<RenderCommand>()
        val line = RelativeLine.vertical(
            x = snapRelX,
            startY = centerY - StaffSpace(2f),
            endY = centerY + StaffSpace(2f),
            thickness = config.engravingDefaults.thinBarlineThickness,
        )
        val absLine = transformer.toAbsolute(line)
        commands += DrawLine(
            start = absLine.start,
            end = absLine.end,
            thickness = absLine.thickness,
            color = RenderColor.BLACK,
            bounds = RenderHelpers.calculateLineBounds(absLine),
        )

        val clefElement = ClefElement.create(
            time = onset,
            staffIndex = staffHit.staffIndex,
            clef = clef,
            isInitial = false,
            scale = 0.82f,
        )
        val clefX = snapRelX - clefElement.minimumWidth - StaffSpace(0.45f)
        val drawOffset = RelativePoint(clefX, centerY)
        commands += clefElement.geometryList.flatMap { it.draw(drawOffset, transformer) }

        return GhostClef(
            staffTrackId = staffTrack.id,
            onset = onset,
            clef = clef,
            commands = commands,
            anchor = transformer.toAbsolute(RelativePoint(snapRelX, centerY)),
        )
    }

    private fun snapOnset(
        result: RenderResult,
        runtime: RuntimeScore,
        targetAbsX: Float,
        systemY: Float,
    ): Pair<TimeCode, Float>? {
        val systemPositions = result.timeCodePositions.values
            .filter { systemY in it.topY..it.bottomY }
            .ifEmpty { result.timeCodePositions.values.toList() }
        val tcToX = systemPositions.associate { it.timeCode to it.x }
        val known = systemPositions
            .map { absoluteWholeNotes(it.timeCode, runtime) to it.x }
            .sortedBy { it.first }
        val sysMeasures = systemPositions.map { it.timeCode.measure }
        val firstM = sysMeasures.minOrNull() ?: maxOf(1, result.firstMeasure)
        val lastM = sysMeasures.maxOrNull() ?: maxOf(firstM, result.lastMeasure)

        val candidates = LinkedHashSet<TimeCode>()
        for (pos in systemPositions) candidates.add(pos.timeCode)
        for (m in firstM..lastM) {
            val ts = runtime.getTimeSignatureAt(m)
            for (i in 0 until ts.numerator) {
                candidates.add(TimeCode.of(m, ts.beatUnit * i))
            }
        }

        fun xForTimeCode(tc: TimeCode): Float? {
            tcToX[tc]?.let { return it }
            if (known.isEmpty()) return null
            val t = absoluteWholeNotes(tc, runtime)
            if (t <= known.first().first) return known.first().second
            if (t >= known.last().first) return known.last().second
            for (j in 0 until known.size - 1) {
                val (t0, x0) = known[j]
                val (t1, x1) = known[j + 1]
                if (t in t0..t1) {
                    if (t1 == t0) return x0
                    val f = ((t - t0) / (t1 - t0)).toFloat()
                    return x0 + f * (x1 - x0)
                }
            }
            return known.last().second
        }

        return candidates
            .mapNotNull { tc -> xForTimeCode(tc)?.let { tc to it } }
            .minByOrNull { abs(it.second - targetAbsX) }
    }

    private fun absoluteWholeNotes(tc: TimeCode, runtime: RuntimeScore): Double {
        var acc = 0.0
        for (m in 1 until tc.measure) {
            val ts = runtime.getTimeSignatureAt(m)
            acc += ts.numerator.toDouble() / ts.denominator
        }
        val beat = tc.beat ?: Fraction.ZERO
        acc += beat.numerator.toDouble() / beat.denominator
        return acc
    }
}
