package com.mecon.input

import com.mecon.api.primitive.Duration
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TrackId
import com.mecon.api.runtime.RuntimeScore

data class NoteEntryCaret(
    val staffTrackId: TrackId,
    val voiceTrackId: TrackId,
    val voiceNumber: Int,
    val onset: TimeCode,
)

object NoteEntryNavigation {
    fun advance(runtime: RuntimeScore, start: TimeCode, duration: Duration): TimeCode =
        advance(runtime, start, duration.toFraction())

    fun advance(runtime: RuntimeScore, start: TimeCode, length: Fraction): TimeCode {
        var measure = start.measure
        var beat = start.beat ?: Fraction.ZERO
        var remaining = length
        val lastMeasure = runtime.measures.maxOfOrNull { it.value.number } ?: measure
        while (remaining.isPositive && measure <= lastMeasure) {
            val measureLength = runtime.getTimeSignatureAt(measure).measureDuration()
            val available = measureLength - beat
            if (remaining < available) return TimeCode.of(measure, beat + remaining)
            remaining -= available
            measure += 1
            beat = Fraction.ZERO
        }
        return if (measure <= lastMeasure) {
            TimeCode.of(measure, beat)
        } else {
            val lastLength = runtime.getTimeSignatureAt(lastMeasure).measureDuration()
            TimeCode.of(lastMeasure, lastLength)
        }
    }

    fun retreat(runtime: RuntimeScore, start: TimeCode, duration: Duration): TimeCode {
        var measure = start.measure
        var beat = start.beat ?: Fraction.ZERO
        var remaining = duration.toFraction()
        while (remaining.isPositive) {
            if (remaining <= beat) return TimeCode.of(measure, beat - remaining)
            remaining -= beat
            if (measure <= 1) return TimeCode.of(1, Fraction.ZERO)
            measure -= 1
            beat = runtime.getTimeSignatureAt(measure).measureDuration()
        }
        return TimeCode.of(measure, beat)
    }
}
