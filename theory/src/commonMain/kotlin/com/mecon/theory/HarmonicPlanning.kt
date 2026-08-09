package com.mecon.theory

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.Fraction
import com.mecon.api.primitive.TimeCode
import com.mecon.api.primitive.TimeSignature
import com.mecon.theory.constraint.SlotDomain
import kotlin.jvm.JvmInline

@JvmInline
value class HarmonySlotId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

/**
 * Exact harmonic time span. Durations are expressed as fractions of a whole note, matching
 * [TimeCode.beat] and the storage duration primitives.
 */
data class HarmonicTimeSpan(
    val onset: TimeCode,
    val duration: Fraction,
) {
    init { require(duration.isPositive) { "Harmonic duration must be positive" } }
}

data class MeterSpan(
    val startMeasure: Int,
    val timeSignature: TimeSignature,
) {
    init { require(startMeasure >= 1) }
}

data class MeterPlan(
    val spans: List<MeterSpan>,
) {
    init {
        require(spans.isNotEmpty()) { "A meter plan must contain at least one span" }
        require(spans.first().startMeasure == 1) { "A meter plan must start at measure 1" }
        require(spans.zipWithNext().all { (a, b) -> a.startMeasure < b.startMeasure }) {
            "Meter spans must be strictly ordered"
        }
    }

    fun timeSignatureAt(measure: Int): TimeSignature =
        spans.last { it.startMeasure <= measure }.timeSignature

    fun beatWeightAt(time: TimeCode): BeatWeight {
        val beat = time.beat ?: Fraction.ZERO
        return if (beat.isZero) BeatWeight.STRONG else BeatWeight.WEAK
    }

    companion object {
        val TWO_FOUR = MeterPlan(listOf(MeterSpan(1, TimeSignature.TWO_FOUR)))
        val FOUR_FOUR = MeterPlan(listOf(MeterSpan(1, TimeSignature.FOUR_FOUR)))
    }
}

data class ConstraintSlot(
    val id: HarmonySlotId,
    val time: HarmonicTimeSpan,
    val domain: SlotDomain,
    val sourceAnchor: EventId? = null,
)

data class HarmonicTimeline(
    val meterPlan: MeterPlan,
    val spans: List<HarmonicTimeSpan>,
) {
    init {
        require(spans.isNotEmpty()) { "A harmonic timeline must contain at least one span" }
        require(spans.zipWithNext().all { (a, b) -> a.onset < b.onset }) {
            "Harmonic onsets must be strictly ordered"
        }
    }

    companion object {
        fun equalQuarterSlots(count: Int): HarmonicTimeline {
            require(count > 0)
            return HarmonicTimeline(
                meterPlan = MeterPlan.FOUR_FOUR,
                spans = List(count) { slot ->
                    HarmonicTimeSpan(
                        onset = TimeCode.of(
                            measure = (slot / 4) + 1,
                            beat = (slot % 4).let { quarter ->
                                if (quarter == 0) Fraction.ZERO else Fraction(quarter, 4)
                            },
                        ),
                        duration = Fraction.QUARTER,
                    )
                },
            )
        }

        fun twoFour(durations: List<Fraction>): HarmonicTimeline {
            require(durations.isNotEmpty())
            var measure = 1
            var beat = Fraction.ZERO
            val spans = durations.map { duration ->
                require(duration.isPositive)
                val result = HarmonicTimeSpan(TimeCode.of(measure, beat), duration)
                beat += duration
                while (beat >= TimeSignature.TWO_FOUR.measureDuration()) {
                    beat -= TimeSignature.TWO_FOUR.measureDuration()
                    measure++
                }
                result
            }
            return HarmonicTimeline(MeterPlan.TWO_FOUR, spans)
        }
    }
}

internal fun defaultConstraintSlots(domains: List<SlotDomain>): List<ConstraintSlot> {
    val timeline = HarmonicTimeline.equalQuarterSlots(domains.size)
    return domains.mapIndexed { index, domain ->
        ConstraintSlot(
            id = HarmonySlotId("slot-$index"),
            time = timeline.spans[index],
            domain = domain,
        )
    }
}

internal fun defaultTonalPlan(key: Key, slotCount: Int): TonalPlan =
    TonalPlan(
        listOf(
            TonalSpan(
                window = SlotWindow(0, slotCount - 1),
                context = TonalContext.fromKey(key),
            )
        )
    )
