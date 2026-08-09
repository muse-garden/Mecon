package com.mecon.core.engine

import com.mecon.api.computed.ComputedTempoKeyframe
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.orderedStaffs
import com.mecon.api.runtime.resolvedTempoKeyframes
import com.mecon.api.storage.events.TempoDisplayStyle

/** Resolves tempo references and decides which tempo elements exist for rendering. */
object TempoComputer {
    fun compute(runtime: RuntimeScore): List<ComputedTempoKeyframe> {
        val topStaff = runtime.orderedStaffs().firstOrNull() ?: return emptyList()
        val resolved = runtime.resolvedTempoKeyframes()
        return resolved.mapIndexed { index, keyframe ->
            val source = keyframe.source
            val style = when (source.displayStyle) {
                TempoDisplayStyle.AUTO -> if (source.text.isNullOrBlank()) {
                    TempoDisplayStyle.METRONOME
                } else TempoDisplayStyle.TEXT_AND_METRONOME
                else -> source.displayStyle
            }
            ComputedTempoKeyframe(
                id = source.id,
                time = source.onset,
                staffTrackId = topStaff.id,
                staffIndex = 0,
                effectiveBpm = keyframe.effectiveBpm,
                displayStyle = style,
                transitionToNext = source.transitionToNext,
                nextTime = resolved.getOrNull(index + 1)?.source?.onset,
                source = source,
            )
        }
    }
}

