package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedScore
import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.elements.BarlineElement
import com.mecon.renderer.elements.ClefElement
import com.mecon.renderer.elements.KeySignatureElement
import com.mecon.renderer.enums.toClefType
import com.mecon.renderer.geometry.StaffSpace
import com.mecon.renderer.smufl.BravuraFont

internal data class SystemHeaderPart(
    val staffIndex: Int,
    val clef: ClefElement?,
    val key: KeySignatureElement?,
    val width: StaffSpace,
)

internal data class SystemLineHeader(
    val width: StaffSpace,
    val parts: List<SystemHeaderPart>,
) {
    fun toLineStartHeaders(systemIndex: Int, baseX: StaffSpace): List<LineStartHeader> =
        parts.map { LineStartHeader(systemIndex, it.staffIndex, it.clef, it.key, baseX) }
}

context(BravuraFont)
internal class SystemHeaderComputer(private val config: RenderLayoutConfig) {
    fun compute(
        measure: Int,
        staffTracks: List<StaffInfo>,
        computed: ComputedScore,
    ): SystemLineHeader {
        val measureTime = TimeCode.ofMeasure(measure)
        val leadIn = BarlineElement.create(TimeCode.ZERO, BarlineType.SINGLE, 0).minimumWidth +
            config.spaceAfterBarline
        val parts = staffTracks.map { staff ->
            val activeClefEvent = computed.clefs
                .filter { it.staffTrackId == staff.trackId && it.time <= measureTime }
                .maxByOrNull { it.time }
            val activeClef = activeClefEvent?.clef ?: staff.clef
            val activeKeyEvent = computed.keySignatures
                .filter { it.staffTrackId == staff.trackId && it.time <= measureTime }
                .maxByOrNull { it.time }
            val activeKey = activeKeyEvent?.keySignature ?: staff.keySignature

            val clef = ClefElement.create(
                time = measureTime,
                staffIndex = staff.staffIndex,
                clef = activeClef,
                isInitial = true,
                staffTrackId = staff.trackId,
                sectionTime = measureTime,
            ).copy(relativeX = leadIn)
            val clefWidth = clef.minimumWidth + config.spaceAfterClef
            val key = if (activeKey.fifths != 0) {
                KeySignatureElement.create(
                    time = measureTime,
                    staffIndex = staff.staffIndex,
                    keySignature = activeKey,
                    isInitial = true,
                    clefType = activeClef.toClefType(),
                    staffTrackId = staff.trackId,
                    sectionTime = measureTime,
                ).copy(relativeX = leadIn + clefWidth)
            } else {
                null
            }
            val keyWidth = if (key != null) {
                key.minimumWidth + config.spaceAfterKeySignature
            } else {
                StaffSpace.ZERO
            }
            SystemHeaderPart(
                staffIndex = staff.staffIndex,
                clef = clef,
                key = key,
                width = leadIn + clefWidth + keyWidth,
            )
        }
        return SystemLineHeader(
            width = parts.maxOfOrNull { it.width } ?: StaffSpace.ZERO,
            parts = parts,
        )
    }
}
