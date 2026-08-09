package com.mecon.renderer.layout

import com.mecon.api.primitive.BarlineType
import com.mecon.api.primitive.TimeCode
import com.mecon.renderer.geometry.StaffSpace
import kotlinx.serialization.Serializable

/**
 * Layout information for a barline.
 */
@Serializable
data class BarlineLayout(
    /** Time position of the barline */
    val time: TimeCode,
    /** X coordinate of the barline */
    val x: StaffSpace,
    /** Type of barline */
    val type: BarlineType,
    /** Measure number after this barline (0 for start of piece) */
    val measureNumber: Int
) {
    companion object {
        /**
         * Create a barline layout at the start of a measure.
         */
        fun measureStart(
            measureNumber: Int,
            time: TimeCode,
            x: StaffSpace,
            type: BarlineType = BarlineType.SINGLE
        ): BarlineLayout = BarlineLayout(time, x, type, measureNumber)
    }
}

/**
 * Collection of barline layouts for a system.
 */
@Serializable
data class BarlineLayoutMap(
    private val barlines: List<BarlineLayout>
) {
    /**
     * Get barline at a specific time.
     */
    fun atTime(time: TimeCode): BarlineLayout? =
        barlines.find { it.time == time }

    /**
     * Get barline for a specific measure number.
     */
    fun forMeasure(measureNumber: Int): BarlineLayout? =
        barlines.find { it.measureNumber == measureNumber }

    /**
     * Get all barlines in time order.
     */
    fun all(): List<BarlineLayout> = barlines

    /**
     * Get barlines within a time range.
     */
    fun inRange(startTime: TimeCode, endTime: TimeCode): List<BarlineLayout> =
        barlines.filter { it.time >= startTime && it.time <= endTime }

    /**
     * Get the barline immediately before a time position.
     */
    fun before(time: TimeCode): BarlineLayout? =
        barlines.filter { it.time < time }.maxByOrNull { it.time }

    /**
     * Get the barline immediately after a time position.
     */
    fun after(time: TimeCode): BarlineLayout? =
        barlines.filter { it.time > time }.minByOrNull { it.time }

    /**
     * Get total number of barlines.
     */
    val size: Int get() = barlines.size

    /**
     * Check if empty.
     */
    fun isEmpty(): Boolean = barlines.isEmpty()

    companion object {
        val EMPTY = BarlineLayoutMap(emptyList())

        fun fromList(barlines: List<BarlineLayout>): BarlineLayoutMap =
            BarlineLayoutMap(barlines.sortedBy { it.time })
    }
}
