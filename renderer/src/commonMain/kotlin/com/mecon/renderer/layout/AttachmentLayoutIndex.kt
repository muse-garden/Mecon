package com.mecon.renderer.layout

import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedStaffAttachment
import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.computed.ComputedVoltaAttachment
import com.mecon.api.computed.ComputedOrnamentMark

internal object AttachmentLayoutIndex {
    fun anchorMeasure(attachment: ComputedStaffAttachment): Int =
        if (attachment is ComputedVoltaAttachment) {
            attachment.ending.startMeasure
        } else {
            attachment.time.measure
        }

    fun crossSystemSpans(
        attachments: List<ComputedStaffAttachment>,
        slots: UnifiedTimeSlotMap,
    ): List<Pair<Int, Int>> = attachments.mapNotNull { attachment ->
        val endTime = when (attachment) {
            is ComputedHairpin -> attachment.endTime
            is ComputedOctaveShift -> attachment.endTime
            is ComputedTempoKeyframe -> attachment.nextTime.takeIf { attachment.isGradual }
            is ComputedVoltaAttachment -> attachment.endTime
            is ComputedOrnamentMark -> attachment.endTime
            else -> null
        } ?: return@mapNotNull null
        val startSystem = slots.atTime(attachment.time)?.systemIndex ?: return@mapNotNull null
        val endSystem = slots.atTime(endTime)?.systemIndex ?: return@mapNotNull null
        if (startSystem == endSystem) null else startSystem to endSystem
    }

    fun hostingSystems(
        attachments: List<ComputedStaffAttachment>,
        slots: UnifiedTimeSlotMap,
    ): Set<Int> = buildSet {
        for (attachment in attachments) {
            slots.atTime(attachment.time)?.let { add(it.systemIndex) }
        }
    }
}
