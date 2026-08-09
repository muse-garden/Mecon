package com.mecon.core.engine.edit

import com.mecon.api.interaction.LayoutBreakKind
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.storage.tracks.StoragePageBreak
import com.mecon.api.storage.tracks.StorageSystemBreak

/** Immutable edits for the mutually-exclusive forced system/page boundary at a measure opening. */
object LayoutBreakEditEngine {
    fun set(score: RuntimeScore, beforeMeasure: Int, kind: LayoutBreakKind?): RuntimeScore? {
        val maxMeasure = score.measures.maxOfOrNull { it.value.number } ?: return null
        if (beforeMeasure !in 2..maxMeasure) return null
        val retained = score.globalTrack.events.filterNot { event ->
            event.onset.measure == beforeMeasure &&
                (event is StorageSystemBreak || event is StoragePageBreak)
        }
        val onset = TimeCode.of(beforeMeasure, Fraction.ZERO)
        val replacement = when (kind) {
            LayoutBreakKind.SYSTEM -> StorageSystemBreak(onset)
            LayoutBreakKind.PAGE -> StoragePageBreak(onset)
            null -> null
        }
        val events = (retained + listOfNotNull(replacement)).sortedBy { it.onset }
        val systemBreaks = events.filterIsInstance<StorageSystemBreak>().mapTo(LinkedHashSet()) { it.onset.measure }
        val pageBreaks = events.filterIsInstance<StoragePageBreak>().mapTo(LinkedHashSet()) { it.onset.measure }
        return score.copy(
            globalTrack = score.globalTrack.copy(events = events),
            forcedSystemBreaks = systemBreaks + pageBreaks,
            forcedPageBreaks = pageBreaks,
        )
    }
}
