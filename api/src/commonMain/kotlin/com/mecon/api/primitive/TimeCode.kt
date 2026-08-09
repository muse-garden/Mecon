package com.mecon.api.primitive

import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

/**
 * TimeCode represents a time position in a score.
 *
 * Designed as a list of fractions supporting multi-level time representation:
 * - [measure] - Measure number (1-based)
 * - [measure, beat] - Position within measure
 * - [measure, beat, grace] - Grace note position
 *
 * Comparison: Compare components left-to-right. Shorter lists are padded with zeros.
 *
 * Serialization format:
 * ```yaml
 * # Two-component format (measure + beat)
 * onset: { measure: 1, beat: { numerator: 0, denominator: 1 } }
 *
 * # Three-component format (measure + beat + grace)
 * onset: { measure: 1, beat: { numerator: 0, denominator: 1 }, grace: { numerator: 0, denominator: 1 } }
 *
 * # Legacy format (also supported)
 * onset:
 *   components:
 *     - { numerator: 1, denominator: 1 }
 *     - { numerator: 0, denominator: 1 }
 * ```
 */
@Serializable(with = TimeCodeSerializer::class)
data class TimeCode(
    val components: List<Fraction>
) : Comparable<TimeCode> {

    init {
        require(components.isNotEmpty()) { "TimeCode must have at least one component" }
    }

    /**
     * Measure number (1-based) - first component
     */
    val measure: Int get() = components[0].numerator

    /**
     * Beat position within measure (if available)
     */
    val beat: Fraction? get() = components.getOrNull(1)

    /**
     * Grace note position (if available)
     */
    val grace: Fraction? get() = components.getOrNull(2)

    override fun compareTo(other: TimeCode): Int {
        val maxLen = maxOf(components.size, other.components.size)
        for (i in 0 until maxLen) {
            val a = components.getOrNull(i) ?: Fraction.ZERO
            val b = other.components.getOrNull(i) ?: Fraction.ZERO
            val cmp = a.compareTo(b)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /**
     * Add a duration to the last component.
     */
    operator fun plus(duration: Fraction): TimeCode {
        if (components.isEmpty()) return TimeCode(listOf(duration))
        val last = components.last()
        return TimeCode(components.dropLast(1) + (last + duration))
    }

    /**
     * Check if this TimeCode has the same measure as another.
     */
    fun sameMeasure(other: TimeCode): Boolean = measure == other.measure

    /**
     * Format as readable string
     */
    fun format(): String = components.joinToString(":") { it.toString() }

    override fun toString(): String = format()

    companion object {
        val ZERO = TimeCode(listOf(Fraction.ZERO))
        val START = TimeCode(listOf(Fraction.ONE))

        /**
         * Create a TimeCode from measure number only.
         */
        fun ofMeasure(measure: Int): TimeCode =
            TimeCode(listOf(Fraction(measure, 1)))

        /**
         * Create a TimeCode from measure and beat.
         */
        fun of(measure: Int, beat: Fraction): TimeCode =
            TimeCode(listOf(Fraction(measure, 1), beat))

        /**
         * Create a TimeCode from measure, beat numerator and denominator.
         */
        fun of(measure: Int, beatNumerator: Int, beatDenominator: Int): TimeCode =
            TimeCode(listOf(Fraction(measure, 1), Fraction(beatNumerator, beatDenominator)))

        /**
         * Create a TimeCode from measure, beat, and grace position.
         */
        fun of(measure: Int, beat: Fraction, grace: Fraction): TimeCode =
            TimeCode(listOf(Fraction(measure, 1), beat, grace))
    }
}

/**
 * TimeRange represents a range of time positions.
 */
@Serializable
data class TimeRange(
    val start: TimeCode,
    val end: TimeCode
) {
    init {
        require(start <= end) { "Start must be <= end" }
    }

    operator fun contains(time: TimeCode): Boolean =
        time >= start && time <= end

    fun overlaps(other: TimeRange): Boolean =
        start <= other.end && end >= other.start

    companion object {
        fun single(time: TimeCode): TimeRange = TimeRange(time, time)
    }
}

/**
 * Custom serializer for TimeCode supporting both legacy and new formats.
 *
 * New format:
 * ```yaml
 * onset: { measure: 1, beat: { numerator: 0, denominator: 1 } }
 * onset: { measure: 1, beat: { numerator: 1, denominator: 4 }, grace: { numerator: 0, denominator: 1 } }
 * ```
 *
 * Legacy format:
 * ```yaml
 * onset:
 *   components:
 *     - { numerator: 1, denominator: 1 }
 *     - { numerator: 0, denominator: 1 }
 * ```
 */
object TimeCodeSerializer : KSerializer<TimeCode> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TimeCode") {
        element<Int>("measure", isOptional = true)
        element<Fraction>("beat", isOptional = true)
        element<Fraction>("grace", isOptional = true)
        element<List<Fraction>>("components", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: TimeCode) {
        encoder.encodeStructure(descriptor) {
            when (value.components.size) {
                1 -> {
                    // Only measure (as integer measure number)
                    encodeIntElement(descriptor, 0, value.measure)
                    encodeSerializableElement(descriptor, 1, Fraction.serializer(), Fraction.ZERO)
                }
                2 -> {
                    // Measure + beat
                    encodeIntElement(descriptor, 0, value.measure)
                    encodeSerializableElement(descriptor, 1, Fraction.serializer(), value.components[1])
                }
                3 -> {
                    // Measure + beat + grace
                    encodeIntElement(descriptor, 0, value.measure)
                    encodeSerializableElement(descriptor, 1, Fraction.serializer(), value.components[1])
                    encodeSerializableElement(descriptor, 2, Fraction.serializer(), value.components[2])
                }
                else -> {
                    // Fallback: use legacy components format
                    encodeSerializableElement(
                        descriptor,
                        3,
                        ListSerializer(Fraction.serializer()),
                        value.components
                    )
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): TimeCode {
        return decoder.decodeStructure(descriptor) {
            var measure: Int? = null
            var beat: Fraction? = null
            var grace: Fraction? = null
            var components: List<Fraction>? = null

            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> measure = decodeIntElement(descriptor, 0)
                    1 -> beat = decodeSerializableElement(descriptor, 1, Fraction.serializer())
                    2 -> grace = decodeSerializableElement(descriptor, 2, Fraction.serializer())
                    3 -> components = decodeSerializableElement(descriptor, 3, ListSerializer(Fraction.serializer()))
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }

            // Build TimeCode from parsed data
            when {
                // New format with measure + beat (+ optional grace)
                measure != null && beat != null -> {
                    val measureFraction = Fraction(measure, 1)
                    if (grace != null) {
                        canonicalTimeCode(listOf(measureFraction, beat, grace))
                    } else {
                        canonicalTimeCode(listOf(measureFraction, beat))
                    }
                }
                // New format with only measure
                measure != null && beat == null -> {
                    TimeCode(listOf(Fraction(measure, 1)))
                }
                // Legacy format with components
                components != null -> {
                    canonicalTimeCode(components)
                }
                else -> {
                    error("Invalid TimeCode format: must have either (measure, beat) or components")
                }
            }
        }
    }

    /**
     * Preserve component depth for ordinary positions, but keep the unique score origin canonical.
     * The serializer historically writes a zero beat for one-component values, so without this
     * normalization `TimeCode.ZERO` would not survive its own serialization round trip.
     */
    private fun canonicalTimeCode(components: List<Fraction>): TimeCode =
        if (components.all { it.isZero }) TimeCode.ZERO else TimeCode(components)
}
