package com.mecon.renderer.geometry

import kotlinx.serialization.Serializable

/**
 * A point in relative coordinates (staff spaces).
 *
 * Used for all position calculations during layout and rendering.
 * The coordinate system uses:
 * - X: horizontal, positive to the right
 * - Y: vertical, positive downward (following screen conventions)
 *
 * The origin (0, 0) is typically the start of a staff system at
 * the middle line (B4 for treble clef).
 */
@Serializable
data class RelativePoint(
    val x: StaffSpace,
    val y: StaffSpace
) {
    operator fun plus(other: RelativePoint) = RelativePoint(x + other.x, y + other.y)
    operator fun minus(other: RelativePoint) = RelativePoint(x - other.x, y - other.y)
    operator fun times(factor: Float) = RelativePoint(x * factor, y * factor)
    operator fun unaryMinus() = RelativePoint(-x, -y)

    /**
     * Convert to absolute (pixel) coordinates.
     */
    fun toAbsolute(scale: ScaleFactor, origin: AbsolutePoint = AbsolutePoint.ZERO): AbsolutePoint =
        AbsolutePoint(
            x = origin.x + scale.toPixels(x),
            y = origin.y + scale.toPixels(y)
        )

    /**
     * Offset by the given values.
     */
    fun offset(dx: StaffSpace, dy: StaffSpace) = RelativePoint(x + dx, y + dy)

    /**
     * Distance to another point.
     */
    fun distanceTo(other: RelativePoint): StaffSpace {
        val dx = (other.x - x).value
        val dy = (other.y - y).value
        return StaffSpace(kotlin.math.sqrt(dx * dx + dy * dy))
    }

    companion object {
        val ZERO = RelativePoint(StaffSpace.ZERO, StaffSpace.ZERO)

        fun of(x: Float, y: Float) = RelativePoint(StaffSpace(x), StaffSpace(y))
    }
}

/**
 * A point in absolute coordinates (pixels).
 *
 * Used for final screen rendering after coordinate transformation.
 */
@Serializable
data class AbsolutePoint(
    val x: Pixels,
    val y: Pixels
) {
    operator fun plus(other: AbsolutePoint) = AbsolutePoint(x + other.x, y + other.y)
    operator fun minus(other: AbsolutePoint) = AbsolutePoint(x - other.x, y - other.y)
    operator fun times(factor: Float) = AbsolutePoint(x * factor, y * factor)
    operator fun unaryMinus() = AbsolutePoint(-x, -y)

    /**
     * Convert to relative (staff space) coordinates.
     */
    fun toRelative(scale: ScaleFactor, origin: AbsolutePoint = ZERO): RelativePoint {
        val adjusted = this - origin
        return RelativePoint(
            x = scale.toStaffSpace(adjusted.x),
            y = scale.toStaffSpace(adjusted.y)
        )
    }

    /**
     * Distance to another point.
     */
    fun distanceTo(other: AbsolutePoint): Pixels {
        val dx = (other.x - x).value
        val dy = (other.y - y).value
        return Pixels(kotlin.math.sqrt(dx * dx + dy * dy))
    }

    companion object {
        val ZERO = AbsolutePoint(Pixels.ZERO, Pixels.ZERO)

        fun of(x: Float, y: Float) = AbsolutePoint(Pixels(x), Pixels(y))
        fun of(x: Int, y: Int) = AbsolutePoint(Pixels.fromInt(x), Pixels.fromInt(y))
    }
}
