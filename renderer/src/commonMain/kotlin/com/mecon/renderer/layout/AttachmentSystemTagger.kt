package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.renderer.geometry.DrawableGeometry
import com.mecon.renderer.geometry.HairpinGeometry
import com.mecon.renderer.geometry.IntervalAttachmentGeometry
import com.mecon.renderer.geometry.RelativePoint
import com.mecon.renderer.geometry.RelativeRect
import com.mecon.renderer.geometry.StaffSpace

internal data class AttachmentLineBounds(
    val startX: StaffSpace,
    val endX: StaffSpace,
)

internal class AttachmentSystemTagger(
    private val measureToLine: Map<Int, Int>,
    private val maxMeasure: Int,
    private val lineBounds: List<AttachmentLineBounds>,
    private val lineFirstNoteX: Map<Int, StaffSpace>,
    private val continuationLead: StaffSpace,
) {
    fun tag(placed: List<PlacedStaffAttachment>): List<PlacedStaffAttachment> {
        if (lineBounds.isEmpty()) return placed
        return buildList {
            for (attachment in placed) {
                val startMeasure = attachment.attachment.time.measure
                val endMeasure = spanEndMeasure(attachment) ?: startMeasure
                val startLine = lineOf(startMeasure)
                val endLine = lineOf(endMeasure)
                if (startLine == endLine) {
                    add(attachment.copy(systemIndex = startLine))
                } else {
                    for (line in startLine..endLine) {
                        clip(attachment, line, startLine, endLine)?.let(::add)
                    }
                }
            }
        }
    }

    private fun lineOf(measure: Int): Int =
        measureToLine[measure]
            ?: if (measure > maxMeasure) lineBounds.lastIndex.coerceAtLeast(0) else 0

    private fun spanEndMeasure(placed: PlacedStaffAttachment): Int? =
        when (val attachment = placed.attachment) {
            is ComputedHairpin -> attachment.endTime.measure
            is ComputedOctaveShift -> attachment.endTime.measure
            is ComputedTempoKeyframe -> if (attachment.isGradual) attachment.nextTime?.measure else null
            is ComputedVoltaAttachment -> attachment.ending.endMeasure
            is ComputedOrnamentMark -> attachment.endTime?.measure
            else -> null
        }

    private fun segmentRange(
        geometryStartX: Float,
        geometryEndX: Float,
        line: Int,
        startLine: Int,
        endLine: Int,
    ): Pair<Float, Float> {
        val bounds = lineBounds[line]
        val continuationStart =
            lineFirstNoteX[line]?.value?.minus(continuationLead.value) ?: bounds.startX.value
        val start = if (line == startLine) geometryStartX else continuationStart
        val end = if (line == endLine) geometryEndX else bounds.endX.value
        return start to end
    }

    private fun clip(
        placed: PlacedStaffAttachment,
        line: Int,
        startLine: Int,
        endLine: Int,
    ): PlacedStaffAttachment? {
        if (lineBounds.getOrNull(line) == null) return null
        val first = line == startLine
        val last = line == endLine
        val geometries = placed.geometries.mapNotNull { geometry ->
            when (geometry) {
                is HairpinGeometry -> {
                    val (start, end) = segmentRange(
                        geometry.startX.value,
                        geometry.endX.value,
                        line,
                        startLine,
                        endLine,
                    )
                    if (end - start < 0.5f) return@mapNotNull null
                    var totalLength = 0f
                    var lengthBefore = 0f
                    for (candidateLine in startLine..endLine) {
                        val (candidateStart, candidateEnd) = segmentRange(
                            geometry.startX.value,
                            geometry.endX.value,
                            candidateLine,
                            startLine,
                            endLine,
                        )
                        val length = (candidateEnd - candidateStart).coerceAtLeast(0f)
                        if (candidateLine < line) lengthBefore += length
                        totalLength += length
                    }
                    val startFraction = if (totalLength <= 0f) 0f else lengthBefore / totalLength
                    val endFraction =
                        if (totalLength <= 0f) 1f else (lengthBefore + end - start) / totalLength
                    val startSpread = geometry.startSpread.value +
                        (geometry.endSpread.value - geometry.startSpread.value) * startFraction
                    val endSpread = geometry.startSpread.value +
                        (geometry.endSpread.value - geometry.startSpread.value) * endFraction
                    val startY = geometry.yCenter.value +
                        (geometry.endYCenter.value - geometry.yCenter.value) * startFraction
                    val endY = geometry.yCenter.value +
                        (geometry.endYCenter.value - geometry.yCenter.value) * endFraction
                    val startX = StaffSpace(start)
                    val endX = StaffSpace(end)
                    geometry.copy(
                        startX = startX,
                        endX = endX,
                        yCenter = StaffSpace(startY),
                        endYCenter = StaffSpace(endY),
                        startSpread = StaffSpace(startSpread),
                        endSpread = StaffSpace(endSpread),
                        bounds = geometry.bounds.withXSpan(startX, endX),
                    )
                }

                is IntervalAttachmentGeometry -> {
                    val (start, end) = segmentRange(
                        geometry.startX.value,
                        geometry.endX.value,
                        line,
                        startLine,
                        endLine,
                    )
                    if (end - start < 0.5f) return@mapNotNull null
                    val startX = StaffSpace(start)
                    val endX = StaffSpace(end)
                    geometry.copy(
                        startX = startX,
                        endX = endX,
                        showStartContent = first,
                        showEndContent = last,
                        bounds = geometry.bounds.withXSpan(startX, endX),
                    )
                }

                else -> geometry
            }
        }
        if (geometries.isEmpty()) return null
        return placed.copy(
            geometries = geometries,
            relativeBounds = geometries.mergedBounds() ?: placed.relativeBounds,
            systemIndex = line,
        )
    }
}

private fun List<DrawableGeometry>.mergedBounds(): RelativeRect? {
    if (isEmpty()) return null
    val minX = minOf { it.bounds.left.value }
    val minY = minOf { it.bounds.top.value }
    val maxX = maxOf { it.bounds.right.value }
    val maxY = maxOf { it.bounds.bottom.value }
    return RelativeRect(
        origin = RelativePoint(StaffSpace(minX), StaffSpace(minY)),
        width = StaffSpace(maxX - minX),
        height = StaffSpace(maxY - minY),
    )
}

private fun RelativeRect.withXSpan(startX: StaffSpace, endX: StaffSpace): RelativeRect =
    RelativeRect(
        origin = RelativePoint(startX, origin.y),
        width = endX - startX,
        height = height,
    )
