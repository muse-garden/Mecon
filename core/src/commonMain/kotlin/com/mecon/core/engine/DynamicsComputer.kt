package com.mecon.core.engine

import com.mecon.api.computed.ComputedDynamicMark
import com.mecon.api.computed.ComputedHairpin
import com.mecon.api.computed.ComputedOctaveShift
import com.mecon.api.computed.ComputedStaffAttachment
import com.mecon.api.computed.ComputedBreathMark
import com.mecon.api.computed.globalBreathComputedId
import com.mecon.api.computed.ComputedOrnamentMark
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.storage.events.StorageDynamicMark
import com.mecon.api.storage.events.StorageHairpin
import com.mecon.api.storage.events.StorageOctaveShiftEnd
import com.mecon.api.storage.events.StorageOctaveShiftStart
import com.mecon.api.storage.events.StorageBreathMark
import com.mecon.api.storage.events.StorageOrnamentMark
import com.mecon.api.storage.tracks.StorageGlobalBreathMark

/**
 * Resolves the raw [com.mecon.api.storage.events.StorageStaffAttachment]s stored
 * on each staff into [ComputedStaffAttachment]s, attaching the display-order
 * staff index the renderer needs.
 *
 * Per AGENTS.md the Computed layer decides *which* elements exist and *which
 * staff* they belong to; the renderer only lays them out. Placement is taken as
 * stored (defaulting to BELOW) — precise / user-adjusted offsets are future work.
 */
object DynamicsComputer {
    fun compute(score: RuntimeScore): List<ComputedStaffAttachment> {
        val result = mutableListOf<ComputedStaffAttachment>()
        score.orderedStaffs().forEachIndexed { staffIndex, staff ->
            // Index end-markers by id for quick look-up when processing start events.
            val endById = staff.attachments
                .filterIsInstance<StorageOctaveShiftEnd>()
                .associateBy { it.id }

            for (attachment in staff.attachments) {
                when (attachment) {
                    is StorageBreathMark -> result.add(
                        ComputedBreathMark(
                            id = attachment.id,
                            time = attachment.onset,
                            staffTrackId = staff.id,
                            staffIndex = staffIndex,
                            placement = attachment.placement,
                            voiceNumber = attachment.voiceNumber,
                            pause = attachment.pause,
                            shape = attachment.shape,
                            source = attachment,
                        )
                    )
                    is StorageDynamicMark -> result.add(
                        ComputedDynamicMark(
                            id = attachment.id,
                            time = attachment.onset,
                            staffTrackId = staff.id,
                            staffIndex = staffIndex,
                            placement = attachment.placement,
                            voiceNumber = attachment.voiceNumber,
                            level = attachment.level,
                            source = attachment,
                        )
                    )
                    is StorageOrnamentMark -> {
                        val sourceEvent = staff.voiceTracks.asSequence()
                            .flatMap { it.events.toList().asSequence() }
                            .firstOrNull { it.id == attachment.sourceEventId }
                            ?: continue
                        result.add(
                            ComputedOrnamentMark(
                                id = attachment.id,
                                time = if (attachment.anchor ==
                                    com.mecon.api.storage.events.OrnamentAnchor.BETWEEN_NOTES
                                ) sourceEvent.endTime else sourceEvent.onset,
                                endTime = attachment.endOnset,
                                staffTrackId = staff.id,
                                staffIndex = staffIndex,
                                placement = attachment.placement,
                                voiceNumber = attachment.voiceNumber,
                                sourceEventId = attachment.sourceEventId,
                                kind = attachment.kind,
                                anchor = attachment.anchor,
                                upperAccidental = attachment.upperAccidental,
                                lowerAccidental = attachment.lowerAccidental,
                                elementDuration = attachment.elementDuration,
                                oscillations = attachment.oscillations,
                                trillPlaybackMode = attachment.trillPlaybackMode,
                                source = attachment,
                            )
                        )
                    }
                    is StorageHairpin -> result.add(
                        ComputedHairpin(
                            id = attachment.id,
                            time = attachment.onset,
                            endTime = attachment.endOnset,
                            staffTrackId = staff.id,
                            staffIndex = staffIndex,
                            placement = attachment.placement,
                            voiceNumber = attachment.voiceNumber,
                            type = attachment.direction,
                            style = attachment.style,
                            source = attachment,
                        )
                    )
                    is StorageOctaveShiftStart -> {
                        val endEvent = endById[attachment.endEventId] ?: continue
                        result.add(
                            ComputedOctaveShift(
                                id = attachment.id,
                                time = attachment.onset,
                                endTime = endEvent.onset,
                                staffTrackId = staff.id,
                                staffIndex = staffIndex,
                                placement = attachment.placement,
                                voiceNumber = attachment.voiceNumber,
                                shiftType = attachment.shiftType,
                                source = attachment,
                            )
                        )
                    }
                    is StorageOctaveShiftEnd -> { /* consumed by the start event above */ }
                }
            }

            score.globalTrack.events.filterIsInstance<StorageGlobalBreathMark>().forEach { breath ->
                result.add(
                    ComputedBreathMark(
                        id = globalBreathComputedId(breath.id, staff.id),
                        time = breath.onset,
                        staffTrackId = staff.id,
                        staffIndex = staffIndex,
                        voiceNumber = null,
                        pause = breath.pause,
                        shape = breath.shape,
                        isGlobal = true,
                        globalEventId = breath.id,
                    )
                )
            }
        }
        return result
    }
}
