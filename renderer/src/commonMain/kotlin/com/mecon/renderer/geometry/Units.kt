package com.mecon.renderer.geometry

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Staff space - the fundamental unit of measurement in music notation.
 *
 * Definition: In a standard 5-line staff, 1 staff space equals the distance
 * between the centers of two adjacent staff lines.
 *
 * All relative measurements in the renderer use staff spaces, allowing
 * for resolution-independent rendering.
 */
@Serializable
@JvmInline
value class StaffSpace(val value: Float) : Comparable<StaffSpace> {

    operator fun plus(other: StaffSpace) = StaffSpace(value + other.value)
    operator fun minus(other: StaffSpace) = StaffSpace(value - other.value)
    operator fun times(factor: Float) = StaffSpace(value * factor)
    operator fun times(factor: Int) = StaffSpace(value * factor)
    operator fun div(divisor: Float) = StaffSpace(value / divisor)
    operator fun div(divisor: Int) = StaffSpace(value / divisor)
    operator fun unaryMinus() = StaffSpace(-value)

    override fun compareTo(other: StaffSpace): Int = value.compareTo(other.value)

    companion object {
        val ZERO = StaffSpace(0f)
        val ONE = StaffSpace(1f)
        val HALF = StaffSpace(0.5f)

        // ========== Layout Constants (not from font metadata) ==========

        /** Default stem length (layout convention, not from font metadata) */
        val STEM_LENGTH = StaffSpace(3.5f)
    }
}

/**
 * Pixels - absolute measurement unit for screen rendering.
 */
@Serializable
@JvmInline
value class Pixels(val value: Float) : Comparable<Pixels> {

    operator fun plus(other: Pixels) = Pixels(value + other.value)
    operator fun minus(other: Pixels) = Pixels(value - other.value)
    operator fun times(factor: Float) = Pixels(value * factor)
    operator fun times(factor: Int) = Pixels(value * factor)
    operator fun div(divisor: Float) = Pixels(value / divisor)
    operator fun div(divisor: Int) = Pixels(value / divisor)
    operator fun unaryMinus() = Pixels(-value)

    override fun compareTo(other: Pixels): Int = value.compareTo(other.value)

    fun toInt(): Int = value.toInt()

    companion object {
        val ZERO = Pixels(0f)

        fun fromInt(value: Int) = Pixels(value.toFloat())
    }
}

/**
 * Scale factor - converts between staff spaces and pixels.
 *
 * @param pixelsPerStaffSpace Number of pixels per staff space
 */
@Serializable
@JvmInline
value class ScaleFactor(val pixelsPerStaffSpace: Float) {

    /**
     * Convert staff space to pixels.
     */
    fun toPixels(sp: StaffSpace): Pixels = Pixels(sp.value * pixelsPerStaffSpace)

    /**
     * Convert pixels to staff space.
     */
    fun toStaffSpace(px: Pixels): StaffSpace = StaffSpace(px.value / pixelsPerStaffSpace)

    /**
     * Scale by a factor.
     */
    operator fun times(factor: Float) = ScaleFactor(pixelsPerStaffSpace * factor)

    companion object {
        /** Default scale: 8 pixels per staff space */
        val DEFAULT = ScaleFactor(8f)

        /** Small scale for overview */
        val SMALL = ScaleFactor(4f)

        /** Large scale for detail editing */
        val LARGE = ScaleFactor(16f)

        /**
         * Calculate scale factor to fit a given staff space width into pixels.
         */
        fun fitWidth(spaceWidth: StaffSpace, pixelWidth: Pixels): ScaleFactor =
            ScaleFactor(pixelWidth.value / spaceWidth.value)
    }
}
