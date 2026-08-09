package com.mecon.input

import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.runtime.RuntimeScore
import com.mecon.api.runtime.resolvedTempoKeyframes
import com.mecon.api.storage.events.TempoTransition
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Immutable, monotonic-clock snapshot used by a realtime take.
 *
 * Musical positions are measured in whole-note fractions. Tempo keyframes are integrated
 * piecewise, so a take crossing a tempo change never assumes one fixed BPM.
 */
class PerformanceClock(
    private val score: RuntimeScore,
    val anchorTime: TimeCode,
    val anchorNanos: Long,
    val inputLatencyNanos: Long = 0L,
) {
    private data class TempoPoint(
        val position: Fraction,
        val bpm: Double,
        val transition: TempoTransition,
    )

    val anchorPosition: Fraction = absolute(score, anchorTime)
    private val tempoPoints = score.resolvedTempoKeyframes()
        .map {
            TempoPoint(
                absolute(score, it.source.onset),
                it.effectiveBpm.toDouble(),
                it.source.transitionToNext,
            )
        }
        .sortedBy { it.position }

    /** Convert an input event timestamp to a score position, applying input-latency compensation. */
    fun positionAt(eventNanos: Long): Fraction {
        val elapsed = eventNanos - inputLatencyNanos - anchorNanos
        return moveByNanos(anchorPosition, elapsed)
    }

    fun timeCodeAt(eventNanos: Long): TimeCode = timeCodeAt(score, positionAt(eventNanos))

    /** Convert a score position back to the monotonic timestamp used by this take. */
    fun nanosAt(time: TimeCode): Long =
        anchorNanos + nanosBetween(anchorPosition, absolute(score, time))

    private fun moveByNanos(from: Fraction, deltaNanos: Long): Fraction {
        if (deltaNanos == 0L) return from
        if (deltaNanos < 0L) {
            // Recording normally moves forward. A small negative latency-adjusted timestamp is
            // nevertheless supported using the tempo effective at the anchor.
            return from + nanosToWhole(deltaNanos, bpmAt(from))
        }
        var position = from
        var remaining = deltaNanos
        while (remaining > 0L) {
            val next = tempoPoints.firstOrNull { it.position > position }
            val bpm = bpmAt(position)
            if (next == null) return position + nanosToWhole(remaining, bpm)
            val untilNext = nanosAcross(position, next.position)
            if (remaining < untilNext) {
                var low = position.toDouble()
                var high = next.position.toDouble()
                repeat(48) {
                    val mid = (low + high) / 2.0
                    val nanos = nanosAcross(position, fractionFromDouble(mid))
                    if (nanos < remaining) low = mid else high = mid
                }
                return fractionFromDouble((low + high) / 2.0)
            }
            position = next.position
            remaining -= untilNext
        }
        return position
    }

    private fun nanosBetween(from: Fraction, to: Fraction): Long {
        if (to == from) return 0L
        if (to < from) return -nanosBetween(to, from)
        var position = from
        var total = 0L
        while (position < to) {
            val next = tempoPoints.firstOrNull { it.position > position && it.position < to }?.position ?: to
            total += nanosAcross(position, next)
            position = next
        }
        return total
    }

    private fun bpmAt(position: Fraction): Double {
        val index = tempoPoints.indexOfLast { it.position <= position }
        if (index < 0) return tempoPoints.firstOrNull()?.bpm ?: score.defaultTempo.toDouble()
        val current = tempoPoints[index]
        val next = tempoPoints.getOrNull(index + 1) ?: return current.bpm
        if (current.transition == TempoTransition.STEP || next.position <= current.position) return current.bpm
        val raw = ((position - current.position) / (next.position - current.position))
            .toDouble().coerceIn(0.0, 1.0)
        val shaped = when (current.transition) {
            TempoTransition.STEP -> 0.0
            TempoTransition.LINEAR -> raw
            TempoTransition.EASE_IN -> raw * raw
            TempoTransition.EASE_OUT -> 1.0 - (1.0 - raw) * (1.0 - raw)
            TempoTransition.EASE_IN_OUT -> raw * raw * (3.0 - 2.0 * raw)
        }
        return current.bpm + (next.bpm - current.bpm) * shaped
    }

    /** Simpson integration of 240/BPM over musical whole-note position. */
    private fun nanosAcross(start: Fraction, end: Fraction): Long {
        if (end <= start) return 0L
        val pointIndex = tempoPoints.indexOfLast { it.position <= start }
        val point = tempoPoints.getOrNull(pointIndex)
        val nextPoint = tempoPoints.getOrNull(pointIndex + 1)
        if (point == null || nextPoint == null || point.transition == TempoTransition.STEP) {
            val bpm = point?.bpm ?: bpmAt(start)
            return ((end - start).toDouble() * NANOS_PER_MINUTE * 4.0 / bpm).toLong()
        }
        val length = end.toDouble() - start.toDouble()
        var slices = ceil(length * 256.0).toInt().coerceAtLeast(8)
        if (slices % 2 != 0) slices += 1
        val h = length / slices
        var weighted = secondsPerWhole(bpmAt(start)) + secondsPerWhole(bpmAt(end))
        for (i in 1 until slices) {
            val position = fractionFromDouble(start.toDouble() + h * i)
            weighted += secondsPerWhole(bpmAt(position)) * if (i % 2 == 0) 2.0 else 4.0
        }
        return (weighted * h / 3.0 * 1_000_000_000.0).toLong()
    }

    private fun secondsPerWhole(bpm: Double): Double = 240.0 / bpm

    private fun nanosToWhole(nanos: Long, bpm: Double): Fraction {
        val whole = nanos.toDouble() * bpm / (NANOS_PER_MINUTE * 4.0)
        return fractionFromDouble(whole)
    }

    companion object {
        private const val NANOS_PER_MINUTE = 60_000_000_000.0
        private const val POSITION_DENOMINATOR = 49_152

        fun absolute(score: RuntimeScore, time: TimeCode): Fraction {
            var result = Fraction.ZERO
            for (measure in 1 until time.measure) {
                result += score.getTimeSignatureAt(measure).measureDuration()
            }
            return result + (time.beat ?: Fraction.ZERO)
        }

        fun timeCodeAt(score: RuntimeScore, absolute: Fraction): TimeCode {
            if (absolute <= Fraction.ZERO) return TimeCode.of(1, Fraction.ZERO)
            var remaining = absolute
            val lastMeasure = score.measures.maxOfOrNull { it.value.number } ?: 1
            for (measure in 1..lastMeasure) {
                val length = score.getTimeSignatureAt(measure).measureDuration()
                if (remaining < length || measure == lastMeasure) {
                    return TimeCode.of(measure, remaining.coerceAtMost(length))
                }
                remaining -= length
            }
            return TimeCode.of(lastMeasure, score.getTimeSignatureAt(lastMeasure).measureDuration())
        }

        private fun fractionFromDouble(value: Double): Fraction =
            Fraction((value * POSITION_DENOMINATOR).roundToInt(), POSITION_DENOMINATOR).simplified()
    }
}
