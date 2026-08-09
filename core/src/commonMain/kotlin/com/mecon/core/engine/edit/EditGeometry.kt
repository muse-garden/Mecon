package com.mecon.core.engine.edit

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeRange
import com.mecon.api.runtime.RuntimeScore

/**
 * Coordinate helpers shared by every note-edit feature: positions are compared in **absolute
 * whole-note units** ([absolute]) — the cumulative length of all preceding measures plus the
 * in-measure beat — so overlap math is trivial across varying time signatures.
 */
internal object EditGeometry {

    /** Whole-note length of [measure] from its effective time signature. */
    fun measureLength(runtime: RuntimeScore, measure: Int): Fraction =
        runtime.getTimeSignatureAt(measure).measureDuration()

    fun isMeasureLocalSpan(start: TimeCode, end: TimeCode): Boolean {
        val endBeat = end.beat ?: Fraction.ZERO
        return end.measure == start.measure || (end.measure == start.measure + 1 && endBeat.isZero)
    }

    /** Absolute position of [time] in whole-note units from the start of the score. */
    fun absolute(runtime: RuntimeScore, time: TimeCode): Fraction {
        var acc = Fraction.ZERO
        for (m in 1 until time.measure) acc = acc + measureLength(runtime, m)
        return acc + (time.beat ?: Fraction.ZERO)
    }

    /** The time code reached by advancing [length] whole-notes from [start], normalising a position
     *  that lands exactly on a barline to the next measure's beat 0. */
    fun advance(runtime: RuntimeScore, start: TimeCode, length: Fraction): TimeCode {
        var measure = start.measure
        var beat = start.beat ?: Fraction.ZERO
        var remaining = length
        while (remaining.isPositive) {
            val measureLen = measureLength(runtime, measure)
            val available = measureLen - beat
            if (remaining < available) {
                return TimeCode.of(measure, beat + remaining)
            }
            // Reaches or passes the barline.
            remaining = remaining - available
            measure += 1
            beat = Fraction.ZERO
        }
        return TimeCode.of(measure, beat)
    }

    /** Inverse of [absolute]: the `(measure, beat)` time code at absolute whole-note position [pos]. */
    fun timeCodeAt(runtime: RuntimeScore, pos: Fraction): TimeCode {
        var acc = Fraction.ZERO
        var measure = 1
        while (true) {
            val len = measureLength(runtime, measure)
            if (pos < acc + len) return TimeCode.of(measure, pos - acc)
            acc = acc + len
            measure += 1
        }
    }

    /** A whole-measure [TimeRange] for measure [measure] (used as the touched span for in-place edits). */
    fun wholeMeasure(measure: Int): TimeRange =
        TimeRange(TimeCode.of(measure, Fraction.ZERO), TimeCode.of(measure + 1, Fraction.ZERO))
}
