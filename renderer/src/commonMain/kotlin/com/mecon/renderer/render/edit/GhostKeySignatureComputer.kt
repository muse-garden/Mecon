package com.mecon.renderer.render.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.KeySignature
import com.mecon.api.primitive.TimeCode
import com.mecon.api.render.RenderColor
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.core.engine.StaffPitchContext
import com.mecon.renderer.elements.KeySignatureElement
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

/** A snapped key-signature preview: target measure/time plus absolute render commands. */
data class GhostKeySignature(
    val measure: Int,
    val onset: TimeCode,
    val keySignature: KeySignature,
    val commands: List<RenderCommand>,
    val anchor: AbsolutePoint,
)

/**
 * Ghost preview for the key-signature pen. Like the time-signature pen, it snaps to a measure
 * downbeat and previews the signature that will apply from that measure.
 */
context(BravuraFont)
class GhostKeySignatureComputer(private val config: RenderLayoutConfig = RenderLayoutConfig.DEFAULT) {
    fun compute(
        result: RenderResult,
        runtime: RuntimeScore,
        point: AbsolutePoint,
        keySignature: KeySignature,
    ): GhostKeySignature? {
        val transformer = result.transformerSnapshot
        val relPoint = transformer.toRelative(point)
        val staffHit = result.spatialIndex.staffAt(relPoint) ?: return null
        val staffTrack = runtime.orderedStaffs().getOrNull(staffHit.staffIndex) ?: return null
        val centerY = staffHit.centerY
        val absCenterY = transformer.toAbsolute(RelativePoint(relPoint.x, centerY)).y.value
        val (onset, snapAbsX) = snapMeasure(result, runtime, point.x.value, absCenterY) ?: return null
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

        val activeClef = StaffPitchContext.timeline(staffTrack).at(onset).clef
        val element = KeySignatureElement.create(
            time = onset,
            staffIndex = staffHit.staffIndex,
            keySignature = keySignature,
            isInitial = false,
            clef = activeClef,
            staffTrackId = staffTrack.id,
        )
        val drawOffset = RelativePoint(snapRelX + StaffSpace(0.6f), centerY)
        commands += element.geometryList.flatMap { it.draw(drawOffset, transformer) }

        return GhostKeySignature(
            measure = onset.measure,
            onset = onset,
            keySignature = keySignature,
            commands = commands,
            anchor = transformer.toAbsolute(RelativePoint(snapRelX, centerY)),
        )
    }

    private fun snapMeasure(
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

        val candidates = (firstM..lastM).map { TimeCode.of(it, Fraction.ZERO) }

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
