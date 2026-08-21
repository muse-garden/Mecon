package com.mecon.renderer.render.edit

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
        val boundary = result.nearestInsertionBoundary(point.x.value, staffHit.systemIndex) ?: return null
        val onset = boundary.time
        val snapRelX = transformer.toRelative(
            AbsolutePoint(Pixels(boundary.absoluteX), Pixels(0f)),
        ).x

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

}
